package com.livetranslate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    onOpenOverlayPermission: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKeyDraft.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    val endpointValue = remember(settings.endpoint) { TextFieldValue(settings.endpoint) }
    val modelValue = remember(settings.modelId) { TextFieldValue(settings.modelId) }
    val apiKeyValue = remember(apiKey) { TextFieldValue(apiKey) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "设置")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "API 连接")
                Text(text = "端点（默认 AI Studio Live WebSocket）")
                TextField(
                    value = endpointValue,
                    onValueChange = { v ->
                        viewModel.update { it.copy(endpoint = v.text.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Endpoint",
                    singleLine = true,
                )
                Text(text = "API Key（本地加密存储）")
                TextField(
                    value = apiKeyValue,
                    onValueChange = { v -> viewModel.setApiKeyDraft(v.text) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "API Key",
                    singleLine = true,
                )
                TextButton(text = "保存 API Key", onClick = viewModel::saveApiKey)
                Text(text = "模型 ID")
                TextField(
                    value = modelValue,
                    onValueChange = { v ->
                        viewModel.update { it.copy(modelId = v.text.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Model ID",
                    singleLine = true,
                )
                Button(
                    onClick = viewModel::testConnection,
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = if (testing) "测试中…" else "连接测试")
                }
                if (!testResult.isNullOrBlank()) {
                    Text(text = testResult.orEmpty())
                }
                Text(text = "协议对齐官方 Live Translate：setup + translationConfig + PCM realtimeInput。")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "字幕外观")
                Text(text = "字体大小：${settings.fontSizeSp.toInt()} sp（系统默认字体）")
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { size -> viewModel.update { it.copy(fontSizeSp = size) } },
                    valueRange = 12f..32f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = "黑底透明度：${(settings.backgroundAlpha * 100).toInt()}%")
                Slider(
                    value = settings.backgroundAlpha,
                    onValueChange = { a -> viewModel.update { it.copy(backgroundAlpha = a) } },
                    valueRange = 0.1f..0.95f,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchPreference(
                    checked = settings.bilingual,
                    onCheckedChange = { c -> viewModel.update { it.copy(bilingual = c) } },
                    title = "双语显示",
                    summary = if (settings.bilingual) "显示原文 + 译文" else "仅显示译文",
                )
                TextButton(text = "重置字幕位置与尺寸", onClick = viewModel::resetOverlayLayout)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "译音（云端返回音频）")
                SwitchPreference(
                    checked = settings.playTranslatedAudio,
                    onCheckedChange = { c -> viewModel.update { it.copy(playTranslatedAudio = c) } },
                    title = "播放译音",
                    summary = "默认关闭。开启后与原视频/音频并行播放，不会暂停原声。",
                )
                Text(text = "译音音量：${(settings.translatedVolume * 100).toInt()}%")
                Slider(
                    value = settings.translatedVolume,
                    onValueChange = { v -> viewModel.update { it.copy(translatedVolume = v) } },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "权限与关于")
                TextButton(text = "打开悬浮窗权限设置", onClick = onOpenOverlayPermission)
                Text(text = "启动字幕时会请求系统录屏授权，用于捕获其它 App 的播放声音。")
                Text(text = "开源个人项目 · API Key 仅存本机 · v0.1.0")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
