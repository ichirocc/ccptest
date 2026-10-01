package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeSwitchTest {
    @Test
    fun `dark lighting switches to night mode`() {
        val switch = NightModeSwitch()
        assertTrue(switch.update(3f))
        assertTrue(switch.night)
    }

    @Test
    fun `bright lighting switches back to normal mode`() {
        val switch = NightModeSwitch()
        switch.update(3f)
        assertTrue(switch.update(200f))
        assertFalse(switch.night)
    }

    @Test
    fun `lighting between the thresholds keeps the current mode`() {
        val switch = NightModeSwitch()
        assertFalse(switch.update(20f))
        assertFalse(switch.night)
        switch.update(5f)
        assertFalse(switch.update(20f))
        assertTrue(switch.night)
    }

    @Test
    fun `reset returns to normal mode`() {
        val switch = NightModeSwitch()
        switch.update(1f)
        switch.reset()
        assertFalse(switch.night)
    }
}
