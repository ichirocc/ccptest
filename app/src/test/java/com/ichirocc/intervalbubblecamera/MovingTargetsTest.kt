package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
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
}
