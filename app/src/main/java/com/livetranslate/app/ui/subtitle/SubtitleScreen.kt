package com.livetranslate.app.ui.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livetranslate.app.data.SupportedLanguages
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.service.SessionBus
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun SubtitleScreen(
    modifier: Modifier = Modifier,
    settings: UserSettings,
    session: SessionBus.UiState,
    onSourceLanguage: (String) -> Unit,
    onTargetLanguage: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    canDrawOverlays: Boolean,
) {
    val running = session.status == SessionBus.Status.Running ||
        session.status == SessionBus.Status.Starting

    val sourceLabels = SupportedLanguages.sourceOptions.map { it.labelZh }
    val sourceCodes = SupportedLanguages.sourceOptions.map { it.code }
    val targetLabels = SupportedLanguages.targetOptions.map { it.labelZh }
    val targetCodes = SupportedLanguages.targetOptions.map { it.code }

    val sourceIndex = sourceCodes.indexOf(settings.sourceLanguageCode).coerceAtLeast(0)
    val targetIndex = targetCodes.indexOf(settings.targetLanguageCode).coerceAtLeast(0)

    var showSourceDialog by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "实时字幕")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "语言（会记住上次选择）")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = "源: ${SupportedLanguages.labelOf(settings.sourceLanguageCode)}",
                        onClick = { showSourceDialog = true },
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "→")
                    TextButton(
                        text = "目标: ${SupportedLanguages.labelOf(settings.targetLanguageCode)}",
                        onClick = { showTargetDialog = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(text = "说明：Live Translate 以目标语配置为主；源语言「自动检测」最稳妥。")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val statusText = when (session.status) {
                    SessionBus.Status.Idle -> "空闲"
                    SessionBus.Status.Starting -> "启动中…"
                    SessionBus.Status.Running -> "运行中"
                    SessionBus.Status.Error -> "错误"
                    SessionBus.Status.Stopped -> "已停止"
                }
                Text(text = "状态：$statusText")
                if (session.message.isNotBlank()) {
                    Text(text = session.message)
                }
                if (!canDrawOverlays) {
                    Text(text = "需要授予「显示在其他应用上层」权限")
                }

                Button(
                    onClick = { if (running) onStop() else onStart() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (running) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColorsPrimary()
                    },
                ) {
                    Text(text = if (running) "停止字幕" else "启动字幕")
                }
            }
        }

        if (session.outputPreview.isNotBlank() || session.inputPreview.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "最近字幕预览")
                    if (session.inputPreview.isNotBlank()) {
                        Text(text = "原文：${session.inputPreview}")
                    }
                    if (session.outputPreview.isNotBlank()) {
                        Text(text = "译文：${session.outputPreview}")
                    }
                }
            }
        }

        TextButton(text = "字幕样式 / API 设置", onClick = onOpenSettings)
        Spacer(modifier = Modifier.height(24.dp))
    }

    OverlayDialog(
        show = showSourceDialog,
        title = "选择源语言",
        onDismissRequest = { showSourceDialog = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sourceLabels.forEachIndexed { index, label ->
                TextButton(
                    text = if (index == sourceIndex) "✓ $label" else label,
                    onClick = {
                        onSourceLanguage(sourceCodes[index])
                        showSourceDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    OverlayDialog(
        show = showTargetDialog,
        title = "选择目标语言",
        onDismissRequest = { showTargetDialog = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            targetLabels.forEachIndexed { index, label ->
                TextButton(
                    text = if (index == targetIndex) "✓ $label" else label,
                    onClick = {
                        onTargetLanguage(targetCodes[index])
                        showTargetDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
