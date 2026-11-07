package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingTargetsTest {
    private val width = MotionDetector.GRID_WIDTH
    private val height = MotionDetector.GRID_HEIGHT

    private fun frame(fill: (x: Int, y: Int) -> Int): LumaFrame =
        LumaFrame(width, height, IntArray(width * height) { fill(it % width, it / width) })

    private val still = frame { x, y -> (x + y) % 150 + 40 }
    private val personMoved = frame { x, y ->
        if (x in 10 until 20 && y in 20 until 50) 250 else (x + y) % 150 + 40
    }

    private val personBox = NormalizedBox(0.10, 0.30, 0.28, 0.85)
    private val parkedCarBox = NormalizedBox(0.60, 0.50, 0.95, 0.90)

    @Test
    fun `most of the change falls inside the moving person`() {
        val mask = MovingTargets.changedMask(still, personMoved, MotionThreshold.DEFAULT)
        assertEquals(1.0, MovingTargets.shareOfChangeInside(mask, width, height, personBox), 1e-9)
        assertEquals(0.0, MovingTargets.shareOfChangeInside(mask, width, height, parkedCarBox), 1e-9)
    }

    @Test
    fun `nothing is inside when nothing changed`() {
        val mask = MovingTargets.changedMask(still, still, MotionThreshold.DEFAULT)
        assertEquals(0.0, MovingTargets.shareOfChangeInside(mask, width, height, personBox), 1e-9)
    }

    @Test
    fun `upright boxes are mapped back to sensor orientation`() {
        val upright = NormalizedBox(0.6, 0.1, 0.9, 0.3)
        assertEquals(upright, upright.unrotate(0))
        assertBox(NormalizedBox(0.1, 0.1, 0.3, 0.4), upright.unrotate(90))
        assertBox(NormalizedBox(0.1, 0.7, 0.4, 0.9), upright.unrotate(180))
        assertBox(NormalizedBox(0.7, 0.6, 0.9, 0.9), upright.unrotate(270))
    }

    @Test
    fun `box center is the middle of the box`() {
        assertEquals(MotionCenter(0.5, 0.25), NormalizedBox(0.4, 0.0, 0.6, 0.5).center)
    }

    private fun assertBox(expected: NormalizedBox, actual: NormalizedBox) {
        assertEquals(expected.left, actual.left, 1e-9)
        assertEquals(expected.top, actual.top, 1e-9)
        assertEquals(expected.right, actual.right, 1e-9)
        assertEquals(expected.bottom, actual.bottom, 1e-9)
    }

    private fun mask(changed: (x: Int, y: Int) -> Boolean) = BooleanArray(width * height) { changed(it % width, it / width) }

    @Test
    fun `a small distant change gives a zoom region around it with a margin`() {
        // 横 40〜44・縦 30〜35 だけが変わった（遠くの小さな人）。
        val region = MovingTargets.changedRegion(mask { x, y -> x in 40..44 && y in 30..35 }, width, height)!!
        assertEquals(MovingTargets.REGION_MIN_SIZE, region.right - region.left, 1e-9)
        assertEquals(MovingTargets.REGION_MIN_SIZE, region.bottom - region.top, 1e-9)
        assertTrue(region.left <= 40.0 / width && region.right >= 45.0 / width)
        assertTrue(region.top <= 30.0 / height && region.bottom >= 36.0 / height)
    }

    @Test
    fun `scattered noise does not stretch the zoom region`() {
        val changed = mask { x, y -> (x in 40..47 && y in 30..39) || (x == 0 && y == 0) || (x == 79 && y == 59) }
        val region = MovingTargets.changedRegion(changed, width, height)!!
        assertTrue(region.area <= MovingTargets.REGION_MAX_AREA)
        assertTrue(region.left > 0.0 && region.right < 1.0)
    }

    @Test
    fun `no zoom region for no change or a change over most of the frame`() {
        assertNull(MovingTargets.changedRegion(mask { _, _ -> false }, width, height))
        assertNull(MovingTargets.changedRegion(mask { x, _ -> x < 70 }, width, height))
    }

    @Test
    fun `a change spread over the whole frame is not concentrated on a person`() {
        val box = NormalizedBox(0.25, 0.25, 0.5, 0.75)
        val everywhere = mask { x, y -> (x + y) % 3 == 0 }
        assertFalse(MovingTargets.changeConcentratedInside(everywhere, width, height, box))
        val onPerson = mask { x, y -> x in 20..39 && y in 15..44 && (x + y) % 2 == 0 }
        assertTrue(MovingTargets.changeConcentratedInside(onPerson, width, height, box))
        assertFalse(MovingTargets.changeConcentratedInside(mask { _, _ -> false }, width, height, box))
    }

    @Test
    fun `a lighting change in one part of the frame disappears once each block is evened out`() {
        // 右上の 4 分の 1（区画の境目に合わせた範囲）だけ 60 明るくなった。
        val lit = frame { x, y -> ((x + y) % 150 + 40) + if (x >= 40 && y < 30) 60 else 0 }
        val plain = FrameAlignment.alignedMask(still, lit, FrameShift.NONE, MotionThreshold.DEFAULT)
        val evened = FrameAlignment.alignedMask(still, lit, FrameShift.NONE, MotionThreshold.DEFAULT, FrameAlignment.BLOCK_SIZE)
        assertTrue(plain.count { it } > width * height / 5)
        assertEquals(0, evened.count { it })
        // 人が動いた変化は、区画ごとにそろえても残る（人の形は区画の境目にそろわない）。
        val person = frame { x, y -> if (x in 12..17 && y in 22..47) 250 else (x + y) % 150 + 40 }
        val evenedPerson = FrameAlignment.alignedMask(still, person, FrameShift.NONE, MotionThreshold.DEFAULT, FrameAlignment.BLOCK_SIZE)
        assertTrue(evenedPerson.count { it } > 0)
    }

    @Test
    fun `the motion grid averages every pixel in each cell`() {
        // 160×120 の画像を 80×60 に: 格子 1 つに 2×2 画素。白と黒の市松模様は灰色になる。
        val w = 160
        val h = 120
        val white = (0xFF shl 24) or 0xFFFFFF
        val black = 0xFF shl 24
        val argb = IntArray(w * h) { if ((it % w + it / w) % 2 == 0) white else black }
        val grid = MotionDetector.gridFromArgb(argb, w, h)
        assertEquals(MotionDetector.GRID_WIDTH, grid.width)
        assertTrue(grid.pixels.all { it in 125..130 })
        // 1 画素ずらしても、平均した値はほとんど変わらない（点で取ると白と黒が入れ替わる）。
        val shifted = IntArray(w * h) { if ((it % w + it / w + 1) % 2 == 0) white else black }
        val grid2 = MotionDetector.gridFromArgb(shifted, w, h)
        assertTrue(grid.pixels.indices.all { kotlin.math.abs(grid.pixels[it] - grid2.pixels[it]) <= 1 })
    }
}
