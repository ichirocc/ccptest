package com.ichirocc.intervalbubblecamera

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettlingTest {
    private val settled = ThreeAState(
        ae = CaptureResult.CONTROL_AE_STATE_CONVERGED,
        awb = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        af = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
    )

    @Test
    fun `a camera has settled once exposure, white balance and focus have`() {
        assertTrue(CameraSettling.settled(settled))
        assertFalse(CameraSettling.settled(settled.copy(ae = CaptureResult.CONTROL_AE_STATE_SEARCHING)))
        assertFalse(CameraSettling.settled(settled.copy(awb = CaptureResult.CONTROL_AWB_STATE_SEARCHING)))
        assertFalse(CameraSettling.settled(settled.copy(af = CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)))
        assertFalse(CameraSettling.settled(settled.copy(af = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)))
    }

    @Test
    fun `fixed-focus cameras and unreported items count as settled`() {
        assertTrue(CameraSettling.settled(settled.copy(af = CaptureResult.CONTROL_AF_STATE_INACTIVE)))
        assertTrue(CameraSettling.settled(ThreeAState(ae = null, awb = null, af = null)))
        assertTrue(CameraSettling.settled(settled.copy(ae = CaptureResult.CONTROL_AE_STATE_LOCKED)))
    }

    @Test
    fun `waiting ends when every camera settled, but never too early or too late`() {
        val searching = settled.copy(ae = CaptureResult.CONTROL_AE_STATE_SEARCHING)
        assertFalse(CameraSettling.doneWaiting(listOf(settled), waitedMs = 50, maxWaitMs = 1000))
        assertTrue(CameraSettling.doneWaiting(listOf(settled), waitedMs = 120, maxWaitMs = 1000))
        assertFalse(CameraSettling.doneWaiting(listOf(settled, searching), waitedMs = 500, maxWaitMs = 1000))
        // まだ状態が届いていないカメラがあれば待つ。
        assertFalse(CameraSettling.doneWaiting(listOf(null), waitedMs = 500, maxWaitMs = 1000))
        assertTrue(CameraSettling.doneWaiting(listOf(searching), waitedMs = 1000, maxWaitMs = 1000))
    }
}
