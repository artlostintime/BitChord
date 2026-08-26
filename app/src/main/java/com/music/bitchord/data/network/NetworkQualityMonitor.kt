package com.music.bitchord.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Watches the active network and derives the audio [QualityClass] the app
 * should target, with hysteresis: the class only changes after the raw
 * reading has been STABLE for [STABILITY_WINDOW_MS], so a momentary dip on an
 * otherwise fast connection never downgrades the stream mid-song.
 *
 * Raw signal: [NetworkCapabilities.linkDownstreamBandwidthKbps] — coarse but
 * dependency-free, updated by the platform on every network callback.
 */
object NetworkQualityMonitor {

    enum class QualityClass { VERY_SLOW, SLOW, MEDIUM, FAST, VERY_FAST }

    data class Reading(val kbps: Long, val metered: Boolean, val validated: Boolean)

    private const val STABILITY_WINDOW_MS = 30_000L
    private const val POLL_MS = 5_000L

    /** The class currently being targeted — stable for [STABILITY_WINDOW_MS]. */
    private val _effective = MutableStateFlow(QualityClass.FAST)
    val effective: StateFlow<QualityClass> = _effective.asStateFlow()

    /** Latest raw reading, for UI that wants the live value. */
    private val _raw = MutableStateFlow(Reading(0L, false, false))
    val raw: StateFlow<Reading> = _raw.asStateFlow()

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _raw.value = Reading(
                    kbps = caps.linkDownstreamBandwidthKbps.toLong(),
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )

        // Hysteresis loop: classify every poll; only commit to _effective once
        // the classification has held steady for the whole window.
        scope.launch {
            var pendingClass: QualityClass? = null
            var pendingSince = 0L
            while (true) {
                val r = _raw.value
                val live = classify(r)
                if (live != _effective.value) {
                    val now = System.currentTimeMillis()
                    if (live == pendingClass) {
                        if (pendingSince == 0L) pendingSince = now
                        else if (now - pendingSince >= STABILITY_WINDOW_MS) {
                            _effective.value = live
                            pendingClass = null
                            pendingSince = 0L
                        }
                    } else {
                        pendingClass = live
                        pendingSince = now
                    }
                } else {
                    pendingClass = null
                    pendingSince = 0L
                }
                delay(POLL_MS)
            }
        }
    }

    private fun classify(r: Reading): QualityClass = when {
        !r.validated || r.kbps <= 0 -> QualityClass.VERY_SLOW
        r.kbps < 150 -> QualityClass.VERY_SLOW
        r.kbps < 500 -> QualityClass.SLOW
        r.kbps < 2_000 -> QualityClass.MEDIUM
        r.metered -> QualityClass.FAST
        else -> QualityClass.VERY_FAST
    }

    /** Rough floor in kbps each class can sustain comfortably. */
    fun minKbpsFor(c: QualityClass): Long = when (c) {
        QualityClass.VERY_SLOW -> 32
        QualityClass.SLOW -> 96
        QualityClass.MEDIUM -> 256
        QualityClass.FAST -> 1_000
        QualityClass.VERY_FAST -> 5_000
    }.toLong().also { roundToLong(it.toFloat()) }
}
