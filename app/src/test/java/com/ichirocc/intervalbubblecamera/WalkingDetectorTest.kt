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

    @Test
    fun `a few irregular jolts are not walking`() {
        val detector = WalkingDetector()
        // 0.2 秒ほどの強い衝撃が、ばらばらの間隔で 3 回（段差・ぶつけた など）。
        val last = detector.feed(3.0) { t -> if (t in 0.2..0.4 || t in 1.45..1.6 || t in 2.6..2.8) 14.0 else 9.8 }
        assertFalse(detector.isWalking(last))
    }

    @Test
    fun `a capture waits for a steady moment but not forever`() {
        assertFalse(SteadyMoment.shouldCapture(angularSpeed = 1.5, waitedMs = 100))
        assertTrue(SteadyMoment.shouldCapture(angularSpeed = 0.2, waitedMs = 100))
        assertTrue(SteadyMoment.shouldCapture(angularSpeed = 1.5, waitedMs = SteadyMoment.MAX_WAIT_MS))
        assertTrue(SteadyMoment.shouldCapture(angularSpeed = null, waitedMs = 0))
    }

    @Test
    fun `walking is detected however the phone is held`() {
        val detector = WalkingDetector()
        // スマホが斜めに傾いていても（重力が x と z に分かれる）、上下の揺れで歩行が分かる。
        var t = 0L
        while (t <= 3_000_000_000L) {
            val vertical = 9.8 + 2 * sin(2 * PI * 1.8 * t / 1e9)
            detector.add(t, (vertical * 0.6).toFloat(), 0f, (vertical * 0.8).toFloat())
            t += stepNanos
        }
        assertTrue(detector.isWalking(t - stepNanos))
    }
}
