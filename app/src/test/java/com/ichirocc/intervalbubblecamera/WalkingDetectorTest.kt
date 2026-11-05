package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class WalkingDetectorTest {
    private val stepNanos = 20_000_000L // 毎秒 50 回

    /** [seconds] 秒ぶん、加速度の大きさ [magnitude]（重力を含む、時刻は秒）を縦向きに加える。 */
    private fun WalkingDetector.feed(seconds: Double, magnitude: (Double) -> Double): Long {
        var t = 0L
        while (t <= (seconds * 1e9).toLong()) {
            add(t, 0f, magnitude(t / 1e9).toFloat(), 0f)
            t += stepNanos
        }
        return t - stepNanos
    }

    @Test
    fun `walking with the phone is detected`() {
        val detector = WalkingDetector()
        // 1 秒に 1.8 歩、1 歩ごとに ±2 m/s² 揺れる。
        val last = detector.feed(3.0) { 9.8 + 2 * sin(2 * PI * 1.8 * it) }
        assertTrue(detector.isWalking(last))
    }

    @Test
    fun `a still or hand-held phone is not walking`() {
        val noise = Random(1)
        val still = WalkingDetector()
        assertFalse(still.isWalking(still.feed(3.0) { 9.8 + noise.nextGaussian() * 0.05 }))
        val shaking = WalkingDetector()
        assertFalse(shaking.isWalking(shaking.feed(3.0) { 9.8 + 0.3 * sin(2 * PI * 4.0 * it) }))
    }

    @Test
    fun `a single bump is not walking`() {
        val detector = WalkingDetector()
        val last = detector.feed(3.0) { if (it in 1.0..1.3) 15.8 else 9.8 }
        assertFalse(detector.isWalking(last))
    }

    @Test
    fun `old readings do not count`() {
        val detector = WalkingDetector()
        val last = detector.feed(3.0) { 9.8 + 2 * sin(2 * PI * 1.8 * it) }
        assertFalse(detector.isWalking(last + 1_500_000_000L))
        assertFalse(WalkingDetector().isWalking(0L))
    }
}
