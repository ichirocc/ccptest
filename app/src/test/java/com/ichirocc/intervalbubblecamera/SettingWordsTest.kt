package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingWordsTest {
    @Test
    fun `each preset reads as an understandable level`() {
        assertEquals(SettingWords.Sensitivity.NORMAL, SettingWords.sensitivityOf(CapturePreset.STANDARD.pixelThreshold))
        // 外・遠くは、変化の大きさはおすすめと同じで、変化の広さを小さくして遠くの小さな人を拾う。
        assertEquals(SettingWords.Sensitivity.NORMAL, SettingWords.sensitivityOf(CapturePreset.DISTANT.pixelThreshold))
        assertEquals(SettingWords.AreaSize.SMALL, SettingWords.areaSizeOf(CapturePreset.STANDARD.areaPermille))
        assertEquals(SettingWords.AreaSize.TINY, SettingWords.areaSizeOf(CapturePreset.DISTANT.areaPermille))
        assertEquals(SettingWords.AreaSize.PART, SettingWords.areaSizeOf(CapturePreset.NEARBY.areaPermille))
        assertEquals(SettingWords.Battery.NORMAL, SettingWords.batteryOf(CapturePreset.STANDARD.intervalSeconds))
        assertEquals(SettingWords.Battery.LASTS_LONG, SettingWords.batteryOf(CapturePreset.LONG_RUN.intervalSeconds))
    }

    @Test
    fun `the levels cover the whole range of each slider in order`() {
        assertEquals(SettingWords.Sensitivity.VERY_SENSITIVE, SettingWords.sensitivityOf(MotionThreshold.PIXEL_MIN))
        assertEquals(SettingWords.Sensitivity.VERY_DULL, SettingWords.sensitivityOf(MotionThreshold.PIXEL_MAX))
        val sensitivities = (MotionThreshold.PIXEL_MIN..MotionThreshold.PIXEL_MAX).map { SettingWords.sensitivityOf(it).ordinal }
        assertEquals(sensitivities.sorted(), sensitivities)
        assertEquals(SettingWords.Sensitivity.entries.size, sensitivities.toSet().size)

        assertEquals(SettingWords.AreaSize.TINY, SettingWords.areaSizeOf(MotionThreshold.AREA_MIN))
        assertEquals(SettingWords.AreaSize.WIDE, SettingWords.areaSizeOf(MotionThreshold.AREA_MAX))
        val areas = (MotionThreshold.AREA_MIN..MotionThreshold.AREA_MAX).map { SettingWords.areaSizeOf(it).ordinal }
        assertEquals(areas.sorted(), areas)

        assertEquals(SettingWords.Battery.DRAINS_FAST, SettingWords.batteryOf(IntervalPolicy.MIN_SECONDS))
        assertEquals(SettingWords.Battery.LASTS_LONG, SettingWords.batteryOf(IntervalPolicy.MAX_SECONDS))
    }

    @Test
    fun `shots per hour follow the interval`() {
        assertEquals(360, SettingWords.shotsPerHour(10))
        assertEquals(3600, SettingWords.shotsPerHour(1))
        assertEquals(60, SettingWords.shotsPerHour(60))
    }
}
