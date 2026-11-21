package com.omni.llm

import android.content.Context
import com.omni.llm.data.AntiCrashGuard
import com.omni.llm.data.CloudFallbackGate
import com.omni.llm.data.DeviceCapabilityProbe
import com.omni.llm.data.KeylessCloudRouter
import com.omni.llm.data.ManifestRepository
import com.omni.llm.data.ModelDownloadManager
import com.omni.llm.data.ModelRepository
import com.omni.llm.data.EngineState
import com.omni.llm.data.SettingsManager
import com.omni.llm.service.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-rolled dependency container. No Hilt: the graph is six objects, and adding an annotation
 * processor to a project like this only slows down the build and hides the wiring.
 */
class AppContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val probe = DeviceCapabilityProbe(context)
    val guard = AntiCrashGuard(context)
    val settings = SettingsManager(context)
    val manifests = ManifestRepository(context)
    val downloads = ModelDownloadManager(context, scope)
    val models = ModelRepository(context, scope, manifests, downloads, probe)
    val cloud = KeylessCloudRouter(
        context.getSharedPreferences("cloud_router", Context.MODE_PRIVATE)
    )
    val cloudGate = CloudFallbackGate(settings)
    val llm = LlmClient(context)

    /** Shared "what is currently loaded" state so the Chat and Models tabs cannot disagree. */
    val engine = EngineState()

    private val _profile = MutableStateFlow(probe.probe())
    val profile: StateFlow<DeviceCapabilityProbe.DeviceProfile> = _profile.asStateFlow()

    fun refreshProfile() {
        val gpu = (llm.connection.value as? LlmClient.Connection.Ready)?.let {
            DeviceCapabilityProbe.GpuStatus(it.gpuAvailable, it.backendReport)
        }
        _profile.value = probe.probe(gpu)
    }

    /** Current safe caps for this device. */
    fun caps(): DeviceCapabilityProbe.Caps = probe.caps(probe.probe())
}
