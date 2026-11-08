package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 画面全体のずれ。今回の画像の (x, y) は、前回の画像の (x - [dx], y - [dy]) に写っていたものに当たる
 * （動体検知の 80×60 の格子の画素単位）。
 */
data class FrameShift(val dx: Int, val dy: Int) {
    /** 正規化座標（画像の幅・高さを 1 とする）でのずれ。 */
    fun normalizedX(width: Int): Double = dx.toDouble() / width
    fun normalizedY(height: Int): Double = dy.toDouble() / height

    companion object {
        val NONE = FrameShift(0, 0)
    }
}

/**
 * スマホが動いた（手持ちのぶれ・向きの変化）回でも動体を見つけるための、画面全体のずれの打ち消し
 * （手ぶれ補正・カメラの自己運動の補償と同じ考え方）。前回の画像を今回の画像に重なるようずらしてから比べ、
 * それでも変わったところだけを動いたものとする。
 */
object FrameAlignment {
    /** 探すずれの最大（格子の画素。80 画素の幅でおよそ画角の 1 割、手持ちのぶれならほぼ収まる）。 */
    const val MAX_SHIFT = 10

    /** 重なりがこれより少ないずれは使わない（比べられる部分が狭すぎる）。 */
    private const val MIN_OVERLAP_RATIO = 0.5

