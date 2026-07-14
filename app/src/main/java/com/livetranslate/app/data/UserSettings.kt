package com.livetranslate.app.data

import androidx.annotation.StringRes
import com.livetranslate.app.R

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
        const val OVERLAY_Y = -1
        const val OVERLAY_WIDTH_DP = 360
        const val OVERLAY_HEIGHT_DP = 120
    }
}

data class LanguageOption(
    val code: String,
    @StringRes val labelRes: Int,
)

object SupportedLanguages {
    val targetOptions: List<LanguageOption> = listOf(
        LanguageOption("zh-Hans", R.string.lang_zh_hans),
        LanguageOption("zh-Hant", R.string.lang_zh_hant),
        LanguageOption("en", R.string.lang_en),
        LanguageOption("ja", R.string.lang_ja),
        LanguageOption("ko", R.string.lang_ko),
        LanguageOption("es", R.string.lang_es),
        LanguageOption("fr", R.string.lang_fr),
        LanguageOption("de", R.string.lang_de),
        LanguageOption("ru", R.string.lang_ru),
        LanguageOption("pt-BR", R.string.lang_pt_br),
        LanguageOption("pt-PT", R.string.lang_pt_pt),
        LanguageOption("it", R.string.lang_it),
        LanguageOption("ar", R.string.lang_ar),
        LanguageOption("hi", R.string.lang_hi),
        LanguageOption("th", R.string.lang_th),
        LanguageOption("vi", R.string.lang_vi),
        LanguageOption("id", R.string.lang_id),
        LanguageOption("tr", R.string.lang_tr),
        LanguageOption("pl", R.string.lang_pl),
        LanguageOption("nl", R.string.lang_nl),
        LanguageOption("uk", R.string.lang_uk),
    )

    val sourceOptions: List<LanguageOption> = listOf(
        LanguageOption("auto", R.string.lang_auto),
    ) + targetOptions

    fun labelResOf(code: String): Int =
        (sourceOptions + targetOptions).firstOrNull { it.code == code }?.labelRes
            ?: R.string.lang_en
}
