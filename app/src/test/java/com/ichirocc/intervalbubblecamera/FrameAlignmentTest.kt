package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameAlignmentTest {
    private val width = MotionDetector.GRID_WIDTH
    private val height = MotionDetector.GRID_HEIGHT
    private val random = Random(1)
    private val scene = IntArray(width * height) { 20 + random.nextInt(200) }
    private val previous = LumaFrame(width, height, scene)

    /** スマホが ([dx], [dy]) 動いたときの今回の画像。縁の新しく写った部分は別の模様。 */
    private fun shifted(dx: Int, dy: Int, edit: (x: Int, y: Int, value: Int) -> Int = { _, _, v -> v }): LumaFrame {
        val newEdge = Random(2)
        return LumaFrame(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val px = x - dx
            val py = y - dy
            val value = if (px in 0 until width && py in 0 until height) scene[py * width + px] else 20 + newEdge.nextInt(200)
            edit(x, y, value)
        })
    }

    @Test
    fun `the shift of the whole frame is found`() {
        assertEquals(FrameShift(3, -2), FrameAlignment.estimate(previous, shifted(3, -2)))
        assertEquals(FrameShift(-7, 5), FrameAlignment.estimate(previous, shifted(-7, 5)))
        assertEquals(FrameShift.NONE, FrameAlignment.estimate(previous, previous))
    }

    @Test
    fun `a moved phone with nothing moving is no motion once the shift is cancelled`() {
        val current = shifted(4, 3)
        // そのまま比べると画面じゅうが変わって見える。
        assertTrue(MotionDetector.compare(previous, current, MotionThreshold.DEFAULT).changedRatio > 0.5)
        val shift = FrameAlignment.estimate(previous, current)
        val aligned = FrameAlignment.compareAligned(previous, current, shift, MotionThreshold.DEFAULT)
        assertFalse(aligned.motionDetected)
        assertTrue(FrameAlignment.aligned(aligned))
    }

    @Test
    fun `a moving person is found while the phone moves`() {
        // スマホが動いたうえで、横 50〜57・縦 20〜39 に人が入ってきた。
        val current = shifted(-3, 2) { x, y, v -> if (x in 50..57 && y in 20..39) 255 - v / 4 else v }
        val shift = FrameAlignment.estimate(previous, current)
        assertEquals(FrameShift(-3, 2), shift)
        val aligned = FrameAlignment.compareAligned(previous, current, shift, MotionThreshold.DEFAULT)
        assertTrue(aligned.motionDetected)
        val center = aligned.center!!
        assertEquals(54.0 / width, center.x, 0.03)
        assertEquals(30.0 / height, center.y, 0.05)
        assertTrue(FrameAlignment.aligned(aligned))
    }

    @Test
    fun `a frame that cannot be lined up is reported as not aligned`() {
        val other = Random(3)
        val unrelated = LumaFrame(width, height, IntArray(width * height) { 20 + other.nextInt(200) })
        val shift = FrameAlignment.estimate(previous, unrelated)
        assertFalse(FrameAlignment.aligned(FrameAlignment.compareAligned(previous, unrelated, shift, MotionThreshold.DEFAULT)))
    }

    @Test
    fun `tracked people move with the frame`() {
        val tracker = TargetTracker()
        val person = DetectedTarget(NormalizedBox(0.1, 0.2, 0.3, 0.9), TargetKind.PERSON)
        val first = tracker.update(listOf(person), { 0.0 }).single()
        tracker.shift(0.05, 0.0)
        // スマホが右へ動いて人が画面上で 0.05 ずれただけなら、同じ人で、動いてはいない。
        val moved = DetectedTarget(NormalizedBox(0.15, 0.2, 0.35, 0.9), TargetKind.PERSON)
        val second = tracker.update(listOf(moved), { 0.0 }).single()
        assertEquals(first.id, second.id)
        assertFalse(second.moving)
    }
}
