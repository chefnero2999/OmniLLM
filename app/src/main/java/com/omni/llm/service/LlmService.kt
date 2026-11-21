package com.omni.llm.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import com.omni.llm.MainActivity
import com.omni.llm.R
import com.omni.llm.core.ChatTurn
import com.omni.llm.core.LlamaBridge
import com.omni.llm.data.AntiCrashGuard
import com.omni.llm.data.DeviceCapabilityProbe
import com.omni.llm.data.ModelPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The inference process (`:llm`).
 *
 * This service exists so that a model load or a runaway generation can only ever kill *this*
 * process. The UI survives, shows "Reload model", and the user is not thrown out of the app.
 *
 * Two watchdogs run here:
 *  - a thermal watchdog, which halves threads when the SoC reports MODERATE or worse;
 *  - a stall watchdog, which cancels a generation that stops producing tokens (a wedged decode
 *    would otherwise sit on the CPU until the battery dies).
 */
class LlmService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val messenger by lazy { Messenger(worker) }
    private lateinit var guard: AntiCrashGuard
    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var replyTo: Messenger? = null
    @Volatile private var generating = false
    @Volatile private var lastTokenAt = 0L
    @Volatile private var currentThreads = 4
    @Volatile private var thermalHalved = false
    @Volatile private var loadedModelId: String? = null

    override fun onCreate() {
        super.onCreate()
        guard = AntiCrashGuard(this)
        workerThread = HandlerThread("omni-llm-worker").also { it.start() }
        worker = Handler(workerThread.looper) { handleMessage(it) }
        startForegroundSafely()
        acquireWakeLock()
        Log.i(TAG, "LlmService created pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopWatchdogs()
        runCatching { LlamaBridge.unload() }
        workerThread.quitSafely()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "LlmService destroyed")
    }

    // ---- Foreground -------------------------------------------------------------------------

    private fun startForegroundSafely() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Your prompts never leave this device.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        try {
            // dataSync: the honest type for "keep working while the screen is off".
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniLLM::inference").apply {
            setReferenceCounted(false)
            try {
                acquire(30 * 60 * 1000L)  // hard ceiling: never drain the battery by accident
            } catch (t: Throwable) {
                Log.w(TAG, "wakelock acquire failed: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release() }
    }

    // ---- Message handling -------------------------------------------------------------------

    private fun handleMessage(msg: Message): Boolean {
        replyTo = msg.replyTo ?: replyTo
        val requestId = msg.data.getInt(LlmProtocol.K_REQUEST_ID, 0)
        when (msg.what) {
            LlmProtocol.MSG_LOAD -> load(msg.data, requestId)
            LlmProtocol.MSG_GENERATE -> generate(msg.data, requestId)
            LlmProtocol.MSG_CANCEL -> {
                Log.i(TAG, "cancel requested")
                LlamaBridge.cancel()
            }
            LlmProtocol.MSG_UNLOAD -> {
                generating = false
                LlamaBridge.unload()
                loadedModelId = null
                guard.markLoadFinished()
                send(LlmProtocol.R_PONG, requestId, Bundle().apply {
                    putString(LlmProtocol.K_STATUS, "unloaded")
                })
            }
            LlmProtocol.MSG_RESET -> LlamaBridge.resetConversation()
            LlmProtocol.MSG_SET_THREADS -> {
                val n = msg.data.getInt(LlmProtocol.K_N_THREADS, currentThreads)
                currentThreads = n
                LlamaBridge.setThreads(n)
            }
            LlmProtocol.MSG_COUNT_TOKENS -> {
                val roles = msg.data.getStringArrayList(LlmProtocol.K_ROLES) ?: arrayListOf()
                val contents = msg.data.getStringArrayList(LlmProtocol.K_CONTENTS) ?: arrayListOf()
                val tokens = if (roles.size == contents.size) {
                    LlamaBridge.countTokens(roles.indices.map { ChatTurn(roles[it], contents[it]) })
                } else -1
                send(LlmProtocol.R_TOKEN_COUNT, requestId, Bundle().apply {
                    putInt(LlmProtocol.K_TOKEN_COUNT, tokens)
                })
            }
            LlmProtocol.MSG_SAVE_KV -> {
                val path = msg.data.getString(LlmProtocol.K_KV_PATH)
                val ok = path != null && LlamaBridge.saveKvState(java.io.File(path))
                Log.i(TAG, "saveKvState -> $ok")
                send(LlmProtocol.R_PONG, requestId, Bundle().apply { putBoolean("kvSaved", ok) })
            }
            LlmProtocol.MSG_PING -> send(LlmProtocol.R_PONG, requestId, Bundle().apply {
                putString(LlmProtocol.K_STATUS, "alive")
                putString(LlmProtocol.K_JSON, LlamaBridge.backendInfo())
                putBoolean("gpu", LlamaBridge.hasGpuBackend())
                putBoolean("loaded", LlamaBridge.isLoaded)
            })
        }
        return true
    }

    private fun load(data: Bundle, requestId: Int) {
        val path = data.getString(LlmProtocol.K_MODEL_PATH) ?: return
        val modelId = data.getString(LlmProtocol.K_MODEL_ID) ?: "imported"
        val nCtx = data.getInt(LlmProtocol.K_N_CTX, 2048)
        val nBatch = data.getInt(LlmProtocol.K_N_BATCH, 512)
        val nUbatch = data.getInt(LlmProtocol.K_N_UBATCH, nBatch)
        val nThreads = data.getInt(LlmProtocol.K_N_THREADS, 4)
        var gpuLayers = data.getInt(LlmProtocol.K_GPU_LAYERS, 0)
        val useMmap = data.getBoolean(LlmProtocol.K_USE_MMAP, true)
        val useMlock = data.getBoolean(LlmProtocol.K_USE_MLOCK, false)
        var flash = data.getBoolean(LlmProtocol.K_FLASH_ATTN, false)
        val q8Kv = data.getBoolean(LlmProtocol.K_Q8_KV, false)
        // Speculative decoding is opt-in: the draft path only arrives when the user picked one.
        val draftPath = data.getString(LlmProtocol.K_DRAFT_PATH)?.takeIf { it.isNotBlank() }
        val draftGpuLayers = data.getInt(LlmProtocol.K_DRAFT_GPU_LAYERS, 0)
        val draftTokens = data.getInt(LlmProtocol.K_DRAFT_TOKENS, 4)

        // Final enforcement point. Whatever the UI thinks, this is what the SoC will actually do.
        if (gpuLayers > 0 && !LlamaBridge.hasGpuBackend()) {
            Log.w(TAG, "GPU layers requested but no GPU backend is compiled in; forcing 0")
            gpuLayers = 0
        }
        if (gpuLayers > 0 && flash) {
            Log.w(TAG, "flash attention forced off because gpuLayers > 0")
            flash = false
        }

        val file = java.io.File(path)
        if (!file.isFile) {
            sendError(requestId, "Model file is missing on disk.")
            return
        }

        // 5.2 pre-flight: refuse before llama.cpp allocates anything.
        val available = availableRamBytes()
        val kvPerToken = data.getLong("kvBytesPerToken", 128L * 1024)
        val preflight = guard.preflight(file.length(), nCtx, kvPerToken, available, useMmap)
        // The service always proceeds. The UI has already shown the warning and taken an explicit
        // "try anyway" tap; re-refusing here would discard that decision. The one thing we still
        // do is tell the client it is about to happen so the crash-recovery notice makes sense.
        if (!preflight.allowed) {
            Log.w(TAG, "loading anyway despite preflight: ${preflight.message}")
            send(LlmProtocol.R_WARNING, requestId, Bundle().apply {
                putString(LlmProtocol.K_MESSAGE, preflight.message)
            })
        }

        // A draft model roughly doubles resident weights. Validate the pair here, once, and fall
        // back to plain decoding rather than refusing an otherwise loadable target.
        var effectiveDraft: String? = null
        if (draftPath != null) {
            val draftFile = java.io.File(draftPath)
            val verdict = when {
                !draftFile.isFile -> "Draft model file is missing on disk."
                draftFile.absolutePath == file.absolutePath ->
                    "The draft model cannot be the same file as the target model."
                else -> LlamaBridge.speculativeVerdict(file, draftFile, available, nCtx)
            }
            if (verdict == null) {
                effectiveDraft = draftPath
            } else {
                Log.w(TAG, "speculative decoding disabled: $verdict")
                send(LlmProtocol.R_WARNING, requestId, Bundle().apply {
                    putString(LlmProtocol.K_MESSAGE, "Speculative decoding disabled. $verdict")
                })
            }
        }

        generating = false
        currentThreads = nThreads
        // 5.4: write the marker *before* touching native memory. If the process dies here, the
        // next launch sees it and degrades instead of repeating the crash.
        guard.markLoadStarted(modelId, file.name, gpuLayers, nCtx)

        val progressHandler = Handler(mainLooper)
        try {
            LlamaBridge.load(
                file,
                LlamaBridge.LoadConfig(
                    nCtx = nCtx,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    nThreads = nThreads,
                    gpuLayers = gpuLayers,
                    useMmap = useMmap,
                    useMlock = useMlock,
                    flashAttention = flash,
                    q8KvCache = q8Kv && flash,
                    draftModelPath = effectiveDraft,
                    draftGpuLayers = draftGpuLayers,
                    draftTokens = draftTokens,
                ),
            ) { fraction ->
                progressHandler.post {
                    send(LlmProtocol.R_LOAD_PROGRESS, requestId, Bundle().apply {
                        putFloat(LlmProtocol.K_FRACTION, fraction)
                    })
                }
                true
            }
        } catch (t: Throwable) {
            // Includes InferenceException and any stray Throwable: the load must never escape.
            guard.markLoadFinished()
            val message = t.message ?: t::class.java.simpleName
            guard.appendCrashSignature("load-failed model=$modelId gpuLayers=$gpuLayers ctx=$nCtx :: $message")
            sendError(requestId, message)
            return
        }

        guard.markLoadFinished()
        loadedModelId = modelId

        // Cross-session prefix cache: restore the KV state saved by the previous session so a
        // returning user does not pay the system-prompt prefill again. The native side rejects
        // the file when the model fingerprint or n_ctx differs, so a stale cache is harmless.
        val kvFile = com.omni.llm.data.ModelPaths.kvCacheFile(this)
        if (kvFile.isFile && kvFile.length() > 0L) {
            val restored = LlamaBridge.loadKvState(kvFile)
            Log.i(TAG, "cross-session KV restore -> $restored (${kvFile.length()} bytes)")
            if (!restored) kvFile.delete()
        }
        val info = LlamaBridge.modelInfo()
        send(LlmProtocol.R_LOADED, requestId, Bundle().apply {
            putString(LlmProtocol.K_JSON, JSONObject().apply {
                put("description", info?.description ?: "")
                put("context", info?.context ?: nCtx)
                put("trainContext", info?.trainContext ?: 0)
                put("parameters", info?.parameters ?: 0L)
                put("sizeBytes", info?.sizeBytes ?: file.length())
                put("hasChatTemplate", info?.hasChatTemplate ?: false)
                put("layerCount", info?.layerCount ?: 0)
                put("gpuLayers", gpuLayers)
                put("threads", nThreads)
                put("mmap", useMmap)
                put("gpuBackend", LlamaBridge.hasGpuBackend())
                put("speculative", effectiveDraft != null)
                put("draftLoaded", LlamaBridge.isDraftLoaded())
            }.toString())
        })
        startWatchdogs()
    }

    private fun generate(data: Bundle, requestId: Int) {
        if (!LlamaBridge.isLoaded) {
            sendError(requestId, "No model is loaded.")
            return
        }
        val roles = data.getStringArrayList(LlmProtocol.K_ROLES) ?: arrayListOf()
        val contents = data.getStringArrayList(LlmProtocol.K_CONTENTS) ?: arrayListOf()
        if (roles.size != contents.size || roles.isEmpty()) {
            sendError(requestId, "Malformed message list.")
            return
        }
        val messages = roles.indices.map { ChatTurn(roles[it], contents[it]) }
        val sampling = LlamaBridge.SamplingConfig(
            maxTokens = data.getInt(LlmProtocol.K_MAX_TOKENS, 512),
            temperature = data.getFloat(LlmProtocol.K_TEMPERATURE, 0.7f),
            topP = data.getFloat(LlmProtocol.K_TOP_P, 0.9f),
            topK = data.getInt(LlmProtocol.K_TOP_K, 40),
            minP = data.getFloat(LlmProtocol.K_MIN_P, 0.05f),
            repeatPenalty = data.getFloat(LlmProtocol.K_REPEAT_PENALTY, 1.1f),
            seed = data.getInt(LlmProtocol.K_SEED, -1),
        )

        generating = true
        lastTokenAt = System.currentTimeMillis()
        acquireWakeLock()
        try {
            LlamaBridge.generate(messages, sampling) { piece, count ->
                lastTokenAt = System.currentTimeMillis()
                val ok = send(LlmProtocol.R_TOKEN, requestId, Bundle().apply {
                    putString(LlmProtocol.K_PIECE, piece)
                    putInt(LlmProtocol.K_COUNT, count)
                })
                // If the UI process died, stop burning the battery for nobody.
                generating && ok
            }
            val stats = LlamaBridge.lastStats()
            send(LlmProtocol.R_DONE, requestId, Bundle().apply {
                putString(LlmProtocol.K_JSON, JSONObject().apply {
                    put("promptTokens", stats.promptTokens)
                    put("evaluatedTokens", stats.evaluatedTokens)
                    put("generatedTokens", stats.generatedTokens)
                    put("prefillMs", stats.prefillMs)
                    put("decodeMs", stats.decodeMs)
                    put("tokensPerSec", stats.tokensPerSec)
                    put("contextFull", stats.contextFull)
                    put("draftProposed", stats.draftProposed)
                    put("draftAccepted", stats.draftAccepted)
                }.toString())
            })
        } catch (c: LlamaBridge.CancelledException) {
            send(LlmProtocol.R_DONE, requestId, Bundle().apply {
                putBoolean("cancelled", true)
                putString(LlmProtocol.K_JSON, JSONObject().apply {
                    put("generatedTokens", c.stats.generatedTokens)
                    put("tokensPerSec", c.stats.tokensPerSec)
                    put("prefillMs", c.stats.prefillMs)
                    put("draftProposed", c.stats.draftProposed)
                    put("draftAccepted", c.stats.draftAccepted)
                }.toString())
            })
        } catch (t: Throwable) {
            guard.appendCrashSignature(
                "generate-failed model=$loadedModelId :: ${t.message ?: t::class.java.simpleName}"
            )
            sendError(requestId, t.message ?: t::class.java.simpleName)
        } finally {
            generating = false
            releaseWakeLock()
        }
    }

    // ---- Watchdogs --------------------------------------------------------------------------

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private fun startWatchdogs() {
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener == null && pm != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                onThermalStatus(status)
            }
            thermalListener = listener
            runCatching { pm.addThermalStatusListener(mainExecutor, listener) }
            // 30 s polling as a belt-and-braces check for devices that do not deliver callbacks.
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(30_000)
                    runCatching { onThermalStatus(pm.currentThermalStatus) }
                }
            }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (generating && System.currentTimeMillis() - lastTokenAt > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "stall watchdog: no token for ${STALL_TIMEOUT_MS}ms, cancelling")
                    LlamaBridge.cancel()
                }
            }
        }
    }

    private fun onThermalStatus(status: Int) {
        if (status >= PowerManager.THERMAL_STATUS_MODERATE && !thermalHalved) {
            thermalHalved = true
            val reduced = (currentThreads / 2).coerceAtLeast(1)
            LlamaBridge.setThreads(reduced)
            Log.w(TAG, "thermal status $status -> threads halved to $reduced")
            send(LlmProtocol.R_THERMAL, 0, Bundle().apply {
                putInt(LlmProtocol.K_STATUS, status)
                putString(
                    LlmProtocol.K_MESSAGE,
                    "Device warm — performance reduced to prevent crash. Threads halved to $reduced.",
                )
            })
        } else if (status < PowerManager.THERMAL_STATUS_LIGHT && thermalHalved) {
            thermalHalved = false
            LlamaBridge.setThreads(currentThreads)
            Log.i(TAG, "thermals recovered -> threads restored to $currentThreads")
        }
    }

    private fun stopWatchdogs() {
        val pm = getSystemService(PowerManager::class.java)
        thermalListener?.let { runCatching { pm?.removeThermalStatusListener(it) } }
        thermalListener = null
    }

    // ---- Plumbing ---------------------------------------------------------------------------

    private fun availableRamBytes(): Long {
        val am = getSystemService(ActivityManager::class.java) ?: return 0
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }

    private fun send(what: Int, requestId: Int, data: Bundle): Boolean {
        val target = replyTo ?: return false
        val msg = Message.obtain(null, what).apply {
            this.data = data.apply { putInt(LlmProtocol.K_REQUEST_ID, requestId) }
        }
        return try {
            target.send(msg)
            true
        } catch (_: RemoteException) {
            replyTo = null
            generating = false
            false
        }
    }

    private fun sendError(requestId: Int, message: String) {
        send(LlmProtocol.R_ERROR, requestId, Bundle().apply {
            putString(LlmProtocol.K_ERROR, message)
        })
    }

    companion object {
        private const val TAG = "LlmService"
        private const val CHANNEL_ID = "omni_inference"
        private const val NOTIFICATION_ID = 4711
        private const val STALL_TIMEOUT_MS = 120_000L

        fun intent(context: Context): Intent = Intent(context, LlmService::class.java)
    }
}
