package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.roundToInt

/** 縮小した輝度画像（0〜255）。前後のフレームは同じ幅・高さで比較する。 */
class LumaFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "size must be positive" }
        require(pixels.size == width * height) { "pixels must be width * height" }
    }
}

/** 動いた画素の重心。画像の左上が (0, 0)、右下が (1, 1)。 */
data class MotionCenter(val x: Double, val y: Double)

data class MotionResult(
    val changedRatio: Double,
    val motionDetected: Boolean,
    val center: MotionCenter? = null,
)

/**
 * 動体と判定する閾値（ユーザーが設定する）。
 * [pixelThreshold]: 画素の明るさ（0〜255）がこれより大きく変わったら「変化した画素」とする。
 * [areaPermille]: 変化した画素が画面のこの割合（0.1% 単位）以上なら動体ありとする。
 * [noise]: 暗い場面で見積もった雑音（[NightMotion]）。あれば、画素の明るさごとに、雑音より十分大きい
 *   変化だけを「変化した画素」とする（[pixelThresholdAt]。[pixelThreshold] より下げることはない）。
 */
data class MotionThreshold(val pixelThreshold: Int, val areaPermille: Int, val noise: NoiseModel? = null) {
    init {
        require(pixelThreshold in PIXEL_MIN..PIXEL_MAX) { "pixelThreshold out of range" }
        require(areaPermille in AREA_MIN..AREA_MAX) { "areaPermille out of range" }
    }

    /** 明るさごとの閾値（雑音が無ければ 256 段とも [pixelThreshold]）。 */
    private val thresholds: IntArray by lazy {
        IntArray(256) { luma ->
            val fromNoise = noise?.let { NightMotion.thresholdForNoise(it.sigma(luma.toDouble())) } ?: 0
            maxOf(pixelThreshold, fromNoise)
        }
    }

    /** 前回の明るさが [luma]（0〜255）の画素の閾値。 */
    fun pixelThresholdAt(luma: Int): Int = thresholds[luma.coerceIn(0, 255)]

    val areaRatio: Double get() = areaPermille / 1000.0

    companion object {
        const val PIXEL_MIN = 5
        const val PIXEL_MAX = 100
        const val AREA_MIN = 1
        const val AREA_MAX = 200
        val DEFAULT = MotionThreshold(pixelThreshold = 28, areaPermille = 10)

        fun clamped(pixelThreshold: Int, areaPermille: Int): MotionThreshold = MotionThreshold(
            pixelThreshold.coerceIn(PIXEL_MIN, PIXEL_MAX),
            areaPermille.coerceIn(AREA_MIN, AREA_MAX),
        )

        fun pixelFromProgress(progress: Int): Int = (progress + PIXEL_MIN).coerceIn(PIXEL_MIN, PIXEL_MAX)
        fun progressFromPixel(pixel: Int): Int = pixel.coerceIn(PIXEL_MIN, PIXEL_MAX) - PIXEL_MIN
        fun areaFromProgress(progress: Int): Int = (progress + AREA_MIN).coerceIn(AREA_MIN, AREA_MAX)
        fun progressFromArea(area: Int): Int = area.coerceIn(AREA_MIN, AREA_MAX) - AREA_MIN

        /** 0.1% 単位の値を「1.5%」のような表示にする。 */
        fun formatAreaPercent(areaPermille: Int): String = "${areaPermille / 10}.${areaPermille % 10}%"
    }
}

object MotionDetector {
    const val GRID_WIDTH = 80
    const val GRID_HEIGHT = 60

    /**
     * 前後の輝度画像を比べ、変化した画素の割合がしきい値以上なら動体ありとする。
     * 露出の自動調整による全体の明るさの変化は、平均輝度の差を差し引いて打ち消す。
     */
    fun compare(
        previous: LumaFrame,
        current: LumaFrame,
        threshold: MotionThreshold,
    ): MotionResult {
        require(previous.width == current.width && previous.height == current.height) {
            "frames must have the same size"
        }
        val count = current.pixels.size
        val meanShift = (current.pixels.sum() - previous.pixels.sum()).toDouble() / count
        val shift = meanShift.roundToInt()

        var changed = 0
        var sumX = 0L
        var sumY = 0L
        for (i in 0 until count) {
            if (abs(current.pixels[i] - previous.pixels[i] - shift) > threshold.pixelThresholdAt(previous.pixels[i])) {
                changed++
                sumX += i % current.width
                sumY += i / current.width
            }
        }
        val ratio = changed.toDouble() / count
        val detected = ratio >= threshold.areaRatio
        val center = if (detected) {
            MotionCenter(
                x = (sumX.toDouble() / changed + 0.5) / current.width,
                y = (sumY.toDouble() / changed + 0.5) / current.height,
            )
        } else {
            null
        }
        return MotionResult(ratio, detected, center)
    }

