package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePresetTest {
    @Test
    fun `the standard preset keeps the previous defaults`() {
        assertEquals(IntervalPolicy.DEFAULT_SECONDS, CapturePreset.STANDARD.intervalSeconds)
        assertEquals(MotionThreshold.DEFAULT, CapturePreset.STANDARD.threshold)
        assertEquals(CapturePreset.STANDARD, CapturePreset.DEFAULT)
    }

    @Test
    fun `saved values are recognised as their preset`() {
        for (preset in CapturePreset.choices) {
            assertEquals(preset, CapturePreset.matching(preset.intervalSeconds, preset.threshold))
        }
        assertEquals(CapturePreset.CUSTOM, CapturePreset.matching(7, MotionThreshold.DEFAULT))
        assertEquals(CapturePreset.CUSTOM, CapturePreset.matching(10, MotionThreshold(29, 10)))
    }

    @Test
    fun `every preset is a valid setting and they differ from each other`() {
        for (preset in CapturePreset.choices) {
            assertTrue(preset.intervalSeconds in IntervalPolicy.MIN_SECONDS..IntervalPolicy.MAX_SECONDS)
            assertEquals(preset.threshold, MotionThreshold.clamped(preset.pixelThreshold, preset.areaPermille))
        }
        val values = CapturePreset.choices.map { Triple(it.intervalSeconds, it.pixelThreshold, it.areaPermille) }
        assertEquals(values.size, values.toSet().size)
        assertFalse(CapturePreset.CUSTOM in CapturePreset.choices)
    }

    @Test
    fun `the previous distant values are upgraded to the current distant preset`() {
        val upgraded = CapturePreset.upgradedThreshold(5, MotionThreshold(22, 3))
        assertEquals(CapturePreset.DISTANT.threshold, upgraded)
        assertEquals(CapturePreset.DISTANT, CapturePreset.matching(5, upgraded!!))
        assertEquals(null, CapturePreset.upgradedThreshold(10, MotionThreshold(22, 3)))
        assertEquals(null, CapturePreset.upgradedThreshold(5, MotionThreshold(24, 3)))
    }

    @Test
    fun `distant scenes are more sensitive and nearby scenes less`() {
        val standard = CapturePreset.STANDARD
        assertTrue(CapturePreset.DISTANT.pixelThreshold <= standard.pixelThreshold)
        assertTrue(CapturePreset.DISTANT.areaPermille < standard.areaPermille)
        assertTrue(CapturePreset.NEARBY.areaPermille > standard.areaPermille)
        assertTrue(CapturePreset.LONG_RUN.intervalSeconds > standard.intervalSeconds)
    }
}
