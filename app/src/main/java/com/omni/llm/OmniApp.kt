package com.omni.llm

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch

class OmniApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        container.scope.launch {
            // 5.4 crash-loop breaker: must run before anything can try to load a model.
            val crash = runCatching { container.guard.detectCrashLoop() }.getOrNull()
            if (crash != null) {
                Log.w(TAG, "previous run crashed during load: ${crash.signature}")
                container.settings.update { current ->
                    // Drop to the last settings that were *proven* to work (a completed
                    // generation), not blindly to zero. If nothing ever succeeded, that is 0.
                    val safeLayers = current.lastStableGpuLayers.coerceIn(0, current.gpuLayers)
                    val safeCtx = if (current.lastStableGpuLayers > 0) current.contextLength
                                  else minOf(current.contextLength, 2048)
                    Log.w(TAG, "degrading to last stable: gpuLayers=$safeLayers ctx=$safeCtx")
                    current.copy(
                        gpuLayers = safeLayers,
                        contextLength = safeCtx,
                        pendingCrashNotice =
                            "OmniLLM crashed while loading ${crash.fileName.ifBlank { "a model" }} " +
                                "(${crash.gpuLayers} GPU layers, ${crash.contextLength} context). " +
                                "Reset to your last working settings: $safeLayers GPU layers, $safeCtx context.",
                    )
                }
            }

            // Section 4.1: device-adaptive defaults, applied exactly once.
            val profile = container.probe.probe()
            val defaults = container.probe.defaults(profile)
            container.settings.initializeDefaults(profile.performanceCores, defaults)

            // Warm the model list so the Models tab is never empty on first frame.
            runCatching { container.models.refreshManifest(refreshRemote = true) }
        }
    }

    companion object {
        private const val TAG = "OmniApp"
    }
}
