package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSelectionPolicyTest {
    private fun camera(
        key: String,
        logicalKey: String? = null,
        megapixels: Long = 12,
        mono: Boolean = false,
        focal: Float = 4.0f,
    ) = CameraCandidate(key, logicalKey, megapixels * 1_000_000, mono, listOf(focal))

    @Test
    fun `pixel style back camera keeps ultra wide and tele but not the duplicate wide`() {
        val cameras = listOf(
            camera("back0", focal = 6.9f),
            camera("back2", logicalKey = "back0", megapixels = 50, focal = 6.9f),
            camera("back3", logicalKey = "back0", megapixels = 48, focal = 2.0f),
            camera("back4", logicalKey = "back0", megapixels = 48, focal = 18.0f),
            camera("front1", megapixels = 42, focal = 2.7f),
        )
        assertEquals(listOf("back0", "back3", "back4", "front1"), CameraSelectionPolicy.select(cameras))
    }

    @Test
    fun `budget phone auxiliary mono and low resolution cameras are dropped`() {
        val cameras = listOf(
            camera("back0", megapixels = 50),
            camera("back2", megapixels = 2, mono = true),
            camera("back3", megapixels = 2),
            camera("front1", megapixels = 8),
        )
        assertEquals(listOf("back0", "front1"), CameraSelectionPolicy.select(cameras))
    }

    @Test
    fun `a physical camera is kept when its logical camera is unknown`() {
        assertEquals(listOf("back5"), CameraSelectionPolicy.select(listOf(camera("back5", logicalKey = "back9"))))
    }
}
