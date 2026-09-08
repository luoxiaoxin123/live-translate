package com.livetranslate.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SameLanguageCaptionModeTest {

    @Test
    fun entersOnFirstMatchingLanguageCode() {
        val mode = SameLanguageCaptionMode()
        assertEquals(true, mode.onDetectedInputLanguage("zh-Hans", "zh-Hans"))
        assertTrue(mode.enabled)
        assertNull(mode.onDetectedInputLanguage("zh", "zh-Hans"))
        assertTrue(mode.enabled)
    }

    @Test
    fun staysEnabledAfterSingleMismatch() {
        val mode = SameLanguageCaptionMode()
        mode.onDetectedInputLanguage("zh-Hans", "zh-Hans")
        assertNull(mode.onDetectedInputLanguage("en", "zh-Hans"))
        assertTrue(mode.enabled)
    }

    @Test
    fun leavesAfterTwoConsecutiveMismatches() {
        val mode = SameLanguageCaptionMode()
        mode.onDetectedInputLanguage("zh-Hans", "zh-Hans")
        mode.onDetectedInputLanguage("en", "zh-Hans")
        assertEquals(false, mode.onDetectedInputLanguage("en", "zh-Hans"))
        assertFalse(mode.enabled)
    }

    @Test
    fun mismatchStreakResetsWhenMatchReturns() {
        val mode = SameLanguageCaptionMode()
        mode.onDetectedInputLanguage("zh-Hans", "zh-Hans")
        mode.onDetectedInputLanguage("en", "zh-Hans")
        assertNull(mode.onDetectedInputLanguage("zh", "zh-Hans"))
        assertTrue(mode.enabled)
        assertNull(mode.onDetectedInputLanguage("en", "zh-Hans"))
        assertTrue(mode.enabled)
    }

    @Test
    fun scriptFallbackEntersWhenOutputStillEmpty() {
        val mode = SameLanguageCaptionMode()
        assertEquals(
            true,
            mode.onInputTextFallback(
                "今天天气很好我们一起去公园走走吧",
                "zh-Hans",
                outputEmpty = true,
            ),
        )
        assertTrue(mode.enabled)
    }

    @Test
    fun scriptFallbackDoesNotRunOnceTranslationArrived() {
        val mode = SameLanguageCaptionMode()
        assertNull(
            mode.onInputTextFallback(
                "今天天气很好我们一起去公园走走吧",
                "zh-Hans",
                outputEmpty = false,
            ),
        )
        assertFalse(mode.enabled)
    }

    @Test
    fun blankLanguageCodeIsIgnored() {
        val mode = SameLanguageCaptionMode()
        assertNull(mode.onDetectedInputLanguage(null, "zh-Hans"))
        assertNull(mode.onDetectedInputLanguage("  ", "zh-Hans"))
        assertFalse(mode.enabled)
    }

    @Test
    fun translatedAudioFollowsUserSettingUntilSameLanguage() {
        val mode = SameLanguageCaptionMode()
        assertTrue(mode.shouldPlayTranslatedAudio(userEnabled = true))
        assertFalse(mode.shouldPlayTranslatedAudio(userEnabled = false))

        mode.onDetectedInputLanguage("zh-Hans", "zh-Hans")
        assertFalse(mode.shouldPlayTranslatedAudio(userEnabled = true))
        assertFalse(mode.shouldPlayTranslatedAudio(userEnabled = false))
    }

    @Test
    fun translatedAudioResumesAfterLeavingSameLanguage() {
        val mode = SameLanguageCaptionMode()
        mode.onDetectedInputLanguage("zh-Hans", "zh-Hans")
        mode.onDetectedInputLanguage("en", "zh-Hans")
        assertFalse(mode.shouldPlayTranslatedAudio(userEnabled = true))

        mode.onDetectedInputLanguage("en", "zh-Hans")
        assertFalse(mode.enabled)
        assertTrue(mode.shouldPlayTranslatedAudio(userEnabled = true))
    }

    @Test
    fun resetRestoresTranslatedAudio() {
        val mode = SameLanguageCaptionMode()
        mode.onDetectedInputLanguage("zh", "zh-Hans")
        assertFalse(mode.shouldPlayTranslatedAudio(userEnabled = true))
        mode.reset()
        assertTrue(mode.shouldPlayTranslatedAudio(userEnabled = true))
    }
}
