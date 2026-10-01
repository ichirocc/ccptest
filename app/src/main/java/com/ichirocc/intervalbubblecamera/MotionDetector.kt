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
 */
data class MotionThreshold(val pixelThreshold: Int, val areaPermille: Int) {
    init {
        require(pixelThreshold in PIXEL_MIN..PIXEL_MAX) { "pixelThreshold out of range" }
        require(areaPermille in AREA_MIN..AREA_MAX) { "areaPermille out of range" }
    }

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
            if (abs(current.pixels[i] - previous.pixels[i] - shift) > threshold.pixelThreshold) {
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
