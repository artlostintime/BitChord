package com.music.bitchord.data.extensions

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Reads the SpotiFLAC extension registry (registry.json) from one or more
 * user-configured repo URLs.
 *
 * The registry is a flat list of entries; multiple repos are merged with the
 * first-seen id winning, so a user-added repo can't shadow an official entry
 * of the same id by being listed later.
 */
object ExtensionRegistryClient {

    /** The official registry shipped as the default repo. */
    const val OFFICIAL_REGISTRY_URL =
        "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One entry in a registry.json. */
    @Serializable
    data class RegistryEntry(
        val id: String,
        val name: String = "",
        val display_name: String = "",
        val version: String = "",
        val description: String = "",
        val download_url: String = "",
        val sha256: String = "",
        val category: String = "",
        val tags: List<String> = emptyList(),
        val min_app_version: String = "",
    )

    @Serializable
    private data class RegistryDocument(
        val version: Int = 1,
        val extensions: List<RegistryEntry> = emptyList(),
    )

    /**
     * Fetches every [repoUrl] in turn and returns the merged entry list.
     *
     * A repo that is unreachable or malformed is skipped rather than failing
     * the whole fetch — the user may have one bad URL among good ones, and the
     * others should still resolve.
     */
    suspend fun fetch(repoUrls: List<String>): List<RegistryEntry> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, RegistryEntry>()
        var hadFailure = false
        var lastError: Throwable? = null
        for (url in repoUrls.filter { it.isNotBlank() }) {
            runCatching {
                val body = Http.client.newCall(Request.Builder().url(url).build()).execute().use {
                    if (!it.isSuccessful) throw Exception("HTTP ${it.code} from $url")
                    it.body?.string() ?: throw Exception("empty body from $url")
                }
                val doc = json.decodeFromString<RegistryDocument>(body)
                for (entry in doc.extensions) {
                    if (entry.id.isNotBlank()) merged.putIfAbsent(entry.id, entry)
                }
            }.onFailure {
                hadFailure = true
                lastError = it
            }
        }
        // Every repo failed to load: surface it so the screen shows the error
        // instead of a silent "no extensions". A repo that resolves but is
        // genuinely empty still returns an empty list (not an error).
        if (merged.isEmpty() && hadFailure) {
            throw lastError ?: Exception("Failed to load extensions")
        }
        merged.values.toList()
    }
}
