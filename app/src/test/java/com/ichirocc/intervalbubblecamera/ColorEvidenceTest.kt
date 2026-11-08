package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorEvidenceTest {
    private val width = 40
    private val height = 30
    private val grass = 0xFF557D37.toInt()

    // 明るさは芝生とほぼ同じで、色だけが違う服。
    private val shirt = 0xFF7A6A40.toInt()

    private fun thumb(fill: (x: Int, y: Int) -> Int) = ColorThumb(width, height, IntArray(width * height) { fill(it % width, it / width) })

    @Test
    fun `a small person whose colour differs from the grass is seen moving`() {
        val previous = thumb { x, y -> if (x in 10..11 && y in 10..15) shirt else grass }
        val current = thumb { x, y -> if (x in 14..15 && y in 10..15) shirt else grass }
        val mask = ColorEvidence.changedMask(previous, current, FrameShift.NONE, 80, 60)!!
        val box = NormalizedBox(13.0 / width, 9.0 / height, 17.0 / width, 17.0 / height)
        assertTrue(ColorEvidence.movedInside(mask, width, height, box))
        // 何も変わっていない所の枠では動いていない。
        assertFalse(ColorEvidence.movedInside(mask, width, height, NormalizedBox(0.6, 0.6, 0.8, 0.9)))
    }

    @Test
    fun `a change spread over the whole frame is not counted`() {
        // 手ぶれで画面じゅうの模様がずれた（どこも同じように変わる）。
        val previous = thumb { x, y -> if ((x + y) % 2 == 0) shirt else grass }
        val current = thumb { x, y -> if ((x + y) % 2 == 1) shirt else grass }
        val mask = ColorEvidence.changedMask(previous, current, FrameShift.NONE, 80, 60)!!
        assertFalse(ColorEvidence.movedInside(mask, width, height, NormalizedBox(0.3, 0.3, 0.4, 0.5)))
    }

    @Test
    fun `the shift of the whole frame is cancelled before comparing`() {
        val previous = thumb { x, _ -> if (x % 7 == 0) shirt else grass }
        // 今回は全体が 2 画素右にずれた（格子では 80/40 = 2 倍なので 4 マス）。
        val current = thumb { x, _ -> if ((x - 2) % 7 == 0 && x >= 2) shirt else grass }
        val mask = ColorEvidence.changedMask(previous, current, FrameShift(4, 0), 80, 60)!!
        assertEquals(0, mask.count { it })
    }

    @Test
    fun `thumbnails of different sizes are not compared`() {
        val other = ColorThumb(20, 15, IntArray(20 * 15) { grass })
        assertNull(ColorEvidence.changedMask(other, thumb { _, _ -> grass }, FrameShift.NONE, 80, 60))
    }

    @Test
    fun `the four tiles cover the whole frame and are used only for distant scenes while cool`() {
        val tiles = TiledDetection.regions
        assertEquals(4, tiles.size)
        for (y in 0 until 20) for (x in 0 until 20) {
            val px = (x + 0.5) / 20
            val py = (y + 0.5) / 20
            assertTrue(tiles.any { px in it.left..it.right && py in it.top..it.bottom })
        }
        assertTrue(TiledDetection.enabled(CapturePreset.DISTANT, HeatLevel.NORMAL))
        assertFalse(TiledDetection.enabled(CapturePreset.DISTANT, HeatLevel.WARM))
        assertFalse(TiledDetection.enabled(CapturePreset.STANDARD, HeatLevel.NORMAL))
    }
}
