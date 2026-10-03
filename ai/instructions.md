# NotiFy — AI Instructions & Development Rules

> **Target Audience:** Future AI coding assistants and developers working on `NotiFy`.
> **Package:** `com.notify` | **Branch:** `feature/android-auth` | **Head commit:** `5a53694`
> **Generated:** 2026-10-03 | **Source verified:** code + git log

---

## 1. DO-NOT-BREAK (Hard Safety Fence)

The following features are **working** and must never regress. Any task that could touch these areas requires a VERIFY step (file + line + evidence) before any edit.

| Feature | Primary Files |
|---|---|
| **Playback** (play/pause/seek) | `PlaybackController.kt`, `PlaybackQueueCoordinator.kt`, `NotiFyPlaybackService` |
| **Queue management** | `PlaybackQueueCoordinator.kt` L341–L418 |
| **Shuffle** (SHUFFLE + SMART_SHUFFLE) | `PlaybackQueueCoordinator.kt` L358–L414 |
| **Resume (position restore)** | `PlaybackSnapshotStore.kt`, `PlaybackQueueCoordinator.kt` L250 |
| **Radio recommendations** | `RadioWindowManager.kt` |
| **Downloads** (enqueue, WorkManager, recover) | `OfflineDownloadManager.kt`, `TrackDownloadWorker.kt` |
| **Artist photos** | `CuratedArtistsRepository.kt`, `curated-artists` edge fn |
| **Search spinner / snackbar** | `SnackbarManager.kt`, `NotiFyApp.kt` L68–L152 |
| **Artwork quality** | `ArtworkResolution`, `LocalArtworkStore` in `core/playback` |
| **Android Auto** | `AndroidAutoMediaTreeProvider.kt`, `PlaybackQueueCoordinator.kt` |
| **Existing Edge Functions** | `supabase/functions/` (curated-artists, resolve-*, trending-jiosaavn, telegram-admin) |
| **Duration-mismatch filter** | `ResolvedStreamProviderChain.kt` L491–L538 (`validateDuration`) |
| **Stream resolver chain** | `ResolvedStreamProviderChain.kt` (Stage a->b->c->d) |

---

## 2. Workflow Rules (Mandatory for Every Task)

```
RULE 1  — Har task ek alag git commit hona chahiye.
RULE 2  — Pehle VERIFY (file + line number + evidence), phir FIX. Andaza nahi.
RULE 3  — Cause unclear ho to RUKO aur report karo. Assume mat karo.
RULE 4  — Version bump (versionCode / versionName) band hai.
RULE 5  — Koi bhi empty catch block mat daalo.
RULE 6  — Koi bhi secret (token, key, service_role) repo mein commit nahi hoga.
RULE 7  — Har step ke baad compileDebugKotlin + testDebugUnitTest PASS hona chahiye.
RULE 8  — Naya feature tabhi implement karo jab user ne explicitly approve kiya ho.
RULE 9  — Kisi bhi existing file ka refactor / rename / move user approval ke bina nahi.
RULE 10 — ai/ folder ka commit alag karo ("docs: ..." prefix), code commits se mix mat karo.
```

---

## 3. Project-Specific Invariants (NEVER VIOLATE)

### 3.1 Player Looper Confinement
- ExoPlayer / MediaSession operations MUST execute on **Player Application Looper (Main Thread)**.
- `dispatchOnPlayerLooper()` at `PlaybackQueueCoordinator.kt` L310 is the ONLY safe entry point.
- Capturing player state for snapshot persistence must be synchronous on main thread before IO dispatch.

### 3.2 Room Database Schema
- **Current version: 7** (`NotiFyDatabase`).
- `exportSchema = true` enforced in `app/build.gradle.kts`.
- Any schema change requires a `Migration(X, Y)` class + JSON export committed to `app/schemas/`.
- NEVER use `fallbackToDestructiveMigration()` in production.

### 3.3 Stream Resolution Pipeline Order (DO NOT REORDER)
```
Stage a: Offline file (< 5ms)             — OfflineDownloadManager.getOfflinePlaybackUriForSource()
Stage b: In-memory StreamUrlCache (< 1ms) — StreamUrlCache.get()
Stage c: Parallel Race (2800ms cap)       — FastInnerTube | SoundCloud | JioSaavn | Deezer
         Cobalt: DISABLED (isCobaltEnabled = false — ResolvedStreamProviderChain.kt L334)
Stage d: yt-dlp fallback (35s total cap)  — OnlineStreamResolver (youtubedl-android on-device)
```
Evidence: `ResolvedStreamProviderChain.kt` L33–L54 (class Javadoc), L91–L92 (timeouts), L334 (Cobalt flag)

### 3.4 Download Boundaries
- Files stored in `notify_offline/` via `NotiFyOfflineProvider.kt` (scoped internal storage only).
- MAX_CONCURRENT_AUDIO_DOWNLOADS = 1 (`OfflineDownloadManager.kt` L57).
- Single global WorkManager chain name: `notify_offline_download_queue` (L58).

### 3.5 Remote yt-dlp Backend = DEPRECATED
- Commit `20c4488`: reverted remote backend. App uses `youtubedl-android` **on-device**.
- `backend-ytdlp/` folder exists but is NOT used by the Android app.

### 3.6 AuthGate = Mandatory Entry Guard
- `MainActivity.kt` L80: `AuthGate { NotiFyApp(...) }` — all app content gated.
- `onResume()` L97–L104: calls `authViewModel.onAppResume(onRevokedOrExpired = { playbackViewModel.pause() })`.
- Gate states (sealed interface `AuthGateState`):
  `Loading -> NeedLogin -> NeedNickname -> NeedKey -> Ready | Error`
