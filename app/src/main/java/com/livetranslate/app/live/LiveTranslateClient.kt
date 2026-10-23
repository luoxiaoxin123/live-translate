package com.livetranslate.app.live

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import com.livetranslate.app.R
import com.livetranslate.app.util.AppStrings
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live Translate WebSocket client.
 *
 * Wire format aligned with:
 * - https://ai.google.dev/gemini-api/docs/live-api/live-translate
 * - google-genai Python SDK `_LiveConnectParameters_to_mldev` converter
 *
 * Live Translation has no source-language field (unlike Live Agent). Only
 * `targetLanguageCode` and `echoTargetLanguage` are sent. Input language is
 * auto-detected; when it already matches the target, `echoTargetLanguage=true`
 * parrots the original instead of staying silent.
 *
 * Correct setup shape (AI Studio / mldev):
 * ```
 * {
 *   "setup": {
 *     "model": "models/gemini-3.5-live-translate-preview",
 *     "generationConfig": {
 *       "responseModalities": ["AUDIO"],
 *       "translationConfig": {
 *         "targetLanguageCode": "zh-Hans",
 *         "echoTargetLanguage": true
 *       }
 *     },
 *     "inputAudioTranscription": {},
 *     "outputAudioTranscription": {}
 *   }
 * }
 * ```
 */
class LiveTranslateClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Gate for live audio: true only while the current connection can take it. */
    private val setupComplete = AtomicBoolean(false)
    /** The current connection reached setupComplete (stays true once it closes). */
    private val connectionWasReady = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)

    /**
     * Audio captured while no connection is ready (rotation / reconnect),
     * replayed after the next setupComplete. Guarded by [pendingLock], which
     * also orders the replay ahead of live chunks.
     */
    private val pendingLock = Any()
    private val pendingAudio = PendingAudioBuffer(
        maxBytes = MAX_PENDING_AUDIO_MS * BYTES_PER_MS_16K,
        maxFrameBytes = REPLAY_FRAME_MS * BYTES_PER_MS_16K,
    )

    /**
     * Latest session-resumption handle from the server. Non-null once the
     * session is resumable; pass it as [SessionConfig.resumptionHandle] on
     * reconnect to continue the same session on a new connection.
     */
    @Volatile
    var lastResumptionHandle: String? = null
        private set

    /** Drop a handle the server rejected so the next connect starts a new session. */
    fun forgetResumptionHandle() {
        lastResumptionHandle = null
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Text/control events (transcripts, errors, goAway). SUSPEND overflow means
     * a slow collector delays the producer instead of dropping transcript text.
     */
    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    /**
     * High-rate audio chunks on their own flow. Dropping these under pressure
     * only blips the translated voice; transcript text on [events] is never lost.
     */
    private val _audioChunks = MutableSharedFlow<LiveEvent.AudioChunk>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioChunks: SharedFlow<LiveEvent.AudioChunk> = _audioChunks.asSharedFlow()

    data class SessionConfig(
        val endpoint: String,
        val apiKey: String,
        val modelId: String,
        val targetLanguageCode: String,
        val echoTargetLanguage: Boolean = true,
        /** Server-issued handle; non-null reconnects into the previous session. */
        val resumptionHandle: String? = null,
    )

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Ready : ConnectionState()
        data class Failed(val message: String) : ConnectionState()
        data object Closed : ConnectionState()
    }

    sealed class LiveEvent {
        data class InputTranscript(val text: String, val languageCode: String? = null) : LiveEvent()
        data class OutputTranscript(val text: String, val languageCode: String? = null) : LiveEvent()
        data class AudioChunk(val pcm: ByteArray, val mimeType: String?) : LiveEvent() {
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = pcm.contentHashCode()
        }
        data class Error(val message: String) : LiveEvent()
        data object SetupComplete : LiveEvent()
        data class Debug(val message: String) : LiveEvent()

        /** Server will terminate this connection soon; reconnect to continue. */
        data class GoAway(val timeLeftMs: Long?) : LiveEvent()
    }

    fun connect(config: SessionConfig) {
        closeInternal(intentional = true, notify = false)
        intentionalClose.set(false)
        setupComplete.set(false)
        connectionWasReady.set(false)
        _connectionState.value = ConnectionState.Connecting

        val key = config.apiKey.trim()
        if (key.isBlank()) {
            fail(AppStrings.get(R.string.client_error_api_key_empty))
            return
        }
        if (config.endpoint.trim().isBlank()) {
            fail(AppStrings.get(R.string.client_error_endpoint_empty))
            return
        }

        val url = buildUrl(config.endpoint, key)
        Log.i(TAG, "Connecting (key redacted): ${redactUrl(url)}")
        emitDebug("连接中… ${redactUrl(url)}")

        // Do NOT set Content-Type on the WS handshake — some stacks mishandle it.
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                /** A reconnect replaces the socket; ignore stale callbacks. */
                private fun isStale(ws: WebSocket): Boolean = ws !== this@LiveTranslateClient.webSocket

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (isStale(webSocket)) return
                    Log.i(TAG, "WebSocket open code=${response.code}")
                    emitDebug("WebSocket 已打开，发送 setup…")
                    val setup = buildSetupMessage(config)
                    Log.i(TAG, "setup payload: $setup")
                    val ok = webSocket.send(setup)
                    if (!ok) {
                        fail(AppStrings.get(R.string.client_error_send_setup))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isStale(webSocket)) return
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (isStale(webSocket)) return
                    // Server may send binary JSON frames.
                    handleMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isStale(webSocket)) return
                    if (intentionalClose.get()) return
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    val code = response?.code
                    val msg = buildString {
                        append(t.message ?: t.javaClass.simpleName)
                        if (code != null) append(" (HTTP $code)")
                        if (!body.isNullOrBlank()) append(" · $body")
                    }
                    Log.e(TAG, "WebSocket failure: $msg", t)
                    fail(msg)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (isStale(webSocket)) return
                    Log.i(TAG, "WebSocket closing: $code $reason")
                    // Start buffering audio for the next connection.
                    setupComplete.set(false)
                    if (!intentionalClose.get() && !connectionWasReady.get()) {
                        fail(
                            AppStrings.get(
                                R.string.client_error_closed_before_setup,
                                code,
                                reason.ifBlank { "(no reason)" },
                            ),
                        )
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isStale(webSocket)) return
                    Log.i(TAG, "WebSocket closed: $code $reason")
                    if (intentionalClose.get()) {
                        _connectionState.value = ConnectionState.Idle
                        return
                    }
                    if (!connectionWasReady.get() && _connectionState.value !is ConnectionState.Failed) {
                        fail(
                            AppStrings.get(
                                R.string.client_error_closed,
                                code,
                                reason.ifBlank { "(no reason)" },
                            ),
                        )
                    } else if (_connectionState.value !is ConnectionState.Failed) {
                        _connectionState.value = ConnectionState.Closed
                    }
                }
            },
        )
    }

    /**
     * Settings "连接测试".
     *
     * Important: do NOT wait only on SharedFlow events — setupComplete can arrive
     * before a collector is registered (replay=0) and get lost, showing a false
     * "stuck in Connecting". Poll [connectionState] which is always up to date.
     */
    suspend fun testConnection(config: SessionConfig, timeoutMs: Long = 25_000): Result<String> {
        connect(config)
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                when (val state = _connectionState.value) {
                    is ConnectionState.Ready ->
                        return Result.success(AppStrings.get(R.string.test_ok_setup))
                    is ConnectionState.Failed ->
                        return Result.failure(Exception(state.message))
                    is ConnectionState.Closed ->
                        return Result.failure(Exception(AppStrings.get(R.string.test_closed_no_setup)))
                    else -> delay(40)
                }
            }
            val state = _connectionState.value
            val detail = when (state) {
                is ConnectionState.Failed -> state.message
                is ConnectionState.Connecting ->
                    AppStrings.get(R.string.test_connecting_hint)
                is ConnectionState.Ready -> AppStrings.get(R.string.test_ok)
                else -> AppStrings.get(R.string.test_timeout, timeoutMs, state)
            }
            if (state is ConnectionState.Ready) {
                Result.success(detail)
            } else {
                Result.failure(Exception(detail))
            }
        } finally {
            close()
        }
    }

    /**
     * Streams one PCM chunk. While no connection is ready (between connect and
     * setupComplete, or after a drop) the chunk is buffered instead and
     * replayed once the next connection completes setup.
     */
    fun sendPcm16le(chunk: ByteArray, sampleRate: Int = 16_000) {
        if (chunk.isEmpty()) return
        synchronized(pendingLock) {
            val ws = webSocket
            if (ws == null || !setupComplete.get()) {
                pendingAudio.add(chunk)
                return
            }
            sendAudio(ws, chunk, sampleRate)
        }
    }

    private fun sendAudio(ws: WebSocket, chunk: ByteArray, sampleRate: Int) {
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val msg = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", b64)
                        .put("mimeType", "audio/pcm;rate=$sampleRate"),
                ),
            )
            .toString()
        val ok = ws.send(msg)
        if (!ok) {
            Log.w(TAG, "sendPcm dropped (socket closed or send queue full)")
        }
    }

    fun close() {
        closeInternal(intentional = true, notify = true)
        synchronized(pendingLock) { pendingAudio.clear() }
    }

    fun destroy() {
        close()
        scope.cancel()
        // Do not shutdown shared client dispatcher aggressively if reused; create per-instance is fine.
        runCatching {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun closeInternal(intentional: Boolean, notify: Boolean) {
        intentionalClose.set(intentional)
        setupComplete.set(false)
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            runCatching { ws.close(1000, "client close") }
            runCatching { ws.cancel() }
        }
        if (notify && _connectionState.value !is ConnectionState.Failed) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    private fun fail(message: String) {
        setupComplete.set(false)
        _connectionState.value = ConnectionState.Failed(message)
        emitEvent(LiveEvent.Error(message))
    }

    private fun emitDebug(message: String) {
        emitEvent(LiveEvent.Debug(message))
    }

    private fun emitEvent(event: LiveEvent) {
        if (event is LiveEvent.AudioChunk) {
            if (!_audioChunks.tryEmit(event)) {
                scope.launch { _audioChunks.emit(event) }
            }
            return
        }
        // With SUSPEND overflow a failed tryEmit means the buffer is full —
        // suspend in the IO scope until there's room; never drop text events.
        if (!_events.tryEmit(event)) {
            scope.launch { _events.emit(event) }
        }
    }

    private fun handleMessage(text: String) {
        if (text.isBlank()) return
        Log.d(TAG, "← ${text.take(500)}")
        try {
            val root = JSONObject(text)

            // setupComplete may be empty object: { "setupComplete": {} }
            if (root.has("setupComplete") || root.has("setup_complete")) {
                completeSetup()
                // State first (sync) so pollers never miss Ready even if event is dropped.
                _connectionState.value = ConnectionState.Ready
                // tryEmit is non-suspending; fall back to launch if buffer full
                if (!_events.tryEmit(LiveEvent.SetupComplete)) {
                    scope.launch { _events.emit(LiveEvent.SetupComplete) }
                }
                return
            }

            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val message = buildString {
                    if (err != null) {
                        val code = err.opt("code")
                        val status = err.optString("status")
                        val msg = err.optString("message")
                        if (code != null) append("[$code] ")
                        if (status.isNotBlank()) append("$status: ")
                        append(msg.ifBlank { err.toString() })
                    } else {
                        append(root.toString())
                    }
                }
                fail(message)
                return
            }

            // Top-level session-management messages arrive without serverContent —
            // they must be handled before the serverContent branch below returns.
            val goAway = root.optJSONObject("goAway") ?: root.optJSONObject("go_away")
            if (goAway != null) {
                val timeLeftMs = parseDurationMs(goAway.optString("timeLeft").ifBlank { goAway.optString("time_left") })
                Log.i(TAG, "goAway: timeLeft=$timeLeftMs ms")
                emitEvent(LiveEvent.GoAway(timeLeftMs))
            }

            val resumption = root.optJSONObject("sessionResumptionUpdate")
                ?: root.optJSONObject("session_resumption_update")
            if (resumption != null) {
                val resumable = resumption.optBoolean("resumable", false)
                val newHandle = resumption.optStringOrNull("newHandle")
                    ?: resumption.optStringOrNull("new_handle")
                if (resumable && !newHandle.isNullOrBlank()) {
                    lastResumptionHandle = newHandle
                    emitDebug("会话可续接（handle 已更新）")
                }
            }

            val serverContent = root.optJSONObject("serverContent")
                ?: root.optJSONObject("server_content")
                ?: return

            val input = serverContent.optJSONObject("inputTranscription")
                ?: serverContent.optJSONObject("input_transcription")
            if (input != null) {
                val t = input.optString("text")
                if (t.isNotBlank()) {
                    emitEvent(
                        LiveEvent.InputTranscript(
                            text = t,
                            languageCode = input.optStringOrNull("languageCode")
                                ?: input.optStringOrNull("language_code"),
                        ),
                    )
                }
            }

            val output = serverContent.optJSONObject("outputTranscription")
                ?: serverContent.optJSONObject("output_transcription")
            if (output != null) {
                val t = output.optString("text")
                if (t.isNotBlank()) {
                    emitEvent(
                        LiveEvent.OutputTranscript(
                            text = t,
                            languageCode = output.optStringOrNull("languageCode")
                                ?: output.optStringOrNull("language_code"),
                        ),
                    )
                }
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
                ?: serverContent.optJSONObject("model_turn")
            val parts: JSONArray? = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData")
                        ?: part.optJSONObject("inline_data")
                        ?: continue
                    val dataB64 = inline.optString("data")
                    if (dataB64.isBlank()) continue
                    val mime = inline.optStringOrNull("mimeType")
                        ?: inline.optStringOrNull("mime_type")
                    val bytes = Base64.decode(dataB64, Base64.DEFAULT)
                    emitEvent(LiveEvent.AudioChunk(bytes, mime))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
            emitDebug("解析消息失败: ${e.message}")
        }
    }

    /** Replay audio buffered between connections, then let live audio through. */
    private fun completeSetup() {
        synchronized(pendingLock) {
            val ws = webSocket
            if (ws != null && pendingAudio.sizeBytes > 0) {
                Log.i(
                    TAG,
                    "replaying ${pendingAudio.sizeBytes / BYTES_PER_MS_16K} ms of buffered audio" +
                        " (dropped ${pendingAudio.droppedBytes / BYTES_PER_MS_16K} ms)",
                )
                pendingAudio.drain().forEach { sendAudio(ws, it, 16_000) }
            }
            setupComplete.set(true)
            connectionWasReady.set(true)
        }
    }

    /**
     * Build setup message matching google-genai mldev converter:
     * - responseModalities + translationConfig → generationConfig
     * - input/output transcription → setup top-level
     * - sessionResumption + contextWindowCompression (per session-management docs):
     *   sliding-window compression lifts the 15-minute session cap, and the
     *   resumption handle lets one session survive the ~10-minute connection cap.
     */
    private fun buildSetupMessage(config: SessionConfig): String {
        val model = config.modelId.trim().removePrefix("models/")
        val target = config.targetLanguageCode.trim().ifBlank { "zh-Hans" }

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "translationConfig",
                JSONObject()
                    .put("targetLanguageCode", target)
                    .put("echoTargetLanguage", config.echoTargetLanguage),
            )

        val resumption = config.resumptionHandle?.let { handle ->
            JSONObject().put("handle", handle)
        } ?: JSONObject()

        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", resumption)
            .put(
                "contextWindowCompression",
                JSONObject().put("slidingWindow", JSONObject()),
            )

        return JSONObject().put("setup", setup).toString()
    }

    /** protobuf JSON duration (e.g. "59.5s", "60s") → milliseconds, null if absent. */
    private fun parseDurationMs(value: String): Long? {
        if (value.isBlank()) return null
        val trimmed = value.trim().trimEnd('s')
        return trimmed.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    private fun buildUrl(endpoint: String, apiKey: String): String {
        var base = endpoint.trim()
        // Strip trailing spaces / accidental quotes from paste
        base = base.trim('"', '\'')
        // If user pasted a full URL that already has key=, leave it (but prefer our key)
        if (base.contains("key=")) {
            // Replace existing key
            return base.replace(Regex("""([?&]key=)[^&]*"""), "$1$apiKey")
        }
        val separator = if (base.contains("?")) "&" else "?"
        return "$base${separator}key=$apiKey"
    }

    private fun redactUrl(url: String): String =
        url.replace(Regex("""([?&]key=)[^&]+"""), "$1***")

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val v = optString(name, "")
        return v.ifBlank { null }
    }

    companion object {
        private const val TAG = "LiveTranslateClient"

        /** 16 kHz mono PCM16: 32 bytes per millisecond. */
        private const val BYTES_PER_MS_16K = 32
        private const val MAX_PENDING_AUDIO_MS = 10_000
        private const val REPLAY_FRAME_MS = 1_000

        /** Quota / rate-limit failures: retry on the next stored API key. */
        fun isQuotaError(message: String): Boolean =
            message.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("429")
    }
}
