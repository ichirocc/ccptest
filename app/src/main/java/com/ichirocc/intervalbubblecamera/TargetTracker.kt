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

data class DetectedTarget(val box: NormalizedBox, val kind: TargetKind)

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
 */
class TargetTracker(private val maxMisses: Int = 3) {
    private class Track(val id: Int, val kind: TargetKind, var box: NormalizedBox, var misses: Int)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    val hasTracks: Boolean get() = tracks.isNotEmpty()

    /**
     * 今回の検出で追跡を更新し、今回見えている対象を返す。動いたかどうかは
     * 「変化した画素の [MovingTargets.MIN_SHARE_OF_CHANGE] 以上が枠に入る」か
     * 「前回から中心が [MIN_MOVE] 以上動いた」で決める。
     */
    fun update(detections: List<DetectedTarget>, shareOfChange: (NormalizedBox) -> Double): List<TrackedTarget> {
        val unmatched = tracks.toMutableList()
        val seen = mutableListOf<TrackedTarget>()

        for (detection in detections.sortedByDescending { it.box.area }) {
            val match = unmatched
                .filter { it.kind == detection.kind }
                .map { it to similarity(it.box, detection.box) }
                .filter { it.second > 0.0 }
                .maxByOrNull { it.second }
                ?.first

            val movedBy = match?.let { distance(it.box.center, detection.box.center) } ?: 0.0
            val moving = shareOfChange(detection.box) >= MovingTargets.MIN_SHARE_OF_CHANGE || movedBy >= MIN_MOVE

            val track = if (match != null) {
                unmatched.remove(match)
                match.box = detection.box
                match.misses = 0
                match
            } else {
                Track(nextId++, detection.kind, detection.box, 0).also { tracks.add(it) }
            }
            seen.add(TrackedTarget(track.id, track.kind, track.box, moving))
        }

        unmatched.forEach { it.misses++ }
        tracks.removeAll { it.misses > maxMisses }
        return seen
    }

    fun clear() {
        tracks.clear()
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
        const val MIN_MOVE = 0.03

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
