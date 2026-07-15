package com.livetranslate.app.data

/**
 * Where PCM for Live Translate comes from.
 */
enum class AudioSourceMode {
    /** Other apps' media playback only (MediaProjection capture). */
    MEDIA,

    /** Device microphone only. */
    MIC,

    /** Mix media playback + microphone. */
    MEDIA_AND_MIC,
    ;

    val needsMediaProjection: Boolean
        get() = this == MEDIA || this == MEDIA_AND_MIC

    val needsMicrophone: Boolean
        get() = this == MIC || this == MEDIA_AND_MIC

    companion object {
        fun fromStorage(raw: String?): AudioSourceMode =
            entries.firstOrNull { it.name == raw } ?: MEDIA
    }
}
