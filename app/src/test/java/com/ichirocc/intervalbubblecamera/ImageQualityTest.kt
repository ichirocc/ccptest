package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ImageQualityTest {
    private val width = MotionDetector.GRID_WIDTH
    private val height = MotionDetector.GRID_HEIGHT

    private fun noisy(mean: Int, sigma: Double, seed: Long): LumaFrame {
        val random = Random(seed)
        return LumaFrame(width, height, IntArray(width * height) {
            (mean + random.nextGaussian() * sigma).toInt().coerceIn(0, 255)
        })
    }

    @Test
    fun `the noise level of a frame is estimated`() {
        assertEquals(5.0, NightMotion.noiseSigma(noisy(120, 5.0, 1)), 0.7)
        assertEquals(12.0, NightMotion.noiseSigma(noisy(120, 12.0, 2)), 1.5)
        // なめらかな明るさの変化は雑音ではない。
        val gradient = LumaFrame(width, height, IntArray(width * height) { (it % width) * 2 + (it / width) })
        assertEquals(0.0, NightMotion.noiseSigma(gradient), 1e-9)
    }

    @Test
    fun `the threshold rises with the noise only in dark frames`() {
        val user = MotionThreshold.DEFAULT
        assertSame(user, NightMotion.thresholdFor(noisy(150, 10.0, 3), noisy(150, 10.0, 4), user))
        // 暗くてざらついた場面: 3 × √2 × 10 ≈ 43 まで上げる。
        val dark = NightMotion.thresholdFor(noisy(30, 10.0, 5), noisy(30, 10.0, 6), user)
        assertTrue(dark.pixelThreshold in 38..50)
        assertEquals(user.areaPermille, dark.areaPermille)
        // 暗くても雑音が小さければ、ユーザーの設定のまま。
        assertSame(user, NightMotion.thresholdFor(noisy(30, 2.0, 7), noisy(30, 2.0, 8), user))
    }

    @Test
    fun `a still dark scene is no longer motion once the threshold follows the noise`() {
        val previous = noisy(30, 12.0, 9)
        val current = noisy(30, 12.0, 10)
        val user = MotionThreshold.DEFAULT
        assertTrue(MotionDetector.compare(previous, current, user).motionDetected)
        assertFalse(MotionDetector.compare(previous, current, NightMotion.thresholdFor(previous, current, user)).motionDetected)
    }

    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun `a dark image is brightened and a bright one is kept`() {
        val w = 64
        val h = 48
        val dark = LowLightEnhancer.enhance(IntArray(w * h) { gray(20) }, w, h)
        val darkValue = dark[w * 10 + 10] and 0xFF
        assertTrue("dark pixel became $darkValue", darkValue in 61..255)
        assertEquals(0xFF, (dark[0] ushr 24) and 0xFF)

        val bright = LowLightEnhancer.enhance(IntArray(w * h) { gray(200) }, w, h)
        val brightValue = bright[w * 10 + 10] and 0xFF
        assertTrue("bright pixel became $brightValue", brightValue in 200..255)
        assertTrue(LowLightEnhancer.meanLuma(dark) > LowLightEnhancer.meanLuma(IntArray(w * h) { gray(20) }))
    }

    @Test
    fun `a blurred image is less sharp`() {
        val w = 40
        val h = 40
        val sharp = IntArray(w * h) { if ((it % w / 4 + it / w / 4) % 2 == 0) 220 else 30 }
        val blurred = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var sum = 0
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { sum += sharp[yy * w + xx]; n++ }
            }
            sum / n
        }
        assertTrue(Sharpness.varianceOfLaplacian(sharp, w, h) > 2 * Sharpness.varianceOfLaplacian(blurred, w, h))
        assertEquals(0.0, Sharpness.varianceOfLaplacian(IntArray(w * h) { 100 }, w, h), 1e-9)
    }

    @Test
    fun `a shot much softer than recent ones is blurry`() {
        val history = listOf(100.0, 110.0, 90.0)
        assertTrue(Sharpness.isBlurry(40.0, history))
        assertFalse(Sharpness.isBlurry(60.0, history))
        assertFalse(Sharpness.isBlurry(10.0, listOf(100.0, 100.0)))
    }

    @Test
    fun `a hand-held capture waits less than a walking one`() {
        assertFalse(SteadyMoment.shouldCapture(1.0, 300, SteadyMoment.MAX_WAIT_HANDHELD_MS))
        assertTrue(SteadyMoment.shouldCapture(1.0, SteadyMoment.MAX_WAIT_HANDHELD_MS, SteadyMoment.MAX_WAIT_HANDHELD_MS))
        assertFalse(SteadyMoment.shouldCapture(1.0, SteadyMoment.MAX_WAIT_HANDHELD_MS, SteadyMoment.MAX_WAIT_MS))
    }
}
