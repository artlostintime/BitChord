package com.music.bitchord.data.extensions

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.net.URI

/**
 * A [MusicSource] backed by one installed SpotiFLAC extension.
 *
 * The extension's `index.js` is loaded into a sandboxed QuickJS VM that exposes
 * the host globals the SpotiFLAC runtime defines: `registerExtension` (captures
 * the extension's `searchTracks`/`download` callbacks), `fetch` (network,
 * gated by the manifest's `permissions.network` allowlist), `storage` (a
 * per-extension key/value store) and `file.download` (writes a file, or — in
 * stream-capture mode — resolves and returns the final URL so we can hand it
 * to ExoPlayer as a [SourceStream]).
 *
 * Track ids are packed with [SourceRegistry.trackKey], exactly like the module
 * source, so playback routes back here.
 */
class ExtensionSource(
    override val config: SourceConfig,
    private val context: Context,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.EXTENSION
    override val displayName: String get() = config.displayName

    private val extId get() = config.id

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun manifest(): SflxInstaller.ExtensionManifest =
        SflxInstaller.readManifest(SflxInstaller.installDir(context, extId))

    private fun storagePrefs(): SharedPreferences =
        context.getSharedPreferences("bitchord_ext_$extId", Context.MODE_PRIVATE)

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        val dir = SflxInstaller.installDir(context, extId)
        if (!File(dir, "index.js").exists()) {
            return@withContext SourceHealth.Rejected("Extension $extId is not installed")
        }
        val manifest = manifest()
        // signedSession@1 is now satisfied by the ZarzSession binding; only
        // genuinely unknown features still reject the extension.
        val unsupported = manifest.requiredRuntimeFeatures.filter { it != "signedSession@1" }
        if (unsupported.isNotEmpty()) {
            return@withContext SourceHealth.Rejected("Requires unsupported runtime feature ${unsupported.first()}")
        }
        // ponytail: health only checks files + declared features, not that the
        // JS actually exports searchTracks/download — that is verified at load.
        SourceHealth.Ok(manifest.displayName.ifBlank { manifest.name }.ifBlank { extId })
    }

    private suspend fun ensureLoaded(): Result<Unit> {
        if (ExtensionJs.isLoaded(extId)) return Result.success(Unit)
        val dir = SflxInstaller.installDir(context, extId)
        val jsFile = File(dir, "index.js")
        if (!jsFile.exists()) return Result.failure(IllegalStateException("index.js missing for $extId"))
        return runCatching { ExtensionJs.load(extId, jsFile.readText(), manifest(), storagePrefs()) }
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureLoaded().onFailure {
                TrackLog.w(TAG, "$displayName: load failed — ${it.message}")
                return@withContext emptyList()
            }
            val raw = ExtensionJs.search(extId, query).getOrElse {
                TrackLog.w(TAG, "$displayName: search failed — ${it.message}")
                return@withContext emptyList()
            }
            val tracks = runCatching { json.decodeFromString<List<ExtensionTrack>>(raw) }
                .getOrElse {
                    // Many provider APIs nest the list under a key (tracks/results/
                    // data/items) instead of returning it bare; try those before
                    // giving up. A bare non-array (or a type mismatch) is logged so a
                    // silent empty search — which would otherwise let YouTube win with
                    // no trace — is visible.
                    val fromWrapper = extractTrackList(raw)
                    if (fromWrapper == null) {
                        TrackLog.w(
                            TAG,
                            "$displayName: search JSON decode failed — ${it.message} (raw=${raw.take(200)})",
                        )
                    } else {
                        TrackLog.d(TAG, "$displayName: search recovered ${fromWrapper.size} track(s) from wrapped JSON")
                    }
                    fromWrapper ?: emptyList()
                }
            tracks.take(limit).map { t ->
                Song(
                    videoId = SourceRegistry.trackKey(config.id, t.id),
                    title = t.title,
                    artist = t.artist,
                    albumName = t.album.ifBlank { null },
                    thumbnailUrl = t.albumCover ?: t.thumbnailUrl,
                    durationText = if (t.duration > 0) {
                        "${t.duration / 60}:${"%02d".format(t.duration % 60)}"
                    } else null,
                    sourceQuality = qualityTier(t),
                )
            }
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            ensureLoaded().onFailure {
                TrackLog.w(TAG, "$displayName: load failed — ${it.message}")
                return@withContext null
            }
            val quality = when (request) {
                is StreamRequest.Lossless -> "LOSSLESS"
                is StreamRequest.Best -> "HIGH"
                is StreamRequest.Capped -> if (request.maxKbps <= 128) "LOW" else "HIGH"
            }
            val url = ExtensionJs.captureStreamUrl(extId, trackId, quality).getOrElse {
                TrackLog.w(TAG, "$displayName: stream failed for $trackId — ${it.message}")
                return@withContext null
            }
            if (url.isBlank()) {
                TrackLog.w(TAG, "$displayName: stream returned blank url for $trackId (capture failed)")
                return@withContext null
            }
            // Tidal-style extensions return a DASH/BTS manifest (base64 MPD or
            // JSON {urls:[...]}), not a playable URL. Rewrite it to a local
            // DASH playlist ExoPlayer can open — see [resolveManifest].
            val playable = resolveManifest(url, trackId)
            SourceStream(
                url = playable,
                format = StreamFormat(codec = if (playable.startsWith("file://")) "flac" else codecOf(playable)),
                sourceLabel = displayName,
            )
        }

    /**
     * Tidal (and kin) don't hand back a playable URL from `download()` — they
     * return a DASH/BTS manifest describing an init segment plus FLAC media
     * segments. ExoPlayer can't open a raw segment list, so we materialise a
     * local DASH manifest (.mpd) in [Context.cacheDir] and return a `file://`
     * URI. Media3's DefaultMediaSourceFactory parses DASH from a file URI
     * (media3-exoplayer-dash, added for this).
     *
     * ponytail: segments are not pre-joined; playback relies on the CDN URLs
     * named in the manifest staying valid for the session. Re-resolve if a
     * segment 403s — there is no manifest refresh here.
     */
    private fun resolveManifest(raw: String, trackId: String): String {
        val trimmed = raw.trim()
        // JSON manifest: {"urls":[...]}, optionally {"init":..., "urls":[...]}.
        if (trimmed.startsWith("{")) {
            return runCatching {
                val obj = JSONObject(trimmed)
                val urls = obj.optJSONArray("urls")
                if (urls != null && urls.length() > 0) {
                    if (urls.length() == 1) {
                        urls.getString(0) // single direct chunk URL
                    } else {
                        val init = obj.optString("init").takeIf { it.isNotBlank() }
                        val media = (0 until urls.length()).map { i -> urls.getString(i) }
                        writeDashPlaylist(trackId, init, media)
                    }
                } else {
                    raw // not a shape we recognise; pass through unchanged
                }
            }.getOrDefault(raw)
        }
        // base64 MPD: starts with PD94 (base64 of "<?xm") or decodes to "<MPD".
        if (looksLikeBase64Mpd(trimmed)) {
            val decoded = decodeBase64(trimmed) ?: return raw
            if (decoded.contains("<MPD", ignoreCase = true) ||
                decoded.trimStart().startsWith("<?xml", ignoreCase = true)
            ) {
                return writeCacheFile(trackId, "mpd", decoded)
            }
        }
        return raw
    }

    /** Builds a DASH MPD with a SegmentList (init + media) and writes it to cache. */
    private fun writeDashPlaylist(trackId: String, init: String?, media: List<String>): String {
        val segList = buildString {
            append("        <SegmentList>\n")
            if (init != null) append("          <Initialization sourceURL=\"${init.xmlEscape()}\"/>\n")
            for (m in media) append("          <SegmentURL media=\"${m.xmlEscape()}\"/>\n")
            append("        </SegmentList>\n")
        }
        val mpd = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT0H0M0S" minBufferTime="PT1S" profiles="urn:mpeg:dash:profile:full:2011">
  <Period>
    <AdaptationSet id="0" mimeType="audio/flac" subsegmentAlignment="true">
      <Representation id="0" codecs="flac" mimeType="audio/flac" bandwidth="1000000">
$segList      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
        return writeCacheFile(trackId, "mpd", mpd)
    }

    private fun writeCacheFile(trackId: String, ext: String, content: String): String {
        val safeId = trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(context.cacheDir, "tidal_$safeId.$ext")
        file.writeText(content)
        return "file://${file.absolutePath}"
    }

    private fun looksLikeBase64Mpd(s: String): Boolean {
        if (s.startsWith("PD94")) return true // base64 of "<?xm"
        if (!s.matches(Regex("^[A-Za-z0-9+/_-]+={0,2}$"))) return false
        return decodeBase64(s)?.contains("<MPD", ignoreCase = true) == true
    }

    private fun decodeBase64(s: String): String? {
        val clean = s.replace(Regex("\\s+"), "")
        return runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }
            .getOrElse {
                runCatching { String(Base64.decode(clean, Base64.URL_SAFE)) }.getOrNull()
            }
    }

    private fun String.xmlEscape(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Best-effort tier from the row's free-text quality/format, like ModuleSource. */
    private fun qualityTier(t: ExtensionTrack): String? {
        val text = "${t.audioQuality} ${t.format}".uppercase()
        return when {
            text.isBlank() -> null
            LOSSLESS_HINTS.any { it in text } -> "LOSSLESS"
            LOW_HINTS.any { it in text } -> "LOW"
            HIGH_HINTS.any { it in text } -> "HIGH"
            else -> null
        }
    }

    private fun codecOf(url: String): String? =
        url.substringBefore('?').substringAfterLast('.').lowercase()
            .takeIf { it in AUDIO_EXTENSIONS }

    /**
     * Recovers a track list from a provider response that wraps it under a
     * common key instead of returning the array bare — see [search]. Returns
     * null when [raw] isn't a JSON object or holds no recognised list, so the
     * caller still logs the original decode failure rather than masking it.
     */
    private fun extractTrackList(raw: String): List<ExtensionTrack>? {
        val t = raw.trim()
        if (!t.startsWith("{")) return null
        return runCatching {
            val obj = JSONObject(t)
            for (key in listOf("tracks", "results", "data", "items", "list", "track_list", "song_list")) {
                val arr = obj.optJSONArray(key) ?: continue
                val decoded = runCatching {
                    json.decodeFromString<List<ExtensionTrack>>(arr.toString())
                }.getOrNull()
                if (!decoded.isNullOrEmpty()) return decoded
            }
            null
        }.getOrNull()
    }

    private companion object {
        const val TAG = "BitChord"
        val LOSSLESS_HINTS = listOf("LOSSLESS", "FLAC", "ALAC", "HI-RES", "HI_RES", "HIRES", "24-BIT", "16-BIT", "WAV")
        val LOW_HINTS = listOf("LOW", "128", "96KBPS", "64")
        val HIGH_HINTS = listOf("HIGH", "320", "MP3", "AAC", "M4A", "OPUS", "OGG")
        val AUDIO_EXTENSIONS = setOf("flac", "alac", "wav", "aiff", "mp3", "m4a", "aac", "ogg", "opus", "webm")
    }
}

