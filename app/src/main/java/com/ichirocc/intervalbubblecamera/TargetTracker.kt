package com.ichirocc.intervalbubblecamera

import kotlin.math.hypot

enum class TargetKind(val isPerson: Boolean) {
    /** 全身・大部分が写った人（物体検出）。 */
    PERSON(true),

    /** 体の一部（姿勢推定で見えている関節だけから作った枠。脚・足首・つま先を含む）。 */
    BODY_PART(true),

    /** 手だけ（手の検出）。 */
    HAND(true),

    /** 自転車・車・バイク・バス・トラック。 */
    VEHICLE(false),
}

/** 検出した対象。[score] は検出の確からしさ（0〜1。姿勢推定・手の検出のように出ないものは 1）。 */
data class DetectedTarget(val box: NormalizedBox, val kind: TargetKind, val score: Double = 1.0) {
    /** 確からしさが十分で、これだけで新しい対象として数えてよいか。 */
    val confident: Boolean get() = score >= TargetTracker.HIGH_SCORE
}

/** 追跡中の対象。[moving] はこの回に動いたと判定されたか。 */
data class TrackedTarget(
    val id: Int,
    val kind: TargetKind,
    val box: NormalizedBox,
    val moving: Boolean,
)

/**
 * 1 台のカメラで検出した人・車などを撮影ごとにつなぎ、同じ対象に同じ ID を振る。
 * 枠の重なり（IoU）か中心の近さで前回の対象と対応づけ、見失って [maxMisses] 回続いたら捨てる。
 *
 * 対応づけは ByteTrack（Zhang et al., "ByteTrack: Multi-Object Tracking by Associating Every
 * Detection Box", ECCV 2022）の 2 段階にならう。確からしさの高い検出で追跡を更新・新規作成したあと、
 * 確からしさの低い検出は「まだ見つかっていない追跡中の対象」とだけ対応づける。半分隠れた・ぶれた人を
 * 見失いにくくしつつ、低い検出だけで新しい対象（誤検出）を作らない。
 */
