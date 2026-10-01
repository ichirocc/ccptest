package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun `pixel 10 pro xl uses the flagship profile`() {
        assertEquals(DeviceProfile.FLAGSHIP, DeviceProfile.detect("Pixel 10 Pro XL", 0))
    }

    @Test
    fun `oppo a5 5g uses the standard profile`() {
        assertEquals(DeviceProfile.STANDARD, DeviceProfile.detect("CPH2735", 0))
    }

    @Test
    fun `a high media performance class is treated as flagship`() {
        assertEquals(DeviceProfile.FLAGSHIP, DeviceProfile.detect("Unknown", 35))
    }

    @Test
    fun `flagship tries the npu first and both profiles end on cpu`() {
        assertEquals("NPU", DeviceProfile.FLAGSHIP.delegates.first())
        DeviceProfile.entries.forEach { assertEquals("CPU", it.delegates.last()) }
        assertTrue(DeviceProfile.FLAGSHIP.detectSize > DeviceProfile.STANDARD.detectSize)
    }
}
