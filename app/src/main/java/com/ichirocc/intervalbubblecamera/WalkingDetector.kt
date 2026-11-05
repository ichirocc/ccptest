package com.ichirocc.intervalbubblecamera

import kotlin.math.sqrt

/**
 * 撮影している人が歩いている（体ごと動いている）かを、加速度の大きさの揺れから判定する。
 * 歩くと 1 歩ごとに加速度が上下し（毎秒 1〜3 回）、手のぶれよりずっと大きく揺れる。
 * 歩数計のセンサー（身体活動の権限が要る）は使わず、加速度センサーだけで判定する。
 */
class WalkingDetector(private val windowNanos: Long = WINDOW_NANOS) {
    private val times = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Double>()

    /** 加速度センサーの値（m/s²、重力を含む）を加える。 */
    fun add(timestampNanos: Long, x: Float, y: Float, z: Float) {
        times.addLast(timestampNanos)
        magnitudes.addLast(sqrt((x * x + y * y + z * z).toDouble()))
        dropOlderThan(timestampNanos - windowNanos)
    }

    /**
     * 時刻 [nowNanos]（センサーと同じ時計）の直前 [windowNanos] に、歩いているときの揺れ
     * （大きさが [MIN_STD] 以上で、[MIN_STEPS] 歩以上）があったか。値が届いていなければ false。
     */
    fun isWalking(nowNanos: Long): Boolean {
        dropOlderThan(nowNanos - windowNanos)
        if (times.size < MIN_SAMPLES) return false
        if (nowNanos - times.last() > MAX_AGE_NANOS) return false
        if (times.last() - times.first() < MIN_SPAN_NANOS) return false

        val mean = magnitudes.average()
        val std = sqrt(magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size)
        if (std < MIN_STD) return false

        // 1 歩 = 平均より十分上へ抜けた回数。続けて抜けた揺れは 1 歩として数える。
        val high = mean + STEP_LEVEL * std
        var steps = 0
        var above = false
        var lastStep = Long.MIN_VALUE
        for (i in magnitudes.indices) {
            val isAbove = magnitudes[i] > high
            if (isAbove && !above && times[i] - lastStep >= MIN_STEP_INTERVAL_NANOS) {
                steps++
                lastStep = times[i]
            }
            above = isAbove
        }
        return steps >= MIN_STEPS
    }

    fun clear() {
        times.clear()
        magnitudes.clear()
    }

    private fun dropOlderThan(limit: Long) {
        while (times.isNotEmpty() && times.first() < limit) {
            times.removeFirst()
            magnitudes.removeFirst()
        }
    }

    companion object {
        /** 判定に使う直近の時間。 */
        const val WINDOW_NANOS = 3_000_000_000L

        /** 加速度の大きさの揺れ（標準偏差、m/s²）がこれ以上なら体ごと動いている。手のぶれは 0.3 程度まで。 */
        const val MIN_STD = 1.0

        /** 判定に使う時間の中でこれだけ歩けば歩いている（ゆっくり歩いても 3 秒で 3 歩はある）。 */
        const val MIN_STEPS = 3

        private const val STEP_LEVEL = 0.5
        private const val MIN_STEP_INTERVAL_NANOS = 250_000_000L
        private const val MIN_SAMPLES = 20
        private const val MIN_SPAN_NANOS = 2_000_000_000L
        private const val MAX_AGE_NANOS = 1_000_000_000L
    }
}