class TargetTracker(
    private val maxMisses: Int = 3,
    /** 人の位置がこれ以上動いたら「動いた」とみなす（カメラの画角で変える。[CameraDetectionTuning]）。 */
    private val minMove: Double = MIN_MOVE,
) {
    private class Track(val id: Int, val kind: TargetKind, var box: NormalizedBox, var misses: Int)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    val hasTracks: Boolean get() = tracks.isNotEmpty()

    /**
     * 今回の検出で追跡を更新し、今回見えている対象を返す。動いたかどうかは
     * 「変化した画素の [MovingTargets.MIN_SHARE_OF_CHANGE] 以上が枠に入る」か
     * 「前回から中心が [minMove] 以上動いた」で決める。
     */
    fun update(detections: List<DetectedTarget>, shareOfChange: (NormalizedBox) -> Double): List<TrackedTarget> {
        val unmatched = tracks.toMutableList()
        val seen = mutableListOf<TrackedTarget>()
        val (high, low) = detections.partition { it.confident }

        // 1 段目: 確からしさの高い検出。対応する対象が無ければ新しく追跡を始める。
        for (detection in high.sortedByDescending { it.box.area }) {
            val match = unmatched
                .filter { it.kind == detection.kind }
                .map { it to similarity(it.box, detection.box) }
                .filter { it.second > 0.0 }
                .maxByOrNull { it.second }
                ?.first
            val track = match ?: Track(nextId++, detection.kind, detection.box, 0).also { tracks.add(it) }
            seen.add(follow(track, detection, matched = match != null, unmatched, shareOfChange))
        }

        // 2 段目: 確からしさの低い検出は、1 段目で見つからなかった対象と枠がよく重なるときだけ使う。
        for (detection in low.sortedByDescending { it.score }) {
            val match = unmatched
                .filter { it.kind == detection.kind }
                .map { it to iou(it.box, detection.box) }
                .filter { it.second >= LOW_SCORE_MATCH_IOU }
                .maxByOrNull { it.second }
                ?.first ?: continue
            seen.add(follow(match, detection, matched = true, unmatched, shareOfChange))
        }

        unmatched.forEach { it.misses++ }
        tracks.removeAll { it.misses > maxMisses }
        return seen
    }

    /** [track] を今回の [detection] の位置に進め、動いたかどうかを決める。 */
    private fun follow(
        track: Track,
        detection: DetectedTarget,
        matched: Boolean,
        unmatched: MutableList<Track>,
        shareOfChange: (NormalizedBox) -> Double,
    ): TrackedTarget {
        val movedBy = if (matched) distance(track.box.center, detection.box.center) else 0.0
        val moving = shareOfChange(detection.box) >= MovingTargets.MIN_SHARE_OF_CHANGE || movedBy >= minMove
        if (matched) {
            unmatched.remove(track)
            track.box = detection.box
            track.misses = 0
        }
        return TrackedTarget(track.id, track.kind, track.box, moving)
    }

    fun clear() {
        tracks.clear()
    }

    /**
     * 画面全体が正規化座標で ([dx], [dy]) ずれたとき（スマホが動いた）、追跡中の位置も同じだけずらす。
     * 追跡をやり直さずに続けられ、ずれを打ち消したうえで人の位置が動いたかが分かる。
     */
    fun shift(dx: Double, dy: Double) {
        for (track in tracks) {
            val box = track.box
            track.box = NormalizedBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
        }
    }

    private fun similarity(a: NormalizedBox, b: NormalizedBox): Double {
        val iou = iou(a, b)
        if (iou >= MIN_IOU) return 1.0 + iou
        val d = distance(a.center, b.center)
        return if (d <= MAX_CENTER_DISTANCE) 1.0 - d / MAX_CENTER_DISTANCE else 0.0
    }

    companion object {
        const val MIN_IOU = 0.1
        const val MAX_CENTER_DISTANCE = 0.2
        /**
         * 人の位置がこれ以上動いたら「動いた」とみなす（広角）。実機の写真で確かめると、何も動いていなくても
         * 検出した枠は撮るたびに 3% ほどゆれるので、それより大きくする。
         */
        const val MIN_MOVE = 0.04

        /** これ以上の確からしさの検出は、それだけで対象として数える（物体検出のこれまでの閾値）。 */
        const val HIGH_SCORE = 0.4

        /** 物体検出に出させる確からしさの下限。[HIGH_SCORE] 未満は追跡中の対象の続きとしてだけ使う。 */
        const val LOW_SCORE = 0.2

        /** 確からしさの低い検出を追跡中の対象と対応づけるのに要る枠の重なり。 */
        const val LOW_SCORE_MATCH_IOU = 0.3

        fun iou(a: NormalizedBox, b: NormalizedBox): Double {
            val inter = NormalizedBox(
                maxOf(a.left, b.left),
                maxOf(a.top, b.top),
                minOf(a.right, b.right),
                minOf(a.bottom, b.bottom),
            ).area
            val union = a.area + b.area - inter
            return if (union <= 0.0) 0.0 else inter / union
        }

        private fun distance(a: MotionCenter, b: MotionCenter): Double = hypot(a.x - b.x, a.y - b.y)

        /** ピントを合わせる対象: 動いている人（手・体の一部を含む）を車より優先し、大きいものを選ぶ。 */
        fun focusTarget(targets: List<TrackedTarget>): TrackedTarget? = targets
            .filter { it.moving }
            .maxWithOrNull(compareBy<TrackedTarget> { it.kind.isPerson }.thenBy { it.box.area })

        /** 別の向き・切り出した範囲で同じ対象を重ねて見つけたとみなす枠の重なり（IoU）。 */
        const val FUSE_IOU = 0.55

        /**
         * 人を探す画像の向き（時計回りの角度）。スマホの向きから見た正立の向き [uprightDegrees] を先に、
         * 続けて 90° ずつ回した残りの 3 つ。
         */
        fun detectionRotations(uprightDegrees: Int): List<Int> {
            val first = ((uprightDegrees % 360) + 360) % 360 / 90 * 90
            return (0 until 4).map { (first + it * 90) % 360 }
        }

        /**
         * いくつかの向きや切り出した範囲で重ねて見つけた同じ対象を 1 つにまとめる。Weighted Boxes Fusion
         * （Solovyev et al., "Weighted boxes fusion: Ensembling boxes from different object detection
         * models", Image and Vision Computing 2021）にならい、確からしさの高い順に、種類が同じで枠が
         * [FUSE_IOU] 以上重なるものを束ね、枠は確からしさで重みをつけた平均、確からしさは束の最大にする。
         * 捨てずに平均するので、向きや拡大のしかたで少しずつずれた枠が、より正しい位置にまとまる。
         */
        fun fuse(detections: List<DetectedTarget>): List<DetectedTarget> {
            val clusters = mutableListOf<MutableList<DetectedTarget>>()
            for (detection in detections.sortedByDescending { it.score }) {
                val cluster = clusters.firstOrNull { members ->
                    members[0].kind == detection.kind && iou(weightedBox(members), detection.box) >= FUSE_IOU
                }
                if (cluster != null) cluster.add(detection) else clusters.add(mutableListOf(detection))
            }
            return clusters.map { members ->
                DetectedTarget(weightedBox(members), members[0].kind, members.maxOf { it.score })
            }
        }

        private fun weightedBox(members: List<DetectedTarget>): NormalizedBox {
            if (members.size == 1) return members[0].box
            // 確からしさが 0 のものだけなら、ただの平均にする。
            val weights = members.map { it.score }.let { w -> if (w.sum() > 0.0) w else w.map { 1.0 } }
            val total = weights.sum()
            fun avg(part: (NormalizedBox) -> Double): Double =
                members.indices.sumOf { part(members[it].box) * weights[it] } / total
            return NormalizedBox(avg { it.left }, avg { it.top }, avg { it.right }, avg { it.bottom })
        }

        /**
         * スマホが動いた回に保存する理由になる人（全身・体の一部・手）。車などと、確からしさの低い検出は含めない。
         * 複数いれば一番大きく写った人（ピントを合わせる対象）。
         */
        fun personInFrame(detections: List<DetectedTarget>): DetectedTarget? = detections
            .filter { it.kind.isPerson && it.confident }
            .maxByOrNull { it.box.area }

        /**
         * 姿勢推定や手の検出の関節点（正規化座標）から、見えている点だけで枠を作る。
         * 見えている点が [minPoints] 未満なら null。
         */
        fun boxFromLandmarks(
            points: List<Pair<Double, Double>>,
            visible: List<Boolean>,
            minPoints: Int = 2,
            padding: Double = 0.03,
        ): NormalizedBox? {
            val shown = points.filterIndexed { i, (x, y) ->
                visible.getOrElse(i) { false } && x in 0.0..1.0 && y in 0.0..1.0
            }
            if (shown.size < minPoints) return null
            return NormalizedBox(
                (shown.minOf { it.first } - padding).coerceAtLeast(0.0),
                (shown.minOf { it.second } - padding).coerceAtLeast(0.0),
                (shown.maxOf { it.first } + padding).coerceAtMost(1.0),
                (shown.maxOf { it.second } + padding).coerceAtMost(1.0),
            )
        }
    }
}
