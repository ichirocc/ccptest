package com.ichirocc.intervalbubblecamera

import kotlin.math.atan

/** 画角で分けたカメラの種類。 */
enum class LensClass(val label: String) {
    ULTRA_WIDE("超広角"),
    WIDE("広角"),
    TELE("望遠"),
}

/**
 * カメラごとの人の検出の調整。画角が広いほど人は小さく写り、同じ距離を歩いても画面の上ではあまり動かない。
 * 画角が狭いほど人は大きく写り、少し動いただけで画面の上では大きく動く。
 */
data class DetectionTuning(
    val lensClass: LensClass,
    /** 人の検出に渡す画像の大きさの倍率（機種ごとの大きさに掛ける）。 */
    val detectSizeFactor: Double,
    /** 人の位置がこれ以上（画面の幅・高さを 1 とする）動いたら「動いた」とみなす。 */
    val minMove: Double,
    /** 変化した辺りを切り出して探すときの、切り出す範囲の最小の幅・高さ。小さいほど大きく拡大して探す。 */
    val zoomMinRegion: Double,
)

object CameraDetectionTuning {
    /** 横の画角（度）がこれ以上なら超広角。 */
    const val ULTRA_WIDE_FOV = 85.0

    /** 横の画角（度）がこれ以下なら望遠。 */
    const val TELE_FOV = 40.0

    val ULTRA_WIDE = DetectionTuning(LensClass.ULTRA_WIDE, detectSizeFactor = 1.5, minMove = 0.02, zoomMinRegion = 0.12)
    val WIDE = DetectionTuning(LensClass.WIDE, detectSizeFactor = 1.0, minMove = TargetTracker.MIN_MOVE, zoomMinRegion = MovingTargets.REGION_MIN_SIZE)
    val TELE = DetectionTuning(LensClass.TELE, detectSizeFactor = 0.75, minMove = 0.045, zoomMinRegion = 0.3)

    /** 焦点距離 ÷ センサーの幅 [focalOverSensorWidth] から求めた横の画角（度）。 */
    fun horizontalFovDegrees(focalOverSensorWidth: Double): Double =
        Math.toDegrees(2 * atan(1 / (2 * focalOverSensorWidth)))

    /**
     * カメラの調整。焦点距離 ÷ センサーの幅 [focalOverSensorWidth] から画角を求めて決める。分からなければ広角。
     * 前面カメラ（[front]）は自分や近くの人を大きく写すことが多いので、画角によらず広角と同じにする。
     */
    fun forCamera(focalOverSensorWidth: Double?, front: Boolean): DetectionTuning {
        if (front || focalOverSensorWidth == null || focalOverSensorWidth <= 0.0) return WIDE
        val fov = horizontalFovDegrees(focalOverSensorWidth)
        return when {
            fov >= ULTRA_WIDE_FOV -> ULTRA_WIDE
            fov <= TELE_FOV -> TELE
            else -> WIDE
        }
    }
}