    /**
     * 前回 [previous] と今回 [current] の画面全体のずれを、重なった部分の明るさの差（露出の変化は
     * 差し引く）が一番小さくなるずれとして求める。
     */
    fun estimate(previous: LumaFrame, current: LumaFrame, maxShift: Int = MAX_SHIFT): FrameShift {
        require(previous.width == current.width && previous.height == current.height) {
            "frames must have the same size"
        }
        val width = current.width
        val height = current.height
        val shift = meanShift(previous, current)
        var best = FrameShift.NONE
        var bestCost = Double.MAX_VALUE
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                val overlapWidth = width - abs(dx)
                val overlapHeight = height - abs(dy)
                if (overlapWidth * overlapHeight < width * height * MIN_OVERLAP_RATIO) continue
                var sum = 0L
                for (y in maxOf(0, dy) until minOf(height, height + dy)) {
                    val row = y * width
                    val previousRow = (y - dy) * width
                    for (x in maxOf(0, dx) until minOf(width, width + dx)) {
                        sum += abs(current.pixels[row + x] - previous.pixels[previousRow + x - dx] - shift)
                    }
                }
                val cost = sum.toDouble() / (overlapWidth * overlapHeight)
                // 同じくらいならずれの小さいほうを選ぶ（模様の無い壁などで遠くへ飛ばない）。
                if (cost < bestCost - 1e-9 || (abs(cost - bestCost) <= 1e-9 && abs(dx) + abs(dy) < abs(best.dx) + abs(best.dy))) {
                    bestCost = cost
                    best = FrameShift(dx, dy)
                }
            }
        }
        return best
    }

    /**
     * ずれ [shift] を打ち消して比べたときに変わった画素（[MovingTargets.changedMask] と同じ基準）。
     * 前回の画像に写っていなかった縁の部分は「変わっていない」とする。
     */
    fun alignedMask(
        previous: LumaFrame,
        current: LumaFrame,
        shift: FrameShift,
        threshold: MotionThreshold,
        blockSize: Int = 0,
    ): BooleanArray {
        val width = current.width
        val height = current.height
        val mean = meanShift(previous, current)
        val blockShift = if (blockSize > 0) blockMeanShifts(previous, current, shift, blockSize) else null
        return BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val px = x - shift.dx
            val py = y - shift.dy
            val offset = blockShift?.get((y / blockSize) * ((width + blockSize - 1) / blockSize) + x / blockSize) ?: mean
            px in 0 until width && py in 0 until height &&
                abs(current.pixels[i] - previous.pixels[py * width + px] - offset) >
                threshold.pixelThresholdAt(previous.pixels[py * width + px])
        }
    }

    /**
     * ずれを打ち消して比べた動体検知の結果。変わった画素の割合は、前回と重なった部分に対する割合
     * （縁は比べられないため）。
     */
    fun compareAligned(previous: LumaFrame, current: LumaFrame, shift: FrameShift, threshold: MotionThreshold): MotionResult {
        val width = current.width
        val height = current.height
        val mask = alignedMask(previous, current, shift, threshold)
        val overlap = (width - abs(shift.dx)) * (height - abs(shift.dy))
        var changed = 0
        var sumX = 0L
        var sumY = 0L
        for (i in mask.indices) if (mask[i]) {
            changed++
            sumX += i % width
            sumY += i / width
        }
        val ratio = if (overlap <= 0) 0.0 else changed.toDouble() / overlap
        val detected = ratio >= threshold.areaRatio
        val center = if (detected) {
            MotionCenter((sumX.toDouble() / changed + 0.5) / width, (sumY.toDouble() / changed + 0.5) / height)
        } else {
            null
        }
        return MotionResult(ratio, detected, center)
    }

    /**
     * 区画（[blockSize] 格子四方）ごとの、前回から今回への明るさの平均の差。照明が画面の一部だけ明るく・暗く
     * なっても、区画ごとに差を引けばその変化は消える（人の動きは区画の中で形が変わるので残る）。
     */
    private fun blockMeanShifts(previous: LumaFrame, current: LumaFrame, shift: FrameShift, blockSize: Int): IntArray {
        val width = current.width
        val height = current.height
        val bw = (width + blockSize - 1) / blockSize
        val bh = (height + blockSize - 1) / blockSize
        val sums = LongArray(bw * bh)
        val counts = IntArray(bw * bh)
        for (y in 0 until height) for (x in 0 until width) {
            val px = x - shift.dx
            val py = y - shift.dy
            if (px !in 0 until width || py !in 0 until height) continue
            val b = (y / blockSize) * bw + x / blockSize
            sums[b] += (current.pixels[y * width + x] - previous.pixels[py * width + px]).toLong()
            counts[b]++
        }
        return IntArray(bw * bh) { if (counts[it] == 0) 0 else Math.round(sums[it].toDouble() / counts[it]).toInt() }
    }

    /** 区画ごとに明るさをそろえて比べるときの区画の大きさ（格子。80×60 なら 8×6 区画）。 */
    const val BLOCK_SIZE = 10

    /** スマホが止まっている扱いでも、これだけ（格子）までのずれは重ね直す（2° 未満の手ぶれ）。 */
    const val STEADY_MAX_SHIFT = 2

    /** [mask] のうち、前回と重なった部分に対する変化した画素の割合。 */
    fun changedRatio(mask: BooleanArray, width: Int, height: Int, shift: FrameShift): Double {
        val overlap = (width - abs(shift.dx)) * (height - abs(shift.dy))
        return if (overlap <= 0) 0.0 else mask.count { it }.toDouble() / overlap
    }

    /**
     * ずれ [shift] を打ち消しても画面の [MAX_ALIGNED_CHANGE_RATIO] 以上が変わっている、または見つけたずれが探した範囲
     * [maxShift] の端（それより大きく動いた）なら、打ち消しきれていない（回りすぎ・その場で回した・大きく動いた）と
     * みなす。その回は、ずれを打ち消した比べ方を使わない。
     */
    fun aligned(result: MotionResult, shift: FrameShift = FrameShift.NONE, maxShift: Int = MAX_SHIFT): Boolean =
        result.changedRatio < MAX_ALIGNED_CHANGE_RATIO && abs(shift.dx) < maxShift && abs(shift.dy) < maxShift

    /**
     * 重ね直せたとみなす、重ね直した後に変わった画素の割合の上限。実機の写真で、手に持って重ね直せた回は 0.5〜10%、
     * スマホをひざの上などで大きく動かして重ね直せていない回は 30〜48% だった。
     */
    const val MAX_ALIGNED_CHANGE_RATIO = 0.25

    private fun meanShift(previous: LumaFrame, current: LumaFrame): Int =
        ((current.pixels.sum() - previous.pixels.sum()).toDouble() / current.pixels.size).roundToInt()
}
