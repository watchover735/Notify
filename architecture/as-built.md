# As-Built Architecture & Dependency Specification

## 1. System Overview & Build Identifiers

- **Application ID**: `com.notify`
- **Current Version**: `versionCode = 14`, `versionName = "0.5.0"` (configured in [app/build.gradle.kts](file:///c:/NotiFy/app/build.gradle.kts#L16-L17))
- **Room Database Schema**: `version = 7` (configured in [NotiFyDatabase.kt](file:///c:/NotiFy/app/src/main/java/com/notify/download/db/NotiFyDatabase.kt#L26))
- **Source Control Status**: Direct workspace inspection confirms **no Git repository** exists in `c:\NotiFy`. No fictitious commit hashes or remotes are referenced.
- **Physical Device Status**: `adb devices -l` indicates no active physical device is currently attached over USB/TCP. Automated unit tests are executed and reported separately from physical device verifications.

---

## 2. Module Hierarchy & Dependency Boundaries

The project is structured into 5 Gradle modules with strict unidirectional dependency boundaries:

```mermaid
graph TD
    subgraph UI_And_App [":app (Application Module)"]
        UI[Jetpack Compose UI & ViewModels]
        Coord[PlaybackQueueCoordinator]
        SnapshotStore[PlaybackSnapshotStore]
        RoomDB[NotiFyDatabase v7]
        Worker[WorkManager Download / Artwork Workers]
    end

    subgraph Core_Playback [":core:playback (Android Library)"]
        Service[NotiFyPlaybackService]
        Delegate[PlaybackQueueDelegate Registry]
        Mapper[MediaItemMapper]
        Factory[ResolvedPlaybackItemFactory]
    end

    subgraph Core_Downloads [":core:downloads (Android Library)"]
        Downloader[DownloadEngine / StorageManager]
        DownDao[OfflineDownloadDao / DownloadQueueDao]
    end

    subgraph Core_Local [":core:local (Android Library)"]
        LocalRepo[LocalAudioRepository]
        SafGrant[SafUriGrantManager]
        AvailChecker[LocalAudioAvailabilityChecker]
    end

    subgraph Core_Model [":core:model (Pure Kotlin JVM)"]
        TrackModel[Track / TrackId / AudioSource]
        QueueModel[QueueEntry / QueueOrigin / PlaybackSnapshot]
        Errors[PlaybackError / DownloadStatus]
    end

    UI --> Core_Playback
    UI --> Core_Downloads
    UI --> Core_Local
    UI --> Core_Model

    Coord --> Core_Playback
    Coord --> Core_Downloads
    Coord --> Core_Local
    Coord --> Core_Model

    Service --> Core_Model
    Service -.->|Inversion of Control via Registry| Delegate

    Core_Downloads --> Core_Model
    Core_Local --> Core_Model
```

### Dependency Invariants:
1. `core:model`: Pure Kotlin JVM, strictly zero Android or Media3 dependencies.
2. `core:playback`: Depends strictly on `:core:model` and AndroidX Media3 (`exoplayer`, `session`, `common`). It has **zero** dependencies on `:core:local`, Room, or `:app`.
3. `core:local`: Depends strictly on `:core:model` and Android framework for SAF / MediaStore cursor reading.
4. `core:downloads`: Depends strictly on `:core:model`, Room, WorkManager, OkHttp, and yt-dlp.
5. Inversion of control: `NotiFyPlaybackService` in `:core:playback` does not reference `PlaybackQueueCoordinator` in `:app`. Instead, `PlaybackQueueDelegateRegistry` in `:core:playback` accepts a factory registered by `NotiFyApplication` in `:app`.

---

## 3. Playback Architecture & Threading Model

```mermaid
sequenceDiagram
    autonumber
    participant UI as PlaybackController (Main Thread)
    participant Coord as PlaybackQueueCoordinator
    participant IO as IO Dispatcher
    participant Service as NotiFyPlaybackService
    participant Player as ExoPlayer (Player Looper)
    participant Store as PlaybackSnapshotStore

    Note over UI,Player: User taps Playlist Item or Search Result
    UI->>Coord: playQueueEntries(entries, startIndex)
    Coord->>Coord: Increment session ID, update queueDescriptors
    Coord->>IO: launch initiateQueuePlayback(sessionId)
    IO->>IO: resolveDescriptor (Offline -> Local -> Online Stream)
    IO->>Player: dispatchOnPlayerLooper { setMediaItems([item]), prepare(), play() }
    
    Note over Player,Store: CRITICAL THREAD BOUNDARY (Wrong-Thread Fix)
    Player-->>Coord: State captured on Player Looper (pos, repeat, shuffle)
    Coord->>IO: launch { snapshotStore.saveSnapshot(capturedState) }
    
    Note over Coord,Player: Lookahead Prefetch (LOOKAHEAD_COUNT = 2)
    Coord->>IO: launch triggerLookaheadReplenishment(sessionId)
    IO->>IO: resolveDescriptor(lookaheadIndex)
    IO->>Player: dispatchOnPlayerLooper { addMediaItem(item) }
    
    Note over Player,Coord: Automatic Transition on Song Completion
    Player->>Coord: onMediaItemTransition(mediaItem, reason)
    Coord->>Coord: Match queueEntryId, update currentIndex & UI
    Coord->>IO: triggerLookaheadReplenishment(sessionId)
```

---

## 4. Complete Data Flow Diagram

```mermaid
flowchart TD
    subgraph Sources [Audio Sources]
        direction TB
        OfflineFile["1. Permanent Offline Download\n(App-Managed Storage)"]
        LocalUri["2. Valid SAF / MediaStore URI"]
        YTMStream["3. InnerTube / yt-dlp Stream\n(Ephemeral Stream URL)"]
    end

    subgraph Resolver [PlaybackQueueCoordinator Resolution]
        P1{"Priority 1:\nOffline Available?"}
        P2{"Priority 2:\nLocal URI Accessible?"}
        P3{"Offline Mode Active?"}
        P4{"Priority 4/5:\nOnline Stream Resolvable?"}
        Fail["Typed Playback Error\n(Offline Skip or UI Alert)"]
    end

    subgraph AudioPipeline [Playback & Persistence Engine]
        Exo[ExoPlayer / MediaSessionService]
        SnapFile[("Atomic filesDir/playback_snapshot.json")]
        RoomStore[("NotiFyDatabase v7 Room")]
        History[("search_history / tracks")]
    end

    Sources --> Resolver
    P1 -- Yes --> Exo
    P1 -- No --> P2
    P2 -- Yes --> Exo
    P2 -- No --> P3
    P3 -- Yes (Offline) --> Fail
    P3 -- No (Online) --> P4
    P4 -- Yes --> Exo
    P4 -- No --> Fail

    Exo -->|Player Looper Capture| SnapFile
    Exo -->|Record Play History| History
    P4 -.->|Store Matched Source ID| RoomStore
```
