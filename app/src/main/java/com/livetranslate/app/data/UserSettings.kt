package com.livetranslate.app.data

/**
 * All user-facing settings that are safe to put in DataStore (not the API key).
 */
data class UserSettings(
    val endpoint: String = Defaults.ENDPOINT,
    val modelId: String = Defaults.MODEL_ID,
    val sourceLanguageCode: String = Defaults.SOURCE_LANGUAGE,
    val targetLanguageCode: String = Defaults.TARGET_LANGUAGE,
    val fontSizeSp: Float = Defaults.FONT_SIZE_SP,
    val backgroundAlpha: Float = Defaults.BACKGROUND_ALPHA,
    val bilingual: Boolean = Defaults.BILINGUAL,
    val playTranslatedAudio: Boolean = Defaults.PLAY_TRANSLATED_AUDIO,
    val translatedVolume: Float = Defaults.TRANSLATED_VOLUME,
    val overlayX: Int = Defaults.OVERLAY_X,
    val overlayY: Int = Defaults.OVERLAY_Y,
    val overlayWidthDp: Int = Defaults.OVERLAY_WIDTH_DP,
    val overlayHeightDp: Int = Defaults.OVERLAY_HEIGHT_DP,
) {
    object Defaults {
        // Official Gemini Live API WebSocket (API key appended at runtime).
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MODEL_ID = "gemini-3.5-live-translate-preview"
        const val SOURCE_LANGUAGE = "auto"
        const val TARGET_LANGUAGE = "zh-Hans"
        const val FONT_SIZE_SP = 18f
        const val BACKGROUND_ALPHA = 0.65f
        const val BILINGUAL = false
        const val PLAY_TRANSLATED_AUDIO = false
        const val TRANSLATED_VOLUME = 0.8f
        const val OVERLAY_X = 24
        const val OVERLAY_Y = -1 // -1 means "default ~ lower third"
        const val OVERLAY_WIDTH_DP = 360
        const val OVERLAY_HEIGHT_DP = 120
    }
}

data class LanguageOption(
    val code: String,
    val labelZh: String,
)

object SupportedLanguages {
    val targetOptions: List<LanguageOption> = listOf(
        LanguageOption("zh-Hans", "中文（简体）"),
        LanguageOption("zh-Hant", "中文（繁体）"),
        LanguageOption("en", "英语"),
        LanguageOption("ja", "日语"),
        LanguageOption("ko", "韩语"),
        LanguageOption("es", "西班牙语"),
        LanguageOption("fr", "法语"),
        LanguageOption("de", "德语"),
        LanguageOption("ru", "俄语"),
        LanguageOption("pt-BR", "葡萄牙语（巴西）"),
        LanguageOption("pt-PT", "葡萄牙语（葡萄牙）"),
        LanguageOption("it", "意大利语"),
        LanguageOption("ar", "阿拉伯语"),
        LanguageOption("hi", "印地语"),
        LanguageOption("th", "泰语"),
        LanguageOption("vi", "越南语"),
        LanguageOption("id", "印尼语"),
        LanguageOption("tr", "土耳其语"),
        LanguageOption("pl", "波兰语"),
        LanguageOption("nl", "荷兰语"),
        LanguageOption("uk", "乌克兰语"),
    )

    val sourceOptions: List<LanguageOption> = listOf(
        LanguageOption("auto", "自动检测"),
    ) + targetOptions

    fun labelOf(code: String): String =
        (sourceOptions + targetOptions).firstOrNull { it.code == code }?.labelZh ?: code
}
