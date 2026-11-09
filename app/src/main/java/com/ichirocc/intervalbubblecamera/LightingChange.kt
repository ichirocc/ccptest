package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.sqrt

/** 前回の明るさ p から今回の明るさを予想する a × p + b（照明・露出が変わったときの明るさの関係）。 */
data class BrightnessModel(val gain: Double, val offset: Double) {
    fun predict(previous: Double): Double = (gain * previous + offset).coerceIn(0.0, 255.0)

    companion object {
        val SAME = BrightnessModel(1.0, 0.0)
    }
}

/**
 * 照明や露出の変化を打ち消してから比べる。部屋の照明が暗くなる・片側の照明が消えると、明るい所は大きく、
 * 暗い所は少しだけ変わる（掛け算の変化）ので、明るさの差の平均を引くだけでは画面じゅうが変わって見える。
 * そこで、前回と今回の明るさの関係を a × 前回 + b として画面全体で当てはめ（大きく変わった 2 割は人などの
 * 動きとみなして除き、もう一度当てはめる）、さらに区画ごとにも当てはめ直す。白く飛んだ所は比べない。
 *
 * 実機の写真 33 枚に照明の変化（全体が暗く・明るく、片側が消える、窓から日が差す、影、色合い、スポット
 * ライト）を加えて確かめると、照明だけで保存してしまう回が大きく減り（おすすめ: 正しく保存しなかったのが
 * 119/231 → 170/231）、これまでの場面の判定はほぼ変わらなかった。区画ごとの比べ方に近くとの比を組み合わせると、
 * 一部だけの照明（窓・影・スポットライト）で正しく保存しなかったのが 107/287 → 200/287 になり、保存するべき
 * 場面の見落としは 3/158 増えただけだった（実機の写真 41 枚）。
 */
object LightingChange {
    /** これ以上の明るさは白く飛んでいるとみなす（前回・予想）。 */
    const val SATURATED = 250

    /** 今回がこれ以上なら、白く飛んだ所として比べない。 */
    const val CURRENT_SATURATED = 245

    /** 当てはめに使う明るさの下限（真っ黒は使わない）。 */
    private const val DARK = 5

    /** 当てはめに要る画素の数。足りなければ明るさの差の平均だけを合わせる。 */
    private const val MIN_SAMPLES = 20

    /** 2 回目の当てはめで使う、差の小さい画素の割合。 */
    private const val KEEP = 0.8

    /** 区画の中の明るさのばらつきがこれ以下なら、傾きは求めず差の平均だけを合わせる。 */
    private const val MIN_BLOCK_SPREAD = 4.0

