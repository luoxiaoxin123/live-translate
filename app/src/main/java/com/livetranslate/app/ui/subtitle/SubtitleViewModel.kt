package com.livetranslate.app.ui.subtitle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.data.UserSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SubtitleViewModel(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun hasApiKey(): Boolean = apiKeyStore.hasApiKey()

    suspend fun setSourceLanguage(code: String) {
        settingsRepository.update { it.copy(sourceLanguageCode = code) }
    }

    suspend fun setTargetLanguage(code: String) {
        settingsRepository.update { it.copy(targetLanguageCode = code) }
    }
}

class SubtitleViewModelFactory(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SubtitleViewModel(settingsRepository, apiKeyStore) as T
    }
}
