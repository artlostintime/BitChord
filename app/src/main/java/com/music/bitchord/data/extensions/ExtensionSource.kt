package com.music.bitchord.data.extensions

import android.content.Context
import android.content.SharedPreferences
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
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
        if (manifest.requiredRuntimeFeatures.contains("signedSession@1")) {
            return@withContext SourceHealth.Rejected("Requires unsupported runtime feature signedSession@1")
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
        ExtensionJs.load(extId, jsFile.readText(), manifest(), storagePrefs())
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
                .getOrElse { emptyList() }
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
            if (url.isBlank()) return@withContext null
            SourceStream(
                url = url,
                format = StreamFormat(codec = codecOf(url)),
                sourceLabel = displayName,
            )
        }

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
            if (manifest.requiredRuntimeFeatures.contains("signedSession@1")) {
                throw UnsupportedOperationException("signedSession@1 runtime feature unsupported")
            }
            val qjs = QuickJs.create(Dispatchers.Default)
            qjs.maxStackSize = 512 * 1024L
            meta[extId] = Meta(manifest.permissions.network, storage)
            bindConsole(qjs)
            bindFetch(qjs, extId)
            bindStorage(qjs, extId)
            bindFile(qjs, extId)
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
            if (raw.startsWith("{") && raw.contains("\"error\"")) {
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
                qjs.evaluate<Unit>(
                    "var __ext_dl_res = undefined;" +
                        "(async function(){" +
                        "  try { __ext_dl_res = JSON.stringify(await globalThis.__ext.download(" +
                        "    ${JSONObject.quote(trackId)}, ${JSONObject.quote(quality)}, '$CAPTURE', null)); }" +
                        "  catch(e){ __ext_dl_res = JSON.stringify({error: e && e.message ? e.message : String(e)}); }" +
                        "})();",
                )
                val raw = qjs.evaluate<String>("__ext_dl_res")
                if (raw.isBlank() || raw == "undefined") {
                    throw IllegalStateException("extension download returned no stream url")
                }
                if (raw.startsWith("{") && raw.contains("\"error\"")) {
                    throw IllegalStateException(parseError(raw))
                }
                extractUrl(raw)
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

    private fun bindFetch(qjs: QuickJs, extId: String) {
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
                        // Stream-capture mode: hand the URL back instead of writing.
                        // The extension's download() is expected to return this so
                        // captureStreamUrl can read it without re-entering the VM.
                        return finalUrl
                    }
                    downloadToFile(finalUrl, path)
                    return path
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
                runCatching { JSONObject(t).optString("url", "") }.getOrDefault("")
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("extension download returned an object without a url")
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
