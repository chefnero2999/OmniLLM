package com.omni.llm.data

import android.content.Context
import com.omni.llm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    val family: String,
    val fileName: String,
    val paramCount: Long,
    val quantization: String,
    val fileSizeBytes: Long,
    val downloadUrl: String,
    val sha256: String,
    val minRamMb: Int,
    val recommendedContext: Int,
    val kvBytesPerToken: Long,
    val tier: String,
    val license: String,
    val description: String,
    /** "everyday" | "coding" | "reasoning" — drives grouping in the Models tab. */
    val category: String = "everyday",
    val tags: List<String> = emptyList(),
)

@Serializable
data class ModelsManifest(
    val schema: Int,
    val updated: String,
    val note: String = "",
    val models: List<ModelEntry>,
)

/**
 * The curated model list.
 *
 * Deliberately *not* a live Hugging Face search: that is slow, rate-limited and can hand the
 * user a 7B F16 file that instantly OOMs. The bundled asset is the source of truth and always
 * works offline; a remote copy (if you host one) can refresh it without shipping an update.
 */
class ManifestRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File get() = File(context.filesDir, "manifest_cache.json")

    /** Remote manifest URL; override by editing `OMNI_MANIFEST_URL` in app/build.gradle.kts. */
    private val remoteUrl: String get() = BuildConfig.OMNI_MANIFEST_URL

    suspend fun load(): ModelsManifest = withContext(Dispatchers.IO) {
        // Cached remote manifest wins when it is valid, otherwise fall back to the asset.
        readCache()?.let { if (validate(it).isEmpty()) return@withContext it }
        readAsset()
    }

    /** Best-effort refresh. Returns the manifest now in effect. */
    suspend fun refreshFromNetwork(): ModelsManifest = withContext(Dispatchers.IO) {
        val current = load()
        if (remoteUrl.isBlank() || remoteUrl.contains("REPLACE_ME")) return@withContext current
        val fetched = runCatching {
            val request = Request.Builder().url(remoteUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                json.decodeFromString<ModelsManifest>(body)
            }
        }.getOrNull() ?: return@withContext current

        val problems = validate(fetched)
        if (problems.isNotEmpty()) return@withContext current
        runCatching { cacheFile.writeText(json.encodeToString(ModelsManifest.serializer(), fetched)) }
        fetched
    }

    private fun readAsset(): ModelsManifest = runCatching {
        val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
        json.decodeFromString<ModelsManifest>(text)
    }.getOrElse { ModelsManifest(1, "unknown", "asset read failed", emptyList()) }

    private fun readCache(): ModelsManifest? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString<ModelsManifest>(cacheFile.readText())
    }.getOrNull()

    /**
     * Manifest validation. This is where the "no F16, nothing above Q8_0" rule is enforced.
     *
     * This validates the *catalog we ship*, not the user's freedom: a remote manifest cannot
     * smuggle a 40 GB F16 file into the curated list. It deliberately does NOT restrict imported
     * files — anything the user picks with the file picker is theirs to try, and llama.cpp will
     * say what it thinks of it.
     */
    fun validate(manifest: ModelsManifest): List<String> {
        val problems = mutableListOf<String>()
        if (manifest.models.isEmpty()) problems.add("manifest has no models")
        manifest.models.forEach { m ->
            if (m.quantization !in ALLOWED_QUANTS) {
                problems.add("${m.id}: quantization ${m.quantization} is not allowed on mobile")
            }
            if (m.sha256.length != 64) problems.add("${m.id}: sha256 is not 64 hex chars")
            if (m.fileSizeBytes <= 0) problems.add("${m.id}: invalid size")
            if (!m.downloadUrl.startsWith("https://")) problems.add("${m.id}: download URL must be https")
            if (!m.fileName.endsWith(".gguf")) problems.add("${m.id}: file is not a .gguf")
            if (m.recommendedContext < 512) problems.add("${m.id}: context too small")
        }
        return problems
    }

    companion object {
        /**
         * Quantisations allowed in the *bundled/remote curated catalog*. Q4_K_M is the default and
         * Q8_0 the ceiling. Imports bypass this by design (see [validate]).
         */
        val ALLOWED_QUANTS = setOf("Q4_K_M", "Q5_K_M", "Q6_K", "Q8_0")

        /**
         * Threshold for the RAM *warning*, not a block. Above 55 % of available RAM the model list
         * shows a caution ("may be killed while loading") but still lets the user download and try.
         */
        const val RAM_GATE_FRACTION = 0.55
    }
}
