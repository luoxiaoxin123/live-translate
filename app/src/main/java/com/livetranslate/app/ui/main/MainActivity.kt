package com.livetranslate.app.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livetranslate.app.LiveTranslateApp
import com.livetranslate.app.R
import com.livetranslate.app.service.SessionBus
import com.livetranslate.app.service.SubtitleSessionService
import com.livetranslate.app.ui.settings.SettingsScreen
import com.livetranslate.app.ui.settings.SettingsViewModel
import com.livetranslate.app.ui.settings.SettingsViewModelFactory
import com.livetranslate.app.ui.subtitle.SubtitleScreen
import com.livetranslate.app.ui.subtitle.SubtitleViewModel
import com.livetranslate.app.ui.subtitle.SubtitleViewModelFactory
import com.livetranslate.app.ui.theme.AppTheme
import com.livetranslate.app.util.PermissionUtils
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LiveTranslateApp

        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var tab by remember { mutableIntStateOf(0) }

                val subtitleVm: SubtitleViewModel = viewModel(
                    factory = SubtitleViewModelFactory(app.settingsRepository, app.apiKeyStore),
                )
                val settingsVm: SettingsViewModel = viewModel(
                    factory = SettingsViewModelFactory(app.settingsRepository, app.apiKeyStore),
                )

                val session by SessionBus.state.collectAsStateWithLifecycle()
                val settings by subtitleVm.settings.collectAsStateWithLifecycle()

                var pendingStart by remember { mutableStateOf(false) }

                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        runCatching {
                            SubtitleSessionService.start(this, result.resultCode, result.data!!)
                        }.onFailure {
                            Log.e(TAG, "start service failed", it)
                            SessionBus.setStatus(
                                SessionBus.Status.Error,
                                getString(R.string.msg_service_start_failed, it.message.orEmpty()),
                            )
                        }
                    } else {
                        SessionBus.setStatus(
                            SessionBus.Status.Error,
                            getString(R.string.msg_projection_cancelled),
                        )
                    }
                }

                val overlayLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    if (PermissionUtils.canDrawOverlays(this) && pendingStart) {
                        pendingStart = false
                        launchProjection(projectionLauncher::launch)
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    val ok = granted.values.all { it }
                    if (!ok) {
                        SessionBus.setStatus(
                            SessionBus.Status.Error,
                            getString(R.string.msg_need_permissions),
                        )
                        return@rememberLauncherForActivityResult
                    }
                    if (!PermissionUtils.canDrawOverlays(this)) {
                        pendingStart = true
                        overlayLauncher.launch(PermissionUtils.overlaySettingsIntent(this))
                    } else {
                        launchProjection(projectionLauncher::launch)
                    }
                }

                fun requestStartSubtitle() {
                    try {
                        if (!app.apiKeyStore.hasApiKey()) {
                            SessionBus.setStatus(
                                SessionBus.Status.Error,
                                getString(R.string.msg_need_api_key),
                            )
                            tab = 1
                            return
                        }
                        val needed = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.filter {
                            ContextCompat.checkSelfPermission(this, it) !=
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (needed.isNotEmpty()) {
                            permissionLauncher.launch(needed.toTypedArray())
                            return
                        }
                        if (!PermissionUtils.canDrawOverlays(this)) {
                            pendingStart = true
                            overlayLauncher.launch(PermissionUtils.overlaySettingsIntent(this))
                            return
                        }
                        launchProjection(projectionLauncher::launch)
                    } catch (t: Throwable) {
                        Log.e(TAG, "requestStartSubtitle", t)
                        SessionBus.setStatus(
                            SessionBus.Status.Error,
                            getString(R.string.msg_start_failed, t.message.orEmpty()),
                        )
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // MIUIX: page background is surface (#F7F7F7 gray); cards use surfaceContainer white
                    containerColor = MiuixTheme.colorScheme.surface,
                    bottomBar = {
                        NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = Icons.Outlined.Subtitles,
                                label = stringResource(R.string.tab_subtitle),
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = Icons.Outlined.Settings,
                                label = stringResource(R.string.tab_settings),
                            )
                        }
                    },
                ) { padding ->
                    // Compose AnimatedContent — MIUIX has no dedicated tab page transition.
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        transitionSpec = {
                            val forward = targetState > initialState
                            val duration = 260
                            if (forward) {
                                slideInHorizontally(tween(duration)) { it } togetherWith
                                    slideOutHorizontally(tween(duration)) { -it }
                            } else {
                                slideInHorizontally(tween(duration)) { -it } togetherWith
                                    slideOutHorizontally(tween(duration)) { it }
                            }
                        },
                        label = "main-tab",
                    ) { page ->
                        when (page) {
                            0 -> SubtitleScreen(
                                modifier = Modifier.fillMaxSize(),
                                settings = settings,
                                session = session,
                                onSourceLanguage = { code ->
                                    scope.launch { subtitleVm.setSourceLanguage(code) }
                                },
                                onTargetLanguage = { code ->
                                    scope.launch { subtitleVm.setTargetLanguage(code) }
                                },
                                onStart = { requestStartSubtitle() },
                                onStop = {
                                    scope.launch {
                                        runCatching {
                                            SessionBus.stop()
                                            SubtitleSessionService.stop(this@MainActivity)
                                        }
                                    }
                                },
                                onOpenSettings = { tab = 1 },
                                canDrawOverlays = PermissionUtils.canDrawOverlays(this@MainActivity),
                            )
                            else -> SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = settingsVm,
                                onOpenOverlayPermission = {
                                    startActivity(
                                        PermissionUtils.overlaySettingsIntent(this@MainActivity),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchProjection(launch: (Intent) -> Unit) {
        SessionBus.setStatus(
            SessionBus.Status.Starting,
            getString(R.string.msg_requesting_projection),
        )
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
