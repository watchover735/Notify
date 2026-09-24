# Feature Status & Verification Matrix

This matrix classifies all NotiFy playback behaviours into five rigorous categories:
1. **Verified with Device Evidence**: Physically observed and verified on real Android hardware.
2. **Reported Working**: Observed by user in prior builds; awaiting re-verification on current build.
3. **Automated-Only**: Fully covered and verified by automated unit / Robolectric test suites.
4. **Broken**: Confirmed bug with proven root cause identified in codebase.
5. **Not Implemented**: Feature designed in specification but lacking functional implementation.

| Feature / Situational Behaviour | Current Status | Supporting Evidence & Implementation Details |
| :--- | :--- | :--- |
| **Play playlist item N** (e.g. item 5 -> item 6) | **Automated-Only** | Covered by `AutoNextGateB1Test.tappingTrack2_buildsQueueStartingAtIndex1_andRetainsPredecessors`. Builds full queue starting at index N, retains predecessors. |
| **Browsing without queue interruption** | **Reported Working** | Navigation in Compose UI does not dispatch player stop or mutate `PlaybackQueueCoordinator`. |
| **Tap Play on Search Result X** (Context Replacement) | **Automated-Only** | Repaired in `PlaybackController.playStream` + `PlaybackQueueCoordinator.playResolvedItem`. Verified by `AutoNextGateB1Test.playSearchSongX_evictsPlaylistA_andAlignsMetadata` and `delayedOldSessionPrefetch_doesNotPolluteNewSearchContext`. |
| **Add to Queue / Play Next** | **Not Implemented** | UI contextual menus lack explicit "Add to Queue" and "Play Next" actions targeting `PlaybackQueueCoordinator`. |
| **Natural Track Completion (Auto-Next)** | **Automated-Only** | `LOOKAHEAD_COUNT = 2` contiguous item prefetching and `MediaItemMapper.getQueueEntryId(mediaItem)` in `onMediaItemTransition`. Covered by `AutoNextGateB1Test.transitionByQueueEntryId_advancesCurrentIndexAccurately`. |
| **Manual Next (In-App UI)** | **Automated-Only** | Covered by `AutoNextGateB1Test.immediateNext_beforePrefetchCompletes_handlesNextGracefully`. Handles lookahead hits, pending resolution, and offline bounds. |
| **Notification & Bluetooth Next / Previous** | **Automated-Only** | Repaired in `NotiFyPlaybackService.onCreate()` with custom `MediaSession.Callback` routing `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` and `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` to delegate. |
| **Previous Button Rule** (>3s restart vs previous track) | **Automated-Only** | Implemented in `PlaybackQueueCoordinator.handlePreviousAction()`. Covered by `AutoNextGateB1Test.previousAction_handlesNavigationBackToTrack1`. |
| **Repeat Modes (OFF / ONE / ALL)** | **Automated-Only** | Implemented in `PlaybackController.cycleRepeatMode()`. Covered by `AutoNextGateB1Test.autoplayAndRepeatInvariants`. |
| **Shuffle Mode** | **Automated-Only** | Toggles `Player.setShuffleModeEnabled`. State captured in `PlaybackSnapshot`. |
| **Offline Playback Priority 1** (Validated downloads) | **Automated-Only** | Verified via `downloadManager.getOfflinePlaybackUri(trackId)`. Validated app-managed storage prioritized over online search/streaming. |
| **Offline Skipping & Boundary Handling** | **Automated-Only** | Implemented in `PlaybackQueueCoordinator.findNextPlayableDescriptorIndex()`. Skips non-downloaded items when offline and shows "No more downloaded songs available offline." |
| **State Snapshot Persistence (Thread Safety)** | **Automated-Only** | Repaired in `PlaybackQueueCoordinator.persistSnapshot()`. Player position, repeat, and shuffle are captured strictly on the Player Application Looper before dispatching IO serialization. |
| **Snapshot Restore across Cold Start** | **Automated-Only** | Covered by `AutoNextGateB1Test.snapshotPersistence_neverPersistsSignedStreamingUrls` and `corruptJsonSnapshot_doesNotCrashStartup`. Restores paused state. |
| **Live Service Reconnect** (App reopen while playing) | **Automated-Only** | Implemented in `PlaybackController.synchronizeFromController()`. Hydrates UI without restarting playback. Verified by `PlaybackControllerTest.testSynchronizeFromControllerHydratesUiStateFromAlreadyPlayingSession`. |
| **YouTube Music Radio (Related Song Autoplay)** | **Automated-Only** | Implemented via `InnerTubeWatchNextProvider` (`v1/next` endpoint with `WEB_REMIX`). Appends related candidate descriptors when finite queue is exhausted and Autoplay is enabled. |
| **Permanent Offline Storage Invariants** | **Verified with Device Evidence** | Verified in Phase A / Patch 022. Offline downloads are stored in app-managed internal storage with atomic keys and zero persistent signed URLs. |
| **Track Matching & Metadata Enrichment** | **Reported Working** | InnerTube primary search with yt-dlp fallback operational in `OnlineSearchViewModel`. |

*Note: Device verification is marked as "NOT RUN (No device attached via ADB)" for features requiring physical device validation.*
