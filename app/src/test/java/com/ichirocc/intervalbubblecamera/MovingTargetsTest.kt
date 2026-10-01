package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a detected person overlapping the change is the moving target`() {
        val mask = MovingTargets.changedMask(still, personMoved, MotionThreshold.DEFAULT)
        assertEquals(personBox, MovingTargets.pick(mask, width, height, listOf(personBox, parkedCarBox)))
    }

    @Test
    fun `a detected object away from the change is not moving`() {
        val mask = MovingTargets.changedMask(still, personMoved, MotionThreshold.DEFAULT)
        assertNull(MovingTargets.pick(mask, width, height, listOf(parkedCarBox)))
    }

    @Test
    fun `nothing moves without a change`() {
        val mask = MovingTargets.changedMask(still, still, MotionThreshold.DEFAULT)
        assertNull(MovingTargets.pick(mask, width, height, listOf(personBox)))
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
}
