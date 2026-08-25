package com.music.bitchord.data.spotify

import android.util.LruCache
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify→YouTube Music track matching, mirroring Meld's algorithm:
 * score = 0.45*title bigrams + 0.35*artist bigrams + 0.20*duration similarity,
 * minus a penalty for non-studio variants (live/karaoke/sped-up/…) present in the
 * candidate but not the Spotify title. Accept ≥ 0.35, early-exit ≥ 0.95.
 * Matches are cached in an in-process LRU keyed by Spotify track id.
 */
object SpotifyMapper {

    private const val MIN_MATCH_THRESHOLD = 0.35
    private const val EARLY_EXIT_THRESHOLD = 0.95
    private const val VARIANT_PENALTY_PER_MARKER = 0.15
    private const val MAX_VARIANT_PENALTY = 0.30

    private val VARIANT_MARKER_REGEX = Regex(
        "\\b(live|en vivo|en directo|ao vivo|karaoke|cover|instrumental|" +
            "sped up|spedup|slowed|nightcore|8d|music video|official video|lyric video)\\b"
    )

    private val matchCache = LruCache<String, Song>(512)

    fun clearCache() = matchCache.evictAll()

    /**
     * Resolves a Spotify track to a playable BitChord [Song] by searching YT Music.
     * Returns null when nothing scores above the threshold.
     */
    suspend fun resolve(track: SpotifyClient.SpotifyTrack): Song? = withContext(Dispatchers.IO) {
        matchCache.get(track.id)?.let { return@withContext it }

        val response = Innertube.search("${track.artist} ${track.title}")
        val candidates = InnertubeParser.parseSearchSongs(response)
        if (candidates.isEmpty()) return@withContext null

        var best: Song? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val score = matchScore(track, candidate) - variantPenalty(track.title, candidate.title)
            if (score > bestScore) {
                bestScore = score
                best = candidate
                if (score >= EARLY_EXIT_THRESHOLD) break
            }
        }
        // Threshold check uses the raw score so a live-only track still resolves,
        // it's just deprioritized while a studio version exists.
        best?.takeIf { matchScore(track, it) >= MIN_MATCH_THRESHOLD }?.also { matchCache.put(track.id, it) }
    }

    /** Meld scoring: weighted bigram similarities plus duration bucket. */
    fun matchScore(track: SpotifyClient.SpotifyTrack, candidate: Song): Double {
        val titleSim = bigramSimilarity(track.title, candidate.title)
        val artistSim = bigramSimilarity(track.artist, candidate.artist)
        val durationSim = durationSimilarity(
            track.durationMs / 1000,
            parseDurationSeconds(candidate.durationText),
        )
        return 0.45 * titleSim + 0.35 * artistSim + 0.20 * durationSim
    }

    private fun durationSimilarity(aSec: Long, bSec: Long?): Double {
        if (bSec == null || bSec <= 0 || aSec <= 0) return 0.5 // unknown duration → neutral
        val diff = Math.abs(aSec - bSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.8
            diff <= 10 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    /** Penalty only for markers in the candidate that the Spotify title lacks. */
    private fun variantPenalty(spotifyTitle: String, candidateTitle: String): Double {
        val candMarkers = VARIANT_MARKER_REGEX.findAll(candidateTitle.lowercase()).map { it.value }.toSet()
        if (candMarkers.isEmpty()) return 0.0
        val spotifyMarkers = VARIANT_MARKER_REGEX.findAll(spotifyTitle.lowercase()).map { it.value }.toSet()
        val extra = candMarkers - spotifyMarkers
        return (extra.size * VARIANT_PENALTY_PER_MARKER).coerceAtMost(MAX_VARIANT_PENALTY)
    }

    private fun bigramSimilarity(a: String, b: String): Double {
        val aBigrams = bigrams(normalize(a))
        val bBigrams = bigrams(normalize(b))
        if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
        val common = aBigrams.intersect(bBigrams).size.toDouble()
        return 2.0 * common / (aBigrams.size + bBigrams.size)
    }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()

    private fun bigrams(s: String): Set<String> {
        val cleaned = s.replace(" ", "")
        if (cleaned.length < 2) return setOf(cleaned)
        return (0 until cleaned.length - 1).map { cleaned.substring(it, it + 2) }.toSet()
    }

    private fun parseDurationSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> null
        }
    }
}
