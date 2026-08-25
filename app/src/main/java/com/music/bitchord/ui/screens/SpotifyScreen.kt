package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.spotify.SpotifyClient
import com.music.bitchord.data.spotify.SpotifyMapper
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRowSkeleton
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.thumbnailBorder

/**
 * Spotify recommendations: on open (with a valid sp_dc) it auto-loads the
 * account's liked songs AND every playlist, mirroring Google-sign-in behavior —
 * no pasting URLs. Tapping a playlist drills into its tracks (resolved to
 * YouTube Music via [SpotifyMapper] and playable like the liked-songs queue).
 * Manual URL import is kept as a small secondary action at the bottom.
 */
@Composable
fun SpotifyScreen(
    spDc: String?,
    onOpenLogin: () -> Unit,
    onSignOut: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (spDc.isNullOrBlank()) {
        Column(
            modifier = modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect your Spotify account", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your liked songs and playlists become a playable queue here, matched to YouTube Music.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenLogin) { Text("Sign in with Spotify") }
        }
        return
    }

    var likedLoading by remember { mutableStateOf(true) }
    var likedError by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }

    var playlistsLoading by remember { mutableStateOf(true) }
    var playlistsError by remember { mutableStateOf<String?>(null) }
    var playlists by remember { mutableStateOf<List<SpotifyClient.SpotifyPlaylist>>(emptyList()) }

    var selectedPlaylist by remember { mutableStateOf<SpotifyClient.SpotifyPlaylist?>(null) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var playlistSongs by remember { mutableStateOf<List<Song>?>(null) }

    var playlistUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0) }
    var importTotal by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importedSongs by remember { mutableStateOf<List<Song>?>(null) }

    var resolveProgress by remember { mutableStateOf(0) }
    var resolveTotal by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()

    // ponytail: scroll-linked collapse of the large title, mirroring Home's
    // firstVisibleItem hand-off to the top bar.
    val listState = rememberLazyListState()
    val titleCollapsed by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 24
        }
    }

    fun openPlaylist(playlist: SpotifyClient.SpotifyPlaylist) {
        selectedPlaylist = playlist
        playlistSongs = null
        playlistError = null
        playlistLoading = true
        resolveProgress = 0
        resolveTotal = 0
        scope.launch {
            runCatching {
                val tracks = SpotifyClient.playlistTracks(spDc, playlist.id)
                resolveTotal = tracks.size
                // ponytail: sequential resolve through the shared LRU cache;
                // parallel would hammer Innertube. No ISRC via sp_dc API — fuzzy only.
                val resolved = mutableListOf<Song>()
                tracks.forEachIndexed { i, track ->
                    SpotifyMapper.resolve(track)?.let { resolved += it }
                    resolveProgress = i + 1
                }
                resolved
            }.onSuccess { playlistSongs = it }
                .onFailure { playlistError = it.message ?: "Failed to load playlist" }
            playlistLoading = false
        }
    }

    fun importPlaylist() {
        val id = parsePlaylistId(playlistUrl) ?: run {
            importError = "Paste a Spotify playlist URL or ID"
            return
        }
        scope.launch {
            importing = true
            importError = null
            importedSongs = null
            importProgress = 0
            importTotal = 0
            runCatching {
                val tracks = SpotifyClient.playlistTracks(spDc, id)
                importTotal = tracks.size
                // ponytail: sequential resolve through the shared LRU cache;
                // parallel would hammer Innertube. No ISRC via sp_dc API — fuzzy only.
                val resolved = mutableListOf<Song>()
                tracks.forEachIndexed { i, track ->
                    SpotifyMapper.resolve(track)?.let { resolved += it }
                    importProgress = i + 1
                }
                resolved
            }.onSuccess { importedSongs = it }
                .onFailure { importError = it.message ?: "Import failed" }
            importing = false
        }
    }

    LaunchedEffect(spDc) {
        // Reset drill-down state on (re)sign-in.
        selectedPlaylist = null
        playlistSongs = null
        playlistError = null

        // Liked songs
        runCatching {
            val tracks = SpotifyClient.likedSongs(spDc)
            // Resolve sequentially through the shared LRU cache — parallel
            // searches hammer Innertube and most repeat plays are cache hits.
            tracks.mapNotNull { SpotifyMapper.resolve(it) }
        }.onSuccess {
            songs = it
            likedError = null
        }.onFailure {
            likedError = it.message ?: "Failed to load"
        }
        likedLoading = false

        // User playlists
        runCatching { SpotifyClient.userPlaylists(spDc) }
            .onSuccess {
                playlists = it
                playlistsError = null
            }
            .onFailure {
                playlistsError = it.message ?: "Failed to load playlists"
            }
        playlistsLoading = false
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your Spotify Library",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .graphicsLayer {
                            alpha = if (titleCollapsed) 0f else 1f
                            translationY = if (titleCollapsed) -24f else 0f
                        },
                )
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }

        // ---- Your Playlists ----
        item {
            Text(
                "Your Playlists",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 8.dp, bottom = 4.dp),
            )
        }
        when {
            playlistsLoading -> item {
                SongRowSkeleton()
            }
            playlistsError != null -> item {
                MessageState(message = playlistsError!!)
            }
            playlists.isEmpty() -> item {
                MessageState(message = "No playlists found on this account.")
            }
            else -> items(playlists.size, key = { index -> "${playlists[index].id}#$index" }) { index ->
                val playlist = playlists.getOrNull(index) ?: return@items
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openPlaylist(playlist) }
                        .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    playlist.imageUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).thumbnailBorder(RoundedCornerShape(6.dp)),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${playlist.trackCount} track(s)",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ---- Selected playlist tracks (drill-down) ----
        if (selectedPlaylist != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tracks · ${selectedPlaylist!!.name}",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(onClick = {
                        selectedPlaylist = null
                        playlistSongs = null
                        playlistError = null
                    }) { Text("Back") }
                }
            }
            when {
                playlistLoading -> item {
                    Text(
                        if (resolveTotal == 0) "Resolving playlist…"
                        else "Resolving $resolveProgress/$resolveTotal…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(PAGE_GUTTER),
                    )
                }
                playlistError != null -> item {
                    MessageState(message = playlistError!!)
                }
                playlistSongs.isNullOrEmpty() -> item {
                    MessageState(message = "No tracks resolved for this playlist.")
                }
                else -> {
                    val resolved = playlistSongs!!
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Resolved ${resolved.size} track(s)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (resolved.isNotEmpty()) {
                                Button(onClick = { onPlay(resolved, 0) }) { Text("Play") }
                            }
                        }
                    }
                    items(resolved.size, key = { index -> "${resolved[index].videoId}#$index" }) { index ->
                        val song = resolved.getOrNull(index) ?: return@items
                        SpotifyTrackRow(song, onClick = { onPlay(resolved, index) })
                    }
                }
            }
        }

        // ---- Liked from Spotify ----
        item {
            Text(
                "Liked from Spotify",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            likedLoading -> item {
                SongRowSkeleton()
            }
            likedError != null -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(PAGE_GUTTER),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageState(message = likedError!!)
                    Button(onClick = onOpenLogin) { Text("Sign in again") }
                }
            }
            songs.isEmpty() -> item {
                MessageState(message = "No liked songs found on this account.")
            }
            else -> items(songs.size, key = { index -> "${songs[index].videoId}#$index" }) { index ->
                val song = songs.getOrNull(index) ?: return@items
                SpotifyTrackRow(song, onClick = { onPlay(songs, index) })
            }
        }

        // ---- Manual URL import (demoted secondary action) ----
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Or import a playlist by URL", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = playlistUrl,
                        onValueChange = { playlistUrl = it },
                        placeholder = { Text("Spotify playlist URL or ID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !importing,
                    )
                    Button(onClick = { importPlaylist() }, enabled = !importing) {
                        Text("Import")
                    }
                }
                when {
                    importing -> Text(
                        "Resolving ${importProgress}/${importTotal}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    importError != null -> Text(
                        importError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    importedSongs != null -> {
                        val resolved = importedSongs!!
                        val failed = importTotal - resolved.size
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Resolved ${resolved.size} track(s)" +
                                    if (failed > 0) " · $failed not found" else "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (resolved.isNotEmpty()) {
                                Button(onClick = { onPlay(resolved, 0) }) { Text("Play") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared row for a resolved [Song] used by both the liked-songs list and the
 * drilled-in playlist track list. Reuses the existing artwork + typography style.
 */
@Composable
private fun SpotifyTrackRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = song.artworkAt(160),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).thumbnailBorder(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(song.durationText ?: "", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Pulls a Spotify playlist id out of a pasted URL or bare id. Handles
 * `open.spotify.com/playlist/<id>` and `spotify:playlist:<id>`; a bare base62
 * token is accepted as-is. Returns null when nothing usable is found.
 */
private val PLAYLIST_ID_REGEX = Regex(
    "(?:spotify:playlist:|open\\.spotify\\.com/playlist/)([A-Za-z0-9]+)",
    RegexOption.IGNORE_CASE,
)

private fun parsePlaylistId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    PLAYLIST_ID_REGEX.find(trimmed)?.groupValues?.get(1)?.let { return it }
    if (trimmed.matches(Regex("[A-Za-z0-9]+"))) return trimmed
    return null
}