    /**
     * ARGB の画素 [argb]（幅 [width]・高さ [height]）を、80×60 の格子ごとに明るさを平均して縮める。
     * 格子 1 つに入る画素をすべて平均するので、点で取る縮め方と違い、1 画素に満たない手ぶれで値が
     * 大きく変わらない（実機の写真で確かめると、0.3° の手ぶれで変わる画素が 6〜18% から 1〜3% に減り、
     * 人の動きはほぼそのまま残った）。
     */
    fun gridFromArgb(argb: IntArray, width: Int, height: Int): LumaFrame {
        require(argb.size == width * height) { "pixels must be width * height" }
        val sums = LongArray(GRID_WIDTH * GRID_HEIGHT)
        val counts = IntArray(GRID_WIDTH * GRID_HEIGHT)
        for (y in 0 until height) {
            val gy = (y.toLong() * GRID_HEIGHT / height).toInt()
            for (x in 0 until width) {
                val g = gy * GRID_WIDTH + (x.toLong() * GRID_WIDTH / width).toInt()
                sums[g] += lumaOf(argb[y * width + x]).toLong()
                counts[g]++
            }
        }
        return LumaFrame(GRID_WIDTH, GRID_HEIGHT, IntArray(sums.size) { if (counts[it] == 0) 0 else (sums[it] / counts[it]).toInt() })
    }

    fun lumaOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }
}

sealed interface CameraSetDecision {
    /** 比較できる前回の画像がまだ無い。 */
    data object Baseline : CameraSetDecision
    data class NoMotion(val maxChangedRatio: Double) : CameraSetDecision
    data class Motion(val detectedBy: Set<String>) : CameraSetDecision
}

object CameraSetMotion {
    /**
     * カメラごとの判定結果（前回の画像が無いカメラは null）をまとめ、どれか 1 台でも
     * 動体を検知したら Motion を返す。全台とも前回の画像が無ければ Baseline。
     */
    fun decide(perCamera: Map<String, MotionResult?>): CameraSetDecision {
        val results = perCamera.mapNotNull { (camera, result) -> result?.let { camera to it } }
        if (results.isEmpty()) return CameraSetDecision.Baseline

        val detectedBy = results.filter { it.second.motionDetected }.map { it.first }.toSet()
        return if (detectedBy.isEmpty()) {
            CameraSetDecision.NoMotion(results.maxOf { it.second.changedRatio })
        } else {
            CameraSetDecision.Motion(detectedBy)
        }
    }
}

/** 画像に対する正規化座標の矩形（左上 (0, 0)・右下 (1, 1)）。 */
data class NormalizedBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val area: Double get() = (right - left).coerceAtLeast(0.0) * (bottom - top).coerceAtLeast(0.0)
    val center: MotionCenter get() = MotionCenter((left + right) / 2, (top + bottom) / 2)

    /**
     * 正立画像（撮影画像を時計回りに [rotationDegrees] 回したもの）上の矩形を、
     * 回す前の撮影画像（センサーの向き）上の矩形に戻す。
     */
    fun unrotate(rotationDegrees: Int): NormalizedBox = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> NormalizedBox(top, 1 - right, bottom, 1 - left)
        180 -> NormalizedBox(1 - right, 1 - bottom, 1 - left, 1 - top)
        270 -> NormalizedBox(1 - bottom, left, 1 - top, right)
        else -> this
    }
}

object MovingTargets {
    /** 変化した画素のうち、この割合以上が枠の中にあれば「動いている」とみなす。 */
    const val MIN_SHARE_OF_CHANGE = 0.1

    /** 前後の画像で変化した画素（[MotionDetector.compare] と同じ基準）を true にした配列。 */
    fun changedMask(previous: LumaFrame, current: LumaFrame, threshold: MotionThreshold): BooleanArray {
        require(previous.width == current.width && previous.height == current.height) {
            "frames must have the same size"
        }
        val count = current.pixels.size
        val shift = ((current.pixels.sum() - previous.pixels.sum()).toDouble() / count).roundToInt()
        return BooleanArray(count) {
            abs(current.pixels[it] - previous.pixels[it] - shift) > threshold.pixelThresholdAt(previous.pixels[it])
        }
    }

    /** 拡大して人を探す範囲の、変化した部分の大きさに対する余白（片側）。 */
    const val REGION_MARGIN = 0.5

    /** 拡大して人を探す範囲の最小の幅・高さ（画面に対する割合）。小さすぎると人の全身が入らない。 */
    const val REGION_MIN_SIZE = 0.2

    /** これより広い範囲は拡大しても細かく見えないので、拡大して探さない。 */
    const val REGION_MAX_AREA = 0.5

