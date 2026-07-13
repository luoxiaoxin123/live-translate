package com.livetranslate.app.live

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Minimal Gemini Live Translate WebSocket client.
 * Protocol: https://ai.google.dev/gemini-api/docs/live-api/live-translate
 */
class LiveTranslateClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null

    private val setupComplete = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(
        extraBufferCapacity = 64,
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
    }

    fun connect(config: SessionConfig) {
        close()
        intentionalClose.set(false)
        setupComplete.set(false)
        _connectionState.value = ConnectionState.Connecting

        val url = buildUrl(config.endpoint, config.apiKey)
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket open")
                    val setup = buildSetupMessage(config)
                    webSocket.send(setup)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (intentionalClose.get()) return
                    val msg = t.message ?: "WebSocket failure"
                    Log.e(TAG, "WebSocket failure", t)
                    _connectionState.value = ConnectionState.Failed(msg)
                    scope.launch { _events.emit(LiveEvent.Error(msg)) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code $reason")
                    if (!intentionalClose.get()) {
                        _connectionState.value = ConnectionState.Closed
                    } else {
                        _connectionState.value = ConnectionState.Idle
                    }
                }
            },
        )
    }

    /**
     * Lightweight connectivity check used by Settings "Test connection".
     * Connects, waits for setupComplete (or error), then closes.
     */
    suspend fun testConnection(config: SessionConfig, timeoutMs: Long = 12_000): Result<String> {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun complete(result: Result<String>) {
                if (done.compareAndSet(false, true)) {
                    close()
                    cont.resume(result) {}
                }
            }

            val job = scope.launch {
                events.collect { event ->
                    when (event) {
                        is LiveEvent.SetupComplete -> complete(Result.success("连接成功，会话已建立"))
                        is LiveEvent.Error -> complete(Result.failure(Exception(event.message)))
                        else -> Unit
                    }
                }
            }

            connect(config)
            scope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                complete(Result.failure(Exception("连接超时（${timeoutMs}ms）")))
            }

            cont.invokeOnCancellation {
                job.cancel()
                close()
            }
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
        intentionalClose.set(true)
        setupComplete.set(false)
        receiveJob?.cancel()
        webSocket?.close(1000, "client close")
        webSocket = null
        if (_connectionState.value !is ConnectionState.Failed) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun destroy() {
        close()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun handleMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (root.has("setupComplete") || root.has("setup_complete")) {
                setupComplete.set(true)
                _connectionState.value = ConnectionState.Ready
                scope.launch { _events.emit(LiveEvent.SetupComplete) }
                return
            }

            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val message = err?.optString("message") ?: root.toString()
                _connectionState.value = ConnectionState.Failed(message)
                scope.launch { _events.emit(LiveEvent.Error(message)) }
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
                                languageCode = input.optString("languageCode", null)
                                    ?: input.optString("language_code", null),
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
                                languageCode = output.optString("languageCode", null)
                                    ?: output.optString("language_code", null),
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
                    val mime = inline.optString("mimeType", null)
                        ?: inline.optString("mime_type", null)
                    val bytes = Base64.decode(dataB64, Base64.DEFAULT)
                    scope.launch { _events.emit(LiveEvent.AudioChunk(bytes, mime)) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
        }
    }

    private fun buildSetupMessage(config: SessionConfig): String {
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put(
                "translationConfig",
                JSONObject()
                    .put("targetLanguageCode", config.targetLanguageCode)
                    .put("echoTargetLanguage", config.echoTargetLanguage),
            )

        // Some gateways expect snake_case — send camelCase per official JS docs.
        val setup = JSONObject()
            .put("model", "models/${config.modelId.removePrefix("models/")}")
            .put("generationConfig", generationConfig)

        return JSONObject().put("setup", setup).toString()
    }

    private fun buildUrl(endpoint: String, apiKey: String): String {
        val base = endpoint.trim()
        val separator = if (base.contains("?")) "&" else "?"
        return if (base.contains("key=")) base else "$base${separator}key=$apiKey"
    }

    companion object {
        private const val TAG = "LiveTranslateClient"
    }
}
