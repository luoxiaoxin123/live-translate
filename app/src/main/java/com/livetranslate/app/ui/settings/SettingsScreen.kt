package com.livetranslate.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetranslate.app.BuildConfig
import com.livetranslate.app.R
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.ui.components.PageTitle
import com.livetranslate.app.ui.components.SectionCard
import com.livetranslate.app.ui.components.SettingSwitchRow
import com.livetranslate.app.ui.theme.Booth
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
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
    val apiKeyFields by viewModel.apiKeyFields.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    var endpointField by remember {
        mutableStateOf(TextFieldValue(settings.endpoint, TextRange(settings.endpoint.length)))
    }
    var modelField by remember {
        mutableStateOf(TextFieldValue(settings.modelId, TextRange(settings.modelId.length)))
    }
    var revealKey by remember { mutableStateOf(false) }

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

    // Dark-mode safe field colors (explicit text / cursor colors)
    val scheme = MiuixTheme.colorScheme
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = scheme.disabledOnSurface,
        focusedBorderColor = Booth.Accent,
        unfocusedBorderColor = scheme.outline.copy(alpha = 0.45f),
        focusedLabelColor = Booth.Accent,
        unfocusedLabelColor = scheme.onSurfaceVariantSummary,
        cursorColor = Booth.Accent,
        focusedContainerColor = scheme.surface,
        unfocusedContainerColor = scheme.surface,
        focusedPlaceholderColor = scheme.onSurfaceVariantSummary,
        unfocusedPlaceholderColor = scheme.onSurfaceVariantSummary,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageTitle(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        SmallTitle(
            text = stringResource(R.string.settings_api),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_api_desc),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                OutlinedTextField(
                    value = endpointField,
                    onValueChange = { v ->
                        endpointField = v
                        viewModel.update { it.copy(endpoint = v.text.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { androidx.compose.material3.Text(stringResource(R.string.settings_endpoint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )

                Text(
                    text = stringResource(R.string.settings_api_keys_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                apiKeyFields.forEachIndexed { index, field ->
                    val isLast = index == apiKeyFields.lastIndex
                    val canAdd = isLast && apiKeyFields.size < ApiKeyStore.MAX_KEYS
                    val canRemove = index > 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canAdd) {
                            IconButton(
                                onClick = viewModel::addApiKeyField,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = stringResource(R.string.settings_add_key),
                                    tint = Booth.Accent,
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(40.dp))
                        }
                        OutlinedTextField(
                            value = field,
                            onValueChange = { v -> viewModel.updateApiKeyField(index, v) },
                            modifier = Modifier.weight(1f),
                            label = {
                                androidx.compose.material3.Text(
                                    stringResource(R.string.settings_api_key_n, index + 1),
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (revealKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                        )
                        if (canRemove) {
                            IconButton(
                                onClick = { viewModel.removeApiKeyField(index) },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Remove,
                                    contentDescription = stringResource(R.string.settings_remove_key),
                                    tint = Booth.Danger,
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(40.dp))
                        }
                    }
                }

                TextButton(
                    text = stringResource(
                        if (revealKey) R.string.settings_hide else R.string.settings_show,
                    ),
                    onClick = { revealKey = !revealKey },
                )

                OutlinedTextField(
                    value = modelField,
                    onValueChange = { v ->
                        modelField = v
                        viewModel.update { it.copy(modelId = v.text.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { androidx.compose.material3.Text(stringResource(R.string.settings_model_id)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                Button(
                    onClick = {
                        viewModel.saveApiKeys()
                        viewModel.testConnection()
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = stringResource(
                            if (testing) R.string.settings_testing else R.string.settings_save_and_test,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    text = stringResource(R.string.settings_save_key_only),
                    onClick = viewModel::saveApiKeys,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!testResult.isNullOrBlank()) {
                    val resultText = testResult.orEmpty()
                    val hasOk = resultText.contains("✅")
                    val hasFail = resultText.contains("❌")
                    Text(
                        text = resultText,
                        fontSize = 13.sp,
                        color = when {
                            hasOk && !hasFail -> Booth.Success
                            hasFail && !hasOk -> Booth.Danger
                            else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                    )
                }
            }
        }

        SmallTitle(
            text = stringResource(R.string.settings_appearance),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_font_size, settings.fontSizeSp.toInt()),
                    fontSize = 13.sp,
                )
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { size -> viewModel.update { it.copy(fontSizeSp = size) } },
                    valueRange = 12f..32f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.settings_bg_alpha,
                        (settings.backgroundAlpha * 100).toInt(),
                    ),
                    fontSize = 13.sp,
                )
                Slider(
                    value = settings.backgroundAlpha,
                    onValueChange = { a -> viewModel.update { it.copy(backgroundAlpha = a) } },
                    valueRange = 0.1f..0.95f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.settings_bilingual),
                summary = stringResource(
                    if (settings.bilingual) {
                        R.string.settings_bilingual_on
                    } else {
                        R.string.settings_bilingual_off
                    },
                ),
                checked = settings.bilingual,
                onCheckedChange = { c -> viewModel.update { it.copy(bilingual = c) } },
            )
            TextButton(
                text = stringResource(R.string.settings_reset_appearance),
                onClick = viewModel::resetSubtitleAppearance,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SmallTitle(
            text = stringResource(R.string.settings_voice),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SectionCard {
            SettingSwitchRow(
                title = stringResource(R.string.settings_play_voice),
                summary = stringResource(R.string.settings_play_voice_summary),
                checked = settings.playTranslatedAudio,
                onCheckedChange = { c -> viewModel.update { it.copy(playTranslatedAudio = c) } },
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                R.string.settings_voice_volume,
                                (settings.translatedVolume * 100).toInt(),
                            ),
                        )
                        if (settings.translatedVolume > 1f) {
                            append(stringResource(R.string.settings_voice_volume_boost))
                        }
                    },
                    fontSize = 13.sp,
                )
                Slider(
                    value = settings.translatedVolume.coerceIn(0f, 2f),
                    onValueChange = { v ->
                        viewModel.update { it.copy(translatedVolume = v.coerceIn(0f, 2f)) }
                    },
                    valueRange = 0f..2f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_voice_volume_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        SmallTitle(
            text = stringResource(R.string.settings_permissions),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.settings_open_overlay),
                    onClick = onOpenOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_permissions_hint),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        SmallTitle(
            text = stringResource(R.string.settings_about),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SectionCard {
            val context = LocalContext.current
            val githubUrl = stringResource(R.string.github_url).trim()
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(R.string.settings_github),
                    fontWeight = FontWeight.Medium,
                )
                if (githubUrl.isBlank()) {
                    Text(
                        text = stringResource(R.string.settings_github_pending),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = githubUrl,
                        fontSize = 13.sp,
                        color = Booth.Accent,
                    )
                    TextButton(
                        text = stringResource(R.string.settings_github_open),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
