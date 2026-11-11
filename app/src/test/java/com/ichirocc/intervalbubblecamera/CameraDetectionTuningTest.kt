package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDetectionTuningTest {
    @Test
    fun `the field of view is worked out from the focal length and the sensor width`() {
        // 焦点距離 = センサーの幅 ÷ 2 なら、横の画角はちょうど 90°。
        assertEquals(90.0, CameraDetectionTuning.horizontalFovDegrees(0.5), 1e-9)
    }

    @Test
    fun `cameras are tuned by their field of view`() {
        // 超広角（2.2 mm ÷ 6.4 mm、約 111°）・広角（6.9 mm ÷ 9.6 mm、約 70°）・5 倍望遠（18 mm ÷ 4.8 mm、約 15°）。
        assertEquals(LensClass.ULTRA_WIDE, CameraDetectionTuning.forCamera(2.2 / 6.4, front = false).lensClass)
        assertEquals(LensClass.WIDE, CameraDetectionTuning.forCamera(6.9 / 9.6, front = false).lensClass)
        assertEquals(LensClass.TELE, CameraDetectionTuning.forCamera(18.0 / 4.8, front = false).lensClass)
    }

    @Test
    fun `front cameras and unknown lenses use the standard tuning`() {
        assertEquals(CameraDetectionTuning.WIDE, CameraDetectionTuning.forCamera(2.2 / 6.4, front = true))
        assertEquals(CameraDetectionTuning.WIDE, CameraDetectionTuning.forCamera(null, front = false))
    }

    @Test
    fun `wider lenses look closer and count smaller movements`() {
        val ultraWide = CameraDetectionTuning.ULTRA_WIDE
        val wide = CameraDetectionTuning.WIDE
        val tele = CameraDetectionTuning.TELE
        assertTrue(ultraWide.detectSizeFactor > wide.detectSizeFactor && wide.detectSizeFactor > tele.detectSizeFactor)
        assertTrue(ultraWide.minMove < wide.minMove && wide.minMove < tele.minMove)
        assertTrue(ultraWide.zoomMinRegion < wide.zoomMinRegion && wide.zoomMinRegion < tele.zoomMinRegion)
    }

    @Test
    fun `an ultra-wide tracker counts a small step as moving`() {
        val person = { x: Double -> DetectedTarget(NormalizedBox(x, 0.2, x + 0.2, 0.9), TargetKind.PERSON) }
        val ultraWide = TargetTracker(minMove = CameraDetectionTuning.ULTRA_WIDE.minMove)
        ultraWide.update(listOf(person(0.10)), { 0.0 })
        assertTrue(ultraWide.update(listOf(person(0.115)), { 0.0 }).single().moving)

        val wide = TargetTracker()
        wide.update(listOf(person(0.10)), { 0.0 })
        assertFalse(wide.update(listOf(person(0.115)), { 0.0 }).single().moving)
    }

    @Test
    fun `an ultra-wide zoom region is tighter`() {
        val w = MotionDetector.GRID_WIDTH
        val h = MotionDetector.GRID_HEIGHT
        val mask = BooleanArray(w * h) { it % w in 40..42 && it / w in 30..32 }
        val standard = MovingTargets.changedRegion(mask, w, h)!!
        val tight = MovingTargets.changedRegion(mask, w, h, CameraDetectionTuning.ULTRA_WIDE.zoomMinRegion)!!
        assertTrue(tight.area < standard.area)
        assertEquals(CameraDetectionTuning.ULTRA_WIDE.zoomMinRegion, tight.right - tight.left, 1e-9)
    }
}
