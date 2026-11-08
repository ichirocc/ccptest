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
    ): BooleanArray = LightingChange.changedMask(previous, current, shift, threshold, blockSize)

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
     * ずれを打ち消しても画面の大部分が変わっていれば、打ち消しきれていない（回りすぎ・その場で回した・
     * 大きく動いた）とみなす。その回は、ずれを打ち消した比べ方を使わない。
     */
    fun aligned(result: MotionResult): Boolean = result.changedRatio < CameraMovement.MAX_SUBJECT_CHANGE_RATIO

    private fun meanShift(previous: LumaFrame, current: LumaFrame): Int =
        ((current.pixels.sum() - previous.pixels.sum()).toDouble() / current.pixels.size).roundToInt()
}
