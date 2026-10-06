package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.acos
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

    /** [rotationDegrees] が null（センサー無し・前回の向き無し）なら画面の変化の割合だけで判定する。 */
    fun phoneMoved(rotationDegrees: Double?, changedRatio: Double): Boolean =
        (rotationDegrees != null && rotationDegrees >= MAX_ROTATION_DEGREES) ||
            changedRatio >= MAX_SUBJECT_CHANGE_RATIO
}
