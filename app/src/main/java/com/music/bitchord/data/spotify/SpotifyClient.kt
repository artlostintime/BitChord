package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import com.music.bitchord.data.TrackLog
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

    // Tolerant parsing: Spotify's GQL schema drifts; lenient + coerce keeps us
    // from choking on a field we don't model (mirrors Meld's Json config).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    /** GraphQL hard-caps persisted-query pages at 100; larger values error out. */
    private const val PAGE_LIMIT = 100
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // Persisted-query hashes ported from Meld's SpotifyHashProvider (known-good,
    // sourced from the sonic-liberation/hetu_spotify_gql_client registry). Spotify
    // rotates these — if requests start failing with PersistedQueryNotFound,
    // refresh from Meld's remote registry:
    // https://francescograzioso.github.io/Meld/spotify-gql-hashes.json
    private val HASHES = mapOf(
        "searchDesktop" to "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
        "fetchPlaylist" to "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        // Replaced the previous runtime-unverified hash (390c78e5…) with Meld's
        // verified libraryV3 hash — the old one was the root cause of the empty
        // "No playlists found" result.
        "libraryV3" to "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
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
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    TrackLog.w("SpotifyClient", "gql $operation HTTP ${response.code}: ${text.take(500)}")
                    throw SpotifyAuth.SpotifyException(response.code, "$operation HTTP ${response.code}: $text")
                }
                val root = json.parseToJsonElement(text).jsonObject
                root["errors"]?.takeIf { it !is JsonNull }?.jsonArray?.let { errs ->
                    if (errs.isNotEmpty()) {
                        TrackLog.w("SpotifyClient", "gql $operation errors: ${errs.toString().take(400)}")
                    }
                }
                val data = root["data"]
                if (data == null || data is JsonNull) {
                    // PersistedQueryNotFound / auth errors arrive as HTTP 200 with
                    // data:null — surface the raw body so the failure is diagnosable.
                    TrackLog.w("SpotifyClient", "gql $operation: no data in response: ${text.take(500)}")
                    throw SpotifyAuth.SpotifyException(412, "$operation: no data in GQL response (hash may be stale): $text")
                }
                data.jsonObject
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
        // Meld uses `playlistV2` (not `playlist`); tolerate either key.
        val root = data["playlistV2"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: data["playlist"]?.takeIf { it !is JsonNull }?.jsonObject
        val items = root?.get("content")?.takeIf { it !is JsonNull }?.jsonObject
            ?.get("items")?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val wrapper = item.jsonObject["itemV2"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: return@mapNotNull null
            // Meld: `itemV2.data` holds the track; fall back to the wrapper itself.
            val track = wrapper["data"]?.takeIf { it !is JsonNull }?.jsonObject ?: wrapper
            if (track["__typename"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content != "Track") {
                return@mapNotNull null
            }
            val artists = track["artists"]?.takeIf { it !is JsonNull }?.jsonObject
                ?.get("items")?.takeIf { it !is JsonNull }?.jsonArray
                ?.joinToString(", ") {
                    it.jsonObject["profile"]?.takeIf { p -> p !is JsonNull }?.jsonObject
                        ?.get("name")?.takeIf { n -> n !is JsonNull }?.jsonPrimitive?.content ?: ""
                }
                .orEmpty()
            // URI lives on the wrapper (`_uri`/`uri`) in Meld; fall back to data.uri.
            val uri = wrapper["_uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: wrapper["uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: track["uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: return@mapNotNull null
            SpotifyTrack(
                id = uri.removePrefix("spotify:track:"),
                title = track["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty(),
                artist = artists,
                albumName = track["albumOfTrack"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("name")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                durationMs = track["duration"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("totalMilliseconds")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
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

    /**
     * First track of a playlist, for seeding a radio without fetching the whole
     * list. Reuses [gql]/[parsePlaylistTracks] with a page size of one.
     */
    suspend fun playlistFirstTrack(spDc: String, playlistId: String): SpotifyTrack? {
        val data = gql(
            spDc, "fetchPlaylist",
            buildJsonObject {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", 0)
                put("limit", 1)
                put("enableWatchFeedEntrypoint", false)
            },
        )
        return parsePlaylistTracks(data).firstOrNull()
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
        val items = data["me"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.get("library")?.takeIf { it !is JsonNull }?.jsonObject
            ?.get("tracks")?.takeIf { it !is JsonNull }?.jsonObject
            ?.get("items")?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val wrapper = item.jsonObject["track"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: return@mapNotNull null
            // Meld: `track` is a wrapper whose `data` holds the actual track object.
            // Fall back to the wrapper itself if Spotify returns it unwrapped.
            val track = wrapper["data"]?.takeIf { it !is JsonNull }?.jsonObject ?: wrapper
            val artists = track["artists"]?.takeIf { it !is JsonNull }?.jsonObject
                ?.get("items")?.takeIf { it !is JsonNull }?.jsonArray
                ?.joinToString(", ") {
                    it.jsonObject["profile"]?.takeIf { p -> p !is JsonNull }?.jsonObject
                        ?.get("name")?.takeIf { n -> n !is JsonNull }?.jsonPrimitive?.content ?: ""
                }
                .orEmpty()
            // URI lives on the wrapper (`_uri`/`uri`) in Meld; fall back to data.uri.
            val uri = wrapper["_uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: wrapper["uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: track["uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: return@mapNotNull null
            SpotifyTrack(
                id = uri.removePrefix("spotify:track:"),
                title = track["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty(),
                artist = artists,
                albumName = track["album"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("name")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                durationMs = track["duration"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("totalMilliseconds")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
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
                    // Variables mirror Meld's working myPlaylists request.
                    put("filters", buildJsonArray { add(JsonPrimitive("Playlists")) })
                    put("order", null as String?)
                    put("textFilter", "")
                    put("features", buildJsonArray {
                        add(JsonPrimitive("LIKED_SONGS"))
                        add(JsonPrimitive("YOUR_EPISODES_V2"))
                        add(JsonPrimitive("PRERELEASES"))
                        add(JsonPrimitive("EVENTS"))
                    })
                    put("limit", PAGE_LIMIT)
                    put("offset", offset)
                    put("flatten", true)
                    put("expandedFolders", buildJsonArray {})
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", false)
                },
            )
            val page = parsePlaylists(data)
            all += page
            if (page.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
        }
        // ponytail: libraryV3 + flatten can return the same playlist more than
        // once (folder copy + flat copy); dedupe by id so LazyColumn keys stay
        // unique and scrolling can't hit a key-collision crash.
        return all.distinctBy { it.id }
    }

    private fun parsePlaylists(data: JsonObject): List<SpotifyPlaylist> {
        // Path mirrors Meld's verified libraryV3 parse:
        //   data.me.libraryV3.items[].item (wrapper)
        //     -> wrapper.data (Playlist), wrapper._uri for the id.
        // Tolerant fallbacks keep us alive if Spotify reshuffles the wrapper key
        // or drops the `me` segment.
        val me = data["me"]?.takeIf { it !is JsonNull }?.jsonObject
        val library = (me ?: data)["libraryV3"]?.takeIf { it !is JsonNull }?.jsonObject
        val items = library?.get("items")?.takeIf { it !is JsonNull }?.jsonArray
        if (items == null) {
            TrackLog.w(
                "SpotifyClient",
                "parsePlaylists: no items; dataKeys=${data.keys}, me?=${me != null}, " +
                    "libraryV3?=${library != null}",
            )
            return emptyList()
        }
        return items.mapNotNull { itemElem ->
            val wrapper = itemElem.jsonObject["item"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: itemElem.jsonObject["itemV2"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: return@mapNotNull null
            val typeName = wrapper["__typename"]?.takeIf { it !is JsonNull }
                ?.jsonPrimitive?.content.orEmpty()
            // Accept PlaylistResponseWrapper and any *Playlist* typename
            // (collaborative playlists historically surfaced under variants).
            if (typeName != "PlaylistResponseWrapper" &&
                !typeName.contains("Playlist", ignoreCase = true)
            ) return@mapNotNull null
            val pl = wrapper["data"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: return@mapNotNull null
            if (pl["__typename"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content != "Playlist") {
                return@mapNotNull null
            }
            val uri = wrapper["_uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: pl["uri"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: return@mapNotNull null
            // Tolerant name extraction: try the obvious spots, then walk the
            // wrapper recursively for the first "name" string as a last resort
            // (Spotify has surfaced playlists whose name sits one level deeper
            // than the parser expected). Log the raw item once when even that
            // fails, so the next shape drift is diagnosable.
            val name = pl["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: wrapper["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: findFirstString(itemElem, "name")
                ?: ""
            if (name.isBlank()) {
                TrackLog.w(
                    "SpotifyClient",
                    "parsePlaylists: empty name for uri=$uri; raw=${itemElem.toString().take(400)}",
                )
            }
            SpotifyPlaylist(
                id = uri.removePrefix("spotify:playlist:"),
                name = name,
                trackCount = pl["tracks"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("totalCount")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?.toIntOrNull() ?: 0,
                imageUrl = parsePlaylistImage(pl),
            )
        }
        }

    /**
     * Recursively searches [element] for the first string value keyed "name".
     * Used as a last-resort fallback when a playlist's name isn't where the
     * parser expects — Spotify's GQL shape drifts between account types.
     */
    private fun findFirstString(element: JsonElement, key: String): String? {
        return when (element) {
            is JsonObject -> {
                element[key]?.let { v ->
                    if (v is JsonPrimitive && v.isString) return v.content
                }
                for ((k, v) in element) {
                    if (k == key) continue
                    findFirstString(v, key)?.let { return it }
                }
                null
            }
            is JsonArray -> {
                for (v in element) findFirstString(v, key)?.let { return it }
                null
            }
            else -> null
        }
    }

    /**
     * Extracts a playlist cover URL from the GQL `images` field.
     * Current Web Player shape: `images.items[].sources[].url`. Older/flat
     * shape `images[].url` is accepted as a fallback.
     */
    private fun parsePlaylistImage(pl: JsonObject): String? {
        val imagesElem = pl["images"]?.takeIf { it !is JsonNull } ?: return null
        (imagesElem as? JsonObject)?.get("items")?.takeIf { it !is JsonNull }?.jsonArray?.let { groups ->
            for (group in groups) {
                val url = group.jsonObject["sources"]?.takeIf { it !is JsonNull }?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("url")
                    ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                if (!url.isNullOrEmpty()) return url
            }
        }
        (imagesElem as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { return it }
        return null
    }
}
