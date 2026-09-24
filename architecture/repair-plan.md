# Consolidated Repair Plan & Implementation Sequence

This plan addresses all identified defects in the exact mandated order:
1. **Wrong-thread crash**
2. **Search audio / metadata / old-playlist queue mismatch**
3. **Playlist auto-next and remote controls**
4. **Related-song radio / autoplay**

---

## Issue 1: Wrong-Thread Crash in Snapshot Persistence

### Proven Root Cause
In [PlaybackQueueCoordinator.kt](file:///c:/NotiFy/app/src/main/java/com/notify/playback/PlaybackQueueCoordinator.kt#L679-L700), `persistSnapshot()` directly queries ExoPlayer getters:
```kotlin
val p = player
val pos = p?.currentPosition?.coerceAtLeast(0L) ?: 0L
val repMode = when (p?.repeatMode) { ... }
val shuffled = p?.shuffleModeEnabled ?: false
```
`persistSnapshot()` is called from line 304 inside `scope.launch(ioDispatcher)` in `initiateQueuePlayback`. Media3 ExoPlayer requires all state reads and writes to occur on its `applicationLooper` (Main thread). Accessing `currentPosition` from a coroutine running on `Dispatchers.IO` (`DefaultDispatcher-worker-X`) triggers `IllegalStateException: Player is accessed on the wrong thread`.

### Proposed Architecture & Smallest Compatible Change
1. Ensure snapshot data is captured strictly on the Player Application Looper.
2. Separate capture from serialization:
   ```kotlin
   private fun captureAndPersistSnapshot() {
       dispatchOnPlayerLooper { p ->
           val pos = p.currentPosition.coerceAtLeast(0L)
           val repMode = when (p.repeatMode) {
               Player.REPEAT_MODE_ONE -> RepeatMode.ONE
               Player.REPEAT_MODE_ALL -> RepeatMode.ALL
               else -> RepeatMode.OFF
           }
           val shuffled = p.shuffleModeEnabled
           val currentDescriptors = queueDescriptors.toList()
           val currentIdx = currentIndex
           val autoplay = isAutoplayEnabled
           val seed = radioSeedSourceId

           // Dispatch pure disk IO with pre-captured immutable data
           val scope = serviceScope ?: CoroutineScope(Dispatchers.IO)
           scope.launch(ioDispatcher) {
               val snapshot = PlaybackSnapshot(
                   queue = currentDescriptors,
                   currentIndex = currentIdx,
                   currentPositionMs = pos,
                   repeatMode = repMode,
                   isShuffled = shuffled,
                   isAutoplayEnabled = autoplay,
                   radioSeedSourceId = seed
               )
               snapshotStore.saveSnapshot(snapshot)
           }
       }
   }
   ```
3. **Files Affected**: `app/src/main/java/com/notify/playback/PlaybackQueueCoordinator.kt`.
4. **Preservation Risks**: Zero. Database, storage, and models remain completely untouched.

---

## Issue 2: Search Audio / Metadata / Old-Playlist Queue Mismatch

### Proven Root Cause
1. In `SearchScreen` -> `OnlineSearchViewModel.playCandidate(candidate, onPlayStream)`, the stream URL is resolved and sent to `onPlayStream(domainTrack, streamUrl)`.
2. `NotiFyNavHost` and `NotiFyApp` route `onPlayStream` to `PlaybackController.playStream(track, streamUrl)`.
3. In [PlaybackController.kt](file:///c:/NotiFy/app/src/main/java/com/notify/playback/PlaybackController.kt#L418-L440), `playStream` sets a bare `MediaItem` directly on `MediaController` via `controller.setMediaItem(mediaItem)`.
4. **Fatal Architectural Disconnect**: `PlaybackQueueCoordinator` is completely bypassed. Its `queueDescriptors` and `coordState.currentTrack` remain bound to the old playlist (e.g. "Born to Shine" at index 0).
5. In `PlaybackController.synchronizeFromController`, `coordState.currentTrack ?: currentTrack` evaluates to `coordState.currentTrack` ("Born to Shine").
6. The speaker plays search result X, while UI and notifications display "Born to Shine". When X finishes, the coordinator advances to track 2 of the old playlist.

### Proposed Architecture & Smallest Compatible Change
1. Unify search playback with the authoritative queue coordinator.
2. Tapping a search result creates a new isolated queue context:
   ```kotlin
   val searchEntry = QueueEntry(
       track = searchTrack,
       origin = QueueOrigin.USER
   )
   playbackViewModel.playQueueEntries(listOf(searchEntry), startIndex = 0)
   ```
3. In `PlaybackQueueCoordinator.playQueue`:
   - Increments `playbackSessionId`, immediately invalidating and canceling all previous playlist lookahead and prefetch jobs.
   - Clears `queueDescriptors` and sets `[searchEntry]`.
   - Sets `radioSeedSourceId = searchTrack.sourceId` for subsequent autoplay.
   - Emits new `_coordinatorState` with `currentTrack = searchTrack`, evicting the old playlist immediately.
4. Refactor `PlaybackController.playStream` to synchronize with `PlaybackQueueCoordinator` so legacy callers cannot bypass the coordinator.
5. **Files Affected**:
   - `app/src/main/java/com/notify/ui/search/OnlineSearchViewModel.kt`
   - `app/src/main/java/com/notify/ui/search/SearchScreen.kt`
   - `app/src/main/java/com/notify/ui/navigation/NotiFyNavHost.kt`
   - `app/src/main/java/com/notify/ui/NotiFyApp.kt`
   - `app/src/main/java/com/notify/playback/PlaybackController.kt`
6. **Preservation Risks**: Zero. Search history, downloaded tracks, and Room schema are preserved without mutation.

---

## Issue 3: Playlist Auto-Next and Remote Controls

### Proven Root Cause
1. `NotiFyPlaybackService.onCreate()` initializes `MediaSession.Builder(this, exoPlayer)` without attaching a `MediaSession.Callback`.
2. When a Bluetooth headset, lockscreen control, or notification Next/Previous button triggers `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`, it goes to ExoPlayer directly instead of delegating to `PlaybackQueueCoordinator`.
3. If the next item has not yet been appended to the timeline (or requires offline skipping), default player seek fails.

### Proposed Architecture & Smallest Compatible Change
1. Attach a `MediaSession.Callback` in `NotiFyPlaybackService`:
   ```kotlin
   val sessionCallback = object : MediaSession.Callback {
       override fun onPlayerCommandRequest(
           session: MediaSession,
           controller: MediaSession.ControllerInfo,
           playerCommand: Int
       ): Int {
           when (playerCommand) {
               Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
               Player.COMMAND_SEEK_TO_NEXT -> {
                   val handled = delegate?.handleNextAction() ?: false
                   if (handled) return SessionResult.RESULT_SUCCESS
               }
               Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
               Player.COMMAND_SEEK_TO_PREVIOUS -> {
                   val handled = delegate?.handlePreviousAction() ?: false
                   if (handled) return SessionResult.RESULT_SUCCESS
               }
           }
           return super.onPlayerCommandRequest(session, controller, playerCommand)
       }
   }
   sessionBuilder.setCallback(sessionCallback)
   ```
2. **Files Affected**: `core/playback/src/main/kotlin/com/notify/core/playback/NotiFyPlaybackService.kt`.
3. **Preservation Risks**: Zero. Standard Media3 API.

---

## Issue 4: Related-Song Radio / Autoplay (Gate B2)

### Proven Root Cause
`InnerTubeYouTubeMusicSearchProvider` only implements `v1/search`. No implementation of YouTube Music's `v1/next` endpoint exists in the project. Autoplay currently stops honestly at queue exhaustion.

### Proposed Architecture & Smallest Compatible Change
1. Implement `InnerTubeWatchNextProvider` making HTTP requests to `https://music.youtube.com/youtubei/v1/next` with `videoId` and `WEB_REMIX` client.
2. Extract candidate video IDs, titles, artists, and thumbnails from `tabs[0].tabRenderer.content.musicQueueRenderer`.
3. Deduplicate against current and recent queue entries.
4. When `handleNextAction` or `triggerLookaheadReplenishment` reaches the end of finite queue descriptors and `isAutoplayEnabled == true`:
   - Query `InnerTubeWatchNextProvider.getWatchNext(radioSeedSourceId)`.
   - Append eligible recommendations to `queueDescriptors` with `QueueOrigin.RADIO`.
   - Trigger normal lookahead resolution.
5. If offline or provider fails: update state with honest explanation; never loop indefinitely.
6. **Files Affected**:
   - `app/src/main/java/com/notify/download/matcher/InnerTubeWatchNextProvider.kt` (New)
   - `app/src/main/java/com/notify/playback/PlaybackQueueCoordinator.kt`
7. **Preservation Risks**: Zero. Radio items are transient in-memory queue descriptors and do not touch Room schema or permanent downloads.

---

## Verification Plan

### Automated Regression Tests
1. `AutoNextGateB1Test`: Verify 8 existing Robolectric tests pass.
2. Add new unit test `SearchPlay_EvictsOldPlaylistContext_AndAlignsMetadata`:
   - Start playlist A with 3 tracks.
   - Play search candidate X.
   - Verify `coordinatorState.currentTrack.title == X.title`.
   - Verify `queueDescriptors.size == 1`.
   - Simulate late arrival of playlist A's resolution -> verify it is rejected due to mismatched session ID.
3. Add unit test `SnapshotPersistence_PlayerLooperThreadSafety`:
   - Verify snapshot persistence can be safely triggered during playback without `IllegalStateException`.
4. Run full suite: `.\gradlew.bat testDebugUnitTest`.

### Physical Device Verification (When Device Attached)
1. Playlist auto-next through 3 songs on lockscreen with screen off.
2. Play playlist, then tap search song X -> verify audio, title, artwork, and notification agree on X.
3. Notification and Bluetooth Next / Previous button testing.
4. Airplane mode skipping.
