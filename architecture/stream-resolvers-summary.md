# NotiFy Stream Resolvers Architecture Summary

> **Snapshot Tag / Branch:** `v1-working-local-extraction`  
> **Commit Date:** September 2026  
> **Purpose:** Comprehensive reference for all audio stream resolvers, provider routing, fallbacks, and chain mechanics in the current working application.

---

## 1. High-Level Resolution Pipeline

Every playback request in NotiFy passes through `ResolvedStreamProviderChain.kt` across four coordinated stages:

```
                  ┌───────────────────────────────┐
                  │ Playback Request (Track / URL)│
                  └──────────────┬────────────────┘
                                 │
                   [Stage a: Offline Storage]
                                 ├──> File exists locally? (<5ms) ──> Play local file
                                 │
                   [Stage b: StreamUrlCache]
                                 ├──> Memory cache hit? (<1ms)    ──> Play cached stream
                                 │
                   [Stage c: Parallel HTTP Race] (Max 2.8s)
                                 ├──> FastInnerTube (YouTube Music player v1)
                                 ├──> JioSaavn (Indic / Bollywood / Punjabi tracks)
                                 ├──> SoundCloud (Progressive MP3 / HLS)
                                 ├──> Cobalt (Cloud direct audio extractor)
                                 ├──> Deezer (Preview MP3 fallback, non-Indic)
                                 │    First valid stream wins; others cancelled immediately
                                 │
                   [Stage d: yt-dlp Fallback]
                                 └──> Last-resort fallback via OnlineStreamResolver (20s cap)
```

---

## 2. Detailed Breakdown of Core Resolver Files

### 1. `JioSaavnStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/JioSaavnStreamResolver.kt`
- **Catalog Scope:** Bollywood, Punjabi, Haryanvi, Bhojpuri, South-Indian, and regional South-Asian music.
- **API Endpoint:** `https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=3&p=1&q={query}&_marker=0&api_version=4&ctx=web6dot0`
- **Cipher & Decryption:**
  - JioSaavn returns `encrypted_media_url` in search results.
  - Decrypted locally using legacy **DES** cipher (`DES/ECB/PKCS5Padding`) with static key `38346591`.
  - Replaces `.` with `+` in Base64 string before standard Base64 decode.
  - Marked *fragile*: any decryption failure gracefully falls back to other racers without throwing.
- **Bitrate & Quality Strategy:**
  - Standard API response contains `_96.mp4` by default.
  - **Optimistic Upgrade:** Resolver immediately upgrades URL suffix to `_320.mp4` (bitrate: 320,000 bps, format: `jiosaavn_aac_320`).
  - **Zero-Latency Fallbacks:** Attaches fallback list `[_160.mp4, _96.mp4]` in `ResolvedStream.fallbackUrls`.
  - If ExoPlayer hits HTTP 404 (extremely rare tracks where 320kbps CDN file is missing), `PlaybackQueueCoordinator.kt` transparently retries with `_160.mp4`, then `_96.mp4`.
- **Preview Fallback:** If encrypted URL fails, converts `media_preview_url` (`preview.saavncdn.com` -> `aac.saavncdn.com` `_320.mp4`).
- **Timeout:** 1,500 ms (OkHttpClient connect: 1.5s, read: 1.5s).

---

### 2. `SoundCloudStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/SoundCloudStreamResolver.kt`
- **Catalog Scope:** Global catalog, EDM, independent artists, remixes, covers, podcasts.
- **API Endpoint:** `https://api-v2.soundcloud.com/search/tracks?q={query}&client_id={client_id}&limit=1`
- **Authentication & Scraping:**
  - Dynamically scrapes `client_id` at runtime from `https://soundcloud.com` asset bundles (`https://a-v2.sndcdn.com/assets/*.js`).
  - In-memory atomic cache with 6-hour TTL (`CLIENT_ID_CACHE_TTL_MS`).
  - Secondary fallback to `lastKnownGoodClientId` (in-memory persistent).
  - Emergency static fallback key: `Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo`.
  - **Self-Healing on HTTP 401:** If SoundCloud returns 401 (stale/rotated token), it immediately purges the cache, re-scrapes a fresh `client_id` live from the web, and retries the search/transcoding request.
- **Format Selection:**
  - Prioritizes progressive MP3 transcodings (`protocol: progressive`, format: `soundcloud_mp3_progressive`, bitrate: 128,000 bps).
  - Secondary fallback: HLS (`application/x-mpegURL`, format: `soundcloud_hls`).
- **Timeout:** 1,500 ms.

---

### 3. `DeezerStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/DeezerStreamResolver.kt`
- **Catalog Scope:** Western pop, international rock, classic tracks.
- **API Endpoint:** `https://api.deezer.com/search?q={query}&limit=1`
- **Stream Format:**
  - Extracts 30-second progressive MP3 preview (`preview` URL, 128,000 bps, format: `deezer_preview_mp3_128`).
  - Fast, reliable preview fallback when full-length extraction from other racers fails.
- **Routing Optimization:**
  - **Skipped for Indic tracks:** Indic queries bypass Deezer completely to preserve race slots and reduce useless bandwidth consumption.
- **Timeout:** 1,500 ms.

---

### 4. `CobaltStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/CobaltStreamResolver.kt`
- **Catalog Scope:** YouTube audio streams via Cobalt extraction backend.
- **Endpoint:** `https://api.cobalt.tools/api/json` (configurable to private self-hosted instance).
- **Request:** POST JSON payload `{ "url": canonicalYoutubeUrl, "downloadMode": "audio", "audioFormat": "best" }`.
- **Auto-Demotion Circuit Breaker:**
  - Public Cobalt instance encounters Cloudflare anti-bot checks (Error 1010).
  - After 3 consecutive failures (`FAILURE_THRESHOLD`), the resolver trips its circuit breaker and is demoted for 10 minutes (`DEMOTION_COOLDOWN_MS = 600,000 ms`).
  - During demotion, `isDemoted()` returns `true`, skipping Cobalt in race setup to eliminate dead waiting time.
