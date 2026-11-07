package com.ichirocc.intervalbubblecamera

import java.util.Locale

/**
 * 実機で使って確かめる（ドッグフーディング）ための記録の 1 行。撮影ごと・カメラごとに 1 行（[kind] が
 * "shot"）と、カメラのつなぎ方・温度の変化・失敗などの出来事に 1 行（[kind] が "event"）。分からない欄は空。
 * 写真だけでは分からない「なぜ保存した・しなかったか」「どの撮り方でつないだか」を後から調べるために残す。
 */
data class DogfoodRecord(
    val time: String,
    val kind: String,
    val cycle: Int,
    val camera: String = "",
    // 設定
    val intervalSeconds: Int? = null,
    val pixelThreshold: Int? = null,
    val areaPermille: Int? = null,
    // つなぎ方・撮れた写真
    val fullResolution: Boolean? = null,
    val enhanced: Boolean? = null,
    val night: Boolean? = null,
    val ultraHdr: Boolean? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val exposureSeconds: Double? = null,
    val heat: String = "",
    // スマホの動き
    val phoneMotion: String = "",
    val rotationDegrees: Double? = null,
    val tiltDegrees: Double? = null,
    val walking: Boolean? = null,
    val compensatedShift: String = "",
    // 画像の変化と人の検出
    val rawChangedRatio: Double? = null,
    val changedRatio: Double? = null,
    val noisePixelThreshold: Int? = null,
    val detectionSkipped: Boolean? = null,
    val detections: Int? = null,
    val confidentPersons: Int? = null,
    val zoomed: Boolean? = null,
    // 判定と結果
    val motion: Boolean? = null,
    val reason: String = "",
    val refocused: Boolean? = null,
    val blurFraction: Double? = null,
    val blurRetaken: Boolean? = null,
    val saved: Boolean? = null,
    val message: String = "",
) {
    fun toCsvRow(): String = listOf(
        time, kind, cycle.toString(), camera,
        intervalSeconds.text(), pixelThreshold.text(), areaPermille.text(),
        fullResolution.text(), enhanced.text(), night.text(), ultraHdr.text(),
        imageWidth.text(), imageHeight.text(), exposureSeconds.text(6), heat,
        phoneMotion, rotationDegrees.text(2), tiltDegrees.text(2), walking.text(), compensatedShift,
        rawChangedRatio.text(4), changedRatio.text(4), noisePixelThreshold.text(),
        detectionSkipped.text(), detections.text(), confidentPersons.text(), zoomed.text(),
        motion.text(), reason, refocused.text(), blurFraction.text(5), blurRetaken.text(), saved.text(), message,
    ).joinToString(",") { escape(it) }

    companion object {
        val HEADER: String = listOf(
            "time", "kind", "cycle", "camera",
            "interval_s", "pixel_threshold", "area_permille",
            "full_resolution", "enhanced", "night", "ultra_hdr",
            "image_width", "image_height", "exposure_s", "heat",
            "phone_motion", "rotation_deg", "tilt_deg", "walking", "compensated_shift",
            "raw_changed_ratio", "changed_ratio", "noise_pixel_threshold",
            "detection_skipped", "detections", "confident_persons", "zoomed",
            "motion", "reason", "refocused", "blur_fraction", "blur_retaken", "saved", "message",
        ).joinToString(",")

        /** CSV の欄に入れられる形にする（カンマ・引用符・改行を含むときは引用符で囲む）。 */
        fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }

        private fun Any?.text(): String = when (this) {
            null -> ""
            is Boolean -> if (this) "1" else "0"
            else -> toString()
        }

        private fun Double?.text(digits: Int): String = this?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""
    }
}
