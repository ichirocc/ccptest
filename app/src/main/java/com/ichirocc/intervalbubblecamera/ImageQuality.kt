package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 雑音の大きさが画素の明るさで変わる、という雑音のモデル（分散 = [shotGain] × 明るさ + [readVariance]）。
 * ELD（Wei et al., "A Physics-based Noise Formation Model for Extreme Low-light Raw Denoising", CVPR 2020）の
 * 物理モデルにならう: 光の粒の数のゆらぎ（明るいほど大きい）と、読み出しの雑音（明るさによらない）の和。
 */
data class NoiseModel(val shotGain: Double, val readVariance: Double) {
    /** 明るさ [luma]（0〜255）の画素の、1 枚の画像の雑音の標準偏差。 */
    fun sigma(luma: Double): Double = sqrt(max(0.0, shotGain * luma + readVariance))
}

/**
 * 暗い場面での動体検知。暗いとセンサーの雑音（ざらつき）だけで画素の明るさが毎回ゆらぎ、何も
 * 動いていなくても「変わった」と見えるので、前回と今回の差から雑音を見積もり、画素ごとに、その明るさの
 * 雑音より十分大きい変化だけを動いたとみなす。
 */
object NightMotion {
    /** 平均の明るさ（0〜255）がこれ未満なら暗い場面とみなし、雑音に合わせて閾値を上げる。 */
    const val DARK_MEAN = 60.0

    /**
     * 2 枚の差の雑音（1 枚の √2 倍）のこの倍以上の変化だけを、本当に変わったとみなす
     * （正規分布なら雑音だけで超えるのは 0.3% 未満）。
     */
    const val NOISE_SIGMAS = 3.0

    /** 雑音を見積もる明るさの区切りの幅と、区切りごとに要る画素の数。 */
    private const val BIN_WIDTH = 32
    private const val MIN_BIN_PIXELS = 40

    /**
     * 雑音から決める閾値の上限。80×60 に縮めた画像の雑音はこれよりずっと小さいので、これを超えるのは
     * 雑音ではなく見積もりの失敗（動いたものが多すぎた）。上げすぎて何も動体にならなくなるのを防ぐ。
     */
    const val MAX_NOISE_THRESHOLD = 60

    /** 雑音の標準偏差 [sigma]（1 枚ぶん）に対する、明るさの変化の閾値（[MAX_NOISE_THRESHOLD] まで）。 */
    fun thresholdForNoise(sigma: Double): Int =
        min(MAX_NOISE_THRESHOLD.toDouble(), ceil(NOISE_SIGMAS * sqrt(2.0) * sigma)).toInt()

    fun meanLuma(frame: LumaFrame): Double = frame.pixels.average()

