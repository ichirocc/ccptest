package com.ichirocc.intervalbubblecamera

import kotlin.math.sqrt

/**
 * 撮影している人が歩いている（体ごと動いている）かを、加速度の大きさの揺れから判定する。
 *
 * 歩行の判定は Zee（Rai et al., "Zee: Zero-Effort Crowdsourcing for Indoor Localization",
 * MobiCom 2012）にならい、揺れの「周期性」で見る。歩くと 1 歩ごとに同じ形の揺れがくり返すので、
 * 1 歩ぶん（0.3〜1.0 秒）ずらした自分自身との相関（正規化した自己相関）が高くなる。数回の衝撃や
 * 手のぶれでは高くならない。Brajdic & Harle（"Walk detection and step counting on unconstrained
 * smartphones", UbiComp 2013）の比較でも、ポケット・手持ちなど持ち方によらず安定した方法とされる。
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
     * （大きさが [MIN_STD] 以上で、1 歩ぶんずらした自己相関が [MIN_AUTOCORRELATION] 以上）が
     * あったか。値が届いていなければ false。
     */
    fun isWalking(nowNanos: Long): Boolean {
        dropOlderThan(nowNanos - windowNanos)
        if (times.size < MIN_SAMPLES) return false
        if (nowNanos - times.last() > MAX_AGE_NANOS) return false
        val span = times.last() - times.first()
        if (span < MIN_SPAN_NANOS) return false

        val n = magnitudes.size
        val mean = magnitudes.average()
        val centered = DoubleArray(n) { magnitudes[it] - mean }
        val variance = centered.sumOf { it * it } / n
        if (sqrt(variance) < MIN_STD) return false
        return stepAutocorrelation(centered, span.toDouble() / (n - 1)) >= MIN_AUTOCORRELATION
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
        /** 判定に使う直近の時間（1 歩が 1 秒でも 2 周期以上入る長さ）。 */
        const val WINDOW_NANOS = 3_000_000_000L

        /** 加速度の大きさの揺れ（標準偏差、m/s²）がこれ以上なら体ごと動いている。手のぶれは 0.3 程度まで。 */
        const val MIN_STD = 1.0

        /** 1 歩ぶんずらした自己相関がこれ以上なら、揺れが歩行のようにくり返している（Zee の閾値にならう）。 */
        const val MIN_AUTOCORRELATION = 0.6

        /** 1 歩の長さの範囲（走る〜ゆっくり歩く）。 */
        const val MIN_STEP_NANOS = 300_000_000L
        const val MAX_STEP_NANOS = 1_000_000_000L

        private const val MIN_SAMPLES = 20
        private const val MIN_SPAN_NANOS = 2_000_000_000L
        private const val MAX_AGE_NANOS = 1_000_000_000L

        /**
         * 平均を引いた揺れ [centered]（ほぼ等間隔、間隔 [intervalNanos]）を、1 歩の長さの範囲でずらした
         * 正規化自己相関の最大値（-1〜1）。重なる部分の長さで正規化する（Zee と同じ）。
         */
        fun stepAutocorrelation(centered: DoubleArray, intervalNanos: Double): Double {
            if (intervalNanos <= 0.0) return 0.0
            val minLag = maxOf(1, (MIN_STEP_NANOS / intervalNanos).toInt())
            val maxLag = minOf(centered.size / 2, (MAX_STEP_NANOS / intervalNanos).toInt())
            var best = -1.0
            for (lag in minLag..maxLag) {
                var sum = 0.0
                var energyA = 0.0
                var energyB = 0.0
                for (i in 0 until centered.size - lag) {
                    val a = centered[i]
                    val b = centered[i + lag]
                    sum += a * b
                    energyA += a * a
                    energyB += b * b
                }
                val norm = sqrt(energyA * energyB)
                if (norm > 0.0) best = maxOf(best, sum / norm)
            }
            return best
        }
    }
}

/**
 * 歩きながらの撮影で、ぶれの少ない瞬間を待って撮る。ジャイロで測った回転の速さが小さい瞬間を選ぶ
 * （Karpenko et al., "Digital Video Stabilization and Rolling Shutter Correction using Gyroscopes",
 * Stanford Tech Report 2011 の、ジャイロから画像のぶれを見積もる考え方と、ぶれの少ない瞬間の画像を選ぶ
 * "lucky imaging" の考え方）。歩くと 1 歩ごとにスマホが大きく揺れ、その合間に一瞬止まるので、
 * 最大で 1 歩ぶん待てばたいてい止まった瞬間がくる。
 */
object SteadyMoment {
    /** 回転の速さ（rad/s）がこれ以下なら、撮ってもぶれが小さい（1/30 秒の露出で 0.6° 未満）。 */
    const val STEADY_RAD_PER_SECOND = 0.35

    /** 歩きながらのとき、これ以上は待たない（およそ 1 歩。撮影間隔を大きく延ばさないため）。 */
    const val MAX_WAIT_MS = 700L

    /**
     * 手持ち（歩いていない）のとき、これ以上は待たない。手のふるえは 1 秒に数回なので、止まる瞬間は
     * すぐにくる。三脚などで止まっていれば待たずに撮れる。
     */
    const val MAX_WAIT_HANDHELD_MS = 400L

    /** 今撮ってよいか。止まった瞬間か、もう [maxWaitMs] 待ったなら撮る。速さが分からなければ待たない。 */
    fun shouldCapture(angularSpeed: Double?, waitedMs: Long, maxWaitMs: Long = MAX_WAIT_MS): Boolean =
        angularSpeed == null || angularSpeed <= STEADY_RAD_PER_SECOND || waitedMs >= maxWaitMs
}
