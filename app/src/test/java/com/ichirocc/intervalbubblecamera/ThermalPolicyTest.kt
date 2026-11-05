package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    @Test
    fun `the thermal status decides the heat level`() {
        assertEquals(HeatLevel.NORMAL, ThermalPolicy.level(status = 0, headroom = null))
        assertEquals(HeatLevel.NORMAL, ThermalPolicy.level(status = 1, headroom = 0.5f))
        assertEquals(HeatLevel.WARM, ThermalPolicy.level(status = 2, headroom = null))
        assertEquals(HeatLevel.HOT, ThermalPolicy.level(status = 3, headroom = null))
        assertEquals(HeatLevel.CRITICAL, ThermalPolicy.level(status = 4, headroom = null))
        assertEquals(HeatLevel.CRITICAL, ThermalPolicy.level(status = 6, headroom = 0.1f))
    }

    @Test
    fun `the forecast lowers the load before the phone gets hot`() {
        assertEquals(HeatLevel.WARM, ThermalPolicy.level(status = 0, headroom = 0.9f))
        assertEquals(HeatLevel.HOT, ThermalPolicy.level(status = 1, headroom = 1.05f))
        assertEquals(HeatLevel.NORMAL, ThermalPolicy.level(status = 0, headroom = Float.NaN))
    }

    @Test
    fun `a cool phone keeps the chosen interval`() {
        assertEquals(7_000L, ThermalPolicy.delayAfterCapture(10, 3_000L, HeatLevel.NORMAL))
        assertEquals(0L, ThermalPolicy.delayAfterCapture(1, 3_000L, HeatLevel.NORMAL))
    }

    @Test
    fun `a hot phone captures less often and always rests`() {
        // 設定 1 秒でも、やや熱いときは 5 秒間隔、撮影が長くても 2 秒は休む。
        assertEquals(4_000L, ThermalPolicy.delayAfterCapture(1, 1_000L, HeatLevel.WARM))
        assertEquals(2_000L, ThermalPolicy.delayAfterCapture(1, 8_000L, HeatLevel.WARM))
        assertEquals(12_000L, ThermalPolicy.delayAfterCapture(10, 3_000L, HeatLevel.HOT))
        assertEquals(57_000L, ThermalPolicy.delayAfterCapture(10, 3_000L, HeatLevel.CRITICAL))
        assertEquals(30_000L, ThermalPolicy.delayAfterCapture(10, 50_000L, HeatLevel.CRITICAL))
    }

    @Test
    fun `the summary says what was reduced`() {
        assertEquals("本体の温度: 普通", ThermalPolicy.summary(HeatLevel.NORMAL))
        assertEquals("本体の温度: やや熱い（検出は正立の向きだけ・間隔5秒以上）", ThermalPolicy.summary(HeatLevel.WARM))
        assertEquals(
            "本体の温度: 熱い（検出を止めて画像の変化だけで判定・間隔15秒以上）",
            ThermalPolicy.summary(HeatLevel.HOT),
        )
    }

    @Test
    fun `a lowered load is not raised again until the forecast is clearly lower`() {
        assertEquals(HeatLevel.WARM, ThermalPolicy.level(status = 0, headroom = 0.9f, previous = HeatLevel.NORMAL))
        // 0.85 を少し下回っただけでは戻さない。
        assertEquals(HeatLevel.WARM, ThermalPolicy.level(status = 0, headroom = 0.83f, previous = HeatLevel.WARM))
        assertEquals(HeatLevel.NORMAL, ThermalPolicy.level(status = 0, headroom = 0.79f, previous = HeatLevel.WARM))
        assertEquals(HeatLevel.HOT, ThermalPolicy.level(status = 1, headroom = 0.97f, previous = HeatLevel.HOT))
        assertEquals(HeatLevel.WARM, ThermalPolicy.level(status = 1, headroom = 0.9f, previous = HeatLevel.HOT))
        // 熱くなるほうは、すぐに上げる。
        assertEquals(HeatLevel.CRITICAL, ThermalPolicy.level(status = 4, headroom = null, previous = HeatLevel.WARM))
    }

    @Test
    fun `detection is skipped a few times in a row when nothing changed, more when warm`() {
        assertTrue(DetectionSkipping.shouldSkip(0, HeatLevel.NORMAL))
        assertTrue(DetectionSkipping.shouldSkip(1, HeatLevel.NORMAL))
        assertFalse(DetectionSkipping.shouldSkip(2, HeatLevel.NORMAL))
        assertTrue(DetectionSkipping.shouldSkip(3, HeatLevel.WARM))
        assertFalse(DetectionSkipping.shouldSkip(4, HeatLevel.WARM))
        assertTrue(HeatLevel.WARM.detectScale < HeatLevel.NORMAL.detectScale)
    }
}