    /**
     * 前回 [previous] と今回 [current] の差から、雑音のモデル（ELD の分散 = a × 明るさ + b）を求める。
     * 明るさの区切りごとに、差の中央絶対偏差（MAD）から雑音の大きさを求め（動いた画素が半分未満なら
     * その影響を受けない）、区切りの明るさに対して直線を当てはめる。求められなければ null。
     * スマホが動いた回は、画面全体のずれ [frameShift] を打ち消して重ねた画素どうしで比べる（ずれたまま
     * 比べると、景色の模様の差まで雑音として数えてしまう）。
     */
    fun estimateNoise(previous: LumaFrame, current: LumaFrame, frameShift: FrameShift = FrameShift.NONE): NoiseModel? {
        require(previous.width == current.width && previous.height == current.height) { "frames must have the same size" }
        val width = current.width
        val height = current.height
        val n = current.pixels.size
        val shift = ((current.pixels.sum() - previous.pixels.sum()).toDouble() / n).roundToInt()
        val bins = 256 / BIN_WIDTH
        val diffs = Array(bins) { mutableListOf<Int>() }
        val lumaSums = DoubleArray(bins)
        for (i in 0 until n) {
            val px = i % width - frameShift.dx
            val py = i / width - frameShift.dy
            if (px !in 0 until width || py !in 0 until height) continue
            val before = previous.pixels[py * width + px]
            val luma = (before + current.pixels[i]) / 2
            val bin = (luma / BIN_WIDTH).coerceIn(0, bins - 1)
            diffs[bin].add(current.pixels[i] - before - shift)
            lumaSums[bin] += luma.toDouble()
        }
        val points = mutableListOf<Triple<Double, Double, Int>>() // (明るさ, 1 枚の分散, 画素の数)
        for (bin in 0 until bins) {
            val values = diffs[bin]
            if (values.size < MIN_BIN_PIXELS) continue
            val median = median(values.map { it.toDouble() })
            val mad = median(values.map { abs(it - median) })
            val sigmaOfDifference = 1.4826 * mad
            points.add(Triple(lumaSums[bin] / values.size, sigmaOfDifference * sigmaOfDifference / 2, values.size))
        }
        if (points.isEmpty()) return null
        if (points.size == 1) return NoiseModel(0.0, points[0].second)

        // 重み（画素の数）つきの最小二乗で 分散 = a × 明るさ + b を当てはめる。
        val w = points.sumOf { it.third.toDouble() }
        val mx = points.sumOf { it.first * it.third } / w
        val my = points.sumOf { it.second * it.third } / w
        val sxx = points.sumOf { (it.first - mx) * (it.first - mx) * it.third }
        val sxy = points.sumOf { (it.first - mx) * (it.second - my) * it.third }
        var a = if (sxx > 0.0) sxy / sxx else 0.0
        var b = my - a * mx
        if (a < 0.0) {
            a = 0.0
            b = my
        }
        if (b < 0.0) {
            b = 0.0
            a = points.sumOf { it.second * it.first * it.third } / points.sumOf { it.first * it.first * it.third }
        }
        return NoiseModel(a, b)
    }

