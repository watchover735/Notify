# NotiFy Current State & Architecture Snapshot

> **Date:** October 2026  
> **Repository:** `watchover735/Notify` (Local path: `c:\NotiFy`)  
> **Active Branch:** `feature/android-auth` (Commit `5a53694`)  
> **App Version:** Version Name `0.7.4` (Version Code `20`)

---

## 1. Project Purpose & High-Level Summary

**NotiFy** is a native Android music streaming, search, and offline playback application built with modern Android technology (Jetpack Compose, AndroidX Media3 ExoPlayer, Room, Kotlin Coroutines).  

The core design objectives are:
- **Instantaneous Playback:** Sub-500ms time-to-first-byte (TTFB) achieved via a multi-provider stream racing engine.
- **Resilient Media Sources:** Dynamic fallback across multiple public providers (YouTube InnerTube, JioSaavn, SoundCloud, Deezer, Cobalt, and on-device `youtubedl-android`).
- **Offline Capability:** Scoped local storage downloads with native `aria2c` and `ffmpeg` acceleration.
- **Premium Aesthetics:** OLED dark mode theme (`0xFF050505`), neon accents, glassmorphic cards, and dynamic album art palette extraction.
- **Access Control:** User accounts and license key verification backed by Supabase PostgreSQL and Row Level Security, with a 72-hour offline grace fallback.
- **Car Integration:** Android Auto media browser support via AndroidX Media3 session service.

---

## 2. Technology Stack & Dependencies

| Layer / Domain | Technology / Library | Version | Role in Project |
| :--- | :--- | :--- | :--- |
| **Language** | Kotlin | `2.1.0` | Primary language across all modules |
| **JDK** | Java / OpenJDK | `17` | Compilation target |
| **Build System** | Gradle & Android Gradle Plugin | Gradle `8.13`, AGP `8.8.2` | Multi-module build orchestration |
| **Android SDK** | Android SDK | minSdk `26`, targetSdk `35`, compileSdk `35` | Android 8.0 Oreo up to Android 15 |
| **UI Toolkit** | Jetpack Compose (BOM) | `2024.12.01` | Declarative UI, Material 3, Navigation |
| **Audio Engine** | AndroidX Media3 | `1.5.1` | `ExoPlayer`, `MediaSessionService`, `MediaController` |
| **Local Database** | Room (with KSP) | `2.6.1` | SQLite ORM, version 7, exported JSON schemas |
| **Concurrency** | Kotlin Coroutines & Flow | `1.9.0` | Reactive state management & async processing |
| **Network & REST** | Ktor Client | `3.0.3` (CIO engine) | HTTP calls, content negotiation, Auth plugin |
| **Serialization** | KotlinX Serialization JSON | `1.7.3` | JSON parsing for API responses & state snapshots |
| **Cloud / BaaS** | Supabase Kotlin SDK | `3.1.1` | Auth, Postgrest (database), Realtime, Functions |
| **Auth Provider** | Android Credential Manager | `1.5.0-rc01` + `googleid 1.1.1` | Native Google One-Tap & credential flows |
| **Stream Extraction**| `youtubedl-android` | `0.17.2` | Local on-device yt-dlp, aria2c, and ffmpeg binaries |
| **Image Loading** | Coil Compose | `3.0.4` | Async network image caching & rendering |
| **Background Work** | AndroidX WorkManager | `2.10.0` | Background track downloads |
| **Palette Tinting** | AndroidX Palette | `1.0.0` | Album art dominant color extraction |
| **HTTP Engine** | OkHttp | `4.12.0` | Low-level streaming connections |

---

## 3. Architecture & Directory Structure

The project follows a multi-module architecture:

