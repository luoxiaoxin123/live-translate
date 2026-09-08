package com.livetranslate.app.util

/**
 * Session-scoped switch: when input is already the target language, the overlay
 * should show a single caption line instead of bilingual source/translation,
 * and echoed translation audio should stay silent so it does not double the
 * source soundtrack.
 *
 * Enter on the first matching language code (or a high-confidence script
 * fallback). Leave only after two consecutive mismatches so a single
 * mis-detected token does not flicker the layout or unmute a false echo.
 */
class SameLanguageCaptionMode {
    var enabled: Boolean = false
        private set

    private var matchStreak = 0
    private var mismatchStreak = 0

    /**
     * Translation PCM should play only when the user turned it on and the
     * source is not already the target language (echo would double the video).
     */
    fun shouldPlayTranslatedAudio(userEnabled: Boolean): Boolean = userEnabled && !enabled

    fun reset() {
        enabled = false
        matchStreak = 0
        mismatchStreak = 0
    }

    /**
     * @return the new [enabled] value if it changed, otherwise null
     */
    fun onDetectedInputLanguage(detected: String?, target: String): Boolean? {
        if (detected.isNullOrBlank()) return null
        return if (LanguageCodes.matchesTarget(detected, target)) {
            matchStreak++
            mismatchStreak = 0
            if (!enabled) {
                enabled = true
                true
            } else {
                null
            }
        } else {
            mismatchStreak++
            matchStreak = 0
            if (enabled && mismatchStreak >= 2) {
                enabled = false
                false
            } else {
                null
            }
        }
    }

    /**
     * When the API omits `languageCode`, infer same-language from the input
     * transcript script — only if no translation text has arrived yet.
     */
    fun onInputTextFallback(text: String, target: String, outputEmpty: Boolean): Boolean? {
        if (enabled || !outputEmpty) return null
        if (!LanguageCodes.textLooksLikeTarget(text, target)) return null
        enabled = true
        matchStreak = 1
        mismatchStreak = 0
        return true
    }
}
