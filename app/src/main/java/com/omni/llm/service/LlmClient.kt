package com.omni.llm.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.omni.llm.core.ChatTurn
import com.omni.llm.core.LlamaBridge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * UI-process side of the inference service.
 *
 * The important property: this class never crashes the app when `:llm` dies. Binder death flips
 * [connection] to [Connection.Lost], pending requests get an error, and the UI offers a reload.
 */
class LlmClient(private val context: Context) {

    sealed interface Connection {
        data object Disconnected : Connection
        data object Connecting : Connection
        data class Ready(val gpuAvailable: Boolean, val backendReport: String) : Connection
        /** The inference process died (OOM kill, native crash, user swipe-away). */
        data class Lost(val reason: String) : Connection
    }

    sealed interface LlmEvent {
        data class Token(val piece: String, val count: Int) : LlmEvent
        data class Done(
            val generatedTokens: Int,
            val promptTokens: Int,
            val evaluatedTokens: Int,
            val prefillMs: Long,
            val decodeMs: Long,
            val tokensPerSec: Double,
            val contextFull: Boolean,
            val cancelled: Boolean,
            val draftProposed: Int = 0,
            val draftAccepted: Int = 0,
        ) : LlmEvent
        data class Error(val message: String) : LlmEvent
    }

    private val _connection = MutableStateFlow<Connection>(Connection.Disconnected)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _warnings = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val warnings: SharedFlow<String> = _warnings.asSharedFlow()

    private val requestCounter = AtomicInteger(1)
    private var serviceMessenger: Messenger? = null
    private var binding = false

