package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalPolicyTest {
    @Test
    fun `interval is clamped to the supported range`() {
        assertEquals(1, IntervalPolicy.clampSeconds(-10))
        assertEquals(1, IntervalPolicy.clampSeconds(1))
        assertEquals(60, IntervalPolicy.clampSeconds(60))
        assertEquals(60, IntervalPolicy.clampSeconds(999))
    }

    @Test
    fun `seek progress maps exactly to one through sixty seconds`() {
        assertEquals(1, IntervalPolicy.secondsFromSeekProgress(0))
        assertEquals(60, IntervalPolicy.secondsFromSeekProgress(59))
        assertEquals(0, IntervalPolicy.seekProgressFromSeconds(1))
        assertEquals(59, IntervalPolicy.seekProgressFromSeconds(60))
    }

    @Test
    fun `capture time is subtracted from the interval without going negative`() {
        assertEquals(8_500L, IntervalPolicy.delayAfterCapture(10, 1_500L))
        assertEquals(0L, IntervalPolicy.delayAfterCapture(1, 2_000L))
        assertEquals(1_000L, IntervalPolicy.delayAfterCapture(1, -100L))
    }
}
