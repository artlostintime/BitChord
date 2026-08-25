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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.spotify.SpotifyClient
import com.music.bitchord.data.spotify.SpotifyMapper

/**
 * Spotify recommendations: pulls the account's liked songs and maps each one to
 * a playable YouTube Music track via [SpotifyMapper] (Meld's fuzzy matcher).
 * Tapping a row starts playback of the mapped queue from that track.
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
                "Your liked songs become a playable queue here, matched to YouTube Music.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenLogin) { Text("Sign in with Spotify") }
        }
        return
    }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(spDc) {
        runCatching {
            val tracks = SpotifyClient.likedSongs(spDc)
            // Resolve sequentially through the shared LRU cache — parallel
            // searches hammer Innertube and most repeat plays are cache hits.
            tracks.mapNotNull { SpotifyMapper.resolve(it) }
        }.onSuccess {
            songs = it
            error = null
        }.onFailure {
            error = it.message ?: "Failed to load"
        }
        loading = false
    }

    when {
        loading -> Column(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Matching your liked songs…", style = MaterialTheme.typography.bodySmall)
        }
        error != null -> Column(
            modifier = modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = onOpenLogin) { Text("Sign in again") }
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Liked from Spotify",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
            items(songs.size, key = { songs[it].videoId }) { index ->
                val song = songs[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(songs, index) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = song.artworkAt(160),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
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
                    Text(song.durationText, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (songs.isEmpty()) {
                item {
                    Text(
                        "No liked songs found on this account.",
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}
