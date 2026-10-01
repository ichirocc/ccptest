package com.ichirocc.intervalbubblecamera

/**
 * 対象機種ごとの性能設定。Pixel 10 Pro XL（Tensor G5）は NPU と高精度モデルを使い切り、
 * OPPO A5 5G（Dimensity 6300）は GPU と軽量モデルで速さと電池を優先する。
 */
enum class DeviceProfile(
    val label: String,
    val objectModel: String,
    val poseModel: String,
    /** 試す順の実行先（MediaPipe の Delegate 名）。作れなければ次を試す。 */
    val delegates: List<String>,
    /** 検出に渡す画像の長辺（px）。 */
    val detectSize: Int,
    val maxPoses: Int,
    val jpegQuality: Int,
    /** カメラを切り替えた直後、露出が落ち着くまで待つ時間。 */
    val switchSettleMs: Long,
) {
    FLAGSHIP(
        label = "Pixel 10 Pro XL",
        objectModel = "efficientdet_lite2.tflite",
        poseModel = "pose_landmarker_full.task",
        delegates = listOf("NPU", "GPU", "CPU"),
        detectSize = 1024,
        maxPoses = 5,
        jpegQuality = 95,
        switchSettleMs = 500L,
    ),
    STANDARD(
        label = "OPPO A5 5G",
        objectModel = "efficientdet_lite0.tflite",
        poseModel = "pose_landmarker_lite.task",
        delegates = listOf("GPU", "CPU"),
        detectSize = 640,
        maxPoses = 3,
        jpegQuality = 90,
        switchSettleMs = 800L,
    ),
    ;

    companion object {
        /** Android 14 世代の性能クラス（Build.VERSION_CODES.UPSIDE_DOWN_CAKE）。 */
        private const val FLAGSHIP_PERFORMANCE_CLASS = 34

        /**
         * Pixel 10 系、または高性能機の条件（メディア性能クラス 14 以上）を満たす端末は FLAGSHIP、
         * それ以外（OPPO A5 5G を含む）は STANDARD。
         */
        fun detect(model: String, mediaPerformanceClass: Int): DeviceProfile = when {
            model.startsWith("Pixel 10") -> FLAGSHIP
            mediaPerformanceClass >= FLAGSHIP_PERFORMANCE_CLASS -> FLAGSHIP
            else -> STANDARD
        }
    }
}
