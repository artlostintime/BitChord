# BitChord — Feature Requests

A curated list of requested features for future development. Each request is
labeled (`FR-1` … `FR-5`) for easy reference in discussions and commits.

---

## FR-1 · Audio Quality Selection

**Labels:** `playback` `quality` `settings`

Add playback quality options to the app:

| Mode | Behavior |
|------|----------|
| **Highest** | Try the highest-quality stream first, then fall back. |
| **Balanced** | Balance quality, startup speed, and reliability. |
| **Data Saver** | Prefer lower-quality streams. |
| **Auto** | Automatically choose the best available quality. |

Quality selection should consider **codec, bitrate, sample rate, bit depth,
and lossless/lossy status** — not bitrate alone.

---

## FR-2 · Multiple Audio Providers

**Labels:** `providers` `architecture` `settings`

Add support for multiple audio providers such as **Qobuz**, **TIDAL**,
**YouTube Music**, **Deezer**, and **Local Files**.

Allow users to:

- Enable/disable providers.
- Set provider priority.
- Automatically fall back when a provider doesn't have the track.
- Choose between **Provider Priority**, **Best Quality**, and **Auto** modes.

> Ideally, providers should be modular so new ones can be added later without
> touching core playback logic.

---

## FR-3 · Spotify Recommendations with Alternative Playback

**Labels:** `spotify` `recommendations` `integration`

Add a Meld-like system where Spotify provides recommendations, playlists,
library data, and metadata, while another provider handles the actual audio
playback.

```
Spotify
   ↓
Track matching
   ↓
Qobuz / TIDAL / YouTube Music / etc.
   ↓
Playback
```

Matching requirements:

- Use **ISRC** where available, plus metadata such as artist, title, album,
  and duration.
- Cache successful matches for faster playback later.

---

## FR-4 · Intelligent Source Resolution & Fallback

**Labels:** `playback` `resolver` `reliability`

Add a centralized source resolver that compares available providers and
selects the best playable source.

The resolver should consider:

- User provider preference
- Audio quality
- Codec/bitrate
- Match confidence
- Provider availability

If the selected source fails, automatically try the next-best available
source.

---

## FR-5 · Playback Source & Quality Information

**Labels:** `ui` `now-playing` `transparency`

Show the actual source and quality of the currently playing track, e.g.:

```text
Source: Qobuz
Codec:  FLAC
Quality: 24-bit / 96 kHz
```

or:

```text
Source: YouTube Music
Codec:  Opus
Bitrate: ~256 kbps
```

Rules:

- Clearly distinguish **lossless vs lossy** audio.
- Never label upscaled lossy audio as genuine lossless.
