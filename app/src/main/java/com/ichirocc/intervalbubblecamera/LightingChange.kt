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
 * 119/231 → 170/231）、これまでの場面の判定はほぼ変わらなかった。
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
        val before = IntArray(count) { i ->
            val px = i % width - shift.dx
            val py = i / width - shift.dy
            if (px in 0 until width && py in 0 until height) previous.pixels[py * width + px] else -1
        }
        val overlap = (0 until count).filter { before[it] >= 0 }
        val whole = fit(overlap.map { before[it].toDouble() }, overlap.map { current.pixels[it].toDouble() })
        val predicted = DoubleArray(count) { if (before[it] >= 0) whole.predict(before[it].toDouble()) else 0.0 }
        val expected = predicted.copyOf()
        if (blockSize > 0) {
            val blocksX = (width + blockSize - 1) / blockSize
            val blocksY = (height + blockSize - 1) / blockSize
            for (by in 0 until blocksY) for (bx in 0 until blocksX) {
                val cells = buildList {
                    for (y in by * blockSize until minOf(height, (by + 1) * blockSize)) {
                        for (x in bx * blockSize until minOf(width, (bx + 1) * blockSize)) {
                            val i = y * width + x
                            if (before[i] >= 0) add(i)
                        }
                    }
                }
                if (cells.isEmpty()) continue
                val p = cells.map { predicted[it] }
                val c = cells.map { current.pixels[it].toDouble() }
                val model = if (spread(p) > MIN_BLOCK_SPREAD) fit(p, c) else BrightnessModel(1.0, c.average() - p.average())
                for (i in cells) expected[i] = model.predict(predicted[i])
            }
        }
        return BooleanArray(count) { i ->
            val p = before[i]
            if (p < 0) return@BooleanArray false
            val c = current.pixels[i]
            val saturated = (p >= SATURATED || predicted[i] >= SATURATED) && c >= CURRENT_SATURATED
            !saturated && abs(c - expected[i]) > threshold.pixelThresholdAt(p)
        }
    }

    /** 前回 [p] から今回 [c] を予想する a × p + b を当てはめる（白く飛んだ所・真っ黒な所は使わない）。 */
    fun fit(p: List<Double>, c: List<Double>): BrightnessModel {
        val usable = p.indices.filter { p[it] > DARK && p[it] < SATURATED && c[it] < SATURATED }
        if (usable.size < MIN_SAMPLES) return BrightnessModel(1.0, c.averageOrZero() - p.averageOrZero())
        val first = leastSquares(usable.map { p[it] }, usable.map { c[it] })
        val residuals = usable.map { abs(c[it] - (first.gain * p[it] + first.offset)) }
        val limit = residuals.sorted()[((residuals.size - 1) * KEEP).toInt()]
        val kept = usable.filterIndexed { k, _ -> residuals[k] <= limit }
        return if (kept.size >= MIN_SAMPLES) leastSquares(kept.map { p[it] }, kept.map { c[it] }) else first
    }

    private fun leastSquares(p: List<Double>, c: List<Double>): BrightnessModel {
        val meanP = p.average()
        val meanC = c.average()
        var covariance = 0.0
        var variance = 0.0
        for (k in p.indices) {
            covariance += (p[k] - meanP) * (c[k] - meanC)
            variance += (p[k] - meanP) * (p[k] - meanP)
        }
        if (variance <= 1e-9) return BrightnessModel(1.0, meanC - meanP)
        val gain = covariance / variance
        return BrightnessModel(gain, meanC - gain * meanP)
    }

    private fun spread(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
