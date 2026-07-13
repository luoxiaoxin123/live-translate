package com.livetranslate.app.ui.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranslate.app.data.LanguageOption
import com.livetranslate.app.data.SupportedLanguages
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.service.SessionBus
import com.livetranslate.app.ui.components.PageTitle
import com.livetranslate.app.ui.components.SectionCard
import com.livetranslate.app.ui.components.SectionLabel
import com.livetranslate.app.ui.components.StatusPill
import com.livetranslate.app.ui.components.StatusTone
import com.livetranslate.app.ui.theme.Booth
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class LangPicker { Source, Target }

@OptIn(ExperimentalMaterial3Api::class)
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

    var picker by remember { mutableStateOf<LangPicker?>(null) }

    val (statusLabel, tone) = when (session.status) {
        SessionBus.Status.Idle -> "空闲" to StatusTone.Idle
        SessionBus.Status.Starting -> "启动中" to StatusTone.Working
        SessionBus.Status.Running -> "翻译中" to StatusTone.Ok
        SessionBus.Status.Error -> "出错" to StatusTone.Error
        SessionBus.Status.Stopped -> "已停止" to StatusTone.Warn
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTitle("实时字幕", "捕获系统内放音频 · Gemini Live Translate")

        // Hero control card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E3A8A), Booth.Accent, Color(0xFF38BDF8)),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "同传字幕",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (running) "悬浮窗运行中" else "一键启动系统内录翻译",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                    }
                    StatusPill(label = statusLabel, tone = tone)
                }

                if (session.message.isNotBlank()) {
                    Text(
                        text = session.message,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = { if (running) onStop() else onStart() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        color = Color.White,
                        contentColor = Booth.Accent,
                    ),
                ) {
                    Text(
                        text = if (running) "停止字幕" else "启动字幕",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                if (!canDrawOverlays) {
                    Text(
                        text = "尚未授予悬浮窗权限，启动时会引导你开启。",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        SectionCard {
            SectionLabel("语言")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageChip(
                    title = "源语言",
                    value = SupportedLanguages.labelOf(settings.sourceLanguageCode),
                    onClick = { picker = LangPicker.Source },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "→",
                    fontWeight = FontWeight.Bold,
                    color = Booth.Accent,
                )
                LanguageChip(
                    title = "目标语言",
                    value = SupportedLanguages.labelOf(settings.targetLanguageCode),
                    onClick = { picker = LangPicker.Target },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "选择会自动记住。Live Translate 以目标语为主；源语言建议用「自动检测」。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        if (session.outputPreview.isNotBlank() || session.inputPreview.isNotBlank()) {
            SectionCard {
                SectionLabel("最近预览")
                if (session.inputPreview.isNotBlank()) {
                    Text(
                        text = session.inputPreview,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (session.outputPreview.isNotBlank()) {
                    Text(
                        text = session.outputPreview,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        TextButton(
            text = "字幕样式 / API 设置",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    val currentPicker = picker
    if (currentPicker != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val options: List<LanguageOption> = when (currentPicker) {
            LangPicker.Source -> SupportedLanguages.sourceOptions
            LangPicker.Target -> SupportedLanguages.targetOptions
        }
        val selected = when (currentPicker) {
            LangPicker.Source -> settings.sourceLanguageCode
            LangPicker.Target -> settings.targetLanguageCode
        }
        ModalBottomSheet(
            onDismissRequest = { picker = null },
            sheetState = sheetState,
            containerColor = MiuixTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = if (currentPicker == LangPicker.Source) "选择源语言" else "选择目标语言",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(options) { _, option ->
                        val selectedRow = option.code == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (currentPicker) {
                                        LangPicker.Source -> onSourceLanguage(option.code)
                                        LangPicker.Target -> onTargetLanguage(option.code)
                                    }
                                    picker = null
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.labelZh,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedRow) Booth.Accent else MiuixTheme.colorScheme.onSurface,
                            )
                            if (selectedRow) {
                                Text(text = "已选", color = Booth.Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Booth.AccentSoft.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Booth.Ink,
        )
    }
}