```
c:\NotiFy\
├── app/                                 # Main application module
│   ├── schemas/                         # Room exported schemas (1.json to 7.json)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # Activities, Services, Providers, Receivers
│       │   ├── java/com/notify/
│       │   │   ├── NotiFyApplication.kt # Application entry point (WorkManager, Ktor init)
│       │   │   ├── MainActivity.kt      # Single-activity host for Jetpack Compose
│       │   │   ├── DeveloperConfig.kt   # Developer attribution constants
│       │   │   ├── auth/                # Supabase Auth, Gate UI, Repositories, ViewModels
│       │   │   ├── core/local/db/       # Room Database, entities, DAOs, migrations (1->7)
│       │   │   ├── download/            # Stream resolvers, chains, offline engine, WorkManager
│       │   │   ├── playback/            # Coordinators, controllers, Android Auto tree provider
│       │   │   ├── ui/                  # Compose UI (Home, Search, Library, Player, Theme)
│       │   │   └── updater/             # GitHub releases auto-updater & receiver
│       │   └── res/                     # Mipmaps, drawables, strings, XML descriptors
│       └── test/                        # Local JVM unit tests
├── core/
│   ├── model/                           # Pure domain models (Track, ResolvedStream, Lyrics)
│   ├── local/                           # MediaStore scanner, SAF URI manager, permission helpers
│   ├── playback/                        # NotiFyPlaybackService, Media3 cache, sleep timer
│   └── downloads/                       # Offline storage policies, file validators, queue DAOs
├── supabase/                            # Cloud backend definitions
│   ├── schema.sql                       # Database schema: profiles, license_keys, key_attempts, RLS
│   ├── admin.sql                        # Admin RPCs: generate_keys, revoke_key, extend_user, stats
│   ├── deploy.py                        # Python script to deploy edge functions via Supabase API
│   └── functions/                       # Deno TypeScript Edge Functions (catalog & resolvers)
├── backend-ytdlp/                       # Standalone FastAPI Python service (DEPRECATED/ABANDONED)
├── architecture/                        # Historical architecture documentation
├── ai/                                  # Universal AI project brain & instructions
├── build.gradle.kts                     # Root build configuration
├── settings.gradle.kts                  # Module inclusion definition
└── gradle.properties                    # JVM & compiler options
```

---

## 4. Major Components & Subsystems

