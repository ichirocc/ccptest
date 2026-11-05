package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraMovementTest {
    /** z 軸まわりに [degrees] 回した向き。 */
    private fun turnedBy(degrees: Double): FloatArray {
        val half = Math.toRadians(degrees) / 2
        return floatArrayOf(cos(half).toFloat(), 0f, 0f, sin(half).toFloat())
    }

    @Test
    fun `rotation between orientations is measured in degrees`() {
        assertEquals(0.0, CameraMovement.rotationDegrees(turnedBy(0.0), turnedBy(0.0)), 0.05)
        assertEquals(10.0, CameraMovement.rotationDegrees(turnedBy(0.0), turnedBy(10.0)), 0.05)
        assertEquals(3.0, CameraMovement.rotationDegrees(turnedBy(5.0), turnedBy(8.0)), 0.05)
    }

    @Test
    fun `the same orientation with a flipped sign is no rotation`() {
        val q = turnedBy(30.0)
        val flipped = FloatArray(4) { -q[it] }
        assertEquals(0.0, CameraMovement.rotationDegrees(q, flipped), 0.05)
    }

    @Test
    fun `turning the phone counts as the phone moving`() {
        assertTrue(CameraMovement.phoneMoved(rotationDegrees = 4.0, changedRatio = 0.02))
        assertFalse(CameraMovement.phoneMoved(rotationDegrees = 0.5, changedRatio = 0.02))
    }

    @Test
    fun `a change over half the frame counts as the phone moving even without the sensor`() {
        assertTrue(CameraMovement.phoneMoved(rotationDegrees = null, changedRatio = 0.7))
        assertFalse(CameraMovement.phoneMoved(rotationDegrees = null, changedRatio = 0.1))
    }

    @Test
    fun `a close subject filling the frame is not phone movement when the sensor says the phone stayed still`() {
        assertFalse(CameraMovement.phoneMoved(rotationDegrees = 0.3, changedRatio = 0.7))
    }

    private val flatFaceUp = floatArrayOf(0f, 0f, 9.8f)
    private val standing = floatArrayOf(0f, 9.8f, 0f)

    @Test
    fun `tilt is the angle between two gravity directions`() {
        assertEquals(0.0, CameraMovement.tiltDegrees(flatFaceUp, flatFaceUp), 0.05)
        assertEquals(90.0, CameraMovement.tiltDegrees(flatFaceUp, standing), 0.05)
    }

    @Test
    fun `a phone on its back or face is lying flat`() {
        assertTrue(CameraMovement.isLyingFlat(flatFaceUp))
        assertTrue(CameraMovement.isLyingFlat(floatArrayOf(0.5f, 0.5f, -9.7f)))
        assertFalse(CameraMovement.isLyingFlat(standing))
    }

    @Test
    fun `a person stepping over a flat phone without a gyroscope is not phone movement`() {
        val motion = CameraMovement.judge(rotationDegrees = null, changedRatio = 0.8, tiltDegrees = 0.2, lyingFlat = true)
        assertEquals(PhoneMotion.STILL, motion)
        assertTrue(CameraMovement.closeSubject(motion, changedRatio = 0.8))
    }

    @Test
    fun `tilting the phone counts as moving even without a gyroscope`() {
        assertEquals(
            PhoneMotion.MOVED,
            CameraMovement.judge(rotationDegrees = null, changedRatio = 0.1, tiltDegrees = 5.0, lyingFlat = true),
        )
    }

    @Test
    fun `a large change is saved without detection only when the phone is known to be still`() {
        assertTrue(CameraMovement.closeSubject(PhoneMotion.STILL, changedRatio = 0.35))
        assertFalse(CameraMovement.closeSubject(PhoneMotion.STILL, changedRatio = 0.1))
        assertFalse(CameraMovement.closeSubject(PhoneMotion.UNKNOWN, changedRatio = 0.4))
        assertFalse(CameraMovement.closeSubject(PhoneMotion.MOVED, changedRatio = 0.9))
    }
}
