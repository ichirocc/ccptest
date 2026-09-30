package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    private val width = MotionDetector.GRID_WIDTH
    private val height = MotionDetector.GRID_HEIGHT

    private fun frame(fill: (x: Int, y: Int) -> Int): LumaFrame =
        LumaFrame(width, height, IntArray(width * height) { fill(it % width, it / width) })

    private val background = frame { x, y -> (x * 2 + y) % 200 + 20 }

    @Test
    fun `identical frames have no motion`() {
        val result = MotionDetector.compare(background, background, MotionSensitivity.HIGH)
        assertEquals(0.0, result.changedRatio, 0.0)
        assertFalse(result.motionDetected)
    }

    @Test
    fun `a moving object is detected`() {
        val withObject = frame { x, y ->
            if (x in 30 until 42 && y in 20 until 32) 250 else (x * 2 + y) % 200 + 20
        }
        val result = MotionDetector.compare(background, withObject, MotionSensitivity.MEDIUM)
        assertTrue(result.motionDetected)
    }

    @Test
    fun `global brightness change from auto exposure is ignored`() {
        val brighter = frame { x, y -> (x * 2 + y) % 200 + 20 + 30 }
        val result = MotionDetector.compare(background, brighter, MotionSensitivity.HIGH)
        assertFalse(result.motionDetected)
    }

    @Test
    fun `small sensor noise is ignored`() {
        val noisy = frame { x, y -> (x * 2 + y) % 200 + 20 + if ((x + y) % 2 == 0) 6 else -6 }
        val result = MotionDetector.compare(background, noisy, MotionSensitivity.HIGH)
        assertFalse(result.motionDetected)
    }

    @Test
    fun `sensitivity decides whether a small change counts`() {
        // 4x4 = 16 画素 / 4800 画素 ≒ 0.33%
        val small = frame { x, y ->
            if (x in 10 until 14 && y in 10 until 14) 255 else (x * 2 + y) % 200 + 20
        }
        assertTrue(MotionDetector.compare(background, small, MotionSensitivity.HIGH).motionDetected)
        assertFalse(MotionDetector.compare(background, small, MotionSensitivity.MEDIUM).motionDetected)
        assertFalse(MotionDetector.compare(background, small, MotionSensitivity.LOW).motionDetected)
    }

    @Test
    fun `sensitivity is restored from storage with a safe default`() {
        assertEquals(MotionSensitivity.HIGH, MotionSensitivity.fromStorageKey("high"))
        assertEquals(MotionSensitivity.MEDIUM, MotionSensitivity.fromStorageKey(null))
        assertEquals(MotionSensitivity.MEDIUM, MotionSensitivity.fromStorageKey("unknown"))
    }

    @Test
    fun `luma follows BT601 weights`() {
        assertEquals(0, MotionDetector.lumaOf(0xFF000000.toInt()))
        assertEquals(255, MotionDetector.lumaOf(0xFFFFFFFF.toInt()))
        assertTrue(MotionDetector.lumaOf(0xFF00FF00.toInt()) > MotionDetector.lumaOf(0xFFFF0000.toInt()))
    }
}