    /** requestId -> handler(what, bundle) */
    private val handlers = ConcurrentHashMap<Int, (Int, Bundle) -> Unit>()

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        onMessage(msg)
        true
    })

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                _connection.value = Connection.Disconnected
                return
            }
            serviceMessenger = Messenger(binder)
            binder.linkToDeath({
                Log.e(TAG, "inference process died")
                serviceMessenger = null
                binding = false
                failAll("The model process was stopped by the system. Reload the model.")
                _connection.value = Connection.Lost("Inference process died")
            }, 0)
            ping()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            binding = false
            failAll("The model process disconnected. Reload the model.")
            _connection.value = Connection.Lost("Service disconnected")
        }
    }

    // ---- Connection -------------------------------------------------------------------------

    suspend fun ensureConnected(timeoutMs: Long = 8_000): Boolean {
        if (_connection.value is Connection.Ready) return true
        bind()
        val ready = withTimeoutOrNull(timeoutMs) {
            _connection.first { it is Connection.Ready || it is Connection.Lost }
        }
        return ready is Connection.Ready
    }

    private fun bind() {
        if (binding || serviceMessenger != null) return
        binding = true
        _connection.value = Connection.Connecting
        val intent = Intent(context, LlmService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            binding = false
            _connection.value = Connection.Lost(t.message ?: "could not start the inference service")
        }
    }

    private fun ping() {
        val id = requestCounter.getAndIncrement()
        handlers[id] = { what, data ->
            if (what == LlmProtocol.R_PONG) {
                handlers.remove(id)
                _connection.value = Connection.Ready(
                    gpuAvailable = data.getBoolean("gpu", false),
                    backendReport = data.getString(LlmProtocol.K_JSON).orEmpty(),
                )
            }
        }
        send(LlmProtocol.MSG_PING, id, Bundle(), retries = 3)
    }

    fun disconnect() {
        runCatching { context.unbindService(serviceConnection) }
        serviceMessenger = null
        binding = false
        _connection.value = Connection.Disconnected
    }

    // ---- Load / unload ----------------------------------------------------------------------

    data class LoadResult(
        val description: String,
        val context: Int,
        val trainContext: Int,
        val parameters: Long,
        val layerCount: Int,
        val gpuLayers: Int,
        val threads: Int,
        val mmap: Boolean,
        val hasChatTemplate: Boolean,
        val speculative: Boolean = false,
        val draftLoaded: Boolean = false,
    )

    suspend fun load(
        modelFile: File,
        modelId: String,
        config: LlamaBridge.LoadConfig,
        kvBytesPerToken: Long,
        onProgress: (Float) -> Unit,
    ): Result<LoadResult> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Inference service is not available."))
        }
        val id = requestCounter.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            handlers[id] = handler@{ what, data ->
                when (what) {
                    LlmProtocol.R_LOAD_PROGRESS -> onProgress(data.getFloat(LlmProtocol.K_FRACTION, 0f))
                    LlmProtocol.R_LOADED -> {
                        handlers.remove(id)
                        val json = runCatching {
                            JSONObject(data.getString(LlmProtocol.K_JSON).orEmpty())
                        }.getOrDefault(JSONObject())
                        cont.resume(
                            Result.success(
                                LoadResult(
                                    description = json.optString("description"),
                                    context = json.optInt("context", config.nCtx),
                                    trainContext = json.optInt("trainContext"),
                                    parameters = json.optLong("parameters"),
                                    layerCount = json.optInt("layerCount"),
                                    gpuLayers = json.optInt("gpuLayers"),
                                    threads = json.optInt("threads"),
                                    mmap = json.optBoolean("mmap", true),
                                    hasChatTemplate = json.optBoolean("hasChatTemplate"),
                                    speculative = json.optBoolean("speculative"),
                                    draftLoaded = json.optBoolean("draftLoaded"),
                                )
                            )
                        )
                    }
                    LlmProtocol.R_ERROR -> {
                        handlers.remove(id)
                        cont.resume(Result.failure(IllegalStateException(data.getString(LlmProtocol.K_ERROR))))
                    }
                }
            }
            cont.invokeOnCancellation { handlers.remove(id) }
            val sent = send(LlmProtocol.MSG_LOAD, id, Bundle().apply {
                putString(LlmProtocol.K_MODEL_PATH, modelFile.absolutePath)
                putString(LlmProtocol.K_MODEL_ID, modelId)
                putInt(LlmProtocol.K_N_CTX, config.nCtx)
                putInt(LlmProtocol.K_N_BATCH, config.nBatch)
                putInt(LlmProtocol.K_N_UBATCH, config.nUbatch)
                putInt(LlmProtocol.K_N_THREADS, config.nThreads)
                putInt(LlmProtocol.K_GPU_LAYERS, config.gpuLayers)
                putBoolean(LlmProtocol.K_USE_MMAP, config.useMmap)
                putBoolean(LlmProtocol.K_USE_MLOCK, config.useMlock)
                putBoolean(LlmProtocol.K_FLASH_ATTN, config.flashAttention)
                putBoolean(LlmProtocol.K_Q8_KV, config.q8KvCache)
                config.draftModelPath?.let { putString(LlmProtocol.K_DRAFT_PATH, it) }
                putInt(LlmProtocol.K_DRAFT_GPU_LAYERS, config.draftGpuLayers)
                putInt(LlmProtocol.K_DRAFT_TOKENS, config.draftTokens)
                putLong("kvBytesPerToken", kvBytesPerToken)
            })
            if (!sent) {
                handlers.remove(id)
                cont.resume(Result.failure(IllegalStateException("Could not reach the inference process.")))
            }
        }
    }

    fun unload() {
        send(LlmProtocol.MSG_UNLOAD, requestCounter.getAndIncrement(), Bundle())
    }

    fun resetConversation() {
        send(LlmProtocol.MSG_RESET, requestCounter.getAndIncrement(), Bundle())
    }

    fun cancel() = send(LlmProtocol.MSG_CANCEL, requestCounter.getAndIncrement(), Bundle())

    /** Exact token count for this conversation, or -1 if the engine cannot answer. */
    suspend fun countTokens(messages: List<ChatTurn>): Int {
        if (!ensureConnected(2_000)) return -1
        val id = requestCounter.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            handlers[id] = handler@{ what, data ->
                if (what == LlmProtocol.R_TOKEN_COUNT) {
                    handlers.remove(id)
                    cont.resume(data.getInt(LlmProtocol.K_TOKEN_COUNT, -1))
                } else if (what == LlmProtocol.R_ERROR) {
                    handlers.remove(id)
                    cont.resume(-1)
                }
            }
            cont.invokeOnCancellation { handlers.remove(id) }
            val sent = send(LlmProtocol.MSG_COUNT_TOKENS, id, Bundle().apply {
                putStringArrayList(LlmProtocol.K_ROLES, ArrayList(messages.map { it.role }))
                putStringArrayList(LlmProtocol.K_CONTENTS, ArrayList(messages.map { it.content }))
            })
            if (!sent) {
                handlers.remove(id)
                cont.resume(-1)
            }
        }
    }

    /** Ask the engine to persist its KV state (system prompt + history) for the next launch. */
    fun saveKvState(file: java.io.File) {
        send(LlmProtocol.MSG_SAVE_KV, requestCounter.getAndIncrement(), Bundle().apply {
            putString(LlmProtocol.K_KV_PATH, file.absolutePath)
        })
    }

    // ---- Generation -------------------------------------------------------------------------

    fun generate(
        messages: List<ChatTurn>,
        sampling: LlamaBridge.SamplingConfig,
    ): Flow<LlmEvent> = callbackFlow {
        val id = requestCounter.getAndIncrement()
        handlers[id] = handler@{ what, data ->
            when (what) {
                LlmProtocol.R_TOKEN -> trySend(
                    LlmEvent.Token(
                        data.getString(LlmProtocol.K_PIECE).orEmpty(),
                        data.getInt(LlmProtocol.K_COUNT, 0),
                    )
                )
                LlmProtocol.R_DONE -> {
                    handlers.remove(id)
                    val json = runCatching { JSONObject(data.getString(LlmProtocol.K_JSON).orEmpty()) }
                        .getOrDefault(JSONObject())
                    trySend(
                        LlmEvent.Done(
                            generatedTokens = json.optInt("generatedTokens"),
                            promptTokens = json.optInt("promptTokens"),
                            evaluatedTokens = json.optInt("evaluatedTokens"),
                            prefillMs = json.optLong("prefillMs"),
                            decodeMs = json.optLong("decodeMs"),
                            tokensPerSec = json.optDouble("tokensPerSec"),
                            contextFull = json.optBoolean("contextFull"),
                            cancelled = data.getBoolean("cancelled") || json.optBoolean("cancelled"),
                            draftProposed = json.optInt("draftProposed"),
                            draftAccepted = json.optInt("draftAccepted"),
                        )
                    )
                    close()
                }
                LlmProtocol.R_ERROR -> {
                    handlers.remove(id)
                    trySend(LlmEvent.Error(data.getString(LlmProtocol.K_ERROR).orEmpty()))
                    close()
                }
            }
        }
        val sent = send(LlmProtocol.MSG_GENERATE, id, Bundle().apply {
            putStringArrayList(LlmProtocol.K_ROLES, ArrayList(messages.map { it.role }))
            putStringArrayList(LlmProtocol.K_CONTENTS, ArrayList(messages.map { it.content }))
            putInt(LlmProtocol.K_MAX_TOKENS, sampling.maxTokens)
            putFloat(LlmProtocol.K_TEMPERATURE, sampling.temperature)
            putFloat(LlmProtocol.K_TOP_P, sampling.topP)
            putInt(LlmProtocol.K_TOP_K, sampling.topK)
            putFloat(LlmProtocol.K_MIN_P, sampling.minP)
            putFloat(LlmProtocol.K_REPEAT_PENALTY, sampling.repeatPenalty)
            putInt(LlmProtocol.K_SEED, sampling.seed)
        })
        if (!sent) {
            trySend(LlmEvent.Error("Inference process is not connected."))
            close()
        }
        awaitClose {
            handlers.remove(id)
            // Explicit receiver: inside callbackFlow "cancel" would otherwise resolve to the
            // ProducerScope, not to our service method, and silently do nothing.
            this@LlmClient.cancel()
        }
    }

    // ---- Messaging --------------------------------------------------------------------------

    private fun send(what: Int, requestId: Int, data: Bundle, retries: Int = 1): Boolean {
        val target = serviceMessenger
        if (target == null) {
            if (retries > 0) {
                // A ping right after bind can race the binding; retry on the next main-loop turn.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (serviceMessenger == null) bind()
                    send(what, requestId, data, retries - 1)
                }, 200)
            }
            return false
        }
        val msg = Message.obtain(null, what).apply {
            this.data = Bundle(data).apply { putInt(LlmProtocol.K_REQUEST_ID, requestId) }
            replyTo = incoming
        }
        return try {
            target.send(msg)
            true
        } catch (t: RemoteException) {
            Log.e(TAG, "send failed: ${t.message}")
            serviceMessenger = null
            _connection.value = Connection.Lost("Inference process died")
            false
        }
    }

    private fun onMessage(msg: Message) {
        if (msg.what == LlmProtocol.R_THERMAL) {
            msg.data.getString(LlmProtocol.K_MESSAGE)?.let { _warnings.tryEmit(it) }
            return
        }
        if (msg.what == LlmProtocol.R_WARNING) {
            msg.data.getString(LlmProtocol.K_MESSAGE)?.let { _warnings.tryEmit(it) }
            return
        }
        val requestId = msg.data.getInt(LlmProtocol.K_REQUEST_ID, 0)
        handlers[requestId]?.invoke(msg.what, msg.data)
            ?: Log.w(TAG, "no handler for message ${msg.what} (req=$requestId)")
    }

    private fun failAll(reason: String) {
        val snapshot = handlers.toMap()
        handlers.clear()
        snapshot.forEach { (_, handler) ->
            runCatching { handler(LlmProtocol.R_ERROR, Bundle().apply { putString(LlmProtocol.K_ERROR, reason) }) }
        }
    }

    companion object {
        private const val TAG = "LlmClient"
    }
}
