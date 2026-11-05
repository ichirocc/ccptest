package com.ichirocc.intervalbubblecamera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 暗い場面での動体検知。暗いとセンサーの雑音（ざらつき）だけで画素の明るさが毎回ゆらぎ、何も
 * 動いていなくても「変わった」と見えるので、画像から雑音の大きさを見積もり、明るさの変化の閾値を
 * 雑音より十分大きくする。
 *
 * 雑音の見積もりは Immerkær（"Fast Noise Variance Estimation", Computer Vision and Image
 * Understanding 1996）の方法: 画像の滑らかな変化を打ち消す 3×3 のマスクをかけた絶対値の平均から、
 * 雑音の標準偏差を求める。
 */
object NightMotion {
    /** 平均の明るさ（0〜255）がこれ未満なら暗い場面とみなし、雑音に合わせて閾値を上げる。 */
    const val DARK_MEAN = 60.0

    /**
     * 2 枚の差の雑音（1 枚の √2 倍）のこの倍以上の変化だけを、本当に変わったとみなす
     * （正規分布なら雑音だけで超えるのは 0.3% 未満）。
     */
    const val NOISE_SIGMAS = 3.0

    /** 画像の雑音の標準偏差（明るさ 0〜255 の単位）。Immerkær の方法。 */
    fun noiseSigma(frame: LumaFrame): Double {
        val w = frame.width
        val h = frame.height
        if (w < 3 || h < 3) return 0.0
        val p = frame.pixels
        var sum = 0L
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val v = p[i - w - 1] - 2 * p[i - w] + p[i - w + 1] -
                    2 * p[i - 1] + 4 * p[i] - 2 * p[i + 1] +
                    p[i + w - 1] - 2 * p[i + w] + p[i + w + 1]
                sum += abs(v)
            }
        }
        return sqrt(PI / 2) * sum / (6.0 * (w - 2) * (h - 2))
    }

    fun meanLuma(frame: LumaFrame): Double = frame.pixels.average()

    /**
     * 前回 [previous] と今回 [current] を比べるときの閾値。どちらかが暗ければ、明るさの変化の閾値を
     * 雑音の [NOISE_SIGMAS] 倍（2 枚の差なので √2 倍してから）以上に上げる。ユーザーの設定より
     * 下げることはない。明るい場面ではそのまま。
     */
    fun thresholdFor(previous: LumaFrame, current: LumaFrame, user: MotionThreshold): MotionThreshold {
        if (min(meanLuma(previous), meanLuma(current)) >= DARK_MEAN) return user
        val sigma = max(noiseSigma(previous), noiseSigma(current))
        val needed = ceil(NOISE_SIGMAS * sqrt(2.0) * sigma).toInt()
        if (needed <= user.pixelThreshold) return user
        return MotionThreshold.clamped(needed, user.areaPermille)
    }
}

/**
 * 暗い画像を、人の検出に渡す前に明るくする（保存する写真は変えない）。LIME（Guo et al.,
 * "LIME: Low-Light Image Enhancement via Illumination Map Estimation", IEEE TIP 2017）を軽くしたもの:
 * 画素ごとの R・G・B の最大値を照明とみなし、それをなめらかにした照明マップで各画素を割って明るくする。
 * 照明マップにはガンマ（[GAMMA]）をかけ、明るすぎ・ざらつきすぎを抑える。
 */
object LowLightEnhancer {
    /** 照明マップのガンマ（LIME の論文の値に近い 0.6〜0.8 のうち、暗所で強めに明るくする 0.6）。 */
    const val GAMMA = 0.6

    /** 照明をなめらかにする大きさ（何画素四方で平均するか）。 */
    const val BLOCK = 16

    /** 照明の下限（真っ暗な所で雑音を何十倍にもしないため）。 */
    private const val MIN_ILLUMINATION = 0.04

    /** 平均の明るさ（0〜255）がこれ未満の画像だけ明るくする。 */
    const val DARK_MEAN = NightMotion.DARK_MEAN

