package com.ichirocc.intervalbubblecamera

import kotlin.math.abs

/** 端末が公開しているカメラ 1 台分の情報（撮影に使うかの判定用）。 */
data class CameraCandidate(
    val key: String,
    /** 物理カメラなら、それを束ねる論理カメラの key。論理カメラ・単独のカメラは null。 */
    val logicalKey: String?,
    val pixelCount: Long,
    val isMonochrome: Boolean,
    /** レンズの焦点距離（mm）。複数あれば最初が既定。 */
    val focalLengths: List<Float>,
)

/**
 * 対象機種（Pixel 10 Pro XL・OPPO A5 5G）に合わせ、撮影に向かないカメラと重複するカメラを除く。
 * - 白黒・低画素（深度・マクロ用の補助カメラ）は除く。
 * - 論理カメラの既定レンズと同じ焦点距離の物理カメラは、論理カメラと同じ画になるので除く。
 */
object CameraSelectionPolicy {
    const val MIN_PIXEL_COUNT = 3_000_000L
    private const val SAME_FOCAL_TOLERANCE = 0.05

    fun select(candidates: List<CameraCandidate>): List<String> {
        val byKey = candidates.associateBy { it.key }
        return candidates.filter { camera ->
            if (camera.isMonochrome || camera.pixelCount < MIN_PIXEL_COUNT) return@filter false
            val logical = camera.logicalKey?.let { byKey[it] } ?: return@filter true
            !sameFocalLength(camera.focalLengths.firstOrNull(), logical.focalLengths.firstOrNull())
        }.map { it.key }
    }

    private fun sameFocalLength(a: Float?, b: Float?): Boolean {
        if (a == null || b == null || b <= 0f) return false
        return abs(a - b) / b <= SAME_FOCAL_TOLERANCE
    }
}