### 4.1. Playback Engine & Concurrency
- **[NotiFyPlaybackService.kt](file:///c:/NotiFy/core/playback/src/main/kotlin/com/notify/core/playback/NotiFyPlaybackService.kt):** AndroidX Media3 `MediaSessionService`. Runs in the foreground (`mediaPlayback`). Instantiates `ExoPlayer` with a custom `Media3StreamCache` and custom audio attributes.
- **[PlaybackQueueCoordinator.kt](file:///c:/NotiFy/app/src/main/java/com/notify/playback/PlaybackQueueCoordinator.kt):** 117KB centralized queue coordinator. Maintains queue entries, active index, shuffle states, repeat modes, and handles player synchronization.
  - *Threading Invariant:* `capturePlayerState()` runs synchronously on `Looper.getMainLooper()` before state persistence is dispatched to `Dispatchers.IO` via `PlaybackSnapshotStore`.
- **[PlaybackController.kt](file:///c:/NotiFy/app/src/main/java/com/notify/playback/PlaybackController.kt):** Bridges Compose ViewModels to `MediaSessionService` via `MediaController`.
- **[SleepTimerManager.kt](file:///c:/NotiFy/core/playback/src/main/kotlin/com/notify/core/playback/SleepTimerManager.kt):** Background sleep timer with smooth audio fade-out support.

### 4.2. Stream Resolution Pipeline
- **[ResolvedStreamProviderChain.kt](file:///c:/NotiFy/app/src/main/java/com/notify/download/stream/ResolvedStreamProviderChain.kt):** Coordinates multi-tier stream resolution:
  1. **Stage a (Offline):** Checks local storage for pre-downloaded files (< 5ms).
  2. **Stage b (Cache):** Checks in-memory and disk URL caches (< 1ms).
  3. **Stage c (Parallel Race):** Dispatches concurrent requests to:
     - `FastInnerTubeStreamResolver` (Direct YouTube InnerTube Android client API)
     - `JioSaavnStreamResolver` (Saavn API; prioritized for Indic scripts/keywords via `ProviderRoutingPolicy`)
     - `SoundCloudStreamResolver` (SoundCloud v2 public client API)
     - `DeezerStreamResolver` (Deezer preview/full track resolver)
     - `CobaltStreamResolver` (Cobalt API; subject to circuit-breaker demotion)
     - Strict race timeout cap: 2.8 seconds.
  4. **Stage d (Local yt-dlp Fallback):** `OnlineStreamResolver` executing on-device `youtubedl-android` with a 20-second timeout.

### 4.3. User Authentication & Licensing Gate
- **[AuthGate.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/ui/AuthGate.kt):** Root Compose wrapper intercepting `MainActivity`.
- **[AuthGateViewModel.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/ui/AuthGateViewModel.kt):** Reactive state machine exposing states:
  - `Loading`: Checking local session and entitlement cache.
  - `NeedLogin`: Directs user to [LoginScreen.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/ui/LoginScreen.kt) (Email/Password or Google One-Tap).
  - `NeedNickname`: Directs user to [NicknameScreen.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/ui/NicknameScreen.kt) if profile lacks display name.
  - `NeedKey`: Directs user to [KeyRedemptionScreen.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/ui/KeyRedemptionScreen.kt) to enter a license key.
  - `Ready`: Unlocks `MainScreen` and permits unrestricted music playback.
  - `Error`: Displays network/server errors with retry options.
- **Offline Grace Fallback:** [SupabaseProfileAndKeyRepository.kt](file:///c:/NotiFy/app/src/main/java/com/notify/auth/data/SupabaseProfileAndKeyRepository.kt) caches successful entitlement checks. If the device is offline, access is granted for up to 72 hours from the last verified cloud check.

### 4.4. Local Database & Migrations
- **[NotiFyDatabase.kt](file:///c:/NotiFy/app/src/main/java/com/notify/core/local/db/NotiFyDatabase.kt):** Room database version 7 with 9 tables:
  1. `tracks` (`TrackEntity`): Metadata, duration, artist, album, thumbnail URL.
  2. `playlists` (`PlaylistEntity`): User and system playlists.
  3. `playlist_entries` (`PlaylistEntryEntity`): Join table connecting tracks to playlists with ordering.
  4. `track_sources` (`TrackSourceEntity`): Multi-provider stream URLs and source keys.
  5. `search_history` (`SearchHistoryEntity`): Query history.
  6. `search_candidate_cache` (`SearchCandidateCacheEntity`): Cached mapping of search results.
  7. `recent_search_items` (`RecentSearchItemEntity`): Recently clicked search items.
  8. `offline_downloads` (`OfflineDownloadEntity`): Completed offline download records.
  9. `download_queue` (`DownloadQueueEntity`): Pending and active background download tasks.

---

## 5. Backend & Cloud Infrastructure

### 5.1. Supabase PostgreSQL Schema
- **`public.profiles`:**
  - `id` (UUID, references `auth.users(id)` ON DELETE CASCADE)
  - `email` (TEXT)
  - `nickname` (TEXT)
  - `role` (TEXT: `'user'` or `'admin'`, default `'user'`)
  - `entitled_until` (TIMESTAMPTZ: null = not entitled)
  - `created_at`, `updated_at`
- **`public.license_keys`:**
  - `id` (UUID, primary key)
  - `key_code` (TEXT, unique, e.g. `NOTIFY-XXXX-XXXX`)
  - `duration_days` (INTEGER)
  - `is_lifetime` (BOOLEAN)
  - `status` (TEXT: `'active'`, `'claimed'`, `'revoked'`, `'expired'`)
  - `created_by` (UUID, references `profiles(id)`)
  - `claimed_by` (UUID, references `profiles(id)`)
  - `claimed_at`, `expires_at`, `notes`, `created_at`
- **`public.key_attempts`:**
  - Audit logging table recording user attempts, IP addresses, success/failure, and reasons.
- **RPC Functions (`SECURITY DEFINER`):**
  - `get_entitlement()`: Returns user entitlement status, expiration date, and role.
  - `redeem_key(p_key_code)`: Validates and claims a key, updates user's `entitled_until`, logs to `key_attempts`.
  - `admin_generate_keys(...)`: Generates batches of license keys (restricted to admin role; returns `SETOF public.license_keys`).
  - `admin_revoke_key(...)`: Revokes an active key.
  - `admin_extend_user(...)`: Manually extends user entitlement.
  - `admin_stats()`: Aggregates metrics (active keys, claimed keys, entitled users).

### 5.2. Edge Functions ([supabase/functions/](file:///c:/NotiFy/supabase/functions))
- `curated-artists`: Returns curated artist lists and discovery feeds.
- `trending-jiosaavn`: Scrapes/proxies trending charts from JioSaavn.
- `resolve-jiosaavn`: Resolves JioSaavn track stream URLs.
- `resolve-deezer`: Resolves Deezer track metadata and preview/stream URLs.
- `resolve-soundcloud`: Resolves SoundCloud stream links.
- `telegram-admin`: Telegram bot webhook for generating and distributing license keys remotely.

---

## 6. Build, Run & Verification Process

### Build Commands
```powershell
# Compile Kotlin code across all modules
.\gradlew :app:compileDebugKotlin

# Run local JVM unit tests
.\gradlew :app:testDebugUnitTest

# Assemble Debug APK (outputs to app/build/outputs/apk/debug/app-debug.apk)
.\gradlew :app:assembleDebug

# Assemble Release APK (outputs to app/build/outputs/apk/release/app-release-unsigned.apk)
.\gradlew :app:assembleRelease
```

### Verification Status
- Compilation (`:app:compileDebugKotlin`): **SUCCESS (Exit Code 0)**
- Unit Tests (`:app:testDebugUnitTest`): **SUCCESS (Exit Code 0, 100% passing)**
- Release ProGuard Assembly (`:app:assembleRelease`): **SUCCESS (Exit Code 0)**

---

## 7. Current Feature Status Matrix

| Feature Area | Implementation State | Verification Method |
| :--- | :--- | :--- |
| **Audio Playback Engine** | Fully operational | ExoPlayer + Media3 service running, tested in unit tests and builds |
| **Multi-Provider Stream Race** | Fully operational | `ResolvedStreamProviderChain` with FastInnerTube, JioSaavn, SoundCloud |
| **Offline Downloads** | Fully operational | WorkManager `DownloadWorker` + `youtubedl-android` on internal storage |
| **OLED Dark Mode UI** | Fully operational | Complete Jetpack Compose theme with Material 3 tokens |
| **Player Bottom Sheet** | Fully operational | Expandable mini-player to full-screen player with lyrics and queue |
| **Room Database Migrations** | Fully operational | Migrations 1 through 7 verified, schema JSON files committed |
| **Developer Attribution** | Fully operational | "Developed by Rahul" dialog with Instagram and WhatsApp intents |
| **Auto-Updater** | Fully operational | GitHub Release asset check via DownloadManager |
| **Supabase Authentication** | Fully wired & compile-ready | `AuthGate` wraps `MainActivity`, ViewModels handle login/nickname/key |
| **Google Sign-In** | Blocked by placeholder | Code present; hidden in UI until real `GOOGLE_WEB_CLIENT_ID` is set |
| **Android Auto Integration** | Partially protected | Exposes MediaTree; does not enforce AuthGate on direct service binding |
| **Remote yt-dlp Backend** | Deprecated / Dormant | Code exists in `backend-ytdlp/` but unlinked from Android client |