/** One track as an extension's `searchTracks` returns it. */
@Serializable
private data class ExtensionTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumCover: String? = null,
    val thumbnailUrl: String? = null,
    val duration: Int = 0,
    val audioQuality: String = "",
    val format: String = "",
    val availableQualities: List<String> = emptyList(),
)

/**
 * Per-extension QuickJS host, modelled on [com.music.bitchord.data.sources.module.QuickJsExecutor]
 * but with the SpotiFLAC globals instead of the module's `__spine` surface.
 *
 * Engines are keyed by extension id and kept alive across search→stream so an
 * extension's session state survives, exactly as the module executor does.
 */
internal object ExtensionJs {

    private const val TAG = "BitChord"

    /** Sentinel output path that puts `file.download` into stream-capture mode. */
    private const val CAPTURE = "__capture__"

    private val engines = ConcurrentHashMap<String, QuickJs>()
    private val meta = ConcurrentHashMap<String, Meta>()

    private data class Meta(val allowlist: List<String>, val storage: SharedPreferences)

    fun isLoaded(extId: String): Boolean = engines.containsKey(extId)

    fun unload(extId: String) {
        engines.remove(extId)?.close()
        meta.remove(extId)
    }

    // ── Load ────────────────────────────────────────────────────────────────

    suspend fun load(
        extId: String,
        jsCode: String,
        manifest: SflxInstaller.ExtensionManifest,
        storage: SharedPreferences,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val unsupported = manifest.requiredRuntimeFeatures.filter { it != "signedSession@1" }
            if (unsupported.isNotEmpty()) {
                throw UnsupportedOperationException("${unsupported.first()} runtime feature unsupported")
            }
            val qjs = QuickJs.create(Dispatchers.Default)
            qjs.maxStackSize = 512 * 1024L
            meta[extId] = Meta(manifest.permissions.network, storage)
            bindConsole(qjs)
            bindFetch(qjs, extId)
            bindStorage(qjs, extId)
            bindFile(qjs, extId)
            bindSession(qjs)
            // The extension calls global registerExtension({...}); stash the
            // descriptor on a global so we can invoke its methods by name later.
            qjs.evaluate<Unit>(
                "globalThis.__ext = null;" +
                    "globalThis.registerExtension = function(e){ globalThis.__ext = e; };",
            )
            qjs.evaluate<String>(preprocess(jsCode))
            if (qjs.evaluate<String>("typeof globalThis.__ext") != "object") {
                throw IllegalStateException("extension $extId did not call registerExtension")
            }
            engines[extId] = qjs
        }.onFailure {
            TrackLog.e(TAG, "load failed for $extId: ${it.message}")
            engines[extId]?.close()
            engines.remove(extId)
            meta.remove(extId)
        }
    }

    // ── Call ────────────────────────────────────────────────────────────────

    suspend fun search(extId: String, query: String): Result<String> = withContext(Dispatchers.Default) {
        val qjs = engines[extId] ?: return@withContext Result.failure(IllegalStateException("$extId not loaded"))
        runCatching {
            qjs.evaluate<Unit>(
                "var __ext_res = undefined;" +
                    "(async function(){" +
                    "  try { var r = await globalThis.__ext.searchTracks(${JSONObject.quote(query)});" +
                    "    __ext_res = JSON.stringify(r); }" +
                    "  catch(e){ __ext_res = JSON.stringify({error: e && e.message ? e.message : String(e)}); }" +
                    "})();",
            )
            val raw = qjs.evaluate<String>("__ext_res")
            if (raw.isBlank() || raw == "undefined") {
                // The async IIFE didn't populate __ext_res — searchTracks hung
                // past the caller's timeout or returned nothing. Without this the
                // extension silently yields an empty search and YouTube wins with
                // no trace of why.
                TrackLog.w(TAG, "search($extId): no result resolved (raw='$raw') — searchTracks may not return a promise")
                throw IllegalStateException("$extId search returned no result")
            }
            if (raw.startsWith("{") && raw.contains("\"error\"")) {
                TrackLog.w(TAG, "search($extId): ${parseError(raw)}")
                throw IllegalStateException(parseError(raw))
            }
            raw
        }
    }

    /**
     * Runs the extension's `download(trackId, quality, "__capture__", null)` and
     * returns the resolved stream URL that `file.download` captured.
     */
    suspend fun captureStreamUrl(extId: String, trackId: String, quality: String): Result<String> =
        withContext(Dispatchers.Default) {
            val qjs = engines[extId] ?: return@withContext Result.failure(IllegalStateException("$extId not loaded"))
            runCatching {
                // Clear the capture global first so a stale URL from a previous
                // call can't be mistaken for this one's result.
                qjs.evaluate<Unit>("globalThis.__ext_capture_url = undefined;")
                qjs.evaluate<Unit>(
                    "var __ext_dl_res = undefined;" +
                        "(async function(){" +
                        "  try { var r = await globalThis.__ext.download(" +
                        "    ${JSONObject.quote(trackId)}, ${JSONObject.quote(quality)}, '$CAPTURE', null);" +
                        "    __ext_dl_res = JSON.stringify(r || globalThis.__ext_capture_url || ''); }" +
                        "  catch(e){ __ext_dl_res = JSON.stringify({error: e && e.message ? e.message : String(e)}); }" +
                        "})();",
                )
                val raw = qjs.evaluate<String>("__ext_dl_res")
                if (raw.isBlank() || raw == "undefined") {
                    TrackLog.w(TAG, "captureStreamUrl($extId): download returned no stream url (raw='$raw')")
                    throw IllegalStateException("extension download returned no stream url")
                }
                if (raw.startsWith("{") && raw.contains("\"error\"")) {
                    TrackLog.w(TAG, "captureStreamUrl($extId): ${parseError(raw)}")
                    throw IllegalStateException(parseError(raw))
                }
                val url = extractUrl(raw)
                if (url.isBlank()) {
                    TrackLog.w(TAG, "captureStreamUrl($extId): extracted blank url from '$raw'")
                    throw IllegalStateException("extension download returned no stream url")
                }
                url
            }
        }

    // ── Bindings ─────────────────────────────────────────────────────────────

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : com.dokar.quickjs.binding.FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.d(TAG, "[ext] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("error", object : com.dokar.quickjs.binding.FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.e(TAG, "[ext-err] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("warn", object : com.dokar.quickjs.binding.FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.w(TAG, "[ext-warn] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
        }
    }

    private suspend fun bindFetch(qjs: QuickJs, extId: String) {
        qjs.define("__ext_net") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val url = args[0]?.toString() ?: throw IllegalArgumentException("fetch requires a URL")
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString()
                    checkHost(url, extId)
                    val (code, respBody) = fetchSync(url, method, headersJson, body)
                    return JSONObject().apply {
                        put("status", code)
                        put("ok", code in 200..299)
                        put("body", respBody)
                    }.toString()
                }
            })
        }
        qjs.evaluate<Unit>(FETCH_POLYFILL)
    }

    private fun bindStorage(qjs: QuickJs, extId: String) {
        qjs.define("storage") {
            asyncFunction("get", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val key = args[0]?.toString() ?: throw IllegalArgumentException("storage.get requires a key")
                    // Return the stored JSON value, or the literal "null" when absent
                    // (so `JSON.parse(storage.get(k))` yields JS null).
                    return meta[extId]!!.storage.getString(key, null) ?: "null"
                }
            })
            asyncFunction("set", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val key = args[0]?.toString() ?: throw IllegalArgumentException("storage.set requires a key")
                    val value = args.getOrNull(1)?.toString() ?: "null"
                    meta[extId]!!.storage.edit().putString(key, value).apply()
                    return "true"
                }
            })
        }
    }

    private fun bindFile(qjs: QuickJs, extId: String) {
        qjs.define("file") {
            asyncFunction("download", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val url = args[0]?.toString() ?: throw IllegalArgumentException("file.download requires a URL")
                    val path = args.getOrNull(1)?.toString() ?: throw IllegalArgumentException("file.download requires a path")
                    checkHost(url, extId)
                    val finalUrl = resolveFinalUrl(url)
                    if (path == CAPTURE) {
                        // Stream-capture mode: stash the resolved URL in a global so
                        // captureStreamUrl can recover it even when the extension's
                        // download() returns nothing (many implementations call
                        // file.download for its side effect and return undefined).
                        // The return value still covers extensions that do return it.
                        qjs.evaluate<Unit>("globalThis.__ext_capture_url = ${JSONObject.quote(finalUrl)}")
                        return finalUrl
                    }
                    downloadToFile(finalUrl, path)
                    return path
                }
            })
        }
    }

    private fun bindSession(qjs: QuickJs) {
        qjs.define("session") {
            asyncFunction("signedFetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val url = args[0]?.toString()
                        ?: throw IllegalArgumentException("session.signedFetch requires a url")
                    return ZarzSession.signedFetch(url).getOrElse { throw it }
                }
            })
            function("signedTicket", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val provider = args.getOrNull(0)?.toString()
                        ?: throw IllegalArgumentException("session.signedTicket requires provider")
                    val type = args.getOrNull(1)?.toString()
                        ?: throw IllegalArgumentException("session.signedTicket requires type")
                    val id = args.getOrNull(2)?.toString()
                        ?: throw IllegalArgumentException("session.signedTicket requires id")
                    return ZarzSession.signedTicket(provider, type, id)
                }
            })
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun checkHost(url: String, extId: String) {
        val allow = meta[extId]?.allowlist ?: return
        // ponytail: an empty allowlist allows all hosts (convenience over strict
        // deny-all); tighten to deny-all if extensions abuse the宽松 default.
        if (allow.isEmpty()) return
        val host = runCatching { URI(url).host }.getOrNull()
            ?: throw SecurityException("invalid url: $url")
        if (allow.none { host == it || host.endsWith(".$it") }) {
            throw SecurityException("host $host is not in the extension's network allowlist")
        }
    }

    private fun fetchSync(url: String, method: String, headersJson: String, body: String?): Pair<Int, String> {
        val builder = Request.Builder().url(url)
        try {
            val h = JSONObject(headersJson)
            for (k in h.keys()) builder.header(k, h.optString(k, ""))
        } catch (_: Exception) {
        }
        when (method.uppercase()) {
            "POST" -> builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        Http.client.newCall(builder.build()).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /** Follows redirects without downloading the body, returning the final URL. */
    private fun resolveFinalUrl(url: String): String {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).head().build()).execute().use {
                return it.request.url.toString()
            }
        }
        return Http.client.newCall(Request.Builder().url(url).get().build()).execute().use {
            it.request.url.toString()
        }
    }

    private fun downloadToFile(url: String, path: String) {
        Http.client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} downloading $url")
            val out = File(path)
            out.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { ins -> File(path).outputStream().use { ins.copyTo(it) } }
        }
    }

    private fun parseError(raw: String): String =
        runCatching { JSONObject(raw).optString("error", raw) }.getOrDefault(raw)

    /**
     * The stream URL out of `download()`'s JSON-stringified return.
     *
     * A well-behaved extension returns the URL string (a quoted JSON string
     * here); some return an object with a `url` field. Either way we want the
     * URL, not the wrapper.
     */
    private fun extractUrl(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 ->
                t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            t.startsWith("{") ->
                // A well-behaved extension returns {"url": "..."}. A manifest
                // response (Tidal: {"urls": [...]}) has no "url" field; pass it
                // through so the caller can rewrite it into a playable source.
                runCatching {
                    val u = JSONObject(t).optString("url", "")
                    if (u.isNotBlank()) u else t
                }.getOrDefault(t)
            else -> t
        }
    }

    /** Strips ES-module `export` keywords so plain `index.js` runs as a script. */
    private fun preprocess(code: String): String {
        var r = code
        r = r.replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
        r = r.replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
        r = r.replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        return r
    }

    /**
     * Web-compatible `fetch()` over the native `__ext_net.fetch` binding, mirroring
     * the module executor's wrapper so extensions written against the standard
     * surface work unchanged.
     */
    private const val FETCH_POLYFILL = """
        var fetch = async function(url, options) {
            var method = 'GET';
            var headers = '{}';
            var body = null;
            if (options) {
                method = options.method || 'GET';
                if (options.headers) {
                    if (typeof options.headers === 'string') { headers = options.headers; }
                    else { try { headers = JSON.stringify(options.headers); } catch(e) { headers = '{}'; } }
                }
                if (options.body !== undefined && options.body !== null) {
                    body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                }
            }
            var raw = JSON.parse(await __ext_net.fetch(url, method, headers, body));
            var respBody = raw.body;
            return {
                ok: raw.ok,
                status: raw.status,
                statusText: raw.ok ? 'OK' : 'Error',
                json: function() { try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON: ' + respBody.substring(0, 200)); } },
                text: function() { return respBody; },
                arrayBuffer: function() { throw new Error('Not implemented'); },
                clone: function() { return this; },
                headers: { get: function(k) { return null; } }
            };
        };
    """
}