    /**
     * 前回 [previous] をずれ [shift] だけずらして今回 [current] に重ね、照明の変化を打ち消して比べたときの
     * 変わった画素。[blockSize] が 0 より大きければ、区画ごとにも当てはめ直す。前回に写っていなかった縁と、
     * 白く飛んだ所は「変わっていない」とする。
     */
    fun changedMask(
        previous: LumaFrame,
        current: LumaFrame,
        shift: FrameShift,
        threshold: MotionThreshold,
        blockSize: Int = 0,
    ): BooleanArray {
        require(previous.width == current.width && previous.height == current.height) {
            "frames must have the same size"
        }
        val width = current.width
        val height = current.height
        val count = width * height
        // 今回の画素 i に重なる前回の明るさ（重ならなければ -1）。
        val before = IntArray(count)
        val p = DoubleArray(count)
        val c = DoubleArray(count)
        var n = 0
        for (i in 0 until count) {
            val px = i % width - shift.dx
            val py = i / width - shift.dy
            val value = if (px in 0 until width && py in 0 until height) previous.pixels[py * width + px] else -1
            before[i] = value
            if (value >= 0) {
                p[n] = value.toDouble()
                c[n] = current.pixels[i].toDouble()
                n++
            }
        }
        val work = Work(count)
        val whole = fit(p, c, n, work)
        val predicted = DoubleArray(count)
        for (i in 0 until count) if (before[i] >= 0) predicted[i] = whole.predict(before[i].toDouble())
        val expected = if (blockSize > 0) {
            val fitted = predicted.copyOf()
            val blocksX = (width + blockSize - 1) / blockSize
            val blocksY = (height + blockSize - 1) / blockSize
            val bp = DoubleArray(blockSize * blockSize)
            val bc = DoubleArray(blockSize * blockSize)
            for (by in 0 until blocksY) for (bx in 0 until blocksX) {
                val y0 = by * blockSize
                val y1 = minOf(height, y0 + blockSize)
                val x0 = bx * blockSize
                val x1 = minOf(width, x0 + blockSize)
                var m = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val i = y * width + x
                    if (before[i] >= 0) {
                        bp[m] = predicted[i]
                        bc[m] = current.pixels[i].toDouble()
                        m++
                    }
                }
                if (m == 0) continue
                val model = if (spread(bp, m) > MIN_BLOCK_SPREAD) fit(bp, bc, m, work) else BrightnessModel(1.0, mean(bc, m) - mean(bp, m))
                for (y in y0 until y1) for (x in x0 until x1) {
                    val i = y * width + x
                    if (before[i] >= 0) fitted[i] = model.predict(predicted[i])
                }
            }
            fitted
        } else {
            predicted
        }
        // 区画ごとにそろえて比べるときは、近くの画素との明るさの比も変わったところだけを残す。窓から日が差す・影・
        // スポットライトのように一部だけ照明が変わると、その境目が区画をまたいで当てはめきれないが、照明は近くの
        // 画素を同じように明るく・暗くするので、近くとの比は変わらない（人が動くと比が変わる）。
        val previousNear = if (blockSize > 0) neighbourMeans(previous) else null
        val currentNear = if (blockSize > 0) neighbourMeans(current) else null
        return BooleanArray(count) { i ->
            val before0 = before[i]
            if (before0 < 0) return@BooleanArray false
            val now = current.pixels[i]
            val saturated = (before0 >= SATURATED || predicted[i] >= SATURATED) && now >= CURRENT_SATURATED
            val limit = threshold.pixelThresholdAt(before0)
            val changed = !saturated && abs(now - expected[i]) > limit
            if (!changed || previousNear == null || currentNear == null) return@BooleanArray changed
            val j = (i / width - shift.dy) * width + (i % width - shift.dx)
            val ratioBefore = (before0 + 1.0) / previousNear[j]
            val ratioNow = (now + 1.0) / currentNear[i]
            abs(ratioNow - ratioBefore) * currentNear[i] > limit
        }
    }

    /** 画素ごとの、まわり 3×3（画面の外は端の画素で埋める）の（明るさ + 1）の平均。 */
    private fun neighbourMeans(frame: LumaFrame): DoubleArray {
        val width = frame.width
        val height = frame.height
        return DoubleArray(width * height) { i ->
            val x = i % width
            val y = i / width
            var sum = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, width - 1)
                val ny = (y + dy).coerceIn(0, height - 1)
                sum += frame.pixels[ny * width + nx] + 1.0
            }
            sum / 9
        }
    }

    /** 前回 [p] から今回 [c] を予想する a × p + b を当てはめる（白く飛んだ所・真っ黒な所は使わない）。 */
    fun fit(p: List<Double>, c: List<Double>): BrightnessModel =
        fit(p.toDoubleArray(), c.toDoubleArray(), p.size, Work(p.size))

    /** 計算の途中で使う配列（呼ぶたびに作り直さないように使い回す）。 */
    private class Work(size: Int) {
        val p = DoubleArray(size)
        val c = DoubleArray(size)
        val residual = DoubleArray(size)
        val sorted = DoubleArray(size)
    }

    /** [p]・[c] の先頭 [n] 個に当てはめる。 */
    private fun fit(p: DoubleArray, c: DoubleArray, n: Int, work: Work): BrightnessModel {
        var usable = 0
        for (k in 0 until n) {
            if (p[k] > DARK && p[k] < SATURATED && c[k] < SATURATED) {
                work.p[usable] = p[k]
                work.c[usable] = c[k]
                usable++
            }
        }
        if (usable < MIN_SAMPLES) return BrightnessModel(1.0, mean(c, n) - mean(p, n))
        val first = leastSquares(work.p, work.c, usable)
        for (k in 0 until usable) {
            work.residual[k] = abs(work.c[k] - (first.gain * work.p[k] + first.offset))
            work.sorted[k] = work.residual[k]
        }
        java.util.Arrays.sort(work.sorted, 0, usable)
        val limit = work.sorted[((usable - 1) * KEEP).toInt()]
        var kept = 0
        for (k in 0 until usable) {
            if (work.residual[k] <= limit) {
                work.p[kept] = work.p[k]
                work.c[kept] = work.c[k]
                kept++
            }
        }
        return if (kept >= MIN_SAMPLES) leastSquares(work.p, work.c, kept) else first
    }

    private fun leastSquares(p: DoubleArray, c: DoubleArray, n: Int): BrightnessModel {
        val meanP = mean(p, n)
        val meanC = mean(c, n)
        var covariance = 0.0
        var variance = 0.0
        for (k in 0 until n) {
            covariance += (p[k] - meanP) * (c[k] - meanC)
            variance += (p[k] - meanP) * (p[k] - meanP)
        }
        if (variance <= 1e-9) return BrightnessModel(1.0, meanC - meanP)
        val gain = covariance / variance
        return BrightnessModel(gain, meanC - gain * meanP)
    }

    private fun mean(values: DoubleArray, n: Int): Double {
        if (n == 0) return 0.0
        var sum = 0.0
        for (k in 0 until n) sum += values[k]
        return sum / n
    }

    private fun spread(values: DoubleArray, n: Int): Double {
        val mean = mean(values, n)
        var sum = 0.0
        for (k in 0 until n) sum += (values[k] - mean) * (values[k] - mean)
        return sqrt(sum / n)
    }
}
