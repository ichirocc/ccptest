package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LightingChangeTest {
    private val width = 80
    private val height = 60
    private val threshold = MotionThreshold(28, 10)

    // 明るい所も暗い所もある、模様のある部屋。
    private val room = LumaFrame(width, height, Random(5).let { r -> IntArray(width * height) { 30 + r.nextInt(190) } })

    private fun changed(current: LumaFrame, block: Int = 0) =
        LightingChange.changedMask(room, current, FrameShift.NONE, threshold, block).count { it }

    private fun map(f: (x: Int, y: Int, v: Int) -> Int) =
        LumaFrame(width, height, IntArray(width * height) { f(it % width, it / width, room.pixels[it]).coerceIn(0, 255) })

    @Test
    fun `dimming the whole room is not a change`() {
        // 明るい所ほど大きく暗くなる。明るさの差の平均を引くだけでは、画面の多くが変わって見えた。
        assertEquals(0, changed(map { _, _, v -> (v * 0.75).toInt() }))
    }

    @Test
    fun `brightening until the bright parts are blown out is not a change`() {
        assertEquals(0, changed(map { _, _, v -> (v * 1.25).toInt() }))
    }

    @Test
    fun `turning off the lights on one side is not a change once blocks are fitted`() {
        val half = map { x, _, v -> if (x < width / 2) (v * 0.7).toInt() else v }
        assertEquals(0, changed(half, block = FrameAlignment.BLOCK_SIZE))
    }

    @Test
    fun `a moving person is still found while the room dims`() {
        // 部屋が暗くなると同時に、横 30〜37・縦 20〜39 に人（背景とは関係のない模様）が入ってきた。
        val texture = Random(100)
        val current = map { x, y, v -> if (x in 30..37 && y in 20..39) 120 + texture.nextInt(130) else (v * 0.75).toInt() }
        val mask = LightingChange.changedMask(room, current, FrameShift.NONE, threshold, FrameAlignment.BLOCK_SIZE)
        val inside = (20..39).sumOf { y -> (30..37).count { x -> mask[y * width + x] } }
        assertTrue("the person is found: $inside of 160", inside >= 50)
        assertTrue("little outside the person: ${mask.count { it } - inside}", mask.count { it } - inside <= 70)
        val box = NormalizedBox(30.0 / width, 20.0 / height, 38.0 / width, 40.0 / height)
        assertTrue(MovingTargets.changeConcentratedInside(mask, width, height, box))
    }

    @Test
    fun `a model that predicts the same brightness changes nothing`() {
        assertEquals(0, changed(room))
        val model = LightingChange.fit(listOf(10.0, 50.0, 90.0, 130.0).flatMap { v -> List(10) { v } }, List(40) { 0.0 }.mapIndexed { i, _ -> 20.0 + 0.5 * listOf(10.0, 50.0, 90.0, 130.0)[i / 10] })
        assertEquals(0.5, model.gain, 1e-6)
        assertEquals(20.0, model.offset, 1e-6)
    }
}
