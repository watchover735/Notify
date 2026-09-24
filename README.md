# NotiFy

A private, personal-use Android music player built with native Kotlin, Jetpack Compose, Material 3, and AndroidX Media3 (`MediaSessionService` + `ExoPlayer`).

## Core Architecture Invariants
- **Local-first resilience:** Local audio playback via MediaStore and SAF content URIs continues to work 100% in airplane mode and when all online extractors are disabled.
- **Single player instance:** Exactly one `ExoPlayer` and `MediaSession` owned by `MediaSessionService`. The UI interacts exclusively through a lifecycle-managed `MediaController`.
- **Zero cloud overhead:** No user accounts, remote databases, Firebase, or paid cloud APIs.
- **Durable library:** Playlists, history, and queue snapshots are persisted locally via Room; transient expiring stream URLs are never persisted.

## Verified Toolchain Tuple (Phase 0)
- **Host OS:** Windows 11
- **JDK:** OpenJDK 21 LTS (Alibaba Dragonwell 21.0.12)
- **Android SDK:** `C:\Users\DELL\AppData\Local\Android\Sdk` (Platforms 34, 35, 36)
- **Build Tools:** 35.0.0 / 36.0.0
- **Gradle:** 8.11.1
- **Android Gradle Plugin (AGP):** 8.8.2
- **Kotlin:** 2.0.21 (with Compose Compiler Gradle Plugin)
- **Target / Compile SDK:** API 35
- **Min SDK:** API 26 (Android 8.0 Oreo)

## Project Modules
```text
:app                 Single Activity, navigation, dependency wiring, manifest, APK
:core:model          TrackId, Track, AudioSource, QueueEntry, error/result types (Phase 1)
:core:database       Room DAOs for playlists, history, cached metadata, queue snapshot (Phase 5)
:core:local          MediaStore scan, SAF picker grants, URI access and metadata (Phase 2)
:core:media          MediaSessionService, ExoPlayer, local/HTTP sources, cache (Phase 3)
:core:online         Search, stream resolver, Watch Next, LRCLIB/artwork adapters (Phase 6+)
:core:designsystem   Material 3 tokens and original reusable Compose widgets (Phase 11)
:feature:player      Home, search, library, playlist, queue, player, lyrics, settings UI (Phase 4+)
```

## Build & Test Commands
Assemble debug APK:
```powershell
.\gradlew.bat :app:assembleDebug
```

The compiled APK will be located at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to connected device/emulator:
```powershell
.\gradlew.bat :app:installDebug
# or via adb:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Implementation Progress
- [x] **Phase 0: Setup** — Initialized Gradle toolchain, version catalog, single Compose Activity, verified debug APK build.
- [x] **Phase 1: Domain Models** — Pure Kotlin domain entities (`Track`, `TrackId`, `AudioSource`, `QueueEntry`, typed errors).
- [x] **Phase 2: Local Audio** — MediaStore scanner, SAF picker, URI persistence and permission handling.
- [ ] **Phase 3: MediaSessionService** — Single ExoPlayer, MediaSession, foreground notification, and audio focus.
- [ ] **Phase 4: Controller UI** — MediaController connection, ViewModel, and playback controls.
- [ ] **Phase 5: Room Library** — Playlists, queue snapshot, and playback history persistence.
- [ ] **Phase 6: Online Search** — Multi-source debounced search adapter with fallback handling.
- [ ] **Phase 7: Stream Resolution** — Isolated stream resolver for online sources with expired URL recovery.
- [ ] **Phase 8: Byte Cache** — Bounded SimpleCache for audio ranges with LRU eviction.
- [ ] **Phase 9: Radio (Watch Next)** — Autoplay discovery tail isolated from user queues.
- [ ] **Phase 10: Lyrics** — Synchronized LRCLIB lyrics parser and local .lrc integration.
- [ ] **Phase 11: UI Polish** — Dark Material 3 aesthetic, draggable player sheet, and accessibility.
- [ ] **Phase 12: Hardware & Lifecycle** — Audio focus ducking, noisy receiver, sleep timer, and resumption.
- [ ] **Phase 13: Error Recovery** — Diagnostic logging, cache bypass, and graceful failure states.
- [ ] **Phase 14: Release / Sideload** — ProGuard rules, local signing, and release package.