- **Timeout:** 1,500 ms.

---

### 5. `FastInnerTubeStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/FastInnerTubeStreamResolver.kt`
- **Catalog Scope:** Entire YouTube / YouTube Music database.
- **API Endpoint:** `https://music.youtube.com/youtubei/v1/player`
- **Mechanism:**
  - Queries YouTube Music player API using the `ANDROID_MUSIC` client profile (`InnerTubeConfig.kt`).
  - Iterates `streamingData.adaptiveFormats` for direct progressive audio streams (`mimeType` starting with `audio/`).
  - Filters out signature-cipher tracks; selects candidate with highest bitrate (e.g. 256kbps AAC / Opus).
  - Fast-fails if signature cipher is detected so remaining racers or yt-dlp can immediately take over.
- **Timeout:** 1,500 ms.

---

### 6. `OnlineStreamResolver.kt`
- **Location:** `app/src/main/java/com/notify/download/stream/OnlineStreamResolver.kt`
- **Engine:** Python-based `yt-dlp` running through `youtubedl-android` native runtime.
- **Role:** Heavyweight safety net and last-resort fallback when lightweight racers fail.
- **Concurrency & Dispatcher:**
  - Uses `Dispatchers.IO.limitedParallelism(1)` to strictly isolate heavy Python execution from UI and coroutines.
  - Dedicated timeout of 20,000 ms (`YTDLP_EXECUTION_TIMEOUT_MS`) measured *only after* acquiring the single execution slot (queue wait excluded).
- **Client Profiles Rotation:**
  - `youtube:player_client=tv_embedded,visionos`
  - `youtube:player_client=android`
  - `youtube:player_client=ios,tv,mweb`
  - `youtube:player_client=all`
- **Auto-Update Recovery:** If all client profiles fail, automatically triggers `YtDlpRuntime.updateYtDlp(context, force = true)` and attempts recovery.
- **Preemption:** Background prefetch requests are cancelled immediately when a user foreground tap occurs via `cancelActivePrefetch()`.
- **Permanent Error Filter:** Fast aborts on geo-block, private/deleted video, or copyright claim without wasting retries.

---

## 3. Orchestration & Smart Routing

### `ResolvedStreamProviderChain.kt`
- **Deduplication:** Multiple requests for the same `videoId` join an existing in-flight `Deferred<Result<ResolvedStream>>` (`inFlightResolutions`).
- **Parallel Racing:** Spawns concurrent coroutines for eligible lightweight resolvers inside `executeChainResolution`.
- **Early Winner (<= 2,800 ms):** The first resolver to return a successful stream completes `winnerDeferred`; all other racer coroutines are cancelled.
- **Late Winner Rescue:** If racers exceed 2.8s, yt-dlp fallback is launched concurrently while late racers remain active. Kotlin `select {}` picks whichever finishes first (lightweight late winner or yt-dlp).

### `ProviderRoutingPolicy.kt`
- **Indic Script Detection:** Regex `[\u0900-\u0D7F]` checks Devanagari, Gurmukhi, Bengali, Gujarati, Tamil, Telugu, Kannada, Malayalam.
- **Artist Dictionary:** Word-boundary matched regex covering 100+ Indian artists (Arijit Singh, Sidhu Moose Wala, Masoom Sharma, Renuka Panwar, Diljit, Pritam, Anirudh, Khesari Lal Yadav, etc.).
- **Decision:**
  - If Indic detected: include `JioSaavn`, exclude `Deezer`.
  - If non-Indic: exclude `JioSaavn`, include `Deezer`.

### `StreamUrlCache.kt`
- Thread-safe `ConcurrentHashMap` with 3-hour default TTL.
- Keyed by `videoId` and `youtube:{videoId}` variants.
- Handles HTTP 403 invalidation and restricts retries to exactly 1 to prevent infinite loops.

---

## 4. Playback-Time Quality Fallback Mechanics

When JioSaavn resolves an optimistic `_320.mp4` stream:
1. `ResolvedStream.fallbackUrls` holds `[_160.mp4, _96.mp4]`.
2. `ResolvedPlaybackItemFactory.kt` bundles this list into `MediaItem` extras as `MediaItemMapper.EXTRA_FALLBACK_URLS`.
3. In `PlaybackQueueCoordinator.kt` (`handlePlaybackError`):
   ```kotlin
   if (isSourceError && currentEntry != null) {
       val remainingFallbacks = MediaItemMapper.getFallbackUrls(currentItem)
       if (remainingFallbacks.isNotEmpty()) {
           val nextUrl = remainingFallbacks.first()
           val stillRemaining = remainingFallbacks.drop(1)
           // Seamlessly creates new MediaItem with nextUrl and stillRemaining fallbacks
           // Calls activePlayer.setMediaItem(fallbackItem, currentPos) -> prepare() -> play()
           return
       }
   }
   ```
4. User perceives zero interruption or resolve lag.

---

## 5. Rollback Procedure

If future refactoring or provider migration introduces bugs:

```powershell
# Fetch all tags and branches
git fetch --all --tags

# To inspect the v1 working snapshot:
git checkout v1-working-local-extraction

# To reset current main back to this working state:
git reset --hard v1-working-local-extraction
git push origin main --force
```
