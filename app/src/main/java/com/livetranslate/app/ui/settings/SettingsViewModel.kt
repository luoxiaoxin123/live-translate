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

    fun resetSubtitleAppearance() {
        viewModelScope.launch { settingsRepository.resetSubtitleAppearance() }
    }

    fun testConnection() {
        viewModelScope.launch {
            _testing.value = true
            _testResult.value = "测试中…"
            val s = settings.value
            val key = _apiKeyDraft.value.ifBlank { apiKeyStore.getApiKey() }.trim()
            if (key.isBlank()) {
                _testResult.value = "失败：API Key 为空"
                _testing.value = false
                return@launch
            }
            if (key.length < 16) {
                _testResult.value = "失败：API Key 太短，请检查是否粘贴完整"
                _testing.value = false
                return@launch
            }
            // Persist draft key before test
            apiKeyStore.setApiKey(key)
            val endpoint = s.endpoint.trim().ifBlank { UserSettings.Defaults.ENDPOINT }
            val modelId = s.modelId.trim().ifBlank { UserSettings.Defaults.MODEL_ID }
            val client = LiveTranslateClient()
            val result = runCatching {
                client.testConnection(
                    LiveTranslateClient.SessionConfig(
                        endpoint = endpoint,
                        apiKey = key,
                        modelId = modelId,
                        targetLanguageCode = s.targetLanguageCode.ifBlank { "zh-Hans" },
                    ),
                ).getOrElse { throw it }
            }
            _testResult.value = result.fold(
                onSuccess = { "✅ $it\n端点/模型已验证" },
                onFailure = { e ->
                    val msg = e.message.orEmpty()
                    buildString {
                        append("❌ 失败：")
                        append(msg.ifBlank { e.javaClass.simpleName })
                        append('\n')
                        when {
                            msg.contains("Unable to resolve host", ignoreCase = true) ||
                                msg.contains("Failed to connect", ignoreCase = true) ||
                                msg.contains("timeout", ignoreCase = true) ||
                                msg.contains("Connecting", ignoreCase = true) ->
                                append("提示：网络连接失败，请检查网络后重试。")
                            msg.contains("API_KEY", ignoreCase = true) ||
                                msg.contains("401") ||
                                msg.contains("403") ||
                                msg.contains("PERMISSION", ignoreCase = true) ->
                                append("提示：Key 无效、被禁用，或未开通 Generative Language API。")
                            msg.contains("not found", ignoreCase = true) ||
                                msg.contains("404") ->
                                append("提示：模型 ID 可能不对，确认是 gemini-3.5-live-translate-preview")
                            else ->
                                append("提示：请检查端点、模型 ID 与 API Key。")
                        }
                    }
                },
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
