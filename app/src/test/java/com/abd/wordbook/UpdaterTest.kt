package com.abd.wordbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionCompare() {
        assertTrue(isNewer("1.1", "1.0"))
        assertTrue(isNewer("1.10", "1.9"))
        assertTrue(isNewer("2", "1.9.9"))
        assertTrue(isNewer("1.2.1", "1.2"))
        assertFalse(isNewer("1.0", "1.0"))
        assertFalse(isNewer("1.0.0", "1.0"))
        assertFalse(isNewer("1.9", "1.10"))
    }
}
