package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal Spotify client for recommendations: internal-token management plus the
 * three GraphQL persisted queries we need — search, playlist tracks, liked songs.
 * Mirrors Meld's pathfinder API usage (api-partner.spotify.com).
 */
object SpotifyClient {

    data class SpotifyTrack(
        val id: String,
        val title: String,
        val artist: String,
        val albumName: String?,
        val durationMs: Long,
    )

    data class SpotifyPlaylist(
        val id: String,
        val name: String,
        val trackCount: Int,
        val imageUrl: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    /** GraphQL hard-caps persisted-query pages at 100; larger values error out. */
    private const val PAGE_LIMIT = 100
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // Persisted-query hashes (from Meld; Spotify rotates these — if requests start
    // failing with PersistedQueryNotFound, refresh from Meld's remote registry:
    // https://francescograzioso.github.io/Meld/spotify-gql-hashes.json)
    private val HASHES = mapOf(
        "searchDesktop" to "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
        "fetchPlaylist" to "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
    )

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0

    private val tokenMutex = Mutex()

    /** Called on sign-out so the next call re-authenticates. */
    fun reset() {
        token = null
        tokenExpiresAt = 0
    }

    private suspend fun accessToken(spDc: String): String {
        val cached = token
        if (cached != null && System.currentTimeMillis() < tokenExpiresAt) return cached
        return tokenMutex.withLock {
            val again = token
            if (again != null && System.currentTimeMillis() < tokenExpiresAt) {
                again
            } else {
                val fresh = SpotifyAuth.fetchAccessToken(spDc)
                token = fresh.accessToken
                // Refresh 5 min early to avoid mid-request expiry.
                tokenExpiresAt = System.currentTimeMillis() + (fresh.expiresInSeconds - 300) * 1000
                fresh.accessToken
            }
        }
    }

    private suspend fun gql(spDc: String, operation: String, variables: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("operationName", operation)
                put("variables", variables)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", HASHES.getValue(operation))
                    }
                }
            }.toString()
            val request = Request.Builder()
                .url(GQL_URL)
                .header("Authorization", "Bearer ${accessToken(spDc)}")
                .header("User-Agent", USER_AGENT)
                .header("app-platform", "WebPlayer")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SpotifyAuth.SpotifyException(response.code, "$operation HTTP ${response.code}: $text")
                }
                json.parseToJsonElement(text).jsonObject["data"]!!.jsonObject
            }
        }

    private fun parseTracks(data: JsonObject): List<SpotifyTrack> {
        val items = data.jsonObject["searchV2"]?.jsonObject?.get("results")?.jsonObject
            ?.get("items")?.jsonArray
            ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item.jsonObject["item"]?.jsonObject ?: return@mapNotNull null
            if (obj["__typename"]?.jsonPrimitive?.content != "Track") return@mapNotNull null
            val artists = obj["artists"]?.jsonObject?.get("items")?.jsonArray
                ?.joinToString(", ") { it.jsonObject["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                .orEmpty()
            SpotifyTrack(
                id = obj["uri"]?.jsonPrimitive?.content?.removePrefix("spotify:track:") ?: return@mapNotNull null,
                title = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                artist = artists,
                albumName = obj["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                durationMs = obj["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun search(spDc: String, query: String, limit: Int = 10): List<SpotifyTrack> {
        val data = gql(
            spDc, "searchDesktop",
            buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistConcerts", false)
                put("includePreReleases", false)
                put("includeUserQuery", false)
                put("includeWantedBooksAndDocuments", false)
                put("catalogFeatures", "ALBUMS_EXPANDED")
            },
        )
        return parseTracks(data)
    }

    private fun parsePlaylistTracks(data: JsonObject): List<SpotifyTrack> {
        val items = data.jsonObject["playlist"]?.jsonObject?.get("content")?.jsonObject
            ?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val track = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject
                ?: return@mapNotNull null
            if (track["__typename"]?.jsonPrimitive?.content != "Track") return@mapNotNull null
            val artists = track["artists"]?.jsonObject?.get("items")?.jsonArray
                ?.joinToString(", ") { it.jsonObject["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                .orEmpty()
            SpotifyTrack(
                id = track["uri"]?.jsonPrimitive?.content?.removePrefix("spotify:track:") ?: return@mapNotNull null,
                title = track["name"]?.jsonPrimitive?.content.orEmpty(),
                artist = artists,
                albumName = track["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                durationMs = track["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun playlistTracks(spDc: String, playlistId: String): List<SpotifyTrack> {
        val all = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val data = gql(
                spDc, "fetchPlaylist",
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset)
                    put("limit", PAGE_LIMIT)
                    put("enableWatchFeedEntrypoint", false)
                },
            )
            val page = parsePlaylistTracks(data)
            all += page
            if (page.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
        }
        return all
    }

    suspend fun likedSongs(spDc: String): List<SpotifyTrack> {
        val all = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val data = gql(
                spDc, "fetchLibraryTracks",
                buildJsonObject {
                    put("offset", offset)
                    put("limit", PAGE_LIMIT)
                },
            )
            val page = parseLikedPage(data)
            all += page
            if (page.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
        }
        return all
    }

    private fun parseLikedPage(data: JsonObject): List<SpotifyTrack> {
        val items = data.jsonObject["me"]?.jsonObject?.get("library")?.jsonObject
            ?.get("tracks")?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val track = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
            val artists = track["artists"]?.jsonObject?.get("items")?.jsonArray
                ?.joinToString(", ") { it.jsonObject["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                .orEmpty()
            SpotifyTrack(
                id = track["uri"]?.jsonPrimitive?.content?.removePrefix("spotify:track:") ?: return@mapNotNull null,
                title = track["name"]?.jsonPrimitive?.content.orEmpty(),
                artist = artists,
                albumName = track["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                durationMs = track["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    /**
     * Signed-in user's playlists via the same sp_dc GraphQL persisted-query
     * mechanism as [likedSongs]. `libraryV3` with the `Playlists` filter is the
     * current Web Player operation that returns the account's playlists.
     */
    suspend fun userPlaylists(spDc: String): List<SpotifyPlaylist> {
        val all = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val data = gql(
                spDc, "libraryV3",
                buildJsonObject {
                    put("filters", buildJsonArray { add(JsonPrimitive("Playlists")) })
                    put("order", "DEFAULT")
                    put("textFilter", "")
                    put("offset", offset)
                    put("limit", PAGE_LIMIT)
                },
            )
            val page = parsePlaylists(data)
            all += page
            if (page.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
        }
        return all
    }

    private fun parsePlaylists(data: JsonObject): List<SpotifyPlaylist> {
        // ponytail: libraryV3 response shape derived from the public Web Player
        // schema (itemV2.data, tracks.totalCount, images[].url). Field paths need
        // runtime verification against a real account — adjust if Spotify changed it.
        val items = data.jsonObject["libraryV3"]?.jsonObject?.get("items")?.jsonArray
            ?: return emptyList()
        return items.mapNotNull { item ->
            val pl = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject
                ?: return@mapNotNull null
            if (pl["__typename"]?.jsonPrimitive?.content != "Playlist") return@mapNotNull null
            val uri = pl["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SpotifyPlaylist(
                id = uri.removePrefix("spotify:playlist:"),
                name = pl["name"]?.jsonPrimitive?.content.orEmpty(),
                trackCount = pl["tracks"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.content
                    ?.toIntOrNull() ?: 0,
                imageUrl = pl["images"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("url")?.jsonPrimitive?.content,
            )
        }
    }
}