    /**
     * 変化した部分を囲む、拡大して人を探す範囲（SAHI の考え方。小さく遠い人は画面全体を縮小した画像では
     * 見つからないので、変化した辺りだけを撮影画像から高い解像度で切り出して探す）。
     * ばらばらの小さな変化に引っぱられないよう、変化した画素の 5〜95% の位置で囲み、余白をつける。
     * 変化した画素が少なすぎる、または範囲が広すぎるときは null。
     */
    fun changedRegion(
        mask: BooleanArray,
        width: Int,
        height: Int,
        minSize: Double = REGION_MIN_SIZE,
    ): NormalizedBox? {
        require(mask.size == width * height) { "mask must be width * height" }
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for (i in mask.indices) if (mask[i]) {
            xs.add(i % width)
            ys.add(i / width)
        }
        if (xs.size < MIN_REGION_PIXELS) return null
        xs.sort()
        ys.sort()
        fun at(sorted: List<Int>, q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        val left = at(xs, 0.05).toDouble() / width
        val right = (at(xs, 0.95) + 1).toDouble() / width
        val top = at(ys, 0.05).toDouble() / height
        val bottom = (at(ys, 0.95) + 1).toDouble() / height

        fun span(lo: Double, hi: Double): Pair<Double, Double> {
            val size = maxOf((hi - lo) * (1 + 2 * REGION_MARGIN), minSize).coerceAtMost(1.0)
            val center = (lo + hi) / 2
            val start = (center - size / 2).coerceIn(0.0, 1.0 - size)
            return start to start + size
        }
        val (l, r) = span(left, right)
        val (t, b) = span(top, bottom)
        val region = NormalizedBox(l, t, r, b)
        return region.takeIf { it.area <= REGION_MAX_AREA }
    }

    private const val MIN_REGION_PIXELS = 4

    /**
     * 枠の中の変化が、枠の外より [MIN_CONCENTRATION] 倍以上こい（変化した画素の割合が高い）か。人が動けば
     * 変化はその人の枠に集まり、照明の変化や手ぶれでは画面じゅうに広がるので、この違いで見分ける。
     */
    fun changeConcentratedInside(mask: BooleanArray, width: Int, height: Int, box: NormalizedBox): Boolean {
        val total = mask.count { it }
        if (total == 0) return false
        val x0 = (box.left * width).toInt().coerceIn(0, width)
        val x1 = Math.ceil(box.right * width).toInt().coerceIn(0, width)
        val y0 = (box.top * height).toInt().coerceIn(0, height)
        val y1 = Math.ceil(box.bottom * height).toInt().coerceIn(0, height)
        val insideCells = (x1 - x0) * (y1 - y0)
        if (insideCells <= 0) return false
        val inside = countInside(mask, width, height, box)
        val outsideCells = width * height - insideCells
        val densityInside = inside.toDouble() / insideCells
        val densityOutside = if (outsideCells > 0) (total - inside).toDouble() / outsideCells else 0.0
        return densityInside >= MIN_CONCENTRATION * densityOutside
    }

    /**
     * 枠 [box] の中に、本当に変わった画素があるか（枠の中の格子の [MIN_CHANGED_INSIDE_RATIO] 以上、かつ
     * [MIN_CHANGED_INSIDE_CELLS] 個以上）。ぶれた写真では何も動いていなくても人の枠が撮るたびに 1 割ほど
     * 跳ぶので、「人の位置が動いた」は、枠の中の画素も変わっているときだけ数える。
     */
    fun changedInside(mask: BooleanArray, width: Int, height: Int, box: NormalizedBox): Boolean {
        val x0 = (box.left * width).toInt().coerceIn(0, width)
        val x1 = Math.ceil(box.right * width).toInt().coerceIn(0, width)
        val y0 = (box.top * height).toInt().coerceIn(0, height)
        val y1 = Math.ceil(box.bottom * height).toInt().coerceIn(0, height)
        val cells = (x1 - x0) * (y1 - y0)
        if (cells <= 0) return false
        val needed = maxOf(MIN_CHANGED_INSIDE_CELLS.toDouble(), cells * MIN_CHANGED_INSIDE_RATIO)
        return countInside(mask, width, height, box) >= needed
    }

    /** [changedInside] の、枠の中の格子に対する割合。 */
    const val MIN_CHANGED_INSIDE_RATIO = 0.01

    /** [changedInside] の、最小の数。 */
    const val MIN_CHANGED_INSIDE_CELLS = 2

    /** [changeConcentratedInside] の倍率。 */
    const val MIN_CONCENTRATION = 2.0

    /** 変化した画素のうち、枠の中に入っている割合（変化が無ければ 0）。 */
    fun shareOfChangeInside(mask: BooleanArray, width: Int, height: Int, box: NormalizedBox): Double {
        val totalChanged = mask.count { it }
        if (totalChanged == 0) return 0.0
        return countInside(mask, width, height, box).toDouble() / totalChanged
    }

    private fun countInside(mask: BooleanArray, width: Int, height: Int, box: NormalizedBox): Int {
        val x0 = (box.left * width).toInt().coerceIn(0, width)
        val x1 = Math.ceil(box.right * width).toInt().coerceIn(0, width)
        val y0 = (box.top * height).toInt().coerceIn(0, height)
        val y1 = Math.ceil(box.bottom * height).toInt().coerceIn(0, height)
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) if (mask[y * width + x]) count++
        return count
    }
}
