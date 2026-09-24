# NotiFy: Personal-Use Android Music Player Research and Architecture

## 1. Executive Summary

**Verdict.** Build NotiFy as a small, Android-only Kotlin application with Jetpack Compose and one Media3 player owned by a MediaSessionService. Make playback of user-selected local audio the unconditional core. Add online search, stream resolution, radio, and LRCLIB lyrics as independent, failure-tolerant adapters. Install from Android Studio or a locally built APK; the design requires no account, server, Firebase, or paid infrastructure.

**Repository decision.** Build the application structure from scratch; study [Metrolist](https://github.com/MetrolistGroup/Metrolist) for Android music UX and feature interaction, [SimpMusic](https://github.com/maxrave-dev/SimpMusic) for failure cases and broader feature ideas, [Auxio](https://github.com/OxygenCobalt/Auxio) for local-library behavior, and [Musify](https://github.com/technophilist/Musify) for Compose layout inspiration. Pilot [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) inside an optional streaming adapter because its published releases expose concrete maintenance fixes. This choice **does not establish that a particular song streams today**; that requires an on-device smoke test. InnerTubeX may be evaluated for YouTube Music-specific search and Watch Next, but it is not a proven drop-in source for playable URLs in this audit. [Metrolist README](https://github.com/MetrolistGroup/Metrolist), [SimpMusic README](https://github.com/maxrave-dev/SimpMusic), [NewPipe releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases).

**Evidence boundary.** As of 12 September 2026, public repository pages, selected Gradle settings, official Android documentation, and release notes were accessible. Complete source trees, exact latest meaningful commits for every repository, transitive dependency graphs, and Android device builds were **not** independently audited here. The comparison marks those unknowns rather than inventing Kotlin/AGP/Compose versions or declaring a verified live stream. The architecture below is an implementation recommendation, not a claim that an APK has been built.

The first useful milestone is an APK that plays local MP3s after the screen locks, responds to Bluetooth play/pause/skip, and restores a local playlist. Only then should a failure-prone online source be added. Official Media3 guidance places both player and session in a service and lets UI clients connect with a MediaController. [Android: background playback](https://developer.android.com/media/media3/session/background-playback).

## 2. Verified Repository Findings

**Metrolist.** Its README explicitly advertises background playback, caching, playlists, search, lyrics, and an Android Material 3 interface. It also explicitly declares *maintenance mode*, limited to bug fixes and minor improvements. The accessible settings.gradle.kts lists :app and :innertube and configures a Maven/JitPack path for InnerTubeX, including local development overrides. This is evidence of an integration boundary, **not** proof that the library currently resolves every playable URL or that the application implements every feature exactly as documented. Its repository is GPL-3.0. [README](https://github.com/MetrolistGroup/Metrolist), [settings.gradle.kts](https://github.com/MetrolistGroup/Metrolist/blob/main/settings.gradle.kts).

**SimpMusic.** Its README calls the application Android-and-desktop Compose Multiplatform, describes search, radio-like discovery, cache, lyrics, local analytics, and Android Auto, and warns that YouTube dependency produces player errors. The current root tree includes androidApp, composeApp, desktopApp, cast, crashlytics, and lastfm modules and a core submodule. This makes it a valuable behavior reference but a heavy fork for a single-device personal app. Its repository license is GPL-3.0. Open issues report background transitions, empty responses, and radio/offline playback failures; these are reports, not independently reproduced bugs. [README](https://github.com/maxrave-dev/SimpMusic), [settings.gradle.kts](https://github.com/maxrave-dev/SimpMusic/blob/dev/settings.gradle.kts), [issues](https://github.com/maxrave-dev/SimpMusic/issues), [background report](https://github.com/maxrave-dev/SimpMusic/issues/2222).

**InnerTubeX.** The Metrolist organization lists it as a Kotlin extended InnerTube client, GPL-3.0, updated 8 September 2026, and Metrolist's settings explicitly references its artifacts. Its README/source tree was not directly accessible in this review. Therefore search, player parsing, Watch Next request shape, cipher maintenance, dependency size, and Media3 interoperability are **unverified here**. Do not turn its recent update date into a claim of successful playback. [Metrolist organization](https://github.com/MetrolistGroup), [Metrolist settings](https://github.com/MetrolistGroup/Metrolist/blob/main/settings.gradle.kts).

**NewPipeExtractor.** It is a standalone GPL-3.0 extraction library with documented Gradle usage and support for multiple sites, including YouTube. Its README notes extra core-library desugaring for Android minSdk below 33. Published v0.26.3 release notes describe a workaround for SABR-related failures by selecting another player client; earlier release notes mention signature deobfuscation and n-parameter checks. The v0.26.5 release existed in August 2026. Those changes demonstrate active repair, not permanent compatibility or a guarantee of a YouTube Music-specific Watch Next interface. [README](https://github.com/TeamNewPipe/NewPipeExtractor), [releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases).

**Other candidates.** [Velune](https://github.com/nikhilvishwakarma00/Velune) is a GPL-3.0 Kotlin/Compose YouTube Music client with a large feature scope. [Sonique](https://github.com/07-Ansh/Sonique) is GPL-3.0 and has a distinct MediaServiceCore module. [AuraMusic](https://github.com/TeamAuraMusic/AuraMusic) is GPL-3.0, includes innertube and multiple lyrics/provider modules, and has phone/TV scope. [Flow](https://github.com/A-EDev/Flow) is GPL-3.0, Compose/Material 3, and locally recommends YouTube content. [Meduza](https://github.com/akilaisadev/meduza) is Apache-2.0 and describes a Media3 local-recommendation architecture, but its inspected tree contained only 12 commits and its playback claims were not verified. [Musify](https://github.com/technophilist/Musify) is a Spotify-API-based Compose UI sample; [SpotifyCompose](https://github.com/droidbaza/SpotifyCompose) calls itself work in progress and warns its navigation extensions are deprecated. No license file was visible in the inspected roots of these two UI samples, so use their *patterns*, not copied code or assets. [Auxio](https://github.com/OxygenCobalt/Auxio) adds a useful GPL-3.0 local-player reference; it does not supply an online extractor.

The audit did **not** establish the most recent meaningful commit, actual test coverage, full service source, verified build, exact dependency versions, or complete open-issue taxonomy for all eleven listed repositories. Before copying any code or pinning a dependency, inspect the relevant exact commit, license, source file, and Gradle graph in a normal Git checkout.

## 3. Repository Comparison Table

“Reported” means the repository README or repository tree says so; “unknown” means no source/build confirmation here. Ratings are judgments for **NotiFy**, not a quality score for the original project.

| Repository | Maintenance / license | Architecture / extractor / Media3 | Background / radio / UI | Cleanliness, reusability, risk |
|---|---|---|---|---|
| [InnerTubeX](https://github.com/MetrolistGroup/innertubex) | Recently updated; GPL-3.0 per [organization](https://github.com/MetrolistGroup) | Kotlin InnerTube client; source/API, dependency footprint and Media3 link unverified | Not an app | Evaluate in isolated YTM adapter; high operational uncertainty |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | v0.26.5 listed; GPL-3.0 | Standalone multi-site Java extractor, not a Media3 service | No app UI; YTM radio contract not established | Useful resolver pilot; moderate integration effort, high external-source risk |
| [Metrolist](https://github.com/MetrolistGroup/Metrolist) | Maintenance mode; GPL-3.0 | :app + :innertube; InnerTubeX dependency path; actual service source unverified | Background, playlists, lyrics, cache reported; polished Material 3 | Best whole-app reference; fork adds nonessential features and GPL codebase |
| [SimpMusic](https://github.com/maxrave-dev/SimpMusic) | Active releases/issues; GPL-3.0 | Android/desktop Compose Multiplatform, numerous modules; actual service source unverified | Background, Auto, lyrics, offline, queue reported; extensive UI | Strong reference, high adaptation cost and upstream breakage exposure |
| [Velune](https://github.com/nikhilvishwakarma00/Velune) | Recent activity not established; GPL-3.0 | Kotlin/Compose, provider and lyric folders | Background/offline and synced lyrics reported | Feature-heavy reference; medium-high integration risk |
| [Sonique](https://github.com/07-Ansh/Sonique) | Release availability shown; GPL-3.0 | KMP/Compose with MediaServiceCore | Background/lyrics/Auto reported | Study service separation; source behavior unverified |
| [AuraMusic](https://github.com/TeamAuraMusic/AuraMusic) | Active history visible; GPL-3.0 | Android/TV, innertube and provider modules | Playback and Material 3 reported | Too broad for one phone; provider coupling risk |
| [Flow](https://github.com/A-EDev/Flow) | Active history visible; GPL-3.0 | Android Compose; local recommendation | Discovery-heavy, video-plus-music UI | Study on-device recommendation ideas; copying requires license review |
| [Meduza](https://github.com/akilaisadev/meduza) | 12 commits at inspection; Apache-2.0 | Advertises Media3 + InnerTube; source claims not reproduced | Gapless/radio reported | Easy to study, weak evidence for primary base |
| [Musify](https://github.com/technophilist/Musify) | Last meaningful commit unverified; license not visible | Compose sample using Spotify API | Spotify-inspired UI; unrelated data source | UI moodboard only; no source copying until license established |
| [SpotifyCompose](https://github.com/droidbaza/SpotifyCompose) | WIP; license not visible | UI sample with deprecated navigation helpers | Visual reference only | Do not adopt navigation code or assets |
| [Auxio](https://github.com/OxygenCobalt/Auxio) *(additional)* | Active history visible; GPL-3.0 | Local-first Android music app | Useful local queue/library reference | Relevant because local playback is NotiFy's fallback; no online extraction |

Common limits: stars, commit counts, README features and a visible build.gradle file are **not** proof of a reproducible build, small dependency graph, robust coroutine cancellation, or good test coverage. Exact min/target SDK and toolchain versions are deliberately not asserted for repositories whose relevant Gradle files could not be inspected.

## 4. Extractor Verdict

**Pilot one extractor: NewPipeExtractor, pinned to a tested release, behind StreamResolver.** It has an explicit standalone integration guide and a visible track record of targeted YouTube breakage repairs. Treat v0.26.5 as a *candidate* version rather than a verified working integration: first run three search/resolve/play smoke cases, one longer track, a Bluetooth/lock-screen continuation, and a forced expired-URL recovery on the intended device and network. If the probe fails, ship the same APK with online playback clearly unavailable and local playback intact. [NewPipe README](https://github.com/TeamNewPipe/NewPipeExtractor), [NewPipe releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases).

Use a **separate, replaceable** MusicSearchDataSource for YouTube Music-specific discovery and WatchNextDataSource for recommendations. InnerTubeX is a research candidate for those roles, subject to source and integration verification; the observed Metrolist dependency and recent update do not establish a reliable API contract. A plain YouTube search adapter is a possible first online feature if it yields valid IDs; label it honestly, rather than pretending it is YouTube Music catalog parity. Never assume an endpoint name or request body without inspecting the pinned adapter source and recording a successful test. [Metrolist settings](https://github.com/MetrolistGroup/Metrolist/blob/main/settings.gradle.kts), [InnerTubeX project listing](https://github.com/MetrolistGroup).

Distinct operations:

| Operation | Result | Why it may fail |
|---|---|---|
| Search/metadata | Track ID, title, artist, artwork | Endpoint changes, region rules, rate limits |
| Player-response parsing | Playback formats and response metadata | Client restrictions, response schema |
| Signature / throttling parameters | Corrected stream address | Player JavaScript or client changes |
| URL resolution | Audio format URL plus headers/expiry | 403, 429, missing playable format |
| Playback | Media3 reads media bytes | Network loss, expired URL, decoder error |
| On-the-fly cache | Bounded retained byte ranges | Gaps, storage eviction, URL refresh |
| Download / authorization | Separate, explicit capabilities | A cached range is not a permanent file or playback entitlement |

No extractor removes platform-side restrictions permanently. Do not bundle login cookies or embedded secrets. Do not equate a search result with a playable track.

## 5. Structural Base Verdict

| Choice | Recommendation |
|---|---|
| Fork candidate | **None for v1.** Metrolist is closest to the desired experience, but maintenance mode, GPL reuse obligations, and nonessential features make a full fork a poor fit for a small private Android app. |
| Reference-only whole app | **Metrolist** for music UX, with **SimpMusic** for wider playback and recovery scenarios. |
| UI reference | **Musify** for screen hierarchy and visual rhythm; implement original Compose components and branding. |
| Extractor reference | **NewPipeExtractor** as the first isolated resolver; InnerTubeX remains a source-specific research candidate. |
| Architecture pattern | **Build from scratch** as an Android-only single-activity, modest multi-module project; combine *ideas* and independently implemented adapters. |

Personal installation lowers deployment complexity; it does not make brittle networking more reliable or remove open-source license terms. Avoid cloning feature scope from the benchmarks. A legal code fork remains possible if the license obligations are deliberately accepted; it is simply not the recommended technical starting point.

## 6. Personal-Use Reliability and Dependency Risks

**API and extractor churn.** InnerTube response shapes, client identifiers, cipher code, throttling parameters, and available formats may change without notice. A repository update or release does not prove all tracks work. Pin the tested adapter, keep fixture-based parsers and a small manual smoke list, upgrade independently of UI/service code, and provide a Settings diagnostic that identifies the failing stage. [NewPipe release notes](https://github.com/TeamNewPipe/NewPipeExtractor/releases), [SimpMusic issue reports](https://github.com/maxrave-dev/SimpMusic/issues).

**Expiring signed URLs.** Store stable track IDs and metadata, never a resolved streaming URL as the durable queue record. Resolve on first use and again when a stream expires, a seek crosses a cache hole, or a 403 indicates stale authorization. Retry once with a fresh resolution; cap attempts and show a clear online-unavailable error. Cache keys must use provider + stable ID + chosen format identity, while the upstream request uses the new URL. A partially cached song may still require the network. [Android: network stacks and cache](https://developer.android.com/media/media3/exoplayer/network-stacks).

**Cache and storage.** Use a single SimpleCache instance, a dedicated cache directory and LRU size bound. Partial stream caching improves replay or seeking; it is **not** permanent offline storage. On corruption, release and reconstruct the cache while preserving Room playlists. Never let cache failures block a content URI. [Android: network stacks and cache](https://developer.android.com/media/media3/exoplayer/network-stacks), [Android: downloading media](https://developer.android.com/media/media3/exoplayer/downloading-media).

**Network failures and public limits.** Apply finite timeouts, cancellation, modest backoff with jitter for transient network errors, and no retry storm for 403/404/429. A 429 should suspend further requests temporarily. A failed Watch Next must leave the user's existing queue and current song untouched; a lyric provider failure should show “Lyrics unavailable.” Do not promise that free external services will always be reachable.

**Dependency breakage.** NewPipeExtractor documents GPL and extra desugaring for minSdk below 33; InnerTubeX is also listed as GPL. Before using an implementation, review its license, transitive dependencies, minimum SDK, and current build with the chosen toolchain. Replacing an adapter should not require database migration or rewriting Compose screens. Repository licenses still apply to copied or linked code in a personal project; keep license notices and review obligations if the APK is ever shared. [NewPipe README](https://github.com/TeamNewPipe/NewPipeExtractor), [InnerTubeX listing](https://github.com/MetrolistGroup).

**Hard fallback invariant.** In airplane mode, after extractor failure, without lyrics, and with an empty cache, the user can still browse and play their granted local MP3s, edit local playlists, and use the lock-screen/headset controls.

## 7. Final NotiFy Architecture

Begin with eight modules; split features only when a real dependency or build-time boundary appears. All application logic runs client-side. The Android service and Activity may share the application's **same default process**, but only the service owns ExoPlayer and MediaSession. A service is a lifecycle component, not automatically a separate OS process. [Android: background playback](https://developer.android.com/media/media3/session/background-playback).

~~~text
:app                 Single Activity, navigation, dependency wiring, manifest, APK
:core:model          TrackId, Track, AudioSource, QueueEntry, error/result types
:core:database       Room DAOs for playlists, history, cached metadata, queue snapshot
:core:local          MediaStore scan, SAF picker grants, URI access and metadata
:core:media          MediaSessionService, ExoPlayer, local/HTTP sources, cache, session bridge
:core:online         Search, stream resolver, Watch Next, LRCLIB/artwork adapters
:core:designsystem   Material 3 tokens and original reusable Compose widgets
:feature:player      Home, search, library, playlist, queue, player, lyrics, settings UI
~~~

| Module | Owns / public boundary | Depends on / forbidden | Flow and essential test |
|---|---|---|---|
| app | MainActivity, NavHost, bindings | Other modules; no extractor parsing | Controller created and released with lifecycle; navigation smoke |
| core:model | Immutable Track, SourceId, PlayableRef, PlaybackError | Kotlin only; no Android, Room or network | Pure model serialization/equality |
| core:database | PlaylistDao, HistoryDao, QueueSnapshotDao | model, Room; no UI or Media3 player | Flow from DAO, I/O dispatch; migration/reopen |
| core:local | LocalAudioRepository, LocalUriAccess | model, Android MediaStore/SAF; no online adapters | MediaStore query and revoked URI cases |
| core:media | PlaybackService, PlayerFactory, QueueCoordinator, ControllerConnector | model, local, Media3, database and an online port; no Compose | Player callbacks to StateFlow; service/notification/instrumented test |
| core:online | MusicSearchDataSource, StreamResolver, WatchNextDataSource, LyricsDataSource, ArtworkDataSource | model, HTTP/extractor; no UI or service class | Suspending I/O, cancellation, fixture/HTTP error tests |
| core:designsystem | Colors, typography, MiniPlayer, shared rows | Compose Material 3; no DAOs or networking | Preview/accessibility checks |
| feature:player | Screen-level ViewModels and immutable UiState | model, controller facade, repositories; no ExoPlayer instance | collectAsStateWithLifecycle, ViewModel state tests |

The core:media -> core:online dependency should target interfaces defined in core:model or a tiny :core:ports module if a circular Gradle dependency emerges. Bind implementation in :app using Hilt **only if** construction complexity justifies it; manual factories are acceptable for a personal app. Avoid modules for security, testing, onboarding, or every screen before they provide a useful separation. Room stores metadata and queue state, not MP3 bytes or transient signed URLs.

**Local selection.** MediaStore.Audio provides browsable device audio after the correct runtime permission. SAF ACTION_OPEN_DOCUMENT gives an explicit content URI and a persistable read grant. Store content URIs and source type; do not rely on filesystem paths. Query artist/album/duration/artwork through MediaStore or a metadata reader, deduplicate first on authority+URI/MediaStore ID and optionally on size+duration, detect moved/deleted documents on open, and offer a “Locate again” action. [Android: shared audio](https://developer.android.com/training/data-storage/shared/media), [Android: persisted SAF permissions](https://developer.android.com/training/data-storage/shared/documents-files).

**UI.** Use Material 3 semantic tokens, seeded by approximately #121212 background, #181818 surface, #242424 raised surface, white primary and #B3B3B3 secondary text. Build original artwork treatment and icons. Home shows recent/local first with online sections when available; Search distinguishes local and remote; Library and playlists remain fully accessible offline. Mini-player is a persistent navigation overlay, full player a single draggable sheet with one owner of expansion state; BottomSheetScaffold is the v1 default, custom dragging only if tested nested-scroll behavior requires it. Stable LazyColumn keys, scaled Coil artwork and pause-aware position ticker prevent unnecessary recomposition. Keep loading, empty, denied-permission, offline, and stale-stream states visible.

## 8. Data Flow Diagrams

~~~mermaid
flowchart TD
    UI["Compose screens"] --> VM["ViewModels"]
    VM --> MC["MediaController"]
    MC --> SVC["MediaSessionService"]
    SVC --> PLAYER["One ExoPlayer"]
    PLAYER --> LOCAL["content URI"]
    PLAYER --> CACHE["Bounded SimpleCache"]
    CACHE --> RESOLVE["Fresh stream resolver"]
~~~

The diagram is a recommended control/data boundary, not an observed repository implementation. Media3's service and controller roles are documented by Android; its cache example uses a singleton SimpleCache and CacheDataSource. [Background playback](https://developer.android.com/media/media3/session/background-playback), [network stacks and cache](https://developer.android.com/media/media3/exoplayer/network-stacks).

| Flow | Sequence and invariant |
|---|---|
| Search | Query -> local DAO/MediaStore immediately -> optional remote MusicSearchDataSource after debounce -> merge without suggesting remote items are already playable. |
| Remote playback | Select stable provider ID -> coordinator resolves URL on I/O dispatcher -> validate HTTPS/expected host, content type and expiry -> MediaItem/metadata -> service player -> on 403 refresh once. |
| Local playback | Choose MediaStore/SAF content URI -> check current grant -> MediaItem -> service player; never requires internet or a stream resolver. |
| Cache | Stable key(provider, ID, format) -> cached byte ranges -> upstream refreshed URL for gaps -> LRU eviction; a partial hit is not “downloaded.” |
| Radio | Player event near queue tail -> WatchNextDataSource on cancellable I/O -> de-duplicate by ID -> append to *autoplay tail* only -> leave manually queued entries unchanged. |
| Lyrics | Track metadata -> local lyric cache -> optional LRCLIB get/search -> match title+artist+duration -> parse timestamp lines -> currentPosition binary-search active line. |
| Notification/control | Headset/System UI -> MediaSession -> Player -> MediaController observers -> Compose state; service maintains playback if Activity closes. |

**Radio details.** Trigger when two tracks remain (or an equivalent short threshold), generation-key the request to the current seed and queue revision, debounce duplicate requests, cap added entries to ten, and cap total queue length to fifty. Resolve a candidate only when it approaches playback; skip invalid candidates with a bounded failure count. A user-inserted next track always outranks radio. If Watch Next fails, stop appending and show a small retry action; current playback continues. Watch Next request construction must come from the selected adapter's verified source or a captured successful test, never an assumed endpoint.

**Remote queue timing.** A metadata-only item must not reach ExoPlayer's next transition without a playable source. Materialize the current item and a small rolling next-item window, and refresh if a URL ages out; rapid skip and long pause are explicit regression cases. An alternative is a custom, cancellable DataSource that resolves a stable remote ID just before opening it on Media3's loading thread with a bounded timeout. Choose one approach after an instrumented transition test; never resolve a fifty-item queue into expiring URLs at once.

**Lyric synchronization.** Prefer embedded/local .lrc when present, then cached verified match, then optional LRCLIB. Keep instrumental, plain, synced, uncertain-match, and unavailable states distinct. Reject an obviously mismatched duration/artist; offer manual candidate selection if necessary. Sort parsed timestamps, find the last line with timestamp <= currentPosition + a small display tolerance, recompute after seeks, and only run a 200–500 ms UI ticker while the lyric screen is visible. LRCLIB describes itself as a free synchronized-lyric service with a machine-facing API, but availability and rate limits must be treated as variable. [LRCLIB source](https://github.com/tranxuanthang/lrclib), [API documentation](https://lrclib.net/docs).

## 9. Kotlin Class Blueprint

**Domain models.** TrackId(provider, value), Track(id, title, artist, album, durationMs, artwork), AudioSource.Local(contentUri) or AudioSource.Remote(providerId), QueueEntry(trackId, source, origin: USER/PLAYLIST/RADIO), PlaybackSnapshot(queue, index, positionMs, repeatMode, shuffle), LyricLine(timeMs, text), and typed PlaybackError (PermissionRevoked, MissingFile, Offline, RateLimited, ResolveFailed, ExpiredStream, CacheFailure, DecoderFailure). Keep signed media URLs in an in-memory ResolvedStream only.

**Ports and adapters.** Defaults below are recommended *initial budgets* for implementation tests, not published upstream guarantees. All suspend methods must honor cancellation, surface typed failures, and never be called on the main thread.

| Interface / contract | Input -> output | Failure / cache / timeout / retry |
|---|---|---|
| LocalAudioRepository.scan() | permission and MediaStore -> Flow<List<Track>> | revoked grant/missing file; cached Room metadata refreshed on scan; no network |
| MusicSearchDataSource.search(query, page) | text -> Page<Track> | offline/429/schema; short in-memory metadata cache, ~8 s, one transient retry |
| StreamResolver.resolve(TrackId) | stable ID -> ResolvedStream(uri, headers, formatId, optional expiry) | forbidden/expired/unplayable; no durable URL cache, ~12 s, one refresh on failure |
| WatchNextDataSource.related(seed, page) | ID -> List<Track> | empty/429/parse; short metadata cache, ~10 s, one transient retry |
| LyricsDataSource.find(track) | title/artist/duration -> LyricsResult | absent/uncertain/rate-limited; persist verified text, ~6 s, no aggressive retry |
| ArtworkDataSource.load(artworkRef) | artwork reference -> byte stream/URI | absent/network; bounded image memory/disk cache, cancellation |

**Service and controller sketch — class-level pseudocode.** The precise callback signatures and dependencies must be checked against the pinned Media3 version during implementation. The excerpt shows ownership and lifecycle; it is not asserted to be drop-in compilable:

~~~kotlin
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val created = PlayerFactory.create(this) // one player; local + remote sources
        player = created
        session = MediaSession.Builder(this, created).build()
        // Attach one queue coordinator and event listener owned by this service.
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = session

    override fun onDestroy() {
        // Cancel service-owned resolution/radio/timer jobs.
        player?.release()
        session?.release()
        player = null
        session = null
        super.onDestroy()
    }
}
~~~

The Activity builds a MediaController asynchronously from SessionToken and releases it when its lifecycle ends; a screen never constructs ExoPlayer. Use standard Player commands for play/pause, previous, next, repeat, shuffle, seek and queue changes. The coordinator maps a stable QueueEntry to a fresh playable MediaItem before inserting it, rather than storing expiring URLs in Room. Persist queue/index/position on meaningful events and periodically at a modest interval; restoring state after process death is different from forcing automatic playback. Media3 documents the service lifecycle and an opt-in onPlaybackResumption route; enable the latter only with its required receiver and a fast durable snapshot. [Android: background playback and resumption](https://developer.android.com/media/media3/session/background-playback).

**Manifest sketch.** Permission declarations are Android behavior requirements even for a sideloaded personal APK. Only request broad audio access if browsing MediaStore; SAF-only import can avoid it. Verify the actual min/target SDK chosen at phase 0. [Android: media permissions](https://developer.android.com/training/data-storage/shared/media), [Android: service setup](https://developer.android.com/media/media3/session/background-playback).

~~~xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<service android:name=".PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
~~~

Replace ".PlaybackService" with the service class's fully qualified name if :core:media uses a different package from :app. Route content:// media through the local DefaultDataSource path and only HTTP(S) media through CacheDataSource; do not make a failed network cache a prerequisite to opening local songs.

Prefer MediaSessionService's automatic media notification. Supply title, artist, and artwork through MediaMetadata and supported Player commands; Android 13+ System UI uses the session for much of its media presentation. A custom notification provider should follow a demonstrated need. Audio focus and noisy-output handling belong in the service player configuration; test phone calls, another audio app and disconnecting earbuds. Android documents that Media3 dispatches incoming hardware buttons to the session player and provides onMediaButtonEvent for advanced cases. An earbud's firmware decides what gestures it sends; do not promise interception of every double/triple tap. [Android: background playback](https://developer.android.com/media/media3/session/background-playback), [Android: media controls](https://developer.android.com/media/media3/session/control-playback).

“Previous” should restart the song if it is several seconds in, otherwise step backward; “Next” skips to the next user or radio item. Run these as standard player commands so Bluetooth and notification behavior is consistent. Use one service-scoped sleep-timer job that pauses playback; defer crossfade until ordinary transitions and queue recovery are reliable. Android Auto browsing would require MediaLibraryService and additional tests; it is optional, not v1.

## 10. Gradle and Dependency Recommendations

Start with Android-only Kotlin DSL, a checked-in Gradle wrapper, a version catalog, Compose BOM, core-ktx, lifecycle, navigation-compose, Room, coroutines, and only the Media3 artifacts needed: media3-exoplayer and media3-session. Add media3-datasource if needed explicitly by the chosen API surface, an image loader, and a single HTTP stack for metadata/streaming. The official Media3 release page lists **1.11.0 as stable on 5 August 2026**; using the same 1.11.0 for all Media3 artifacts is a verified starting recommendation, subject to a real project sync. [AndroidX Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3).

**Do not invent a complete AGP/Kotlin/Compose compatibility tuple.** Select the Android Studio installed stable toolchain and its supported Kotlin/Compose plugin combination, pin exact versions in the version catalog after a successful empty-project sync, and record the working Android Studio/JDK/Gradle/AGP/Kotlin/Compose BOM/Room tuple in README. Re-evaluate when updating one component. A published repository's current versions are not an automatic compatibility guarantee for NotiFy.

Use DefaultHttpDataSource.Factory for a lean first build, wrapped in DefaultDataSource.Factory so content URIs and HTTP both work; a shared OkHttp stack can be added later if measurements justify it. Android's Media3 network guidance describes both non-HTTP wrapping and the singleton SimpleCache + CacheDataSource pattern. For the metadata adapter, a small Retrofit+OkHttp or Ktor client can work; choose **Retrofit+OkHttp only when a typed JSON API is stable enough to model**, otherwise a lean OkHttp client with explicit parsing is simpler for unstable responses. Do not force both networking libraries into the app. [Android: network stacks and caching](https://developer.android.com/media/media3/exoplayer/network-stacks).

Add NewPipeExtractor only in :core:online after local playback passes. Before deciding minSdk, account for its documented below-33 desugaring requirement and inspect its actual current dependency graph. An optional InnerTubeX pilot must have its exact Maven coordinates, source license, parser fixtures, build and device playback verified before it is treated as a recommendation to ship. [NewPipe README](https://github.com/TeamNewPipe/NewPipeExtractor), [Metrolist settings](https://github.com/MetrolistGroup/Metrolist/blob/main/settings.gradle.kts).

## 11. Phased Antigravity Roadmap

Every phase ends in a synced, buildable debug APK. The “test” entry specifies work the coding agent must perform; this report did not run those tests.

| Phase | Files/modules and implementation order | Gate: acceptance, manual case, regression |
|---|---|---|
| 0 — Setup | Create :app, Gradle wrapper/catalog, Compose Activity, build README; lock toolchain after sync | assembleDebug succeeds; launch empty screen on target device/emulator; retain same build command |
| 1 — Models | Add :core:model TrackId, Source, QueueEntry, error types; wire placeholder screen | Unit-test ID/source mapping; clean build; no network or player in ViewModel |
| 2 — Local audio | Add :core:local MediaStore scan, SAF picker, permission/URI repository | Select two MP3s, play via content URI, deny/revoke access and see recoverable error; airplane-mode regression |
| 3 — Session | Add :core:media PlayerFactory and PlaybackService, manifest, metadata | One player; play with UI closed and screen locked; headset pause/next; no duplicate audio on reopening |
| 4 — Controller UI | ControllerConnector, player ViewModel, mini/full control basics | Play/pause/seek/previous/next match notification and Bluetooth; reconnect Activity without reset |
| 5 — Durable library | Room playlist/history/queue DAOs and migrations, playlist UI | Create/reorder/delete local playlist, force-stop then restore state, remove a file; playback still works offline |
| 6 — Online search | Implement MusicSearchDataSource adapter with typed errors; separate UI tab | Search shows truthful remote results, 429/offline error and local results remain; parser fixture test |
| 7 — Stream resolution | Add optional NewPipe-backed StreamResolver, I/O coordinator and HTTPS validation | On-device play several representative tracks; expired URL and unplayable item show clear state; no persisted URL |
| 8 — Byte cache | Add dedicated singleton SimpleCache, LRU bound and cache key factory | Seek/replay partial hit, expire upstream URL, evict cache; local play works with cache disabled/corrupted |
| 9 — Radio | Add WatchNext adapter only after its request is verified; append-only radio tail | User queue order survives failed/duplicate suggestions; no request storm; local-only mode unchanged |
| 10 — Lyrics | Add LRCLIB adapter, local .lrc, parser, match scoring, Room lyric cache | Seek jumps to correct line; wrong match rejected; offline/missing lyrics leaves playback uninterrupted |
| 11 — Polish | Original Compose theme, home/search/library/player, draggable mini-player transition | Rotate, navigate, expand/collapse while playing; TalkBack labels and stable list scroll |
| 12 — Hardware/lifecycle | Audio focus/noisy cases, repeat/shuffle, sleep timer, process restoration | Test calls, Bluetooth disconnect, OEM task swipe, background restriction, queue resumption; no leaked service |
| 13 — Recovery | Error map, retries, offline UX, diagnostics and cache cleanup | Inject 403/429/timeout/decoder failure; one bounded recovery; local playlist still plays |
| 14 — QA/APK | Lint, unit/instrumented tests, dependency/license inventory, debug or locally signed APK | assembleDebug and relevant tests pass; install through Android Studio or APK and validate on target phone |

**Antigravity coding contract.** At each phase inspect the existing repository and current files first; make a small coherent edit; compile after meaningful changes; run relevant unit tests, lint and on-device checks; preserve prior behavior; and report (1) analysis, (2) files changed, (3) implementation, (4) tests, (5) build result, (6) limitations, (7) next phase. Keep extractor code out of UI, ExoPlayer out of ViewModels, state immutable, Flow collectors lifecycle-aware, network calls cancellable, no GlobalScope, and no main-thread I/O. Add a regression test for each repaired failure. Do not advance to the next phase while the prior phase fails its gate.

## 12. Best Additional Features

| Priority | Features | Reason |
|---|---|---|
| Must-have | Local playlists and history, local queue persistence, basic search, repeat/shuffle, audio focus, noisy handling, bounded stream retry | Makes the personal music player usable without external services |
| Should-have | Sleep timer, local folder import with SAF, M3U export/import via SAF, backed-up playlist JSON, favorites, duplicate detection, offline recent-search history | High day-to-day value with little infrastructure |
| Optional | Dynamic color, tablet layout, Android Auto via MediaLibraryService, equalizer integration, ReplayGain only with reliable tags, gapless testing, lyric line tap-to-seek | Implement after stable core playback |
| Avoid in v1 | Account sync, listen-together server, AI lyrics translation, cloud backup, paid APIs, unrestricted downloading, elaborate crossfade | Adds dependencies, maintenance or correctness burdens without improving the fallback core |

## 13. Failure and Recovery Strategy

| Failure | Detect | User-facing recovery / invariant |
|---|---|---|
| Extractor/parser breaks | Typed ResolveFailed, fixture regression | Isolate and update adapter; hide remote play action temporarily; local music remains available |
| Signed URL expires | 403 or known expiry at gap/seek | Re-resolve stable ID once, resume position if possible; do not loop |
| Network disappears | Connectivity change plus actual I/O error | Preserve queue; show Offline; play local selection and retry only on user action or return of connectivity |
| Public endpoint rate-limits | 429/retry-after when supplied | Stop requests, back off and surface temporary unavailability; no background hammering |
| Invalid metadata or format | Missing ID, unsafe URL, unsupported MIME | Reject entry and skip bounded number of radio candidates; never crash player |
| Lyrics missing or mismatched | No result / low title-artist-duration confidence | Show unavailable or candidate chooser; do not silently show incorrect lyrics |
| Cache unreadable | Cache exception, failed range read | Recreate bounded cache or bypass for one attempt; no change to Room or local content |
| Local file removed or grant revoked | ContentResolver open fails | Mark missing and offer Locate again; other local songs keep playing |
| App process dies | Recreated service and persisted snapshot | Restore queue/position when next launched; opt-in resumption only after tested |
| Background service blocked | ForegroundServiceStartNotAllowedException or manifest exception | Start from user-initiated playback, fix mediaPlayback declaration and permissions, display actionable error |
| Upstream repo release breaks build | Gradle sync/compile failure | Keep tested lockfile/version, roll back adapter update and review its changelog/license |

Local files, metadata/playlist database, bounded byte cache, and lyric cache are separate stores with separate failure handling. No extractor error should delete personal playlists.

## 14. Final Production Checklist

- [ ] A selected local MP3 plays with airplane mode on, after screen lock, and after Activity removal; user can pick a replacement when a URI is revoked.
- [ ] Exactly one ExoPlayer and MediaSession live in MediaSessionService; the Activity uses MediaController and releases its connection.
- [ ] Notification, lock-screen, Bluetooth play/pause/skip and default previous semantics are tested on the actual earbud/phone combination.
- [ ] Correct mediaPlayback manifest declarations and audio read permissions are applied for the chosen Android versions; no broad file access is requested needlessly.
- [ ] Room playlists, queue, history and lyric cache survive process restart; resolved signed URLs are never durable records.
- [ ] Online search, stream resolution and radio each have typed, visible failures and bounded retries; local playback still works when each is disabled.
- [ ] SimpleCache is singleton, size-bounded, keyed by stable identity and safely bypassable; partial cache is never called an offline download.
- [ ] No hardcoded secrets, unsafe cleartext media URLs, arbitrary unvalidated stream hosts, sensitive URL logging, or nonessential analytics SDK.
- [ ] APK installs through Android Studio or locally signed build; assembleDebug, targeted unit tests, lint and device smoke cases have recorded results.
- [ ] Licenses and notices for actual imported code/dependencies are reviewed before copying or sharing; UI does not contain Spotify logos or proprietary artwork.

**Personal installation.** For development, open the project in Android Studio, let Gradle sync, select the connected phone, and run :app; Android Studio installs a debug APK. For a standalone local install, run ./gradlew :app:assembleDebug, locate app/build/outputs/apk/debug/app-debug.apk, transfer it to the phone, and permit installation from the selected file manager if Android asks. A debug APK is suitable for private testing; for repeatable updates, create and keep a local signing key and sign subsequent APKs with the same key. These are target workflow instructions, not a claim that this report produced an APK.

**Source inventory.** Primary repository evidence: [Metrolist README](https://github.com/MetrolistGroup/Metrolist), [Metrolist settings](https://github.com/MetrolistGroup/Metrolist/blob/main/settings.gradle.kts), [Metrolist organization](https://github.com/MetrolistGroup), [SimpMusic README](https://github.com/maxrave-dev/SimpMusic), [SimpMusic settings](https://github.com/maxrave-dev/SimpMusic/blob/dev/settings.gradle.kts), [SimpMusic issues](https://github.com/maxrave-dev/SimpMusic/issues), [NewPipe README](https://github.com/TeamNewPipe/NewPipeExtractor), [NewPipe releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases), [Velune](https://github.com/nikhilvishwakarma00/Velune), [Sonique](https://github.com/07-Ansh/Sonique), [AuraMusic](https://github.com/TeamAuraMusic/AuraMusic), [Flow](https://github.com/A-EDev/Flow), [Meduza](https://github.com/akilaisadev/meduza), [Musify](https://github.com/technophilist/Musify), [SpotifyCompose](https://github.com/droidbaza/SpotifyCompose), and [Auxio](https://github.com/OxygenCobalt/Auxio). Official implementation evidence: [MediaSessionService](https://developer.android.com/media/media3/session/background-playback), [MediaSession controls](https://developer.android.com/media/media3/session/control-playback), [network and cache](https://developer.android.com/media/media3/exoplayer/network-stacks), [media permissions](https://developer.android.com/training/data-storage/shared/media), [SAF persisted permissions](https://developer.android.com/training/data-storage/shared/documents-files), [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3), [LRCLIB](https://github.com/tranxuanthang/lrclib). The project specification is the attached “Pasted text(7).txt”; this document adopts its 15-part structure with the personal-use update.

## 15. Copy-Paste Prompt for Antigravity

~~~text
Build NotiFy, a PRIVATE personal-use Android music player. Native Kotlin, one Activity, Jetpack Compose UI, MVVM with immutable UI state, and modest multi-module boundaries. It must build as an Android Studio project and install as a debug or locally signed APK. It has no paid backend, mandatory account, Firebase, or Play Store release requirement.

Read the complete NotiFy research and architecture report before making changes. Inspect the actual repository and its Gradle configuration. Do not overwrite working features. Build in the report's phases 0 through 14, stopping whenever a phase does not compile or meet its manual test gate.

Prioritize local MP3 playback first. Query MediaStore with appropriate permission, support SAF-picked content URIs and persist grants, and store playlists/history/queue metadata in Room. Local browsing, playback, playlists and headset controls MUST continue to work in airplane mode and when every online provider fails.

Put the only ExoPlayer and MediaSession in a MediaSessionService. The Compose Activity communicates through a lifecycle-managed MediaController. Implement user-initiated background playback, media notification, lock-screen and Bluetooth controls, audio focus, noisy-device handling, repeat/shuffle, bounded queue persistence, and safe release. Test the chosen phone and earbuds rather than assuming proprietary gesture support.

Keep online search, stream resolution, Watch Next/radio, lyrics and artwork behind separate cancellable interfaces. Pilot a pinned NewPipeExtractor release ONLY after checking the actual source, GPL license, dependency graph, Gradle sync and real-device stream playback. Evaluate InnerTubeX separately for YouTube Music metadata/Watch Next only after inspecting its source and proving a live request. Never invent endpoint payloads, promise that cipher handling is permanent, or persist signed stream URLs. Re-resolve an expired URL once; distinguish 403, 429, offline, parser, format and cache failures. If online extraction is unavailable, mark it unavailable truthfully and ship the working local player.

Use one bounded Media3 SimpleCache for stream byte ranges, with stable provider/track/format cache keys. Do not label partial cache as a permanent offline download. Keep cache errors isolated from local content and Room playlists. Radio may append only de-duplicated, playable candidates behind the user's manual queue, and cannot block the current track. LRCLIB is optional; validate lyric matches, distinguish synced/plain/instrumental/missing, and synchronize lines to player position without keeping a ticker active off-screen.

Build an original Spotify-inspired dark Material 3 Compose interface: home, local/remote search, library, playlists, queue, mini-player, expanded player, lyrics and settings. Use design tokens close to #121212 / #181818 / #242424 / white / #B3B3B3. Do not copy Spotify branding, proprietary assets, or unlicensed UI sample code. Preserve navigation and playback state across Activity recreation.

For every phase: (1) analyze existing code; (2) list files to change; (3) implement the smallest verifiable slice; (4) add focused tests for meaningful logic and regressions; (5) run assembleDebug, relevant tests and lint; (6) report exact build/test results and limitations; (7) propose the next phase. No fake APIs, GlobalScope, main-thread networking, duplicate collectors, duplicate players, hardcoded secrets or unbounded retries. Verify a compatible Android Studio/AGP/Kotlin/Compose/Room toolchain by real sync rather than guessed version numbers; use matching verified stable Media3 artifacts. Provide the final buildable project and honest status of online features.
~~~
