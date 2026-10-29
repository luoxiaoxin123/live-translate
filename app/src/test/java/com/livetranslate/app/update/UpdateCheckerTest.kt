package com.livetranslate.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun newerPatchMinorMajorAreDetected() {
        assertTrue(UpdateChecker.isNewer("v0.4.3", "0.4.2"))
        assertTrue(UpdateChecker.isNewer("v0.5.0", "0.4.9"))
        assertTrue(UpdateChecker.isNewer("v1.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("v0.4.10", "0.4.9"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewer("v0.4.3", "0.4.3"))
        assertFalse(UpdateChecker.isNewer("v0.4.3", "0.4.3-debug"))
        assertFalse(UpdateChecker.isNewer("v0.4.0", "0.4"))
        assertFalse(UpdateChecker.isNewer("v0.4.2", "0.4.3"))
    }

    @Test
    fun malformedVersionsAreIgnored() {
        assertFalse(UpdateChecker.isNewer("nightly", "0.4.3"))
        assertFalse(UpdateChecker.isNewer("v0.5.0", ""))
    }
}
