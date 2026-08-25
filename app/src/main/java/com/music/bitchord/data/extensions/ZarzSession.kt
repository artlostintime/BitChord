package com.music.bitchord.data.extensions

import com.music.bitchord.data.Http
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ-HMAC-V1 signed-session client.
 *
 * Implements the zarz gateway (api.zarz.moe) handshake so QuickJS extensions
 * can obtain a signed session and mint per-request tickets without shipping
 * their own crypto. Mirrors the contract the reference tidal `index.js`
 * expects: bootstrap -> exchange -> signedFetch/signedTicket.
 *
 * The session is cached in memory and established under a single-flight mutex
 * so concurrent extension calls share one handshake.
 */
internal object ZarzSession {

    private const val GATEWAY = "https://api.zarz.moe"
    private const val BOOTSTRAP_URL = "$GATEWAY/bootstrap"
    private const val EXCHANGE_URL = "$GATEWAY/exchange"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Active session, or null before the first successful handshake. */
    @Volatile
    private var session: Session? = null

    /** Single-flight guard so concurrent callers run one handshake. */
    private val mutex = Mutex()

    // ponytail: no session persistence across process restarts — the session
    // lives only in memory and is re-established on cold start. Add disk-backed
    // restore if handshake latency on launch becomes noticeable.
    // ponytail: no secret/key rotation handling — a gateway-rotated key is only
    // noticed on the next SESSION_INVALID. Add proactive rotation if enforced.
    // ponytail: refresh retries exactly once with no backoff. Add exponential
    // backoff if the gateway rate-limits handshakes under load.

    internal data class Session(val sessionId: String, val sessionKey: String)
    private data class Bootstrap(val sessionId: String, val challenge: String)

    // ── Public surface ───────────────────────────────────────────────────────

    /**
     * Returns the cached session, establishing one on first use. Safe for
     * concurrent callers (single-flight via [mutex]).
     */
    suspend fun ensureSession(): Result<Session> {
        session?.let { return Result.success(it) }
        return mutex.withLock {
            session?.let { return@withLock Result.success(it) }
            runCatching { establish() }.onSuccess { session = it }
        }
    }

    /**
     * Forces a fresh handshake, retrying once on SESSION_INVALID, then caches
     * the result. Used when a gateway request reports an invalid session.
     */
    suspend fun refresh(): Result<Session> {
        return mutex.withLock {
            runCatching { establish() }
                .recoverCatching { e ->
                    if (isSessionInvalid(e)) establish() else throw e
                }
                .onSuccess { session = it }
        }
    }

    /**
     * Performs a GET against [url] carrying the X-Zarz-Session header. On a
     * SESSION_INVALID response the session is refreshed once and the request
     * retried; any other failure surfaces as a failed [Result].
     */
    suspend fun signedFetch(url: String): Result<String> {
        return runCatching {
            val s = ensureSession().getOrThrow()
            fetchWith(s.sessionId, url)
        }.recoverCatching { e ->
            if (isSessionInvalid(e)) {
                val s2 = refresh().getOrThrow()
                fetchWith(s2.sessionId, url)
            } else {
                throw e
            }
        }
    }

    /**
     * Mints a per-request ticket for (provider, type, id) as
     * HMAC-SHA256(secret = current session key, message = "$provider:$type:$id")
     * encoded as lowercase hex. Throws [IllegalStateException] with code
     * ZARZ_NO_SESSION if no session has been established yet.
     */
    fun signedTicket(provider: String, type: String, id: String): String {
        val s = session ?: throw zarzError("ZARZ_NO_SESSION")
        return hmacSha256Hex(s.sessionKey, "$provider:$type:$id")
    }

    // ── Handshake ────────────────────────────────────────────────────────────

    private suspend fun establish(): Session {
        val boot = bootstrap()
        val key = exchange(boot)
        return Session(boot.sessionId, key)
    }

    private fun bootstrap(): Bootstrap {
        val req = Request.Builder().url(BOOTSTRAP_URL).get().build()
        val (code, body) = call(req)
        if (code !in 200..299) throw zarzError("ZARZ_BOOTSTRAP_FAILED", code.toString())
        val obj = runCatching { JSONObject(body) }.getOrElse {
            throw zarzError("ZARZ_BOOTSTRAP_FAILED", "bad json")
        }
        val sessionId = obj.optString("sessionId", "")
        val challenge = obj.optString("challenge", "")
        if (sessionId.isBlank() || challenge.isBlank()) {
            throw zarzError("ZARZ_BOOTSTRAP_FAILED", "missing fields")
        }
        return Bootstrap(sessionId, challenge)
    }

    private fun exchange(boot: Bootstrap): String {
        val proof = hmacSha256Hex(boot.challenge, boot.sessionId)
        val req = Request.Builder().url(EXCHANGE_URL)
            .header("X-Zarz-Session", boot.sessionId)
            .post("{}".toRequestBody(JSON))
            .build()
        val (code, body) = call(req)
        if (code !in 200..299) {
            if (body.contains("VERIFY_REQUIRED")) throw zarzError("ZARZ_VERIFY_REQUIRED")
            if (body.contains("SESSION_INVALID")) throw zarzError("ZARZ_SESSION_INVALID")
            throw zarzError("ZARZ_EXCHANGE_FAILED", code.toString())
        }
        val obj = runCatching { JSONObject(body) }.getOrElse {
            throw zarzError("ZARZ_EXCHANGE_FAILED", "bad json")
        }
        // ponytail: exchange response key field assumed `key`; extend the
        // fallback list if the gateway names it differently.
        val key = obj.optString("key", "")
            .ifBlank { obj.optString("sessionKey", "") }
            .ifBlank { obj.optString("token", "") }
        if (key.isBlank()) throw zarzError("ZARZ_EXCHANGE_FAILED", "missing key")
        return key
    }

    // ── HTTP + crypto helpers ────────────────────────────────────────────────

    private fun call(req: Request): Pair<Int, String> {
        Http.client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun fetchWith(sessionId: String, url: String): String {
        val req = Request.Builder().url(url)
            .header("X-Zarz-Session", sessionId)
            .get()
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                if (body.contains("SESSION_INVALID")) throw zarzError("ZARZ_SESSION_INVALID")
                if (body.contains("VERIFY_REQUIRED")) throw zarzError("ZARZ_VERIFY_REQUIRED")
                throw zarzError("ZARZ_FETCH_FAILED", resp.code.toString())
            }
            return body
        }
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun isSessionInvalid(e: Throwable): Boolean =
        e is IllegalStateException && e.message?.contains("ZARZ_SESSION_INVALID") == true

    private fun zarzError(code: String, detail: String = ""): IllegalStateException =
        IllegalStateException(if (detail.isBlank()) code else "$code:$detail")
}
