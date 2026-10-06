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
}
