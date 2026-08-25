package com.music.bitchord.data.sources

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import java.io.File

/**
 * The device's own audio — MediaStore plus the app's downloads — played
 * straight from disk through [LocalMediaRepository].
 *
 * No network, no account, no module VM: a local FLAC is bit-exact, so this
 * source is lossless-capable and, placed high enough in the user's order,
 * outranks a lossy stream for the same recording. It is seeded as a built-in
 * like [YouTubeSource] and can only be disabled, not deleted.
 *
 * Search returns [Song]s whose [Song.videoId] is this source's
 * [trackKey][SourceRegistry.trackKey] wrapping the file's own URI, so a result
 * queued from here routes back to [stream] exactly as a module result does —
 * see [MusicSource.search].
 */
class LocalFilesSource(
    override val config: SourceConfig,
    private val appContext: Context,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.LOCAL
    override val displayName: String get() = config.displayName

    override suspend fun health(): SourceHealth {
        val count = allSongs().size
        return SourceHealth.Ok("$count local track${if (count == 1) "" else "s"}")
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return allSongs()
            .filter { song ->
                song.title.lowercase().contains(q) ||
                    song.artist.lowercase().contains(q) ||
                    song.albumName?.lowercase()?.contains(q) == true
            }
            .take(limit)
            .map { it.copy(videoId = SourceRegistry.trackKey(config.id, it.localUri ?: it.videoId)) }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        // A queued local track carries its file URI inside a trackKey; a bare
        // URI (e.g. from a direct pin) is used as-is.
        val uri = SourceRegistry.parseTrackKey(trackId)?.second ?: trackId
        if (uri.isBlank()) return null
        // Prefer a file:// path: containers that need backward seeking (m4a/aac)
        // play unreliably over content:// — see PlayerConnection.resolvePlaybackUri.
        val song = allSongs().firstOrNull { it.localUri == uri || it.videoId == uri }
        val playable = song?.localPath
            ?.takeIf { runCatching { File(it).exists() && File(it).canRead() }.getOrDefault(false) }
            ?.let { Uri.fromFile(File(it)).toString() }
            ?: uri
        return SourceStream(
            url = playable,
            format = StreamFormat(codec = codecFromUri(playable)),
            sourceLabel = displayName,
        )
    }

    /** Device library + downloads, rescanned at most once per [CACHE_TTL_MS]. */
    private var cache: Pair<Long, List<Song>>? = null
    private suspend fun allSongs(): List<Song> {
        val now = System.currentTimeMillis()
        val cached = cache
        if (cached != null && now - cached.first < CACHE_TTL_MS) return cached.second
        // ponytail: no MediaStore observer, so a file added or removed between
        // windows is invisible until the next miss. Lower TTL or register a
        // ContentObserver if freshness matters more than a periodic rescan.
        val songs = LocalMediaRepository.getLocalMusic(appContext) +
            LocalMediaRepository.getDownloadedSongs(appContext)
        cache = now to songs
        return songs
    }

    private fun codecFromUri(uri: String): String? {
        val ext = uri.substringBefore('?').substringAfterLast('.').lowercase()
        return CODEC_BY_EXT[ext]
    }

    private companion object {
        const val CACHE_TTL_MS = 30_000L
        val CODEC_BY_EXT = mapOf(
            "flac" to "flac", "wav" to "wav", "alac" to "alac",
            "aiff" to "aiff", "aif" to "aiff", "ape" to "ape", "wv" to "wv",
            "dsf" to "dsf", "dff" to "dsf",
            "mp3" to "mp3", "m4a" to "aac", "aac" to "aac", "mp4" to "aac",
            "ogg" to "ogg", "opus" to "opus", "webm" to "webm",
        )
    }
}
