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
    fun `motion center points at the moving object`() {
        val withObject = frame { x, y ->
            if (x in 60 until 70 && y in 5 until 15) 250 else (x * 2 + y) % 200 + 20
        }
        val center = MotionDetector.compare(background, withObject, MotionSensitivity.MEDIUM).center!!
        assertEquals(65.0 / width, center.x, 0.02)
        assertEquals(10.0 / height, center.y, 0.02)
    }

    @Test
    fun `no center is reported without motion`() {
        assertEquals(null, MotionDetector.compare(background, background, MotionSensitivity.HIGH).center)
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

class CameraSetMotionTest {
    private fun result(motion: Boolean, ratio: Double = if (motion) 0.05 else 0.001) =
        MotionResult(ratio, motion, if (motion) MotionCenter(0.5, 0.5) else null)

    @Test
    fun `first cycle is a baseline`() {
        val decision = CameraSetMotion.decide(mapOf("back0" to null, "front1" to null))
        assertEquals(CameraSetDecision.Baseline, decision)
    }

    @Test
    fun `motion on any camera triggers the set`() {
        val decision = CameraSetMotion.decide(
            mapOf(
                "back0" to result(false),
                "back2" to result(false),
                "back3" to result(true),
                "front1" to result(false),
            ),
        )
        assertEquals(CameraSetDecision.Motion(setOf("back3")), decision)
    }

    @Test
    fun `no motion on any camera skips the set with the largest change`() {
        val decision = CameraSetMotion.decide(
            mapOf("back0" to result(false, 0.002), "front1" to result(false, 0.004)),
        )
        assertEquals(CameraSetDecision.NoMotion(0.004), decision)
    }

    @Test
    fun `a camera without a previous frame is not judged`() {
        val decision = CameraSetMotion.decide(mapOf("back0" to result(false), "front1" to null))
        assertTrue(decision is CameraSetDecision.NoMotion)
    }
}
