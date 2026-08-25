package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * Spotify authentication without OAuth or a developer client ID, mirroring Meld:
 * the user logs in via WebView and we capture the `sp_dc` session cookie. That
 * cookie plus a TOTP (HMAC-SHA1 over Spotify server time, secret from a
 * community gist) unlocks the web player's internal token endpoint.
 */
object SpotifyAuth {

    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val NUANCE_GIST_URL =
        "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    class SpotifyException(val code: Int, message: String) : Exception(message)

    data class InternalToken(val accessToken: String, val expiresInSeconds: Long)

    /**
     * Fetches an internal web-player access token using sp_dc and TOTP.
     * Throws [SpotifyException] on network failure or an anonymous token.
     */
    suspend fun fetchAccessToken(spDc: String): InternalToken = withContext(Dispatchers.IO) {
        val nuance = fetchNuance()
        val serverTimeSec = fetchServerTime()
        val totp = generateTotp(nuance.first, serverTimeSec)

        val url = "$TOKEN_URL?reason=transport&productType=web-player" +
            "&totp=$totp&totpServer=$totp&totpVer=${nuance.second}"

        val body = httpGet(url, mapOf("Cookie" to "sp_dc=$spDc"))
        val obj = Json.parseToJsonElement(body).jsonObject
        val isAnonymous = obj["isAnonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val accessToken = obj["accessToken"]?.jsonPrimitive?.content.orEmpty()
        if (isAnonymous || accessToken.isBlank()) {
            throw SpotifyException(401, "Anonymous token — sp_dc invalid or expired")
        }
        val expires = obj["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        InternalToken(accessToken, expires)
    }

    /** Returns (secret, version) of the newest nuance entry in the community gist. */
    private fun fetchNuance(): Pair<String, Int> {
        val body = try {
            httpGet(NUANCE_GIST_URL, emptyMap())
        } catch (e: Exception) {
            throw SpotifyException(503, "Failed to fetch TOTP secret: ${e.message}")
        }
        val files = Json.parseToJsonElement(body).jsonObject["files"]!!.jsonObject
        val content = files.values.first().jsonObject["content"]!!.jsonPrimitive.content
        val nuances = Json.parseToJsonElement(content).jsonArray
            .map { it.jsonObject }
            .map { it["s"]!!.jsonPrimitive.content to it["v"]!!.jsonPrimitive.content.toInt() }
        return nuances.maxByOrNull { it.second }
            ?: throw SpotifyException(500, "No nuance data in gist")
    }

    private fun fetchServerTime(): Long {
        val body = try {
            httpGet(SERVER_TIME_URL, emptyMap())
        } catch (e: Exception) {
            throw SpotifyException(503, "Failed to fetch server time: ${e.message}")
        }
        return Json.parseToJsonElement(body).jsonObject["serverTime"]!!.jsonPrimitive.content.toLong()
    }

    /** RFC 6238-style 6-digit code, HMAC-SHA1 at 30s intervals over base32 secret. */
    internal fun generateTotp(secretBase32: String, serverTimeSec: Long): String {
        val key = base32Decode(secretBase32)
        val timeStep = floor(serverTimeSec / 30.0).toLong()
        val timeBytes = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()
        for (c in input.uppercase().replace("=", "")) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private fun httpGet(urlString: String, extraHeaders: Map<String, String>): String {
        val request = okhttp3.Request.Builder()
            .url(urlString)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SpotifyException(response.code, "HTTP ${response.code}: $body")
            return body
        }
    }
}
