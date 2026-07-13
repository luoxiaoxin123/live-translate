package com.livetranslate.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.data.UserSettingsRepository
import com.livetranslate.app.live.LiveTranslateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _apiKeyDraft = MutableStateFlow(apiKeyStore.getApiKey())
    val apiKeyDraft: StateFlow<String> = _apiKeyDraft.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun setApiKeyDraft(value: String) {
        _apiKeyDraft.value = value
    }

    fun saveApiKey() {
        apiKeyStore.setApiKey(_apiKeyDraft.value)
        _testResult.value = "API Key 已保存（本地加密）"
    }

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun resetOverlayLayout() {
        viewModelScope.launch { settingsRepository.resetOverlayLayout() }
    }

    fun testConnection() {
        viewModelScope.launch {
            _testing.value = true
            _testResult.value = "测试中…"
            val s = settings.value
            val key = _apiKeyDraft.value.ifBlank { apiKeyStore.getApiKey() }
            if (key.isBlank()) {
                _testResult.value = "失败：API Key 为空"
                _testing.value = false
                return@launch
            }
            // Persist draft key before test
            apiKeyStore.setApiKey(key)
            val client = LiveTranslateClient()
            val result = client.testConnection(
                LiveTranslateClient.SessionConfig(
                    endpoint = s.endpoint,
                    apiKey = key,
                    modelId = s.modelId,
                    targetLanguageCode = s.targetLanguageCode,
                ),
            )
            _testResult.value = result.fold(
                onSuccess = { it },
                onFailure = { "失败：${it.message}" },
            )
            client.destroy()
            _testing.value = false
        }
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(settingsRepository, apiKeyStore) as T
    }
}
