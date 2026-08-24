package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconColorTest {
    @Test
    fun `every stored key resolves to the matching color`() {
        AppIconColor.entries.forEach { color ->
            assertEquals(color, AppIconColor.fromStorageKey(color.storageKey))
        }
    }

    @Test
    fun `unknown or missing keys fall back to blue`() {
        assertEquals(AppIconColor.BLUE, AppIconColor.fromStorageKey(null))
        assertEquals(AppIconColor.BLUE, AppIconColor.fromStorageKey("unknown"))
    }

    @Test
    fun `stored keys and launcher aliases are unique`() {
        assertEquals(
            AppIconColor.entries.size,
            AppIconColor.entries.map { it.storageKey }.toSet().size,
        )
        assertEquals(
            AppIconColor.entries.size,
            AppIconColor.entries.map { it.launcherAliasSuffix }.toSet().size,
        )
        assertTrue(AppIconColor.entries.all { it.storageKey.isNotBlank() })
    }
}
