package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min

/**
 * スマホ自体が動いたか（手持ちのぶれ・向きの変化）を判定する。スマホが動くと画面全体が変わり、
 * 写っている人や物が「動いた」ように見えるため、その回は動体として扱わない。
 */
object CameraMovement {
    /** 前回の撮影からスマホがこの角度以上回ったら、スマホが動いたとみなす。 */
    const val MAX_ROTATION_DEGREES = 2.0

    /** 画面のこの割合以上が変わったら、被写体ではなくスマホが動いたとみなす（センサーが無いときの保険）。 */
    const val MAX_SUBJECT_CHANGE_RATIO = 0.5

    /** 2 つの向き（単位クォータニオン [w, x, y, z]）の間の回転角（度）。 */
    fun rotationDegrees(a: FloatArray, b: FloatArray): Double {
        require(a.size == 4 && b.size == 4) { "quaternions must have 4 components" }
        val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).toDouble()
        return Math.toDegrees(2 * acos(min(1.0, dot)))
    }

    /** スマホの傾き（重力の向き）がこの角度以上変わったら、スマホが動いたとみなす。 */
    const val MAX_TILT_DEGREES = 2.0

    /** 重力が画面に垂直な向きからこの角度以内なら、スマホを平らに置いている（上向き・下向き）とみなす。 */
    const val FLAT_DEGREES = 20.0

    /**
     * スマホが止まっていると分かっているとき、画面のこの割合以上が変われば、人の検出に頼らず保存する。
     * スマホの上を人がまたぐなど、すぐ近くを人が覆うと、真下・真横からの姿は検出できないことが多いため。
     */
    const val CLOSE_SUBJECT_RATIO = 0.3

    /** 2 つの重力の向き（加速度 [x, y, z]）の間の角度（度）。 */
    fun tiltDegrees(a: FloatArray, b: FloatArray): Double {
        require(a.size >= 3 && b.size >= 3) { "gravity must have 3 components" }
        val dot = a[0].toDouble() * b[0] + a[1].toDouble() * b[1] + a[2].toDouble() * b[2]
        val norms = norm(a) * norm(b)
        if (norms == 0.0) return 0.0
        return Math.toDegrees(acos((dot / norms).coerceIn(-1.0, 1.0)))
    }

    /** 重力の向き [gravity] から、スマホを平らに置いている（画面が上向きか下向き）か。 */
    fun isLyingFlat(gravity: FloatArray): Boolean {
        val n = norm(gravity)
        if (n == 0.0) return false
        return abs(gravity[2]) / n >= cos(Math.toRadians(FLAT_DEGREES))
    }

    /**
     * スマホが動いたか止まっているかを判定する。
     * - 向きのセンサー（ジャイロ）で回転が測れたら、その角度だけで判定する（近くの人が画面の大半を
     *   占めても、スマホが止まっていれば動体として扱う）。
     * - ジャイロが無くても、重力の向き [tiltDegrees] が変わっていれば動いた。平らに置いたまま傾きが
     *   変わっていなければ止まっている（スマホの上を人がまたいで画面の大半が変わっても、スマホは動いていない）。
     * - どちらも分からなければ、画面の半分以上が変わったら動いたとみなす。
     */
    fun judge(
        rotationDegrees: Double?,
        changedRatio: Double,
        tiltDegrees: Double? = null,
        lyingFlat: Boolean = false,
    ): PhoneMotion = when {
        rotationDegrees != null -> if (rotationDegrees >= MAX_ROTATION_DEGREES) PhoneMotion.MOVED else PhoneMotion.STILL
        tiltDegrees != null && tiltDegrees >= MAX_TILT_DEGREES -> PhoneMotion.MOVED
        tiltDegrees != null && lyingFlat -> PhoneMotion.STILL
        changedRatio >= MAX_SUBJECT_CHANGE_RATIO -> PhoneMotion.MOVED
        else -> PhoneMotion.UNKNOWN
    }

    fun phoneMoved(
        rotationDegrees: Double?,
        changedRatio: Double,
        tiltDegrees: Double? = null,
        lyingFlat: Boolean = false,
    ): Boolean = judge(rotationDegrees, changedRatio, tiltDegrees, lyingFlat) == PhoneMotion.MOVED

    /** スマホが止まっていると分かっていて、すぐ近くを何か（人）が覆ったように画面が大きく変わったか。 */
    fun closeSubject(motion: PhoneMotion, changedRatio: Double): Boolean =
        motion == PhoneMotion.STILL && changedRatio >= CLOSE_SUBJECT_RATIO

    private fun norm(v: FloatArray): Double =
        Math.sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])
}

enum class PhoneMotion {
    MOVED,
    STILL,

    /** センサーで分からず、画面の変化も小さい。 */
    UNKNOWN,
}
