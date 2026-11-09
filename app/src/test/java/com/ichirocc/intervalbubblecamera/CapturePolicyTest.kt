package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    private val everything = CaptureSetup(fullResolution = true, enhanced = true, ultraHdr = true)

    @Test
    fun `the flagship captures with every pixel until it gets hot`() {
        assertTrue(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.NORMAL, night = false))
        // やや熱いときも写真の大きさは落とさない（4080×3072 → 1920×1440 にならないように）。
        assertTrue(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.WARM, night = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.HOT, night = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.CRITICAL, night = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.NORMAL, night = true))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.STANDARD, HeatLevel.NORMAL, night = false))
    }

    @Test
    fun `enhanced capture is used unless night mode`() {
        assertTrue(CapturePolicy.useEnhancedCapture(night = false))
        assertFalse(CapturePolicy.useEnhancedCapture(night = true))
    }

    @Test
    fun `features are dropped one at a time, Ultra HDR first and every pixel last`() {
        assertEquals(everything, CapturePolicy.setupAt(everything, 0))
        assertEquals(CaptureSetup(fullResolution = true, enhanced = true, ultraHdr = false), CapturePolicy.setupAt(everything, 1))
        assertEquals(CaptureSetup(fullResolution = true, enhanced = false, ultraHdr = false), CapturePolicy.setupAt(everything, 2))
        assertEquals(CaptureSetup.PLAIN, CapturePolicy.setupAt(everything, 3))
        assertTrue(CapturePolicy.setupAt(everything, 3).plain)
    }

    @Test
    fun `levels that change nothing are skipped`() {
        assertEquals(1, CapturePolicy.nextLevel(everything, 0))
        // Ultra HDR に対応していないカメラは、最初に質を上げる設定を外す。
        val noHdr = everything.copy(ultraHdr = false)
        assertEquals(2, CapturePolicy.nextLevel(noHdr, 0))
        // 全画素を使わない機種では、質を上げる設定を外せば終わり。
        val notFull = CaptureSetup(fullResolution = false, enhanced = true, ultraHdr = false)
        assertEquals(2, CapturePolicy.nextLevel(notFull, 0))
        assertNull(CapturePolicy.nextLevel(notFull, 2))
        assertNull(CapturePolicy.nextLevel(everything, 3))
    }

    @Test
    fun `a bind failure lasts for the session but a capture failure is retried later`() {
        val fallback = CaptureFallback()
        fallback.bindFailed(listOf("back0"), 1)
        assertEquals(1, fallback.level("back0", 1))
        assertEquals(1, fallback.level("back0", 1_000))
        assertEquals(0, fallback.level("back2", 1))

        fallback.captureFailed("back2", cycle = 5, newLevel = 2)
        assertEquals(2, fallback.level("back2", 5))
        assertEquals(2, fallback.level("back2", 5 + CaptureFallback.RETRY_AFTER_CYCLES - 1))
        assertEquals("tried again later", 0, fallback.level("back2", 5 + CaptureFallback.RETRY_AFTER_CYCLES))
        assertEquals(2, fallback.level(listOf("back0", "back2"), 6))

        fallback.clear()
        assertEquals(0, fallback.level("back0", 1))
    }
}