    /** ARGB の画素 [argb]（幅 [width]・高さ [height]）を明るくした新しい配列を返す。 */
    fun enhance(argb: IntArray, width: Int, height: Int): IntArray {
        require(argb.size == width * height) { "pixels must be width * height" }
        // 照明 = R・G・B の最大値（0〜1）を BLOCK 四方で平均した粗い地図。
        val gw = (width + BLOCK - 1) / BLOCK
        val gh = (height + BLOCK - 1) / BLOCK
        val sum = DoubleArray(gw * gh)
        val count = IntArray(gw * gh)
        for (y in 0 until height) for (x in 0 until width) {
            val c = argb[y * width + x]
            val m = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF)) / 255.0
            val g = (y / BLOCK) * gw + x / BLOCK
            sum[g] += m
            count[g]++
        }
        val grid = DoubleArray(gw * gh) { sum[it] / count[it] }

        val out = IntArray(argb.size)
        for (y in 0 until height) {
            // 粗い地図をなめらかにつなぐ（ブロックの中心どうしを直線でつなぐ）。
            val gy = ((y + 0.5) / BLOCK - 0.5).coerceIn(0.0, (gh - 1).toDouble())
            val y0 = gy.toInt()
            val y1 = min(y0 + 1, gh - 1)
            val fy = gy - y0
            for (x in 0 until width) {
                val gx = ((x + 0.5) / BLOCK - 0.5).coerceIn(0.0, (gw - 1).toDouble())
                val x0 = gx.toInt()
                val x1 = min(x0 + 1, gw - 1)
                val fx = gx - x0
                val t = (grid[y0 * gw + x0] * (1 - fx) + grid[y0 * gw + x1] * fx) * (1 - fy) +
                    (grid[y1 * gw + x0] * (1 - fx) + grid[y1 * gw + x1] * fx) * fy
                val gain = 1.0 / max(t, MIN_ILLUMINATION).pow(GAMMA)
                val c = argb[y * width + x]
                val r = min(255, (((c shr 16) and 0xFF) * gain).toInt())
                val g = min(255, (((c shr 8) and 0xFF) * gain).toInt())
                val b = min(255, ((c and 0xFF) * gain).toInt())
                out[y * width + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** ARGB の画素の平均の明るさ（0〜255）。 */
    fun meanLuma(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        var sum = 0L
        for (c in argb) sum += MotionDetector.lumaOf(c)
        return sum.toDouble() / argb.size
    }
}

/**
 * 写真のくっきり具合（ぶれ・ピンぼけの少なさ）。ラプラシアン（明るさの 2 回微分）の分散で測る
 * （Pech-Pacheco et al., "Diatom autofocusing in brightfield microscopy: a comparative study", ICPR 2000。
 * Pertuz et al., "Analysis of focus measure operators for shape-from-focus", Pattern Recognition 2013 の
 * 比較でも、速く安定した測り方とされる）。ぶれると輪郭がぼやけ、ラプラシアンが小さくなる。
 */
object Sharpness {
    /** 同じカメラの最近の写真の中央値に比べて、この割合未満なら「ぶれている」。 */
    const val BLURRY_RATIO = 0.5

    /** 「ぶれている」と比べるのに要る、最近の写真の数。 */
    const val MIN_HISTORY = 3

    /** 覚えておく最近の写真の数。 */
    const val HISTORY = 10

    /** 明るさ [luma]（幅 [width]・高さ [height]）のラプラシアンの分散。 */
    fun varianceOfLaplacian(luma: IntArray, width: Int, height: Int): Double {
        require(luma.size == width * height) { "pixels must be width * height" }
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val v = (luma[i - width] + luma[i + width] + luma[i - 1] + luma[i + 1] - 4 * luma[i]).toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** 最近の写真 [history] に比べて、[sharpness] の写真がぶれているか。比べる数が足りなければ false。 */
    fun isBlurry(sharpness: Double, history: List<Double>): Boolean {
        if (history.size < MIN_HISTORY) return false
        val sorted = history.sorted()
        val median = sorted[sorted.size / 2]
        return median > 0.0 && sharpness < median * BLURRY_RATIO
    }
}
