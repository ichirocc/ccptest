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

enum class MotionSensitivity(
    val storageKey: String,
    val pixelThreshold: Int,
    val areaThreshold: Double,
) {
    LOW("low", pixelThreshold = 40, areaThreshold = 0.03),
    MEDIUM("medium", pixelThreshold = 28, areaThreshold = 0.01),
    HIGH("high", pixelThreshold = 18, areaThreshold = 0.003),
    ;

    companion object {
        val DEFAULT = MEDIUM

        fun fromStorageKey(key: String?): MotionSensitivity =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
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
        sensitivity: MotionSensitivity,
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
            if (abs(current.pixels[i] - previous.pixels[i] - shift) > sensitivity.pixelThreshold) {
                changed++
                sumX += i % current.width
                sumY += i / current.width
            }
        }
        val ratio = changed.toDouble() / count
        val detected = ratio >= sensitivity.areaThreshold
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
