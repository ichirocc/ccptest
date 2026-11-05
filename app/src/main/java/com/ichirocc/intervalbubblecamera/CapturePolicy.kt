package com.ichirocc.intervalbubblecamera

/** カメラが持っている、撮影の質を上げる機能（Camera2 の特性から調べる）。 */
data class CameraFeatures(
    /** 光学式手ぶれ補正（レンズを動かしてぶれを打ち消す）。 */
    val opticalStabilization: Boolean,
    /** 高品質のノイズ除去。 */
    val highQualityNoiseReduction: Boolean,
    /** 高品質の輪郭の処理。 */
    val highQualityEdge: Boolean,
)

/**
 * 端末のカメラの力をできるだけ使う撮り方を決める。どれも、そのカメラが対応しているときだけ使い、
 * つなげなかった・撮れなかったカメラはふつうの撮り方に戻す。
 */
object CapturePolicy {
    /**
     * 全画素で撮るか。機種の設定（[DeviceProfile.fullResolution]）が有効で、温度が普通で、夜景モードでなく
     * （夜景モードは自分で大きさを決める）、前に全画素でつなげなかったカメラでなければ。
     */
    fun useFullResolution(profile: DeviceProfile, heat: HeatLevel, night: Boolean, fellBack: Boolean): Boolean =
        profile.fullResolution && heat == HeatLevel.NORMAL && !night && !fellBack

    /**
     * 撮影の質を上げる設定（遅れの少ない撮影・光学式手ぶれ補正・高品質の処理）を使うか。夜景モードでは
     * 使わない（メーカーの夜景処理と重なるため）。前につなげなかった・撮れなかったカメラでも使わない。
     */
    fun useEnhancedCapture(night: Boolean, fellBack: Boolean): Boolean = !night && !fellBack
}
