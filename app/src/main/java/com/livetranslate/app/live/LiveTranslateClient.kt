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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live Translate WebSocket client.
 *
 * Wire format aligned with:
 * - https://ai.google.dev/gemini-api/docs/live-api/live-translate
 * - google-genai Python SDK `_LiveConnectParameters_to_mldev` converter
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

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val setupComplete = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    data class SessionConfig(
        val endpoint: String,
        val apiKey: String,
        val modelId: String,
        val targetLanguageCode: String,
        val echoTargetLanguage: Boolean = true,
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
    }

    fun connect(config: SessionConfig) {
        closeInternal(intentional = true, notify = false)
        intentionalClose.set(false)
        setupComplete.set(false)
        _connectionState.value = ConnectionState.Connecting

        val key = config.apiKey.trim()
        if (key.isBlank()) {
            fail("API Key 为空")
            return
        }
        if (config.endpoint.trim().isBlank()) {
            fail("端点为空")
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
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket open code=${response.code}")
                    emitDebug("WebSocket 已打开，发送 setup…")
                    val setup = buildSetupMessage(config)
                    Log.i(TAG, "setup payload: $setup")
                    val ok = webSocket.send(setup)
                    if (!ok) {
                        fail("发送 setup 失败（socket 未就绪）")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Server may send binary JSON frames.
                    handleMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
                    Log.i(TAG, "WebSocket closing: $code $reason")
                    if (!intentionalClose.get() && !setupComplete.get()) {
                        fail("连接在 setup 完成前关闭: $code ${reason.ifBlank { "(no reason)" }}")
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code $reason")
                    if (intentionalClose.get()) {
                        _connectionState.value = ConnectionState.Idle
                        return
                    }
                    if (!setupComplete.get() && _connectionState.value !is ConnectionState.Failed) {
                        fail("连接关闭: $code ${reason.ifBlank { "(no reason)" }}")
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
                        return Result.success("连接成功：setupComplete / Ready")
                    is ConnectionState.Failed ->
                        return Result.failure(Exception(state.message))
                    is ConnectionState.Closed ->
                        return Result.failure(Exception("连接已关闭，未完成 setup"))
                    else -> delay(40)
                }
            }
            val state = _connectionState.value
            val detail = when (state) {
                is ConnectionState.Failed -> state.message
                is ConnectionState.Connecting ->
                    "WebSocket 一直 Connecting：多半连不上 generativelanguage.googleapis.com（网络/代理/防火墙）。端点本身是正确的。"
                is ConnectionState.Ready -> "连接成功"
                else -> "超时 ${timeoutMs}ms，状态=$state"
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

    fun sendPcm16le(chunk: ByteArray, sampleRate: Int = 16_000) {
        val ws = webSocket ?: return
        if (!setupComplete.get()) return
        if (chunk.isEmpty()) return

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
        ws.send(msg)
    }

    fun close() {
        closeInternal(intentional = true, notify = true)
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
        _connectionState.value = ConnectionState.Failed(message)
        scope.launch { _events.emit(LiveEvent.Error(message)) }
    }

    private fun emitDebug(message: String) {
        scope.launch { _events.emit(LiveEvent.Debug(message)) }
    }

    private fun handleMessage(text: String) {
        if (text.isBlank()) return
        Log.d(TAG, "← ${text.take(500)}")
        try {
            val root = JSONObject(text)

            // setupComplete may be empty object: { "setupComplete": {} }
            if (root.has("setupComplete") || root.has("setup_complete")) {
                setupComplete.set(true)
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

            val serverContent = root.optJSONObject("serverContent")
                ?: root.optJSONObject("server_content")
                ?: return

            val input = serverContent.optJSONObject("inputTranscription")
                ?: serverContent.optJSONObject("input_transcription")
            if (input != null) {
                val t = input.optString("text")
                if (t.isNotBlank()) {
                    scope.launch {
                        _events.emit(
                            LiveEvent.InputTranscript(
                                text = t,
                                languageCode = input.optStringOrNull("languageCode")
                                    ?: input.optStringOrNull("language_code"),
                            ),
                        )
                    }
                }
            }

            val output = serverContent.optJSONObject("outputTranscription")
                ?: serverContent.optJSONObject("output_transcription")
            if (output != null) {
                val t = output.optString("text")
                if (t.isNotBlank()) {
                    scope.launch {
                        _events.emit(
                            LiveEvent.OutputTranscript(
                                text = t,
                                languageCode = output.optStringOrNull("languageCode")
                                    ?: output.optStringOrNull("language_code"),
                            ),
                        )
                    }
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
                    scope.launch { _events.emit(LiveEvent.AudioChunk(bytes, mime)) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
            emitDebug("解析消息失败: ${e.message}")
        }
    }

    /**
     * Build setup message matching google-genai mldev converter:
     * - responseModalities + translationConfig → generationConfig
     * - input/output transcription → setup top-level
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

        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        return JSONObject().put("setup", setup).toString()
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
    }
}
