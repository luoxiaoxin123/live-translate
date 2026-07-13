package com.livetranslate.app.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranslate.app.LiveTranslateApp
import com.livetranslate.app.R
import com.livetranslate.app.audio.SystemAudioCapturer
import com.livetranslate.app.audio.TranslatedAudioPlayer
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.live.LiveTranslateClient
import com.livetranslate.app.overlay.SubtitleOverlayController
import com.livetranslate.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single foreground service: MediaProjection capture + Live WS + floating overlay.
 */
class SubtitleSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var capturer: SystemAudioCapturer? = null
    private var liveClient: LiveTranslateClient? = null
    private var audioPlayer: TranslatedAudioPlayer? = null
    private var overlay: SubtitleOverlayController? = null
    private var settingsJob: Job? = null
    private var eventsJob: Job? = null
    private var commandJob: Job? = null

    private var currentSettings: UserSettings = UserSettings()
    private var accumulatedInput = StringBuilder()
    private var accumulatedOutput = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        commandJob = scope.launch {
            SessionBus.commands.collect { cmd ->
                when (cmd) {
                    SessionBus.Command.Stop -> stopEverything("已停止")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("已停止")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null || resultCode != Activity.RESULT_OK) {
                    SessionBus.setStatus(SessionBus.Status.Error, "录屏授权失败")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent) {
        SessionBus.setStatus(SessionBus.Status.Starting, "正在启动…")
        startAsForeground()

        val app = application as LiveTranslateApp
        val apiKey = app.apiKeyStore.getApiKey()
        if (apiKey.isBlank()) {
            SessionBus.setStatus(SessionBus.Status.Error, "请先在设置中填写 API Key")
            stopSelf()
            return
        }

        scope.launch {
            currentSettings = app.settingsRepository.settings.first()
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            if (projection == null) {
                SessionBus.setStatus(SessionBus.Status.Error, "无法创建 MediaProjection")
                stopSelf()
                return@launch
            }
            mediaProjection = projection
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopEverything("录屏权限已撤销")
                    }
                },
                null,
            )

            // Overlay
            val overlayController = SubtitleOverlayController(this@SubtitleSessionService) { x, y, w, h ->
                ioScope.launch {
                    app.settingsRepository.update {
                        it.copy(overlayX = x, overlayY = y, overlayWidthDp = w, overlayHeightDp = h)
                    }
                }
            }
            overlay = overlayController
            overlayController.show(currentSettings)

            // Live client
            val client = LiveTranslateClient()
            liveClient = client
            client.connect(
                LiveTranslateClient.SessionConfig(
                    endpoint = currentSettings.endpoint,
                    apiKey = apiKey,
                    modelId = currentSettings.modelId,
                    targetLanguageCode = currentSettings.targetLanguageCode,
                    echoTargetLanguage = true,
                ),
            )

            // Audio out
            val player = TranslatedAudioPlayer()
            audioPlayer = player
            player.setEnabled(currentSettings.playTranslatedAudio)
            player.setVolume(currentSettings.translatedVolume)

            eventsJob = scope.launch {
                client.events.collect { event ->
                    when (event) {
                        is LiveTranslateClient.LiveEvent.SetupComplete -> {
                            SessionBus.setStatus(SessionBus.Status.Running, "翻译中")
                            startCapture(projection, client)
                        }
                        is LiveTranslateClient.LiveEvent.InputTranscript -> {
                            appendTranscript(accumulatedInput, event.text)
                            val text = accumulatedInput.toString()
                            overlay?.updateTranscripts(input = text, output = null)
                            SessionBus.setPreview(input = text)
                        }
                        is LiveTranslateClient.LiveEvent.OutputTranscript -> {
                            appendTranscript(accumulatedOutput, event.text)
                            val text = accumulatedOutput.toString()
                            overlay?.updateTranscripts(input = null, output = text)
                            SessionBus.setPreview(output = text)
                        }
                        is LiveTranslateClient.LiveEvent.AudioChunk -> {
                            player.playPcm(event.pcm, event.mimeType)
                        }
                        is LiveTranslateClient.LiveEvent.Error -> {
                            SessionBus.setStatus(SessionBus.Status.Error, event.message)
                        }
                    }
                }
            }

            // React to settings changes (style / audio) while running
            settingsJob = scope.launch {
                app.settingsRepository.settings.collectLatest { s ->
                    currentSettings = s
                    overlay?.updateSettings(s)
                    player.setEnabled(s.playTranslatedAudio)
                    player.setVolume(s.translatedVolume)
                }
            }
        }
    }

    private fun startCapture(projection: MediaProjection, client: LiveTranslateClient) {
        if (capturer != null) return
        val cap = SystemAudioCapturer(ioScope)
        capturer = cap
        try {
            cap.start(projection) { pcm ->
                client.sendPcm16le(pcm, 16_000)
            }
            SessionBus.setStatus(SessionBus.Status.Running, "翻译中 · 等待声音…")
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed", e)
            SessionBus.setStatus(SessionBus.Status.Error, e.message ?: "内录启动失败")
            stopEverything(e.message ?: "内录启动失败")
        }
    }

    private fun appendTranscript(buffer: StringBuilder, chunk: String) {
        // Live transcripts are often cumulative or partial; keep last ~800 chars.
        if (chunk.length >= buffer.length && chunk.startsWith(buffer.toString())) {
            buffer.clear()
            buffer.append(chunk)
        } else if (buffer.endsWith(chunk)) {
            // ignore duplicate
        } else {
            if (buffer.isNotEmpty() && !buffer.last().isWhitespace() && !chunk.first().isWhitespace()) {
                buffer.append(' ')
            }
            buffer.append(chunk)
        }
        if (buffer.length > 800) {
            buffer.delete(0, buffer.length - 800)
        }
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, SubtitleSessionService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, LiveTranslateApp.CHANNEL_SUBTITLE)
            .setContentTitle(getString(R.string.notification_subtitle_running))
            .setContentText(getString(R.string.notification_subtitle_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything(message: String) {
        SessionBus.setStatus(SessionBus.Status.Stopped, message)
        capturer?.stop()
        capturer = null
        liveClient?.close()
        liveClient?.destroy()
        liveClient = null
        audioPlayer?.release()
        audioPlayer = null
        overlay?.hide()
        overlay = null
        mediaProjection?.stop()
        mediaProjection = null
        settingsJob?.cancel()
        eventsJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        capturer?.stop()
        liveClient?.destroy()
        audioPlayer?.release()
        overlay?.hide()
        mediaProjection?.stop()
        scope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SubtitleSessionService"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.livetranslate.app.action.START_SUBTITLE"
        const val ACTION_STOP = "com.livetranslate.app.action.STOP_SUBTITLE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SubtitleSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SubtitleSessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
