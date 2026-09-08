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
import com.livetranslate.app.data.SupportedLanguages
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.live.LiveTranslateClient
import com.livetranslate.app.overlay.SubtitleOverlayController
import com.livetranslate.app.ui.main.MainActivity
import com.livetranslate.app.util.AppStrings
import com.livetranslate.app.util.SameLanguageCaptionMode
import com.livetranslate.app.util.TranscriptBuffer
import com.livetranslate.app.util.TranscriptLineBreaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private var currentSettings: UserSettings = UserSettings()
    private var audioSourceMode: AudioSourceMode = AudioSourceMode.MEDIA
    private var captureStarted = false
    private var stopping = false

    private var accumulatedInput = StringBuilder()
    private var accumulatedOutput = StringBuilder()
    private var fullInput = StringBuilder()
    private var fullOutput = StringBuilder()

    private var lastPreviewAtMs = 0L
    private var pendingPreviewInput: String? = null
    private var pendingPreviewOutput: String? = null
    private var previewFlushJob: Job? = null
    private val sameLanguageMode = SameLanguageCaptionMode()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        commandJob = scope.launch {
            SessionBus.commands.collect { cmd ->
                when (cmd) {
                    SessionBus.Command.Stop -> stopEverything(AppStrings.get(R.string.status_stopped))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything(AppStrings.get(R.string.status_stopped))
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
                        // Must still hit startForeground before stopSelf when
                        // launched via startForegroundService (API 26+).
                        startAsForeground()
                        SessionBus.setStatus(SessionBus.Status.Error, AppStrings.get(R.string.error_projection_denied))
                        stopSelf()
                    } else {
                        startSession(resultCode, data)
                    }
                } else {
                    startSession(resultCode = null, data = null)
                }
            }
            else -> {
                // Sticky restart or unknown action: must not sit without startForeground.
                ensureForegroundNotification()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int?, data: Intent?) {
        // A previous session may still be alive (double-tap / callback race) —
        // tear it down synchronously first so overlays and pipelines never double.
        if (sessionJob?.isActive == true || eventsJob?.isActive == true || overlay != null) {
            stopEverything(AppStrings.get(R.string.status_restarting))
        }
        stopping = false
        reconnectAttempts = 0
        SessionBus.setStatus(SessionBus.Status.Starting, AppStrings.get(R.string.status_starting))
        SessionBus.clearExport()
        accumulatedInput.clear()
        accumulatedOutput.clear()
        fullInput.clear()
        fullOutput.clear()
        captureStarted = false
        lastPreviewAtMs = 0L
        pendingPreviewInput = null
        pendingPreviewOutput = null
        previewFlushJob?.cancel()
        previewFlushJob = null
        sameLanguageMode.reset()
        startAsForeground()

        val app = application as LiveTranslateApp
        if (!app.apiKeyStore.hasApiKey()) {
            SessionBus.setStatus(SessionBus.Status.Error, AppStrings.get(R.string.msg_need_api_key))
            stopSelf()
            return
        }

        sessionJob = scope.launch {
            try {
                startSessionInternal(app, resultCode, data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. SecurityException from getMediaProjection, BadTokenException
                // from addView when overlay permission was revoked mid-session.
                Log.e(TAG, "session start failed", e)
                stopEverything(e.message ?: AppStrings.get(R.string.error_session_start_failed), error = true)
            }
        }
    }

    private suspend fun startSessionInternal(
        app: LiveTranslateApp,
        resultCode: Int?,
        data: Intent?,
    ) {
        currentSettings = app.settingsRepository.settings.first()
        currentCoroutineContext().ensureActive()
        // audioSourceMode already set from Intent EXTRA in onStartCommand.

            if (audioSourceMode.needsMediaProjection) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode!!, data!!)
                if (projection == null) {
                    SessionBus.setStatus(SessionBus.Status.Error, AppStrings.get(R.string.error_no_projection))
                    stopEverything(AppStrings.get(R.string.error_no_projection), error = true)
                    return
                }
                mediaProjection = projection
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopEverything(AppStrings.get(R.string.error_projection_revoked))
                        }
                    },
                    null,
                )
            }

            val overlayController = SubtitleOverlayController(
                this@SubtitleSessionService,
                onGeometryChanged = { x, y, w, h ->
                    ioScope.launch {
                        app.settingsRepository.update {
                            it.copy(overlayX = x, overlayY = y, overlayWidthDp = w, overlayHeightDp = h)
                        }
                    }
                },
                // The overlay is the caption surface; closing it ends the session
                // (transcripts are still kept for export via markSessionFinished).
                onCloseRequested = { stopEverything(AppStrings.get(R.string.status_closed_by_user)) },
            )
            overlay = overlayController
            overlayController.show(currentSettings)

            val client = LiveTranslateClient()
            liveClient = client

            val player = TranslatedAudioPlayer()
            audioPlayer = player
            applyTranslatedAudioEnabled()
            player.setVolume(currentSettings.translatedVolume)

            eventsJob = scope.launch {
                launch {
                    client.connectionState.collect { state ->
                        when (state) {
                            is LiveTranslateClient.ConnectionState.Ready -> {
                                reconnectAttempts = 0
                                SessionBus.setStatus(SessionBus.Status.Running, AppStrings.get(R.string.status_translating))
                                startCapturePipeline(client)
                            }
                            is LiveTranslateClient.ConnectionState.Failed -> {
                                handleConnectionLoss(state.message)
                            }
                            is LiveTranslateClient.ConnectionState.Closed -> {
                                handleConnectionLoss(AppStrings.get(R.string.error_disconnected))
                            }
                            else -> Unit
                        }
                    }
                }
                launch {
                    // Audio on its own droppable channel; transcripts on
                    // client.events are never dropped (SUSPEND overflow).
                    client.audioChunks.collect { event ->
                        if (sameLanguageMode.shouldPlayTranslatedAudio(currentSettings.playTranslatedAudio)) {
                            player.playPcm(event.pcm, event.mimeType)
                        }
                    }
                }
                client.events.collect { event ->
                    when (event) {
                        is LiveTranslateClient.LiveEvent.SetupComplete -> {
                            reconnectAttempts = 0
                            SessionBus.setStatus(SessionBus.Status.Running, AppStrings.get(R.string.status_translating))
                            startCapturePipeline(client)
                        }
                        is LiveTranslateClient.LiveEvent.InputTranscript -> {
                            appendTranscript(accumulatedInput, event.text)
                            appendFull(fullInput, event.text)
                            val target = currentSettings.targetLanguageCode
                            val toggled = if (event.languageCode.isNullOrBlank()) {
                                sameLanguageMode.onInputTextFallback(
                                    accumulatedInput.toString(),
                                    target,
                                    outputEmpty = accumulatedOutput.isEmpty(),
                                )
                            } else {
                                sameLanguageMode.onDetectedInputLanguage(
                                    event.languageCode,
                                    target,
                                )
                            }
                            if (toggled != null) {
                                applyCaptionMode(toggled)
                            } else {
                                publishCaptionUpdate(inputChanged = true, outputChanged = false)
                            }
                        }
                        is LiveTranslateClient.LiveEvent.OutputTranscript -> {
                            appendTranscript(accumulatedOutput, event.text)
                            appendFull(fullOutput, event.text)
                            publishCaptionUpdate(inputChanged = false, outputChanged = true)
                        }
                        is LiveTranslateClient.LiveEvent.AudioChunk -> Unit
                        is LiveTranslateClient.LiveEvent.GoAway -> {
                            // Connection is about to be terminated by the server
                            // (~10-minute cap) — resume on a fresh connection.
                            scheduleReconnect(AppStrings.get(R.string.status_server_goaway))
                        }
                        is LiveTranslateClient.LiveEvent.Error -> {
                            handleConnectionLoss(event.message)
                        }
                        is LiveTranslateClient.LiveEvent.Debug -> {
                            Log.d(TAG, event.message)
                        }
                    }
                }
            }

            settingsJob = scope.launch {
                app.settingsRepository.settings.collectLatest { s ->
                    val prev = currentSettings
                    currentSettings = s
                    val appearanceOrAudioChanged =
                        prev.fontSizeSp != s.fontSizeSp ||
                            prev.backgroundAlpha != s.backgroundAlpha ||
                            prev.bilingual != s.bilingual ||
                            prev.playTranslatedAudio != s.playTranslatedAudio ||
                            prev.translatedVolume != s.translatedVolume
                    if (!appearanceOrAudioChanged) return@collectLatest
                    overlay?.updateSettings(s)
                    applyTranslatedAudioEnabled()
                    player.setVolume(s.translatedVolume)
                    if (prev.playTranslatedAudio && !s.playTranslatedAudio) {
                        Log.i(TAG, "translated audio disabled")
                    }
                }
            }

            yield()
            currentCoroutineContext().ensureActive()
            // Rotate keys: try first available via round-robin start index
            val key = app.apiKeyStore.nextRotatedKey()
            if (key.isBlank()) {
                SessionBus.setStatus(SessionBus.Status.Error, AppStrings.get(R.string.msg_need_api_key))
                stopEverything(AppStrings.get(R.string.msg_need_api_key), error = true)
                return
            }
            client.connect(
                LiveTranslateClient.SessionConfig(
                    endpoint = currentSettings.endpoint,
                    apiKey = key,
                    modelId = currentSettings.modelId,
                    targetLanguageCode = SupportedLanguages.canonicalOrDefault(
                        currentSettings.targetLanguageCode,
                    ),
                    // Official: when input is already the target language, echo
                    // instead of staying silent so same-language audio still
                    // produces captions (e.g. Chinese video → Chinese subtitles).
                    echoTargetLanguage = true,
                ),
            )
    }

    /**
     * Connection died or the server announced it will die. If the session is
     * resumable and retries remain, reconnect transparently; otherwise stop.
     */
    private fun handleConnectionLoss(message: String) {
        if (stopping) return
        val client = liveClient
        if (client == null || client.lastResumptionHandle == null ||
            reconnectAttempts >= MAX_RECONNECTS
        ) {
            stopEverything(message, error = true)
            return
        }
        scheduleReconnect(message)
    }

    /**
     * Reconnect with the last resumption handle. The capture pipeline keeps
     * running — sendPcm16le silently drops frames until setupComplete, so no
     * special buffering is needed for a short reconnect window.
     */
    private fun scheduleReconnect(reason: String) {
        if (stopping) return
        // Failed state and Error event can both report the same loss —
        // dedupe while a reconnect is already scheduled.
        if (reconnectJob?.isActive == true) return
        val client = liveClient ?: return
        val handle = client.lastResumptionHandle ?: run {
            stopEverything(reason, error = true)
            return
        }
        reconnectAttempts++
        val attempt = reconnectAttempts
        Log.w(TAG, "reconnect #$attempt after: $reason")
        SessionBus.setStatus(
            SessionBus.Status.Starting,
            AppStrings.get(R.string.status_reconnecting, reason, attempt, MAX_RECONNECTS),
        )
        reconnectJob = scope.launch {
            delay(RECONNECT_BASE_DELAY_MS * attempt)
            if (stopping) return@launch
            val live = liveClient ?: return@launch
            val app = application as LiveTranslateApp
            val key = app.apiKeyStore.nextRotatedKey()
            if (key.isBlank()) {
                stopEverything(AppStrings.get(R.string.msg_need_api_key), error = true)
                return@launch
            }
            live.connect(
                LiveTranslateClient.SessionConfig(
                    endpoint = currentSettings.endpoint,
                    apiKey = key,
                    modelId = currentSettings.modelId,
                    targetLanguageCode = SupportedLanguages.canonicalOrDefault(
                        currentSettings.targetLanguageCode,
                    ),
                    echoTargetLanguage = true,
                    resumptionHandle = handle,
                ),
            )
        }
    }

    private fun startCapturePipeline(client: LiveTranslateClient) {
        if (captureStarted) return
        captureStarted = true
        // Capture threads die on IO — marshal the teardown back to main.
        val onCaptureError: (String) -> Unit = { msg ->
            scope.launch { stopEverything(msg, error = true) }
        }
        try {
            when (audioSourceMode) {
                AudioSourceMode.MEDIA -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException(AppStrings.get(R.string.error_missing_projection))
                    val cap = SystemAudioCapturer(ioScope)
                    mediaCapturer = cap
                    cap.start(projection, { pcm -> client.sendPcm16le(pcm, 16_000) }, onCaptureError)
                }
                AudioSourceMode.MIC -> {
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start({ pcm -> client.sendPcm16le(pcm, 16_000) }, onCaptureError)
                }
                AudioSourceMode.MEDIA_AND_MIC -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException(AppStrings.get(R.string.error_missing_projection))
                    val mixer = PcmMixer { mixed -> client.sendPcm16le(mixed, 16_000) }
                    pcmMixer = mixer
                    val media = SystemAudioCapturer(ioScope)
                    mediaCapturer = media
                    media.start(projection, { pcm -> mixer.offerMedia(pcm) }, onCaptureError)
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start({ pcm -> mixer.offerMic(pcm) }, onCaptureError)
                }
            }
            SessionBus.setStatus(SessionBus.Status.Running, AppStrings.get(R.string.status_waiting_audio))
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed", e)
            SessionBus.setStatus(SessionBus.Status.Error, e.message ?: AppStrings.get(R.string.error_capture_start_failed))
            stopEverything(e.message ?: AppStrings.get(R.string.error_capture_start_failed))
        }
    }

    /**
     * Collapse or restore the overlay when source language equals the target.
     * When collapsed, the input transcript is shown as the single caption line
     * and echoed translation audio is muted so it does not double the source.
     */
    private fun applyCaptionMode(enabled: Boolean) {
        overlay?.setSameLanguageMode(enabled)
        applyTranslatedAudioEnabled()
        publishCaptionUpdate(inputChanged = true, outputChanged = true)
    }

    /**
     * Play translation audio only when the user asked for it *and* the source
     * is a different language. Same-language sessions still request echo from
     * the API (needed for captions) but drop the parroted PCM locally.
     */
    private fun applyTranslatedAudioEnabled() {
        audioPlayer?.setEnabled(
            sameLanguageMode.shouldPlayTranslatedAudio(currentSettings.playTranslatedAudio),
        )
    }

    private fun publishCaptionUpdate(inputChanged: Boolean, outputChanged: Boolean) {
        val inDisplay = TranscriptLineBreaker.format(accumulatedInput.toString())
        val outDisplay = TranscriptLineBreaker.format(accumulatedOutput.toString())
        if (sameLanguageMode.enabled) {
            val line = inDisplay.ifBlank { outDisplay }
            overlay?.updateTranscripts(input = "", output = line)
            publishPreview(input = "", output = line)
        } else {
            overlay?.updateTranscripts(
                input = if (inputChanged) inDisplay else null,
                output = if (outputChanged) outDisplay else null,
            )
            publishPreview(
                input = if (inputChanged) inDisplay else null,
                output = if (outputChanged) outDisplay else null,
            )
        }
    }

    private fun appendTranscript(buffer: StringBuilder, chunk: String) {
        TranscriptBuffer.append(buffer, chunk, maxChars = 800)
    }

    private fun appendFull(buffer: StringBuilder, chunk: String) {
        TranscriptBuffer.append(buffer, chunk, maxChars = 200_000)
    }

    private fun publishPreview(input: String? = null, output: String? = null) {
        if (input != null) pendingPreviewInput = input
        if (output != null) pendingPreviewOutput = output
        val now = System.currentTimeMillis()
        val wait = PREVIEW_THROTTLE_MS - (now - lastPreviewAtMs)
        if (wait <= 0L) {
            flushPreview()
            return
        }
        if (previewFlushJob?.isActive == true) return
        previewFlushJob = scope.launch {
            delay(wait)
            flushPreview()
        }
    }

    private fun flushPreview() {
        previewFlushJob?.cancel()
        previewFlushJob = null
        if (pendingPreviewInput == null && pendingPreviewOutput == null) return
        lastPreviewAtMs = System.currentTimeMillis()
        SessionBus.setPreview(input = pendingPreviewInput, output = pendingPreviewOutput)
        pendingPreviewInput = null
        pendingPreviewOutput = null
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

    /** Used when the process is restarted with a null intent so we still hit startForeground. */
    private fun ensureForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything(message: String, error: Boolean = false) {
        if (stopping) return
        stopping = true
        sessionJob?.cancel()
        sessionJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        flushPreview()
        val inFull = fullInput.toString()
        val rawOut = fullOutput.toString()
        val outFull = when {
            rawOut.isNotBlank() -> rawOut
            sameLanguageMode.enabled -> inFull
            else -> rawOut
        }
        if (inFull.isNotBlank() || outFull.isNotBlank()) {
            SessionBus.markSessionFinished(inFull, outFull, message)
        }
        if (error) {
            SessionBus.setStatus(SessionBus.Status.Error, message)
        } else if (inFull.isBlank() && outFull.isBlank()) {
            SessionBus.setStatus(SessionBus.Status.Stopped, message)
        }
        mediaCapturer?.stop()
        mediaCapturer = null
        micCapturer?.stop()
        micCapturer = null
        pcmMixer?.close()
        pcmMixer = null
        liveClient?.close()
        liveClient?.destroy()
        liveClient = null
        audioPlayer?.release()
        audioPlayer = null
        overlay?.hide()
        overlay = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        settingsJob?.cancel()
        eventsJob?.cancel()
        captureStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mediaCapturer?.stop()
        micCapturer?.stop()
        pcmMixer?.close()
        liveClient?.destroy()
        audioPlayer?.release()
        overlay?.hide()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        scope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SubtitleSessionService"
        private const val NOTIFICATION_ID = 42
        private const val PREVIEW_THROTTLE_MS = 200L
        private const val MAX_RECONNECTS = 6
        private const val RECONNECT_BASE_DELAY_MS = 1_500L
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
