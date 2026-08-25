package com.music.bitchord.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose: a module source is tried first, YouTube Music
 * second, always in that order. Adding a source means adding a [MusicSource]
 * implementation and an entry here, which is the point — every protocol the
 * app speaks is one someone can read in this repo, and a source can't teach
 * the app a new way to behave after it ships.
 *
 * What varies per *instance* — which index, whose module — is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
) {
    /**
     * A URL to a Convx-compatible module-index JSON.
     *
     * The index lists JS plugin descriptors; each plugin ships a JS file
     * that exports `searchTracks()` and `getTrackStreamUrl()`. The app
     * fetches the index, loads each plugin's JS into a QuickJS sandbox,
     * and calls those functions — the same mechanism Convx uses to support
     * services like Tidal, Qobuz, Apple Music, etc.
     *
     * The JS runs in a sandboxed QuickJS VM with no access to the Android
     * runtime, only to a wired-in `fetch()` implementation.
     */
    MODULE(
        label = "Module source",
        detail = "A URL to a Convx-compatible module index. Modules are JS plugins that " +
            "can search and stream from services like Tidal, Qobuz, Apple Music and more.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
    ),

    /**
     * The source the app was built on, listed here so it always has a fixed
     * place: second, behind the module source. It cannot be removed — see
     * [SourceRegistry]. Nothing else in the app can supply a home feed, a
     * radio station or a related-tracks queue.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy — there is no " +
            "lossless rendition to ask for.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
    ),

    /**
     * The device's own audio files and the app's downloads, played from disk.
     *
     * No network and no account, so it is always available offline, and a local
     * FLAC is bit-exact — hence [canServeLossless] is true and a local lossless
     * copy outranks a lossless request served lossy by another source. It needs
     * no configuration, so like [YOUTUBE] it is seeded as a built-in and can
     * only be disabled, not deleted. Placed last in the enum so the historical
     * "module first, YouTube last" default order is preserved until the user
     * reorders — see [SourceRegistry.active].
     */
    LOCAL(
        label = "Local files",
        detail = "Audio on this device and downloads, played straight from " +
            "disk. Offline, and bit-exact when the file is a FLAC.",
        labels = listOf("Offline", "Lossless", "No account"),
        needsServer = false,
        canServeLossless = true,
    ),

    /**
     * An installed SpotiFLAC (.sflx) extension: a sandboxed JavaScript plugin
     * that registers `searchTracks`/`download` callbacks and is driven through
     * [ExtensionSource]. Like [LOCAL] it needs no server config — its files
     * live under `filesDir/extensions/<id>/` — and it can serve lossless.
     *
     * Not built-in: there is no extension until the user installs one, so it
     * is absent from [SourceRegistry]'s seeded kinds and only appears once an
     * [SflxInstaller] install adds its [SourceConfig].
     */
    EXTENSION(
        label = "Extension",
        detail = "An installed SpotiFLAC extension. Search and stream come from " +
            "sandboxed JavaScript that registers search/download callbacks.",
        labels = listOf("FLAC", "Lossless", "Plugin"),
        needsServer = false,
        canServeLossless = true,
    ),
}
