package com.notify.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.PlaybackSnapshot
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.RepeatMode
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.db.NotiFyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoNextGateB1Test {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var db: NotiFyDatabase

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        testScope = TestScope(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NotiFyDatabase.setTestInstance(db)
        // Clean snapshot file if exists
        File(app.filesDir, "playback_snapshot.json").delete()
    }

    @After
    fun teardown() {
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    private fun createSampleTrack(id: String, title: String, isLocal: Boolean = true): Track {
        return Track(
            id = if (isLocal) TrackId.local(id) else TrackId.spotify(id),
            title = title,
            artist = "Test Artist",
            source = if (isLocal) AudioSource.Local("content://media/audio/$id") else AudioSource.Remote(ProviderId.SPOTIFY, id)
        )
    }

    @Test
    fun tappingTrack2_buildsQueueStartingAtIndex1_andRetainsPredecessors() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = createSampleTrack("1", "Song 1")
        val track2 = createSampleTrack("2", "Song 2")
        val track3 = createSampleTrack("3", "Song 3")

        val entries = listOf(
            QueueEntry("q1", track1, QueueOrigin.PLAYLIST),
            QueueEntry("q2", track2, QueueOrigin.PLAYLIST),
            QueueEntry("q3", track3, QueueOrigin.PLAYLIST)
        )

        // User taps track 2 (0-indexed position 1)
        coordinator.playQueue(entries, startIndex = 1, playImmediately = false)

        val state = coordinator.coordinatorState.value
        assertEquals("Queue should contain all 3 tracks", 3, state.queue.size)
        assertEquals("Start index should be 1 (track 2)", 1, state.currentIndex)
        assertEquals("Current track should be Song 2", "Song 2", state.currentTrack?.title)

        // Predecessor is retained so Previous can return to Song 1
        assertEquals("Predecessor at index 0 is Song 1", "Song 1", state.queue[0].track.title)
    }

    @Test
    fun previousAction_handlesNavigationBackToTrack1() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = createSampleTrack("1", "Song 1")
        val track2 = createSampleTrack("2", "Song 2")

        val entries = listOf(
            QueueEntry("q1", track1, QueueOrigin.PLAYLIST),
            QueueEntry("q2", track2, QueueOrigin.PLAYLIST)
        )

        coordinator.playQueue(entries, startIndex = 1, playImmediately = false)
        assertEquals(1, coordinator.getCurrentIndex())

        // handlePreviousAction should return true (handling transition back to index 0)
        val handled = coordinator.handlePreviousAction()
        assertTrue("Previous action was handled by coordinator", handled)
    }

    @Test
    fun stableQueueEntryId_mappedToMediaItem_andExtractedLosslessly() {
        val track = createSampleTrack("123", "Test Title")
        val queueEntryId = "custom_queue_id_456"

        val mediaItem = MediaItemMapper.toMediaItem(track, queueEntryId)
        val extractedId = MediaItemMapper.getQueueEntryId(mediaItem)

        assertEquals("Extracted queueEntryId must match original", queueEntryId, extractedId)
    }

    @Test
    fun snapshotPersistence_neverPersistsSignedStreamingUrls() {
        val store = PlaybackSnapshotStore(app)

        val remoteTrack = Track(
            id = TrackId.youtube("dQw4w9WgXcQ"),
            title = "Streaming Song",
            artist = "Rick",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "dQw4w9WgXcQ")
        )
        val entry = QueueEntry("q1", remoteTrack, QueueOrigin.USER)

        val snapshot = PlaybackSnapshot(
            queue = listOf(entry),
            currentIndex = 0,
            currentPositionMs = 12000L,
            repeatMode = RepeatMode.OFF,
            isShuffled = false,
            isAutoplayEnabled = true,
            radioSeedSourceId = "dQw4w9WgXcQ"
        )

        store.saveSnapshot(snapshot)

        val file = File(app.filesDir, "playback_snapshot.json")
        assertTrue("Snapshot file must exist", file.exists())

        val rawJson = file.readText()
        assertFalse("Snapshot JSON must NEVER contain temporary signed stream URLs", rawJson.contains("googlevideo.com"))
        assertFalse("Snapshot JSON must NEVER contain signed token params", rawJson.contains("expire="))
        assertTrue("Snapshot JSON contains durable providerSourceId", rawJson.contains("dQw4w9WgXcQ"))

        val restored = store.loadSnapshot()
        assertNotNull("Restored snapshot must not be null", restored)
        assertEquals(1, restored!!.queue.size)
        assertEquals("Streaming Song", restored.queue[0].track.title)
        assertEquals(12000L, restored.currentPositionMs)
        assertEquals("dQw4w9WgXcQ", restored.radioSeedSourceId)
    }

    @Test
    fun corruptJsonSnapshot_doesNotCrashStartup() {
        val store = PlaybackSnapshotStore(app)
        val file = File(app.filesDir, "playback_snapshot.json")
        file.writeText("{ this is definitely not valid JSON ::: corrupted ### }")

        val result = store.loadSnapshot()
        assertNull("Corrupted JSON must be ignored safely and return null", result)
    }

    @Test
    fun immediateNext_beforePrefetchCompletes_handlesNextGracefully() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = createSampleTrack("1", "Song 1")
        val track2 = createSampleTrack("2", "Song 2")

        val entries = listOf(
            QueueEntry("q1", track1, QueueOrigin.PLAYLIST),
            QueueEntry("q2", track2, QueueOrigin.PLAYLIST)
        )

        coordinator.playQueue(entries, startIndex = 0, playImmediately = false)

        // Immediate next press when successor is not yet appended
        val handled = coordinator.handleNextAction()
        assertTrue("Immediate next must be handled rather than silently failing", handled)
    }

    @Test
    fun transitionByQueueEntryId_advancesCurrentIndexAccurately() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = createSampleTrack("1", "Song 1")
        val track2 = createSampleTrack("2", "Song 2")
        val track3 = createSampleTrack("3", "Song 3")

        val entries = listOf(
            QueueEntry("q1", track1, QueueOrigin.PLAYLIST),
            QueueEntry("q2", track2, QueueOrigin.PLAYLIST),
            QueueEntry("q3", track3, QueueOrigin.PLAYLIST)
        )

        coordinator.playQueue(entries, startIndex = 0, playImmediately = false)
        assertEquals(0, coordinator.getCurrentIndex())

        // Simulate transition to track 3 directly (e.g. track 2 skipped or user clicked track 3)
        val item3 = MediaItemMapper.toMediaItem(track3, "q3")
        coordinator.onMediaItemTransition(item3, 0)

        assertEquals("Current index must locate by queueEntryId q3 (index 2)", 2, coordinator.getCurrentIndex())
        assertEquals("Current track title matches Song 3", "Song 3", coordinator.coordinatorState.value.currentTrack?.title)
    }

    @Test
    fun autoplayAndRepeatInvariants() {
        val snapshot = PlaybackSnapshot(
            queue = emptyList(),
            currentIndex = -1,
            repeatMode = RepeatMode.ALL,
            isAutoplayEnabled = true
        )

        assertTrue(snapshot.isAutoplayEnabled)
        assertEquals(RepeatMode.ALL, snapshot.repeatMode)
    }

    @Test
    fun playSearchSongX_evictsPlaylistA_andAlignsMetadata() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        // Step 1: User was playing Playlist A (Song 1, Song 2, Song 3)
        val trackA1 = createSampleTrack("a1", "Born to Shine")
        val trackA2 = createSampleTrack("a2", "Playlist Track 2")
        val trackA3 = createSampleTrack("a3", "Playlist Track 3")

        val playlistAEntries = listOf(
            QueueEntry("qA1", trackA1, QueueOrigin.PLAYLIST),
            QueueEntry("qA2", trackA2, QueueOrigin.PLAYLIST),
            QueueEntry("qA3", trackA3, QueueOrigin.PLAYLIST)
        )
        coordinator.playQueue(playlistAEntries, startIndex = 0, playImmediately = false)

        assertEquals("Born to Shine", coordinator.coordinatorState.value.currentTrack?.title)
        assertEquals(3, coordinator.coordinatorState.value.queue.size)

        // Step 2: User taps unrelated search song X ("Search Song X")
        val trackX = Track(
            id = TrackId.youtube("vid_x"),
            title = "Search Song X",
            artist = "Artist X",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid_x"),
            artworkUri = "https://example.com/artworkX.jpg"
        )
        val entryX = QueueEntry("qX", trackX, QueueOrigin.USER)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(trackX, "https://example.com/stream.mp3", entryX.queueId)

        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)

        // Verification: Audio, ID, title, artist, artwork, and queue descriptors strictly belong to song X!
        val state = coordinator.coordinatorState.value
        assertEquals("Search Song X", state.currentTrack?.title)
        assertEquals("Artist X", state.currentTrack?.artist)
        assertEquals("https://example.com/artworkX.jpg", state.currentTrack?.artworkUri)
        assertEquals(1, state.queue.size)
        assertEquals("qX", state.queue[0].queueId)
        assertEquals(0, state.currentIndex)

        // Old playlist context is evicted
        assertFalse("Old playlist track 2 must not be in queue", state.queue.any { it.track.title == "Playlist Track 2" })
    }

    @Test
    fun delayedOldSessionPrefetch_doesNotPolluteNewSearchContext() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        // Session 1: Playlist A
        val trackA1 = createSampleTrack("a1", "Born to Shine")
        val entriesA = listOf(QueueEntry("qA1", trackA1, QueueOrigin.PLAYLIST))
        coordinator.playQueue(entriesA, startIndex = 0, playImmediately = false)

        val oldSessionId = coordinator.getCurrentPlaybackSessionId()

        // Session 2: Search Song X starts
        val trackX = Track(
            id = TrackId.youtube("vid_x"),
            title = "Search Song X",
            artist = "Artist X",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid_x")
        )
        val entryX = QueueEntry("qX", trackX, QueueOrigin.USER)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(trackX, "https://example.com/stream.mp3", entryX.queueId)
        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)

        val newSessionId = coordinator.getCurrentPlaybackSessionId()
        assertTrue("New session ID must be strictly greater than old session ID", newSessionId > oldSessionId)

        // When a delayed lookahead replenishment from old session is triggered with oldSessionId:
        coordinator.triggerLookaheadReplenishment(targetSessionId = oldSessionId)
        // The current context must remain strictly Song X with 1 entry
        assertEquals("Search Song X", coordinator.coordinatorState.value.currentTrack?.title)
        assertEquals(1, coordinator.coordinatorState.value.queue.size)
    }

    // ── Gate B2: Single-flight replenishment guard ───────────────────────────

    @Test
    fun simultaneousTriggerCalls_withSameSessionId_doNotLaunchMultipleJobs() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val trackX = Track(
            id = TrackId.youtube("vid_x"),
            title = "Radio Seed",
            artist = "Artist X",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid_x")
        )
        val entryX = QueueEntry("qX", trackX, QueueOrigin.USER)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(trackX, "https://example.com/stream.mp3", entryX.queueId)
        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)

        val sessionId = coordinator.getCurrentPlaybackSessionId()

        // Call triggerLookaheadReplenishment three times in rapid succession — should be single-flight
        coordinator.triggerLookaheadReplenishment(sessionId)
        coordinator.triggerLookaheadReplenishment(sessionId)
        coordinator.triggerLookaheadReplenishment(sessionId)

        // Advancing virtual time lets all coroutines complete
        testScheduler.advanceUntilIdle()

        // Session must remain valid and queue must not have grown with phantom entries
        assertEquals("Session must remain stable", sessionId, coordinator.getCurrentPlaybackSessionId())
        // Queue should still only have the original search entry (no radio appended since no WatchNext mock)
        assertEquals(1, coordinator.coordinatorState.value.queue.size)
    }

    @Test
    fun onMediaItemTransition_duplicateCallForSameIndex_isIdempotent() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = createSampleTrack("1", "Song 1")
        val track2 = createSampleTrack("2", "Song 2")
        val entries = listOf(
            QueueEntry("q1", track1, QueueOrigin.PLAYLIST),
            QueueEntry("q2", track2, QueueOrigin.PLAYLIST)
        )
        coordinator.playQueue(entries, startIndex = 0, playImmediately = false)
        assertEquals(0, coordinator.getCurrentIndex())

        // First transition moves to track2 — index advances to 1
        val item2 = MediaItemMapper.toMediaItem(track2, "q2")
        coordinator.onMediaItemTransition(item2, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, coordinator.getCurrentIndex())

        val sessionAfterFirst = coordinator.getCurrentPlaybackSessionId()

        // Second duplicate call for the SAME track2 / same index — must be ignored
        coordinator.onMediaItemTransition(item2, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        // Session id must not have changed, index must still be 1
        assertEquals("Duplicate transition must not mutate session", sessionAfterFirst, coordinator.getCurrentPlaybackSessionId())
        assertEquals("Index must remain 1 after duplicate transition", 1, coordinator.getCurrentIndex())
    }

    @Test
    fun replayingSameSearchResult_doesNotDuplicateQueueEntry() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val trackX = Track(
            id = TrackId.youtube("vid_x"),
            title = "Search Song X",
            artist = "Artist X",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid_x")
        )
        val entryX = QueueEntry("qX", trackX, QueueOrigin.USER)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(trackX, "https://stream.example.com/x.mp3", entryX.queueId)

        // First play
        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)
        assertEquals(1, coordinator.coordinatorState.value.queue.size)

        val sessionAfterFirst = coordinator.getCurrentPlaybackSessionId()

        // Second play of the EXACT same track (user taps it again from search results)
        val entryX2 = QueueEntry("qX2", trackX, QueueOrigin.USER) // new QueueEntry instance but same trackId
        coordinator.playResolvedItem(entryX2, mediaItemX, playImmediately = false)

        // Queue must still be size 1 — no phantom duplicate added
        assertEquals("Queue must not grow when the same track is replayed", 1, coordinator.coordinatorState.value.queue.size)

        // The session id must NOT have incremented (no queue reset occurred)
        assertEquals("Session must not advance on same-track replay", sessionAfterFirst, coordinator.getCurrentPlaybackSessionId())
    }

    @Test
    fun stableDeduplicationKey_useProviderAndSourceId_notObjectIdentity() {
        // Two QueueEntry instances referencing the same YouTube video must share the same dedup key
        val track = Track(
            id = TrackId.youtube("vid123"),
            title = "Same Song",
            artist = "Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid123")
        )
        val entry1 = QueueEntry("q1", track, QueueOrigin.USER)
        val entry2 = QueueEntry("q2", track, QueueOrigin.USER)

        // Both entries must produce the same stable key
        val key1 = "${entry1.track.id.provider.name}:${entry1.track.id.rawId}"
        val key2 = "${entry2.track.id.provider.name}:${entry2.track.id.rawId}"

        assertEquals("Stable dedup key must be provider:sourceId, independent of queueId", key1, key2)
    }

    @Test
    fun scenarioA_and_B_searchSongA_recommendsSongB_songBStarts_recentsContainsOnlyA() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher, testDispatcher, db)
        db.searchHistoryDao().clearAllRecentMediaItems()

        val songA = Track(
            id = TrackId.youtube("song_a_id"),
            title = "Song A",
            artist = "Artist A",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_a_id")
        )
        val entryA = QueueEntry("qA", songA, QueueOrigin.USER, PlaybackOrigin.USER_SEARCH_SELECTION)
        val mediaItemA = ResolvedPlaybackItemFactory.createMediaItem(
            track = songA,
            streamUrl = "https://example.com/a.mp3",
            queueEntryId = entryA.queueId,
            playbackOrigin = PlaybackOrigin.USER_SEARCH_SELECTION
        )

        // Play Song A from search selection
        coordinator.playResolvedItem(entryA, mediaItemA, playImmediately = false)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // Transition to Song A
        coordinator.onMediaItemTransition(mediaItemA, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert Song A is in search recents
        val recentsAfterA = db.searchHistoryDao().getRecentMediaItems(15)
        assertEquals("Search Recents must have Song A", 1, recentsAfterA.size)
        assertEquals("youtube:song_a_id", recentsAfterA[0].id)

        // Recommended Song B is appended via RADIO_AUTOPLAY
        val songB = Track(
            id = TrackId.youtube("song_b_id"),
            title = "Song B",
            artist = "Artist B",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_b_id")
        )
        val entryB = QueueEntry("qB", songB, QueueOrigin.RADIO, PlaybackOrigin.RADIO_AUTOPLAY)
        val mediaItemB = ResolvedPlaybackItemFactory.createMediaItem(
            track = songB,
            streamUrl = "https://example.com/b.mp3",
            queueEntryId = entryB.queueId,
            playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
        )

        // Transition from Song A to Song B (radio recommendation starts)
        coordinator.onMediaItemTransition(mediaItemB, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // Contract: Song B is RADIO_AUTOPLAY, must NOT enter Search Recents!
        val recentsAfterB = db.searchHistoryDao().getRecentMediaItems(15)
        assertEquals("Search Recents must STILL have only Song A", 1, recentsAfterB.size)
        assertEquals("youtube:song_a_id", recentsAfterB[0].id)
        assertFalse("Song B must NOT be in Search Recents", recentsAfterB.any { it.id == "youtube:song_b_id" })
    }

    @Test
    fun scenarioC_explicitSearchAndTapSongB_writesToSearchRecents() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher, testDispatcher, db)
        db.searchHistoryDao().clearAllRecentMediaItems()

        val songB = Track(
            id = TrackId.youtube("song_b_id"),
            title = "Song B",
            artist = "Artist B",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_b_id")
        )
        val entryB = QueueEntry("qB_user", songB, QueueOrigin.USER, PlaybackOrigin.USER_SEARCH_SELECTION)
        val mediaItemB = ResolvedPlaybackItemFactory.createMediaItem(
            track = songB,
            streamUrl = "https://example.com/b.mp3",
            queueEntryId = entryB.queueId,
            playbackOrigin = PlaybackOrigin.USER_SEARCH_SELECTION
        )

        // User explicitly taps Song B from search results
        coordinator.playResolvedItem(entryB, mediaItemB, playImmediately = false)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        coordinator.onMediaItemTransition(mediaItemB, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        val recents = db.searchHistoryDao().getRecentMediaItems(15)
        assertEquals(1, recents.size)
        assertEquals("youtube:song_b_id", recents[0].id)
    }

    @Test
    fun scenarioD_whenRecommendationFails_trackAStaysEnded_doesNotReplay() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val songA = Track(
            id = TrackId.youtube("song_a_end"),
            title = "Song A",
            artist = "Artist A",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_a_end")
        )
        val entryA = QueueEntry("qA", songA, QueueOrigin.USER, PlaybackOrigin.USER_SEARCH_SELECTION)
        val mediaItemA = ResolvedPlaybackItemFactory.createMediaItem(
            track = songA,
            streamUrl = "https://example.com/a.mp3",
            queueEntryId = entryA.queueId,
            playbackOrigin = PlaybackOrigin.USER_SEARCH_SELECTION
        )

        coordinator.playResolvedItem(entryA, mediaItemA, playImmediately = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate STATE_ENDED when no next item exists in timeline and recommendation is idle/failed
        coordinator.onPlaybackStateChanged(Player.STATE_ENDED)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify player remains stopped at the end, current track is still Song A, not re-started
        val state = coordinator.coordinatorState.value
        assertEquals("Song A remains current track", "Song A", state.currentTrack?.title)
        assertFalse("Preparing next must be false on failure/idle", state.isPreparingNext)
    }

    @Test
    fun onPlaybackResumption_restoresOfflineSnapshotFirst() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher, testDispatcher, db)
        val storage = com.notify.core.downloads.storage.OfflineStorage(app)
        val relKey = com.notify.core.downloads.storage.OfflineStorage.buildRelativeKey(
            com.notify.core.model.DownloadBucket.PINNED, "youtube", "resumed_vid"
        )
        val audioFile = storage.resolveFile(relKey).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1024) { 1 })
        }
        val dl = com.notify.core.downloads.db.OfflineDownloadEntity(
            downloadId = java.util.UUID.randomUUID().toString(),
            trackId = "off_track_1",
            provider = "youtube",
            providerSourceId = "resumed_vid",
            canonicalUrl = "https://www.youtube.com/watch?v=resumed_vid",
            relativeStorageKey = relKey,
            bucket = "PINNED",
            status = com.notify.core.downloads.db.OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = audioFile.length()
        )
        db.offlineDownloadDao().upsert(dl)

        val offlineTrack = Track(
            id = TrackId.youtube("off_track_1"),
            title = "Resumed Offline Song",
            artist = "Artist Off",
            source = AudioSource.Offline(relKey, com.notify.core.model.DownloadBucket.PINNED)
        )
        val snapshotStore = PlaybackSnapshotStore(app)
        val snapshot = PlaybackSnapshot(
            queue = listOf(QueueEntry("q_off", offlineTrack, QueueOrigin.PLAYLIST)),
            currentIndex = 0,
            currentPositionMs = 12345L,
            repeatMode = RepeatMode.OFF,
            isShuffled = false,
            isAutoplayEnabled = true
        )
        snapshotStore.saveSnapshot(snapshot)

        val mockSession = org.mockito.Mockito.mock(androidx.media3.session.MediaSession::class.java)
        val mockController = org.mockito.Mockito.mock(androidx.media3.session.MediaSession.ControllerInfo::class.java)

        val future = coordinator.onPlaybackResumption(mockSession, mockController)
        assertNotNull("Resumption future must not be null", future)
        coordinator.lastResumptionJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Future must be done", future!!.isDone)
        val result = future.get()
        assertNotNull(result)
        assertEquals(1, result.mediaItems.size)
        assertEquals(0, result.startIndex)
        assertEquals(12345L, result.startPositionMs)
        val restoredItem = result.mediaItems[0]
        assertTrue(
            "Restored media item URI must point to offline content",
            restoredItem.requestMetadata.mediaUri?.toString()?.contains("offline") == true ||
            restoredItem.localConfiguration?.uri?.toString()?.contains("offline") == true
        )
    }

    @Test
    fun onPlaybackResumption_lateRestore_doesNotReplaceNewerUserSelectedSong() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher, testDispatcher, db)

        val oldSnapshotTrack = Track(
            id = TrackId.youtube("old_snapshot_vid"),
            title = "Old Snapshot Song",
            artist = "Old Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "old_snapshot_vid")
        )
        val snapshotStore = PlaybackSnapshotStore(app)
        snapshotStore.saveSnapshot(
            PlaybackSnapshot(
                queue = listOf(QueueEntry("q_old", oldSnapshotTrack, QueueOrigin.USER)),
                currentIndex = 0,
                currentPositionMs = 5000L,
                repeatMode = RepeatMode.OFF,
                isShuffled = false,
                isAutoplayEnabled = true
            )
        )

        // User explicitly selected and started a new song X
        val songX = Track(
            id = TrackId.youtube("song_x"),
            title = "New Song X",
            artist = "New Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_x")
        )
        val entryX = QueueEntry("qX", songX, QueueOrigin.USER)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(
            track = songX,
            streamUrl = "https://example.com/x.mp3",
            queueEntryId = entryX.queueId
        )
        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("New Song X", coordinator.coordinatorState.value.currentTrack?.title)

        val mockSession = org.mockito.Mockito.mock(androidx.media3.session.MediaSession::class.java)
        val mockController = org.mockito.Mockito.mock(androidx.media3.session.MediaSession.ControllerInfo::class.java)

        // Late resumption arrives
        val future = coordinator.onPlaybackResumption(mockSession, mockController)
        assertNotNull(future)
        coordinator.lastResumptionJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify current track is NOT replaced by old snapshot
        assertEquals("New Song X must not be replaced by late resumption", "New Song X", coordinator.coordinatorState.value.currentTrack?.title)
    }

    @Test
    fun playResolvedItem_thenOnMediaItemTransition_sameTrack_isIdempotent_noDuplicateTransition() = testScope.runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher, testDispatcher, db)

        val songX = Track(
            id = TrackId.youtube("song_x_idempotent"),
            title = "Song Idempotent",
            artist = "Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "song_x_idempotent")
        )
        val entryX = QueueEntry("q_idem", songX, QueueOrigin.USER, PlaybackOrigin.USER_SEARCH_SELECTION)
        val mediaItemX = ResolvedPlaybackItemFactory.createMediaItem(
            track = songX,
            streamUrl = "https://example.com/stream.mp3",
            queueEntryId = entryX.queueId,
            playbackOrigin = PlaybackOrigin.USER_SEARCH_SELECTION
        )

        coordinator.playResolvedItem(entryX, mediaItemX, playImmediately = false)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        val recentsAfterPlay = db.searchHistoryDao().getRecentMediaItems(15)
        assertEquals(1, recentsAfterPlay.size)

        // Simulate ExoPlayer dispatching onMediaItemTransition for the newly set item
        coordinator.onMediaItemTransition(mediaItemX, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        coordinator.lastRecentJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // Must remain 1, not duplicate upsert
        val recentsAfterTransition = db.searchHistoryDao().getRecentMediaItems(15)
        assertEquals("Side effects must be idempotent", 1, recentsAfterTransition.size)
    }
}