    /**
     * 前回 [previous] と今回 [current] を比べるときの閾値。どちらかが暗ければ雑音のモデルを付け、画素ごとに
     * その明るさの雑音の [NOISE_SIGMAS] 倍（2 枚の差なので √2 倍してから）以上の変化だけを数える。
     * ユーザーの設定より下げることはない。明るい場面ではそのまま。
     */
    fun thresholdFor(
        previous: LumaFrame,
        current: LumaFrame,
        user: MotionThreshold,
        frameShift: FrameShift = FrameShift.NONE,
    ): MotionThreshold {
        if (min(meanLuma(previous), meanLuma(current)) >= DARK_MEAN) return user
        val noise = estimateNoise(previous, current, frameShift) ?: return user
        return user.copy(noise = noise)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/**
 * 暗い画像を、人の検出に渡す前に明るくする（保存する写真は変えない）。Zero-DCE（Guo et al.,
 * "Zero-Reference Deep Curve Estimation for Low-Light Image Enhancement", CVPR 2020）の明るくする曲線
 * LE(x) = x + α·x·(1 − x) を [ITERATIONS] 回くり返してかける。論文では α を画素ごとに小さな
 * ネットワークで決めるが、ここでは画像全体で 1 つの α を、論文の露出の目標（平均の明るさ
 * [TARGET_EXPOSURE]）に届くよう二分探索で決める。曲線は 256 段の表にしてから当てるので速い。
 */
object LowLightEnhancer {
    /** 論文の露出の目標（明るさ 0〜1 の平均）。 */
    const val TARGET_EXPOSURE = 0.6

    /** 曲線をくり返す回数（論文と同じ 8 回）。 */
    const val ITERATIONS = 8

    /** 平均の明るさ（0〜255）がこれ未満の画像だけ明るくする。 */
    const val DARK_MEAN = NightMotion.DARK_MEAN

    /** 明るさ [x]（0〜1）に曲線を [ITERATIONS] 回かけた値。 */
    fun curve(x: Double, alpha: Double): Double {
        var v = x
        repeat(ITERATIONS) { v += alpha * v * (1 - v) }
        return v
    }

    /** 0〜255 の明るさを曲線で変える表。 */
    fun table(alpha: Double): IntArray = IntArray(256) { (curve(it / 255.0, alpha) * 255).roundToInt().coerceIn(0, 255) }

    /** 明るさの度数分布 [histogram]（256 段）の平均が [TARGET_EXPOSURE] に届く α（0〜1）。 */
    fun alphaFor(histogram: IntArray): Double {
        val total = histogram.sum().toDouble()
        if (total == 0.0) return 0.0
        fun meanAfter(alpha: Double): Double {
            val t = table(alpha)
            var sum = 0.0
            for (i in 0 until 256) sum += t[i].toDouble() * histogram[i]
            return sum / total / 255
        }
        if (meanAfter(0.0) >= TARGET_EXPOSURE) return 0.0
        if (meanAfter(1.0) <= TARGET_EXPOSURE) return 1.0
        var lo = 0.0
        var hi = 1.0
        repeat(20) {
            val mid = (lo + hi) / 2
            if (meanAfter(mid) < TARGET_EXPOSURE) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** ARGB の画素 [argb] を明るくした新しい配列を返す（R・G・B それぞれに同じ曲線をかける）。 */
    fun enhance(argb: IntArray): IntArray {
        val histogram = IntArray(256)
        for (c in argb) histogram[MotionDetector.lumaOf(c)]++
        val t = table(alphaFor(histogram))
        return IntArray(argb.size) { i ->
            val c = argb[i]
            (c and 0xFF000000.toInt()) or (t[(c shr 16) and 0xFF] shl 16) or (t[(c shr 8) and 0xFF] shl 8) or t[c and 0xFF]
        }
    }

    /** ARGB の画素の平均の明るさ（0〜255）。 */
    fun meanLuma(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        var sum = 0L
        for (c in argb) sum += MotionDetector.lumaOf(c)
        return sum.toDouble() / argb.size
    }
}

/** ジャイロで測った回転の速さ（rad/s）の、直近の記録。 */
class GyroHistory(private val keepNanos: Long = KEEP_NANOS) {
    private val times = ArrayDeque<Long>()
    private val speeds = ArrayDeque<Double>()

    fun add(timestampNanos: Long, speed: Double) {
        times.addLast(timestampNanos)
        speeds.addLast(speed)
        while (times.isNotEmpty() && times.first() < timestampNanos - keepNanos) {
            times.removeFirst()
            speeds.removeFirst()
        }
    }

    /** [fromNanos]〜[toNanos] の回転の速さの最大。その間の記録が無ければ null。 */
    fun maxBetween(fromNanos: Long, toNanos: Long): Double? {
        var best: Double? = null
        for (i in times.indices) {
            if (times[i] in fromNanos..toNanos) best = max(best ?: 0.0, speeds[i])
        }
        return best
    }

    fun clear() {
        times.clear()
        speeds.clear()
    }

    companion object {
        /**
         * 覚えておく長さ。撮ってから手ぶれを見積もるまでに、人の検出（向きを変えて何回か・切り出して
         * もう 1 回）が入るので、それより十分長くする（毎秒 50 回で 500 個）。
         */
        const val KEEP_NANOS = 10_000_000_000L
    }
}

/**
 * 手ぶれの大きさを、画像ではなくジャイロから見積もる。Shi et al.（"Deep Online Fused Video
 * Stabilization", WACV 2022）と同じく、撮影中のカメラの回転をジャイロで測る: 露出の間に
 * 回転の速さ ω（rad/s）で t 秒回ると、像は焦点距離 f（画素）× ω × t だけ流れる。画像の幅に対する割合は
 * ω × t × 焦点距離（mm）／センサーの幅（mm）。
 */
object GyroBlur {
    /** 像の流れが画像の幅のこの割合を超えたら「ぶれている」（1200 万画素の幅 4000 画素で 8 画素）。 */
    const val MAX_BLUR_FRACTION = 0.002

    /** 露出の前後にこれだけ広げてジャイロの記録を探す（ジャイロは 20 ミリ秒ごとなので、短い露出でも 1 つは入る）。 */
    const val SEARCH_MARGIN_NANOS = 20_000_000L

    /** 像の流れの、画像の幅に対する割合。 */
    fun blurFraction(angularSpeed: Double, exposureSeconds: Double, focalOverSensorWidth: Double): Double =
        angularSpeed * exposureSeconds * focalOverSensorWidth

    fun isBlurry(fraction: Double): Boolean = fraction > MAX_BLUR_FRACTION
}
