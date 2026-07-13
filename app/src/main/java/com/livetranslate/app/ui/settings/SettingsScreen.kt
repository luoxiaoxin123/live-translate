package com.livetranslate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetranslate.app.ui.components.PageTitle
import com.livetranslate.app.ui.components.SectionCard
import com.livetranslate.app.ui.components.SectionLabel
import com.livetranslate.app.ui.components.SettingSwitchRow
import com.livetranslate.app.ui.theme.Booth
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    onOpenOverlayPermission: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiKeyFromVm by viewModel.apiKeyDraft.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    // Local TextFieldValue keeps selection/cursor. Never recreate from plain String each keystroke.
    var endpointField by remember {
        mutableStateOf(TextFieldValue(settings.endpoint, TextRange(settings.endpoint.length)))
    }
    var modelField by remember {
        mutableStateOf(TextFieldValue(settings.modelId, TextRange(settings.modelId.length)))
    }
    var apiKeyField by remember {
        mutableStateOf(TextFieldValue(apiKeyFromVm, TextRange(apiKeyFromVm.length)))
    }
    var revealKey by remember { mutableStateOf(false) }

    // Hydrate once from DataStore when first non-default values arrive (without clobbering typing)
    var endpointHydrated by remember { mutableStateOf(false) }
    var modelHydrated by remember { mutableStateOf(false) }
    LaunchedEffect(settings.endpoint) {
        if (!endpointHydrated && settings.endpoint.isNotBlank()) {
            endpointField = TextFieldValue(settings.endpoint, TextRange(settings.endpoint.length))
            endpointHydrated = true
        }
    }
    LaunchedEffect(settings.modelId) {
        if (!modelHydrated && settings.modelId.isNotBlank()) {
            modelField = TextFieldValue(settings.modelId, TextRange(settings.modelId.length))
            modelHydrated = true
        }
    }
    LaunchedEffect(apiKeyFromVm) {
        // Only sync if field empty and store has value (first load)
        if (apiKeyField.text.isEmpty() && apiKeyFromVm.isNotEmpty()) {
            apiKeyField = TextFieldValue(apiKeyFromVm, TextRange(apiKeyFromVm.length))
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Booth.Accent,
        unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
        focusedLabelColor = Booth.Accent,
        cursorColor = Booth.Accent,
        focusedContainerColor = MiuixTheme.colorScheme.surface,
        unfocusedContainerColor = MiuixTheme.colorScheme.surface,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTitle("设置", "API、字幕外观与译音")

        SectionCard {
            SectionLabel("API 连接")
            Text(
                text = "默认对接 Google AI Studio Live Translate。可自定义兼容端点。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            OutlinedTextField(
                value = endpointField,
                onValueChange = { v ->
                    endpointField = v
                    viewModel.update { it.copy(endpoint = v.text.trim()) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { androidx.compose.material3.Text("API 端点") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { v ->
                    apiKeyField = v
                    viewModel.setApiKeyDraft(v.text)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { androidx.compose.material3.Text("API Key") },
                singleLine = true,
                visualTransformation = if (revealKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    TextButton(
                        text = if (revealKey) "隐藏" else "显示",
                        onClick = { revealKey = !revealKey },
                    )
                },
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = modelField,
                onValueChange = { v ->
                    modelField = v
                    viewModel.update { it.copy(modelId = v.text.trim()) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { androidx.compose.material3.Text("模型 ID") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            Button(
                onClick = {
                    viewModel.saveApiKey()
                    viewModel.testConnection()
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = if (testing) "测试中…" else "保存并测试连接",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            TextButton(
                text = "仅保存 API Key",
                onClick = viewModel::saveApiKey,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!testResult.isNullOrBlank()) {
                Text(
                    text = testResult.orEmpty(),
                    fontSize = 13.sp,
                    color = when {
                        testResult!!.startsWith("✅") -> Booth.Success
                        testResult!!.startsWith("❌") -> Booth.Danger
                        else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                )
            }
        }

        SectionCard {
            SectionLabel("字幕外观")
            Text(
                text = "字体大小 ${settings.fontSizeSp.toInt()} sp · 系统默认字体",
                fontSize = 13.sp,
            )
            Slider(
                value = settings.fontSizeSp,
                onValueChange = { size -> viewModel.update { it.copy(fontSizeSp = size) } },
                valueRange = 12f..32f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "黑底透明度 ${(settings.backgroundAlpha * 100).toInt()}%",
                fontSize = 13.sp,
            )
            Slider(
                value = settings.backgroundAlpha,
                onValueChange = { a -> viewModel.update { it.copy(backgroundAlpha = a) } },
                valueRange = 0.1f..0.95f,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingSwitchRow(
                title = "双语显示",
                summary = if (settings.bilingual) "显示原文 + 译文" else "仅显示译文",
                checked = settings.bilingual,
                onCheckedChange = { c -> viewModel.update { it.copy(bilingual = c) } },
            )
            TextButton(
                text = "重置字幕外观",
                onClick = viewModel::resetSubtitleAppearance,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            SectionLabel("译音")
            SettingSwitchRow(
                title = "播放译音",
                summary = "云端返回的翻译语音；默认关。开启后与原声并行，不暂停视频。",
                checked = settings.playTranslatedAudio,
                onCheckedChange = { c -> viewModel.update { it.copy(playTranslatedAudio = c) } },
            )
            Text(
                text = buildString {
                    val pct = (settings.translatedVolume * 100).toInt()
                    append("译音音量 $pct%")
                    if (settings.translatedVolume > 1f) append("（增强，可能轻微失真）")
                },
                fontSize = 13.sp,
            )
            Slider(
                value = settings.translatedVolume.coerceIn(0f, 2f),
                onValueChange = { v ->
                    viewModel.update { it.copy(translatedVolume = v.coerceIn(0f, 2f)) }
                },
                // 0–200%：≤100% 走系统音量；>100% 对 PCM 做数字增益
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "可拉过 100%，让译音压过原片外文声。过高可能削波。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }

        SectionCard {
            SectionLabel("权限与关于")
            TextButton(
                text = "打开悬浮窗权限设置",
                onClick = onOpenOverlayPermission,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "启动字幕时会请求系统录屏授权，用于捕获其它 App 的播放声音。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