- On network failure: do NOT log out user; cache fallback applies.
  Evidence: `AuthGateViewModel.kt` L76–L88 (profile fallback), L119–L129 (entitlement fallback).

### 3.7 Secret Management
- Secrets stored ONLY as Deno.env in Supabase Edge Function secrets console.
- Secret names (never hardcode values): `TELEGRAM_BOT_TOKEN`, `ADMIN_TELEGRAM_ID`,
  `TELEGRAM_WEBHOOK_SECRET`, `SUPABASE_SERVICE_ROLE_KEY`.
- `ANON_KEY` in `SupabaseConfig.kt` L15: public anon key — acceptable in source.
- `GOOGLE_WEB_CLIENT_ID` in `SupabaseConfig.kt` L12: currently PLACEHOLDER — Google Sign-In not active.

### 3.8 Git Branch Safety
- NEVER touch: `stash@{0}`, branch `backup/auth-stash`.
- Active branch: `feature/android-auth` (HEAD: 5a53694). Untracked: only `ai/` folder.

---

## 4. Module Structure

```
c:\NotiFy\
├── app/src/main/java/com/notify/
│   ├── auth/                  — AuthGate, LoginScreen, SupabaseAuthRepository,
│   │                            SupabaseProfileAndKeyRepository, GoogleAuthManager, NicknameScreen, KeyEntryScreen
│   ├── playback/              — PlaybackController, PlaybackQueueCoordinator (~2472 lines),
│   │                            RadioWindowManager, AndroidAutoMediaTreeProvider,
│   │                            PlaybackViewModel, PlaybackSnapshotStore
│   ├── download/
│   │   ├── engine/            — OfflineDownloadManager, TrackDownloadWorker, NotiFyOfflineProvider
│   │   ├── stream/            — ResolvedStreamProviderChain, FastInnerTubeStreamResolver,
│   │   │                        DeezerStreamResolver, SoundCloudStreamResolver,
│   │   │                        JioSaavnStreamResolver, CobaltStreamResolver,
│   │   │                        ProviderRoutingPolicy, StreamUrlCache, SupabaseConfig
│   │   ├── matcher/           — InnerTubeWatchNextProvider, InnerTubeYouTubeMusicSearchProvider,
│   │   │                        TrackMatchEngine, YtDlpYouTubeSearchProvider
│   │   └── db/                — Room DAOs, entities, PlaylistRepository, NotiFyDatabase
│   ├── ui/                    — NotiFyApp, SnackbarManager, player/, home/, library/, search/,
│   │                            navigation/, components/, theme/
│   ├── core/preferences/      — UserProfilePreferences (display name)
│   └── updater/               — AppUpdater (GitHub Releases in-app update)
├── core/
│   ├── playback/              — NotiFyPlaybackService, PlaybackErrorMapper, MediaItemMapper,
│   │                            LocalArtworkStore, ArtworkResolution, SleepTimerManager
│   ├── downloads/             — OfflineDownloadDao, DownloadQueueDao, OfflineStorage, SmartStorageManager
│   ├── local/                 — MediaStore scanner, LocalAudioAvailabilityChecker
│   └── model/                 — Track, ResolvedStream, QueueEntry, PlaybackSnapshot, PlaybackError, etc.
├── supabase/
│   ├── schema.sql             — profiles, license_keys, key_attempts + get_entitlement(), redeem_key()
│   ├── admin.sql              — admin_generate_keys(), admin_revoke_key(), admin_revoke_user(), admin_extend_user()
│   ├── telegram_admin_setup.sql — processed_updates, admin_log, pending_confirmations,
│   │                              admin_get_user_info(), admin_get_stats()
│   └── functions/             — Deno/TypeScript Edge Functions
│       ├── curated-artists/   — Artist photo/data proxy
│       ├── resolve-deezer/    — Deezer 30s preview lookup
│       ├── resolve-soundcloud/ — SoundCloud stream resolver
│       ├── resolve-jiosaavn/  — JioSaavn stream resolver
│       ├── resolve-cobalt/    — Cobalt resolver (disabled in Android app)
│       ├── trending-jiosaavn/ — Trending tracks proxy
│       └── telegram-admin/    — Admin bot: genkey, revoke, extend, stats, userinfo (742 lines)
└── backend-ytdlp/             — DEPRECATED remote extraction server (NOT used by Android)
```

---

## 5. Key Configuration

| Item | Value / Location |
|---|---|
| Supabase Project ID | `pnwoccbrpcihjhjmujfb` (`SupabaseConfig.kt` L7) |
| App Package | `com.notify` |
| Last version verified | v0.7.4 / versionCode 20 (commit `f0a7cf1`) |
| Auth branch HEAD | `5a53694` (feat: AuthGate state machine) |
| Google OAuth | NOT active — GOOGLE_WEB_CLIENT_ID is placeholder (`SupabaseConfig.kt` L12) |
| Cobalt resolver | DISABLED (`ResolvedStreamProviderChain.kt` L334 `isCobaltEnabled = false`) |
| Download concurrency | 1 (`OfflineDownloadManager.kt` L57) |
| Stream race timeout | 2800ms (`ResolvedStreamProviderChain.kt` L92) |
| Global resolution timeout | 35000ms (`ResolvedStreamProviderChain.kt` L91) |
