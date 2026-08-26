package com.music.bitchord.data

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * BitChord ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 */
object AppUpdateChecker {

    data class UpdateInfo(val version: String, val releaseUrl: String)

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            TrackLog.d("AppUpdateChecker", "checking $LATEST_RELEASE_URL")
            // GitHub's API rejects unauthenticated requests with no User-Agent
            // (HTTP 403), which is why updates never surfaced before.
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("User-Agent", "BitChord")
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                TrackLog.d("AppUpdateChecker", "latest-release HTTP ${response.code}")
                if (!response.isSuccessful) {
                    TrackLog.w("AppUpdateChecker", "update check failed: HTTP ${response.code}")
                    return@runCatching
                }
                response.body?.string()
            } ?: return@runCatching
            val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val latest = tag.removePrefix("v")
            TrackLog.d("AppUpdateChecker", "latest tag=$latest current=${BuildConfig.VERSION_NAME}")
            val newer = isNewer(latest, BuildConfig.VERSION_NAME)
            TrackLog.d("AppUpdateChecker", "isNewer=$newer")
            if (newer) {
                _available.value = UpdateInfo(latest, url)
            }
        }.onFailure {
            TrackLog.w("AppUpdateChecker", "update check threw: ${it.message}")
        }
    }

    /** Numeric, dot-separated comparison — "1.10" outranks "1.9". */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
