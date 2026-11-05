package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTrackerTest {
    private val noChange: (NormalizedBox) -> Double = { 0.0 }
    private val allChange: (NormalizedBox) -> Double = { 1.0 }

    private fun person(x: Double) = DetectedTarget(NormalizedBox(x, 0.2, x + 0.2, 0.9), TargetKind.PERSON)

    @Test
    fun `the same person keeps the same id while walking`() {
        val tracker = TargetTracker()
        val first = tracker.update(listOf(person(0.10)), noChange).single()
        val second = tracker.update(listOf(person(0.15)), noChange).single()
        assertEquals(first.id, second.id)
        assertTrue("moved 0.05 so it is moving", second.moving)
    }

    @Test
    fun `a still person with no change is not moving`() {
        val tracker = TargetTracker()
        tracker.update(listOf(person(0.10)), noChange)
        assertFalse(tracker.update(listOf(person(0.10)), noChange).single().moving)
    }

    @Test
    fun `change inside the box counts as moving even on first sight`() {
        val tracker = TargetTracker()
        assertTrue(tracker.update(listOf(person(0.10)), allChange).single().moving)
    }

    @Test
    fun `a hand only and a person are tracked separately`() {
        val tracker = TargetTracker()
        val hand = DetectedTarget(NormalizedBox(0.15, 0.4, 0.25, 0.5), TargetKind.HAND)
        val result = tracker.update(listOf(person(0.10), hand), noChange)
        assertEquals(2, result.map { it.id }.toSet().size)
    }

    @Test
    fun `a track is dropped after too many misses and a new id is given`() {
        val tracker = TargetTracker(maxMisses = 2)
        val first = tracker.update(listOf(person(0.10)), noChange).single()
        repeat(3) { tracker.update(emptyList(), noChange) }
        assertFalse(tracker.hasTracks)
        val again = tracker.update(listOf(person(0.10)), noChange).single()
        assertTrue(again.id != first.id)
    }

    @Test
    fun `focus prefers a moving person over a larger moving car`() {
        val targets = listOf(
            TrackedTarget(1, TargetKind.VEHICLE, NormalizedBox(0.0, 0.0, 0.9, 0.9), moving = true),
            TrackedTarget(2, TargetKind.HAND, NormalizedBox(0.4, 0.4, 0.5, 0.5), moving = true),
            TrackedTarget(3, TargetKind.PERSON, NormalizedBox(0.0, 0.0, 0.5, 0.9), moving = false),
        )
        assertEquals(2, TargetTracker.focusTarget(targets)!!.id)
    }

    @Test
    fun `no focus target when nothing moves`() {
        val targets = listOf(TrackedTarget(1, TargetKind.PERSON, NormalizedBox(0.0, 0.0, 0.5, 0.9), moving = false))
        assertNull(TargetTracker.focusTarget(targets))
    }

    @Test
    fun `a partial body box is built from visible landmarks only`() {
        val points = listOf(0.4 to 0.7, 0.6 to 0.95, 0.5 to 0.2, 1.5 to 0.5)
        val visible = listOf(true, true, false, true)
        val box = TargetTracker.boxFromLandmarks(points, visible, padding = 0.0)
        assertNotNull(box)
        assertEquals(NormalizedBox(0.4, 0.7, 0.6, 0.95), box)
        assertNull(TargetTracker.boxFromLandmarks(points, listOf(true, false, false, false)))
    }

    @Test
    fun `a person in frame is found even without movement, and vehicles are not counted`() {
        val car = DetectedTarget(NormalizedBox(0.0, 0.0, 0.9, 0.9), TargetKind.VEHICLE)
        val hand = DetectedTarget(NormalizedBox(0.6, 0.6, 0.7, 0.7), TargetKind.HAND)
        val body = DetectedTarget(NormalizedBox(0.1, 0.1, 0.4, 0.5), TargetKind.BODY_PART)
        assertEquals(body, TargetTracker.personInFrame(listOf(car, hand, body)))
        assertNull(TargetTracker.personInFrame(listOf(car)))
        assertNull(TargetTracker.personInFrame(emptyList()))
    }

    @Test
    fun `detection tries the upright orientation first and then the other three`() {
        assertEquals(listOf(0, 90, 180, 270), TargetTracker.detectionRotations(0))
        assertEquals(listOf(90, 180, 270, 0), TargetTracker.detectionRotations(90))
        assertEquals(listOf(270, 0, 90, 180), TargetTracker.detectionRotations(270))
        assertEquals(listOf(180, 270, 0, 90), TargetTracker.detectionRotations(-180))
    }

    @Test
    fun `the same person found in several orientations is fused into one weighted box`() {
        val strong = DetectedTarget(NormalizedBox(0.10, 0.20, 0.30, 0.90), TargetKind.PERSON, score = 0.9)
        val weak = DetectedTarget(NormalizedBox(0.13, 0.23, 0.33, 0.93), TargetKind.PERSON, score = 0.3)
        val lyingPerson = DetectedTarget(NormalizedBox(0.5, 0.6, 0.95, 0.8), TargetKind.PERSON, score = 0.6)
        val partOfSamePerson = DetectedTarget(strong.box, TargetKind.BODY_PART)

        val fused = TargetTracker.fuse(listOf(weak, lyingPerson, strong, partOfSamePerson))

        assertEquals(3, fused.size)
        val person = fused.single { it.kind == TargetKind.PERSON && it.box.left < 0.4 }
        // 確からしさで重みをつけた平均（0.9 と 0.3 なので 3:1）。確からしさは高いほう。
        assertEquals(0.1075, person.box.left, 1e-9)
        assertEquals(0.9075, person.box.bottom, 1e-9)
        assertEquals(0.9, person.score, 1e-9)
        assertTrue(fused.any { it == lyingPerson })
        assertTrue(fused.any { it.kind == TargetKind.BODY_PART })
    }

    @Test
    fun `a low score detection continues an existing track`() {
        val tracker = TargetTracker()
        val first = tracker.update(listOf(person(0.10)), noChange).single()
        val blurred = DetectedTarget(NormalizedBox(0.12, 0.2, 0.32, 0.9), TargetKind.PERSON, score = 0.25)
        val second = tracker.update(listOf(blurred), noChange).single()
        assertEquals(first.id, second.id)
    }

    @Test
    fun `a low score detection alone does not start a new track`() {
        val tracker = TargetTracker()
        val unsure = DetectedTarget(NormalizedBox(0.5, 0.2, 0.7, 0.9), TargetKind.PERSON, score = 0.25)
        assertTrue(tracker.update(listOf(unsure), allChange).isEmpty())
        assertFalse(tracker.hasTracks)
    }

    @Test
    fun `an unsure person is not a reason to save when the phone moved`() {
        val unsure = DetectedTarget(NormalizedBox(0.5, 0.2, 0.7, 0.9), TargetKind.PERSON, score = 0.25)
        assertNull(TargetTracker.personInFrame(listOf(unsure)))
    }
}
