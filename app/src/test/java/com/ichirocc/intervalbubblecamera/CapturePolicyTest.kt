package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test
    fun `the flagship captures with every pixel while cool`() {
        assertTrue(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.NORMAL, night = false, fellBack = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.WARM, night = false, fellBack = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.NORMAL, night = true, fellBack = false))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.FLAGSHIP, HeatLevel.NORMAL, night = false, fellBack = true))
        assertFalse(CapturePolicy.useFullResolution(DeviceProfile.STANDARD, HeatLevel.NORMAL, night = false, fellBack = false))
    }

    @Test
    fun `enhanced capture is used unless night mode or a camera fell back`() {
        assertTrue(CapturePolicy.useEnhancedCapture(night = false, fellBack = false))
        assertFalse(CapturePolicy.useEnhancedCapture(night = true, fellBack = false))
        assertFalse(CapturePolicy.useEnhancedCapture(night = false, fellBack = true))
    }
}
