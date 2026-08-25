package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val SPOTIFY_ORIGIN = "https://open.spotify.com"

/**
 * In-app Spotify sign-in, same pattern as [YtMusicLoginScreen]: load the real
 * accounts.spotify.com login; once the user lands on open.spotify.com the
 * `sp_dc` session cookie is in the WebView's CookieManager — lift it and hand
 * it to [onCookieCaptured] exactly once.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onCookieCaptured: (spDc: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    private var captured = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (captured || url?.startsWith(SPOTIFY_ORIGIN) != true) return
                        val cookies = CookieManager.getInstance().getCookie(SPOTIFY_ORIGIN) ?: return
                        val spDc = cookies.split(";")
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("sp_dc=") }
                            ?.removePrefix("sp_dc=")
                        if (!spDc.isNullOrBlank()) {
                            captured = true
                            onCookieCaptured(spDc)
                        }
                    }
                }

                loadUrl(SpotifyAuthUrls.LOGIN_URL)
            }
        },
    )
}

private object SpotifyAuthUrls {
    const val LOGIN_URL =
        "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
}
