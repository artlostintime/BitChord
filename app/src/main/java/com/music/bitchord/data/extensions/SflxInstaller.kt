package com.music.bitchord.data.extensions

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.extensions.ExtensionRegistryClient.RegistryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs and removes SpotiFLAC `.sflx` extensions.
 *
 * An `.sflx` is a ZIP of `manifest.json` + `index.js`. Install downloads the
 * archive, verifies its SHA-256 against the registry entry, unzips it into
 * `filesDir/extensions/<id>/`, records the installed version, and registers a
 * [SourceConfig] of kind [SourceKind.EXTENSION] so [SourceRegistry] builds an
 * [ExtensionSource] for it. Uninstall reverses all of that.
 */
object SflxInstaller {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_extensions"
    private const val KEY_INSTALLED = "installed"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parsed `manifest.json` of an installed extension. */
    @Serializable
    data class ExtensionManifest(
        val name: String = "",
        val displayName: String = "",
        val version: String = "",
        val type: List<String> = emptyList(),
        val permissions: Permissions = Permissions(),
        val requiredRuntimeFeatures: List<String> = emptyList(),
        val qualityOptions: List<String> = emptyList(),
    ) {
        @Serializable
        data class Permissions(val network: List<String> = emptyList())
    }

    /** What we persist per installed extension. */
    @Serializable
    data class Installed(
        val id: String,
        val version: String,
        val displayName: String,
    )

    /** Where an extension's files live once unzipped. */
    fun installDir(context: Context, id: String): File =
        File(context.filesDir, "extensions/$id")

    /** Currently installed extensions, keyed by id. */
    fun installed(context: Context): Map<String, Installed> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Installed>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun recordInstalled(context: Context, map: Map<String, Installed>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INSTALLED, json.encodeToString<Map<String, Installed>>(map)).apply()
    }

    /**
     * Downloads, verifies and installs [entry].
     *
     * SHA-256 is checked when the registry supplied one; a missing digest is
     * treated as "no integrity guarantee" rather than a failure, because some
     * registries publish entries without it.
     */
    suspend fun install(context: Context, entry: RegistryEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Http.client.newCall(
                    Request.Builder().url(entry.download_url).build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} downloading ${entry.id}")
                    resp.body?.bytes() ?: throw Exception("empty download for ${entry.id}")
                }

                if (entry.sha256.isNotBlank()) {
                    val digest = bytes.sha256Hex()
                    if (!entry.sha256.equals(digest, ignoreCase = true)) {
                        throw SecurityException(
                            "sha256 mismatch for ${entry.id}: expected ${entry.sha256}, got $digest",
                        )
                    }
                }

                val dir = installDir(context, entry.id)
                dir.deleteRecursively()
                dir.mkdirs()
                unzip(bytes, dir)

                val manifest = readManifest(dir)
                // ponytail: no signature verification beyond sha256; a malicious
                // registry operator who can also swap the bytes defeats it. Add
                // GPG/cosign verification if extensions become untrusted.
                // signedSession@1 is satisfied by the ZarzSession binding; only
                // genuinely unknown features block installation.
                val unsupported = manifest.requiredRuntimeFeatures.filter { it != "signedSession@1" }
                if (unsupported.isNotEmpty()) {
                    throw UnsupportedOperationException(
                        "extension ${entry.id} requires runtime feature ${unsupported.first()}, " +
                            "which this build does not implement",
                    )
                }

                val label = manifest.displayName.ifBlank { entry.display_name.ifBlank { entry.name } }
                val map = installed(context).toMutableMap()
                map[entry.id] = Installed(entry.id, entry.version.ifBlank { manifest.version }, label)
                recordInstalled(context, map)

                SourceRegistry.add(
                    SourceConfig(kind = SourceKind.EXTENSION, id = entry.id, label = label, enabled = true),
                )
                TrackLog.i(TAG, "Installed extension ${entry.id} v${entry.version}")
            }.onFailure { TrackLog.e(TAG, "install failed for ${entry.id}: ${it.message}") }
        }

    /** Deletes an extension's files, forgets it, and drops its source config. */
    suspend fun uninstall(context: Context, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                installDir(context, id).deleteRecursively()
                val map = installed(context).toMutableMap().also { it.remove(id) }
                recordInstalled(context, map)
                SourceRegistry.remove(id)
                ExtensionJs.unload(id)
                TrackLog.i(TAG, "Uninstalled extension $id")
            }.onFailure { TrackLog.e(TAG, "uninstall failed for $id: ${it.message}") }
        }

    /** Reads `manifest.json` from an installed extension's directory. */
    fun readManifest(dir: File): ExtensionManifest {
        val f = File(dir, "manifest.json")
        if (!f.exists()) return ExtensionManifest()
        return runCatching { json.decodeFromString<ExtensionManifest>(f.readText()) }
            .getOrDefault(ExtensionManifest())
    }

    // ── ZIP ────────────────────────────────────────────────────────────────

    private fun unzip(bytes: ByteArray, dir: File) {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // ponytail: minimal traversal guard only; zip-bomb / symlink
                // checks skipped — extensions come from a user-chosen registry.
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw SecurityException("unsafe zip entry: $name")
                }
                val out = File(dir, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }
}
