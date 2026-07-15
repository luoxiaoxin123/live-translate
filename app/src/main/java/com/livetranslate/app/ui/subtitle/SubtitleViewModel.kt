package com.livetranslate.app.ui.subtitle

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.livetranslate.app.R
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.AudioSourceMode
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.data.UserSettingsRepository
import com.livetranslate.app.service.SessionBus
import com.livetranslate.app.util.ExportTranslator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class SubtitleViewModel(
    app: Application,
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : AndroidViewModel(app) {
    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun hasApiKey(): Boolean = apiKeyStore.hasApiKey()

    suspend fun setSourceLanguage(code: String) {
        settingsRepository.update { it.copy(sourceLanguageCode = code) }
    }

    suspend fun setTargetLanguage(code: String) {
        settingsRepository.update { it.copy(targetLanguageCode = code) }
    }

    suspend fun setAudioSource(mode: AudioSourceMode) {
        settingsRepository.update { it.copy(audioSourceMode = mode) }
    }

    fun exportLastSession() {
        val s = SessionBus.state.value
        if (!s.canExport) {
            _exportMessage.value = getApplication<Application>().getString(R.string.subtitle_export_empty)
            return
        }
        val now = LocalDateTime.now()
        val name = ExportTranslator.fileName(now)
        val md = ExportTranslator.buildMarkdown(s.lastInputFull, s.lastOutputFull, now)
        val result = ExportTranslator.saveToDownloads(getApplication(), name, md)
        _exportMessage.value = result.fold(
            onSuccess = { path ->
                getApplication<Application>().getString(R.string.subtitle_export_ok, path)
            },
            onFailure = { e ->
                getApplication<Application>().getString(
                    R.string.subtitle_export_fail,
                    e.message.orEmpty(),
                )
            },
        )
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }
}

class SubtitleViewModelFactory(
    private val app: Application,
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SubtitleViewModel(app, settingsRepository, apiKeyStore) as T
    }
}
