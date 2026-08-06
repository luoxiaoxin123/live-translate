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
import com.livetranslate.app.audio.MicAudioCapturer
import com.livetranslate.app.audio.PcmMixer
import com.livetranslate.app.audio.SystemAudioCapturer
import com.livetranslate.app.audio.TranslatedAudioPlayer
import com.livetranslate.app.data.AudioSourceMode
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.live.LiveTranslateClient
import com.livetranslate.app.overlay.SubtitleOverlayController
import com.livetranslate.app.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Foreground session: audio capture (media / mic / both) + Live WS + floating overlay.
 */
class SubtitleSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var mediaCapturer: SystemAudioCapturer? = null
    private var micCapturer: MicAudioCapturer? = null
    private var pcmMixer: PcmMixer? = null
    private var liveClient: LiveTranslateClient? = null
    private var audioPlayer: TranslatedAudioPlayer? = null
    private var overlay: SubtitleOverlayController? = null
    private var settingsJob: Job? = null
    private var eventsJob: Job? = null
    private var commandJob: Job? = null
    /** Active startSession work; cancelled on stop / re-start to avoid races. */
    private var sessionJob: Job? = null

    private var currentSettings: UserSettings = UserSettings()
    private var audioSourceMode: AudioSourceMode = AudioSourceMode.MEDIA
    private var captureStarted = false
    /** Re-entrancy guard for stopEverything (Failed event + close can both fire). */
    private var stopping = false

    private var accumulatedInput = StringBuilder()
    private var accumulatedOutput = StringBuilder()
    private var fullInput = StringBuilder()
    private var fullOutput = StringBuilder()

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
                val modeName = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                audioSourceMode = AudioSourceMode.fromStorage(modeName)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (audioSourceMode.needsMediaProjection) {
                    if (data == null || resultCode != Activity.RESULT_OK) {
                        promoteThenExit("录屏授权失败", asError = true)
                        return START_NOT_STICKY
                    }
                    startSession(resultCode, data)
                } else {
                    startSession(resultCode = null, data = null)
                }
            }
            else -> {
                // System may restart a dead FGS with a null/unknown intent.
                // MediaProjection tokens cannot be recovered — exit cleanly after
                // a brief startForeground to avoid ForegroundServiceDidNotStartInTimeException.
                Log.w(TAG, "onStartCommand without ACTION_START (intent=$intent); refusing restart")
                promoteThenExit("会话被系统回收，请重新开始", asError = false)
            }
        }
        // MediaProjection + live WS cannot be meaningfully restored after process death.
        return START_NOT_STICKY
    }

    /**
     * When we must leave without a full session, still call startForeground first
     * so the FGS start timeout does not crash the process.
     *
     * Avoid claiming [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] here:
     * without a live MediaProjection token that can SecurityException on Android 14+.
     */
    private fun promoteThenExit(message: String, asError: Boolean) {
        val notification = buildNotification()
        runCatching {
            when {
                // SPECIAL_USE is API 34+; use it only when we lack mic/projection rights.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        }.recoverCatching {
            // Last resort: untyped path to satisfy the FGS start timeout.
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "promoteThenExit startForeground failed", it)
        }
        if (asError) {
            SessionBus.setStatus(SessionBus.Status.Error, message)
        } else {
            SessionBus.setStatus(SessionBus.Status.Stopped, message)
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startSession(resultCode: Int?, data: Intent?) {
        // Cancel any in-flight start and drop previous resources before a new session.
        sessionJob?.cancel()
        sessionJob = null
        releaseSessionResources()
        stopping = false

        SessionBus.setStatus(SessionBus.Status.Starting, "正在启动…")
        SessionBus.clearExport()
        accumulatedInput.clear()
        accumulatedOutput.clear()
        fullInput.clear()
        fullOutput.clear()
        captureStarted = false

        try {
            startAsForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            SessionBus.setStatus(
                SessionBus.Status.Error,
                t.message?.takeIf { it.isNotBlank() } ?: "无法启动前台服务",
            )
            stopSelf()
            return
        }

        val app = application as LiveTranslateApp
        if (!app.apiKeyStore.hasApiKey()) {
            SessionBus.setStatus(SessionBus.Status.Error, "请先在设置中填写 API Key")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        sessionJob = scope.launch {
            try {
                currentSettings = app.settingsRepository.settings.first()

                if (audioSourceMode.needsMediaProjection) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpm.getMediaProjection(resultCode!!, data!!)
                    if (projection == null) {
                        stopEverything("无法创建 MediaProjection", asError = true)
                        return@launch
                    }
                    mediaProjection = projection
                    projection.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                // Handler null → main thread
                                stopEverything("录屏权限已撤销", asError = true)
                            }
                        },
                        null,
                    )
                }

                val overlayController = SubtitleOverlayController(this@SubtitleSessionService) { x, y, w, h ->
                    ioScope.launch {
                        app.settingsRepository.update {
                            it.copy(overlayX = x, overlayY = y, overlayWidthDp = w, overlayHeightDp = h)
                        }
                    }
                }
                overlay = overlayController
                overlayController.show(currentSettings)

                val client = LiveTranslateClient()
                liveClient = client

                val player = TranslatedAudioPlayer()
                audioPlayer = player
                player.setEnabled(currentSettings.playTranslatedAudio)
                player.setVolume(currentSettings.translatedVolume)

                // Child of sessionJob so cancel(sessionJob) tears these down too.
                eventsJob = launch {
                    launch {
                        client.connectionState.collect { state ->
                            when (state) {
                                is LiveTranslateClient.ConnectionState.Ready -> {
                                    SessionBus.setStatus(SessionBus.Status.Running, "翻译中")
                                    startCapturePipeline(client)
                                }
                                is LiveTranslateClient.ConnectionState.Failed -> {
                                    // Full teardown: stop capture, close WS resources, leave FGS.
                                    stopEverything(state.message, asError = true)
                                }
                                is LiveTranslateClient.ConnectionState.Closed -> {
                                    stopEverything("连接已断开", asError = true)
                                }
                                else -> Unit
                            }
                        }
                    }
                    client.events.collect { event ->
                        when (event) {
                            is LiveTranslateClient.LiveEvent.SetupComplete -> {
                                SessionBus.setStatus(SessionBus.Status.Running, "翻译中")
                                startCapturePipeline(client)
                            }
                            is LiveTranslateClient.LiveEvent.InputTranscript -> {
                                appendTranscript(accumulatedInput, event.text)
                                appendFull(fullInput, event.text)
                                val text = accumulatedInput.toString()
                                overlay?.updateTranscripts(input = text, output = null)
                                SessionBus.setPreview(input = text)
                            }
                            is LiveTranslateClient.LiveEvent.OutputTranscript -> {
                                appendTranscript(accumulatedOutput, event.text)
                                appendFull(fullOutput, event.text)
                                val text = accumulatedOutput.toString()
                                overlay?.updateTranscripts(input = null, output = text)
                                SessionBus.setPreview(output = text)
                            }
                            is LiveTranslateClient.LiveEvent.AudioChunk -> {
                                if (currentSettings.playTranslatedAudio) {
                                    player.playPcm(event.pcm, event.mimeType)
                                }
                            }
                            is LiveTranslateClient.LiveEvent.Error -> {
                                // connectionState.Failed also fires; stopEverything is re-entrant-safe.
                                stopEverything(event.message, asError = true)
                            }
                            is LiveTranslateClient.LiveEvent.Debug -> {
                                Log.d(TAG, event.message)
                            }
                        }
                    }
                }

                settingsJob = launch {
                    app.settingsRepository.settings.collectLatest { s ->
                        val prevPlay = currentSettings.playTranslatedAudio
                        currentSettings = s
                        overlay?.updateSettings(s)
                        player.setEnabled(s.playTranslatedAudio)
                        player.setVolume(s.translatedVolume)
                        if (prevPlay && !s.playTranslatedAudio) {
                            Log.i(TAG, "translated audio disabled")
                        }
                    }
                }

                yield()
                if (stopping) return@launch

                val key = app.apiKeyStore.nextRotatedKey()
                if (key.isBlank()) {
                    stopEverything("请先在设置中填写 API Key", asError = true)
                    return@launch
                }
                client.connect(
                    LiveTranslateClient.SessionConfig(
                        endpoint = currentSettings.endpoint,
                        apiKey = key,
                        modelId = currentSettings.modelId,
                        targetLanguageCode = currentSettings.targetLanguageCode,
                        echoTargetLanguage = true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "startSession failed", t)
                stopEverything(
                    t.message?.takeIf { it.isNotBlank() } ?: "启动失败",
                    asError = true,
                )
            }
        }
    }

    private fun startCapturePipeline(client: LiveTranslateClient) {
        if (captureStarted || stopping) return
        captureStarted = true
        try {
            when (audioSourceMode) {
                AudioSourceMode.MEDIA -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException("缺少 MediaProjection")
                    val cap = SystemAudioCapturer(ioScope)
                    mediaCapturer = cap
                    cap.start(projection) { pcm -> client.sendPcm16le(pcm, 16_000) }
                }
                AudioSourceMode.MIC -> {
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start { pcm -> client.sendPcm16le(pcm, 16_000) }
                }
                AudioSourceMode.MEDIA_AND_MIC -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException("缺少 MediaProjection")
                    val mixer = PcmMixer { mixed -> client.sendPcm16le(mixed, 16_000) }
                    pcmMixer = mixer
                    val media = SystemAudioCapturer(ioScope)
                    mediaCapturer = media
                    media.start(projection) { pcm -> mixer.offerMedia(pcm) }
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start { pcm -> mixer.offerMic(pcm) }
                }
            }
            SessionBus.setStatus(SessionBus.Status.Running, "翻译中 · 等待声音…")
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed", e)
            stopEverything(e.message ?: "音频采集启动失败", asError = true)
        }
    }

    private fun appendTranscript(buffer: StringBuilder, chunk: String) {
        if (chunk.length >= buffer.length && chunk.startsWith(buffer.toString())) {
            buffer.clear()
            buffer.append(chunk)
        } else if (buffer.endsWith(chunk)) {
            // ignore
        } else {
            if (buffer.isNotEmpty() && !buffer.last().isWhitespace() && chunk.isNotEmpty() &&
                !chunk.first().isWhitespace()
            ) {
                buffer.append(' ')
            }
            buffer.append(chunk)
        }
        if (buffer.length > 800) {
            buffer.delete(0, buffer.length - 800)
        }
    }

    private fun appendFull(buffer: StringBuilder, chunk: String) {
        // Prefer cumulative server rewrites when present
        if (chunk.length >= buffer.length && buffer.isNotEmpty() && chunk.startsWith(buffer.toString())) {
            buffer.clear()
            buffer.append(chunk)
            return
        }
        if (buffer.endsWith(chunk)) return
        if (buffer.isNotEmpty() && !buffer.last().isWhitespace() && chunk.isNotEmpty() &&
            !chunk.first().isWhitespace()
        ) {
            buffer.append(' ')
        }
        buffer.append(chunk)
        // Cap export size ~200k chars
        if (buffer.length > 200_000) {
            buffer.delete(0, buffer.length - 200_000)
        }
    }

    private fun buildNotification(): Notification {
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
        return NotificationCompat.Builder(this, LiveTranslateApp.CHANNEL_SUBTITLE)
            .setContentTitle(getString(R.string.notification_subtitle_running))
            .setContentText(getString(R.string.notification_subtitle_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (audioSourceMode) {
                AudioSourceMode.MEDIA ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                AudioSourceMode.MIC ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                AudioSourceMode.MEDIA_AND_MIC ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fgsType != 0) {
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Drop capturers / client / overlay / projection without finishing the Service.
     * Used when re-starting a session or as part of [stopEverything].
     */
    private fun releaseSessionResources() {
        settingsJob?.cancel()
        settingsJob = null
        eventsJob?.cancel()
        eventsJob = null
        runCatching { mediaCapturer?.stop() }
        mediaCapturer = null
        runCatching { micCapturer?.stop() }
        micCapturer = null
        runCatching { pcmMixer?.close() }
        pcmMixer = null
        runCatching {
            liveClient?.close()
            liveClient?.destroy()
        }
        liveClient = null
        runCatching { audioPlayer?.release() }
        audioPlayer = null
        runCatching { overlay?.hide() }
        overlay = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        captureStarted = false
    }

    private fun stopEverything(message: String, asError: Boolean = false) {
        if (stopping) return
        stopping = true

        val inFull = fullInput.toString()
        val outFull = fullOutput.toString()
        if (inFull.isNotBlank() || outFull.isNotBlank()) {
            SessionBus.markSessionFinished(inFull, outFull, message)
        } else if (asError) {
            SessionBus.setStatus(SessionBus.Status.Error, message)
        } else {
            SessionBus.setStatus(SessionBus.Status.Stopped, message)
        }

        // Cancel start pipeline first so it cannot recreate resources after we drop them.
        sessionJob?.cancel()
        sessionJob = null
        releaseSessionResources()

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        sessionJob?.cancel()
        sessionJob = null
        releaseSessionResources()
        commandJob?.cancel()
        commandJob = null
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
        const val EXTRA_AUDIO_SOURCE = "audio_source"

        fun start(
            context: Context,
            audioSource: AudioSourceMode,
            resultCode: Int? = null,
            data: Intent? = null,
        ) {
            val intent = Intent(context, SubtitleSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUDIO_SOURCE, audioSource.name)
                if (resultCode != null && data != null) {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
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
