package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Test

class DogfoodRecordTest {
    @Test
    fun `a row has one column per header column`() {
        val columns = DogfoodRecord.HEADER.split(",").size
        val empty = DogfoodRecord(time = "t", kind = "event", cycle = 1)
        assertEquals(columns, empty.toCsvRow().split(",").size)
        val full = DogfoodRecord(
            time = "2026-11-07T10:00:00.000", kind = "shot", cycle = 3, camera = "back0",
            intervalSeconds = 10, pixelThreshold = 28, areaPermille = 10,
            fullResolution = true, enhanced = true, night = false, ultraHdr = true,
            imageWidth = 8160, imageHeight = 6144, exposureSeconds = 0.0115, heat = "NORMAL",
            phoneMotion = "STILL", rotationDegrees = 0.3, tiltDegrees = 0.1, walking = false, compensatedShift = "",
            rawChangedRatio = 0.0123, changedRatio = 0.0123, noisePixelThreshold = 28,
            detectionSkipped = false, detections = 2, confidentPersons = 1, zoomed = false,
            motion = true, reason = "moving_target:PERSON", refocused = true, blurFraction = 0.0004,
            blurRetaken = false, saved = true,
        )
        assertEquals(columns, full.toCsvRow().split(",").size)
    }

    @Test
    fun `numbers and flags are written plainly`() {
        val row = DogfoodRecord(
            time = "t", kind = "shot", cycle = 2, camera = "back2", fullResolution = false, saved = true,
            exposureSeconds = 1 / 30.0, rawChangedRatio = 0.5,
        ).toCsvRow().split(",")
        val header = DogfoodRecord.HEADER.split(",")
        assertEquals("0", row[header.indexOf("full_resolution")])
        assertEquals("1", row[header.indexOf("saved")])
        assertEquals("0.033333", row[header.indexOf("exposure_s")])
        assertEquals("0.5000", row[header.indexOf("raw_changed_ratio")])
        assertEquals("", row[header.indexOf("detections")])
    }

    @Test
    fun `messages with commas, quotes or line breaks stay in one column`() {
        assertEquals("plain", DogfoodRecord.escape("plain"))
        assertEquals("\"a,b\"", DogfoodRecord.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", DogfoodRecord.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", DogfoodRecord.escape("line1\nline2"))
    }
}
