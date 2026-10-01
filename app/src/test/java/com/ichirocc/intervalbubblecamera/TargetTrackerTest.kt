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
}
