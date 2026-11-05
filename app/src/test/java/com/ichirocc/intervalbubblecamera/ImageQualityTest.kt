package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class ImageQualityTest {
    private val width = MotionDetector.GRID_WIDTH
    private val height = MotionDetector.GRID_HEIGHT

    /** 明るさ [base]（画素ごと）に、標準偏差 [sigma]（明るさごと）の雑音を足した画像。 */
    private fun noisy(seed: Long, base: (Int) -> Int, sigma: (Int) -> Double): LumaFrame {
        val random = Random(seed)
        return LumaFrame(width, height, IntArray(width * height) {
            val b = base(it)
            (b + random.nextGaussian() * sigma(b)).toInt().coerceIn(0, 255)
        })
    }

    private fun flat(mean: Int, sigma: Double, seed: Long) = noisy(seed, { mean }, { sigma })

    @Test
    fun `the noise of a dark scene is estimated from two frames`() {
        val noise = NightMotion.estimateNoise(flat(30, 12.0, 1), flat(30, 12.0, 2))!!
        assertEquals(12.0, noise.sigma(30.0), 2.0)
    }

    @Test
    fun `brighter pixels are noisier, as in the physics-based model`() {
        // 分散 = 2 × 明るさ + 4（明るいほどざらつく）。
        val base = { i: Int -> 10 + (i % width) * 240 / (width - 1) }
        val sigma = { b: Int -> sqrt(2.0 * b + 4) }
        val noise = NightMotion.estimateNoise(noisy(3, base, sigma), noisy(4, base, sigma))!!
        assertEquals(2.0, noise.shotGain, 0.7)
        assertTrue(noise.sigma(200.0) > noise.sigma(20.0))
    }

    @Test
    fun `the threshold follows the noise only in dark frames`() {
        val user = MotionThreshold.DEFAULT
        assertSame(user, NightMotion.thresholdFor(flat(150, 10.0, 5), flat(150, 10.0, 6), user))
        val dark = NightMotion.thresholdFor(flat(30, 10.0, 7), flat(30, 10.0, 8), user)
        // 3 × √2 × 10 ≈ 43 まで上がる。設定より下がることはない。
        assertTrue(dark.pixelThresholdAt(30) in 36..52)
        assertEquals(user.pixelThreshold, MotionThreshold.DEFAULT.pixelThresholdAt(30))
        assertTrue(dark.pixelThresholdAt(0) >= user.pixelThreshold)
    }

    @Test
    fun `a still dark scene is no longer motion once the threshold follows the noise`() {
        val previous = flat(30, 12.0, 9)
        val current = flat(30, 12.0, 10)
        val user = MotionThreshold.DEFAULT
        assertTrue(MotionDetector.compare(previous, current, user).motionDetected)
        assertFalse(MotionDetector.compare(previous, current, NightMotion.thresholdFor(previous, current, user)).motionDetected)
    }

    @Test
    fun `a person still counts as motion in the dark`() {
        val previous = flat(30, 6.0, 11)
        val still = flat(30, 6.0, 12)
        // 横 30〜45・縦 10〜49 に、まわりより明るい人が入ってきた。
        val current = LumaFrame(width, height, IntArray(width * height) { i ->
            if (i % width in 30..45 && i / width in 10..49) 110 else still.pixels[i]
        })
        val threshold = NightMotion.thresholdFor(previous, current, MotionThreshold.DEFAULT)
        assertTrue(MotionDetector.compare(previous, current, threshold).motionDetected)
    }

    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun `a dark image is brightened to the exposure target and a bright one is kept`() {
        val dark = LowLightEnhancer.enhance(IntArray(1000) { gray(20) })
        assertEquals(0.6 * 255, LowLightEnhancer.meanLuma(dark), 15.0)
        assertEquals(0xFF, (dark[0] ushr 24) and 0xFF)
        val bright = IntArray(1000) { gray(200) }
        assertEquals(200, LowLightEnhancer.enhance(bright)[0] and 0xFF)
    }

    @Test
    fun `the enhancement curve keeps black and white and never darkens`() {
        for (alpha in listOf(0.0, 0.5, 1.0)) {
            val table = LowLightEnhancer.table(alpha)
            assertEquals(0, table[0])
            assertEquals(255, table[255])
            for (i in 1 until 256) {
                assertTrue(table[i] >= table[i - 1])
                assertTrue(table[i] >= i)
            }
        }
    }

    @Test
    fun `gyro readings during the exposure are found`() {
        val history = GyroHistory()
        history.add(1_000_000_000L, 0.1)
        history.add(1_020_000_000L, 0.8)
        history.add(1_040_000_000L, 0.2)
        assertEquals(0.8, history.maxBetween(1_010_000_000L, 1_050_000_000L)!!, 1e-9)
        assertNull(history.maxBetween(2_000_000_000L, 2_100_000_000L))
    }

    @Test
    fun `turning during the exposure blurs the shot`() {
        // 焦点距離 6.9 mm・センサーの幅 9.6 mm（比 0.72）、露出 1/30 秒。
        val ratio = 6.9 / 9.6
        assertTrue(GyroBlur.isBlurry(GyroBlur.blurFraction(1.0, 1 / 30.0, ratio)))
        assertFalse(GyroBlur.isBlurry(GyroBlur.blurFraction(0.02, 1 / 30.0, ratio)))
        // 同じ回転でも、露出が短ければぶれない。
        assertFalse(GyroBlur.isBlurry(GyroBlur.blurFraction(0.3, 1 / 1000.0, ratio)))
    }

    @Test
    fun `a hand-held capture waits less than a walking one`() {
        assertFalse(SteadyMoment.shouldCapture(1.0, 300, SteadyMoment.MAX_WAIT_HANDHELD_MS))
        assertTrue(SteadyMoment.shouldCapture(1.0, SteadyMoment.MAX_WAIT_HANDHELD_MS, SteadyMoment.MAX_WAIT_HANDHELD_MS))
        assertFalse(SteadyMoment.shouldCapture(1.0, SteadyMoment.MAX_WAIT_HANDHELD_MS, SteadyMoment.MAX_WAIT_MS))
    }
}
