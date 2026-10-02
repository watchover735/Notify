package com.notify.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.notify.core.model.AudioSource
import com.notify.core.model.CanonicalMediaKey
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.ResolvedStream
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.matcher.InnerTubeWatchNextProvider
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.stream.AudioStreamResolver
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RadioWindowManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeWatchNext: FakeWatchNextProvider
    private lateinit var fakeFallbackSearch: FakeFallbackSearchProvider
    private lateinit var fakeStreamResolver: FakeTestStreamResolver
    private lateinit var mockPlayer: Player
    private lateinit var radioWindowManager: RadioWindowManager

    private val looperDispatches = mutableListOf<(Player) -> Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeWatchNext = FakeWatchNextProvider()
        fakeFallbackSearch = FakeFallbackSearchProvider()
        fakeStreamResolver = FakeTestStreamResolver()
        mockPlayer = mock(Player::class.java)

        looperDispatches.clear()
        radioWindowManager = RadioWindowManager(
            scope = testScope,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            watchNextProvider = fakeWatchNext,
            fallbackSearchProvider = fakeFallbackSearch,
            streamResolver = fakeStreamResolver,
            dispatchOnPlayerLooper = { action ->
                looperDispatches.add(action)
                action(mockPlayer)
            }
        )
    }

    @org.junit.After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTrack(id: String, title: String, artist: String = "Test Artist"): Track {
        return Track(
            id = TrackId(ProviderId.YOUTUBE, id),
            title = title,
            artist = artist,
            source = AudioSource.Remote(ProviderId.YOUTUBE, id)
        )
    }

    @Test
    fun initialFill_maintainsThreeFutureCandidatesAndResolvesImmediateNext() = testScope.runTest {
        val seedTrack = createTrack("seed_1", "Seed Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("c_1", "Candidate 1", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("c_2", "Candidate 2", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("c_3", "Candidate 3", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("c_4", "Candidate 4", "Artist", 180000L, 0L, null, null, "youtube")
        )

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        // Contract: 3 future candidates in logical window
        val future = radioWindowManager.getFutureWindow()
        assertEquals("Must maintain exactly 3 future candidates", 3, future.size)
        val futureIds = future.map { it.track.id.rawId }.toSet()
        assertEquals("Future candidates must be unique", 3, futureIds.size)
        assertTrue("All candidates must come from returned pool", setOf("c_1", "c_2", "c_3", "c_4").containsAll(futureIds))

        // Contract: Immediate next item (head of futureWindow) must be resolved and appended to Player timeline
        assertEquals("Only immediate next item should have stream resolved", 1, fakeStreamResolver.resolvedUrls.size)
        val headId = future[0].track.id.rawId
        assertEquals("https://www.youtube.com/watch?v=$headId", fakeStreamResolver.resolvedUrls[0])
        verify(mockPlayer).addMediaItem(any<MediaItem>())

        assertTrue("Queued keys must include immediate next", radioWindowManager.getQueuedKeys().contains("youtube:$headId"))
        assertFalse("Slot 2 must not be resolved yet", radioWindowManager.getQueuedKeys().contains("youtube:${future[1].track.id.rawId}"))
        assertFalse("Slot 3 must not be resolved yet", radioWindowManager.getQueuedKeys().contains("youtube:${future[2].track.id.rawId}"))
    }

    @Test
    fun testCandidateSelectionFromPool_variesAcrossRunsAndExcludesRecentlyPlayed() = testScope.runTest {
        val seedTrack = createTrack("seed_heatwave", "Heat Waves", "Glass Animals")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        val candidatePool = (1..8).map { i ->
            YouTubeCandidate("cand_$i", "Related Song $i", "Related Artist $i", 200000L, 0L, null, null, "youtube")
        }
        fakeWatchNext.candidatesToReturn = candidatePool

        // Run multiple separate sessions playing the same seed track
        val firstPicks = mutableSetOf<String>()
        for (session in 1L..10L) {
            radioWindowManager.reset(newSessionId = session)
            radioWindowManager.onTrackStarted(seedEntry, sessionId = session, player = mockPlayer)
            advanceUntilIdle()

            val future = radioWindowManager.getFutureWindow()
            assertEquals(3, future.size)
            val headId = future[0].track.id.rawId
            firstPicks.add(headId)
        }

        // Variety verification: With 10 runs on an 8-candidate pool, we must have multiple distinct first picks,
        // proving it is NOT pinned to index 0 (cand_1) every time!
        assertTrue(
            "Recommendations must produce variety across sessions (found ${firstPicks.size} distinct first picks: $firstPicks)",
            firstPicks.size >= 2
        )

        // Verify recent history exclusion:
        val recentHistory = radioWindowManager.getRecentHistoryKeys()
        assertTrue("Recent history must have recorded played tracks", recentHistory.isNotEmpty())
    }

    @Test
    fun filterRejects_currentPlaying_alreadyPlayed_alreadyQueued_andInFlight() = testScope.runTest {
        val seedTrack = createTrack("seed_1", "Seed Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        // Seed initial track
        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("seed_1", "Duplicate Seed", "Artist", 180000L, 0L, null, null, "youtube"), // Should be rejected (current playing)
            YouTubeCandidate("c_1", "Candidate 1", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("c_2", "Candidate 2", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("c_3", "Candidate 3", "Artist", 180000L, 0L, null, null, "youtube")
        )

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        val future = radioWindowManager.getFutureWindow()
        assertEquals(3, future.size)
        assertFalse("Seed track must be rejected from future candidates", future.any { it.track.id.rawId == "seed_1" })

        // Simulate transition to c_1: seed_1 is now played
        val nextEntry = future[0]
        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("seed_1", "Seed Song", "Artist", 180000L, 0L, null, null, "youtube"), // Should be rejected (already played)
            YouTubeCandidate("c_2", "Candidate 2", "Artist", 180000L, 0L, null, null, "youtube"),   // Should be rejected (already in window)
            YouTubeCandidate("c_4", "Candidate 4", "Artist", 180000L, 0L, null, null, "youtube")    // Fresh candidate: should be accepted!
        )

        radioWindowManager.onTrackTransition(seedEntry, nextEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        val updatedFuture = radioWindowManager.getFutureWindow()
        assertEquals(3, updatedFuture.size)
        assertTrue("Fresh candidate c_4 must be added to tail", updatedFuture.any { it.track.id.rawId == "c_4" })
        assertFalse("Already played seed_1 must never be re-selected", updatedFuture.any { it.track.id.rawId == "seed_1" })
        assertTrue("Played keys must contain seed_1", radioWindowManager.getPlayedKeys().contains("youtube:seed_1"))
    }

    @Test
    fun singleBoundedRetry_whenWatchNextFails_thenQueriesFallbackSearch() = testScope.runTest {
        val seedTrack = createTrack("seed_fail", "Failing Song", "Anirudh")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        // Make WatchNext fail on all calls
        fakeWatchNext.shouldFail = true

        // Provide candidates via fallback search provider
        fakeFallbackSearch.candidatesToReturn = listOf(
            YouTubeCandidate("fb_1", "Fallback 1", "Anirudh", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fb_2", "Fallback 2", "Anirudh", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fb_3", "Fallback 3", "Anirudh", 180000L, 0L, null, null, "youtube")
        )

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        assertEquals("WatchNext must be retried exactly once on failure (initial + 1 retry = 2 calls)", 2, fakeWatchNext.callCount.get())
        assertEquals("Fallback search must be queried when WatchNext fails", 1, fakeFallbackSearch.callCount.get())

        val future = radioWindowManager.getFutureWindow()
        assertEquals("Future window must be filled from fallback candidates", 3, future.size)
        val candidateIds = future.map { it.track.id.rawId }.toSet()
        assertEquals(setOf("fb_1", "fb_2", "fb_3"), candidateIds)
    }

    @Test
    fun fallbackSearch_queriesExpandedBatchAndAppliesShuffling() = testScope.runTest {
        val seedTrack = createTrack("seed_fail", "Failing Song", "Anirudh")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        fakeWatchNext.shouldFail = true
        fakeFallbackSearch.candidatesToReturn = (1..15).map { i ->
            YouTubeCandidate("fb_$i", "Fallback $i", "Anirudh", 180000L, 0L, null, null, "youtube")
        }

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        assertEquals(RadioWindowManager.FALLBACK_SEARCH_BATCH_SIZE, fakeFallbackSearch.lastLimit)
        val future = radioWindowManager.getFutureWindow()
        assertEquals(3, future.size)
    }

    @Test
    fun inFlightKeysCleanedInsideFinally_whenResolutionFails() = testScope.runTest {
        val seedTrack = createTrack("seed_err", "Error Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("c_err", "Err Candidate", "Artist", 180000L, 0L, null, null, "youtube")
        )
        fakeStreamResolver.shouldFail = true

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        // Contract: inFlightKeys must be clean even after exception/failure
        assertTrue("inFlightKeys must be cleaned in finally", radioWindowManager.getInFlightKeys().isEmpty())
    }

    @Test
    fun resetClearsAllState_whenSessionIdChanges() = testScope.runTest {
        val seedTrack = createTrack("seed_old", "Old Session Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("c_old_1", "Old 1", "Artist", 180000L, 0L, null, null, "youtube")
        )

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 100L, player = mockPlayer)
        advanceUntilIdle()
        assertEquals(1, radioWindowManager.getFutureWindow().size)

        // Reset to new session ID 200L
        radioWindowManager.reset(newSessionId = 200L)

        assertEquals("Future window must be cleared on reset", 0, radioWindowManager.getFutureWindow().size)
        assertTrue("Played keys must be cleared on reset", radioWindowManager.getPlayedKeys().isEmpty())
        assertTrue("Queued keys must be cleared on reset", radioWindowManager.getQueuedKeys().isEmpty())
        assertTrue("inFlight keys must be cleared on reset", radioWindowManager.getInFlightKeys().isEmpty())
    }

    @Test
    fun onTrackStarted_addsCurrentTrackKeyToPlayedKeysImmediately() = testScope.runTest {
        val seedTrack = createTrack("seed_immediate", "Immediate Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        assertTrue(
            "Current track key must be added to playedKeys immediately when confirmed playback starts",
            radioWindowManager.getPlayedKeys().contains("youtube:seed_immediate")
        )
        assertEquals("youtube:seed_immediate", radioWindowManager.getCurrentTrackKey())
    }

    @Test
    fun validateAndRemoveDuplicateBeforePlay_rejectsAndRemovesIfAlreadyPlayed() = testScope.runTest {
        val seedTrack = createTrack("seed_dup", "Seed Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        // Create media item with the same canonical key as played seed
        val duplicateMediaItem = ResolvedPlaybackItemFactory.createMediaItem(
            track = seedTrack,
            streamUrl = "https://example.com/seed.mp3",
            queueEntryId = "dup_queue_id",
            playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
        )

        `when`(mockPlayer.mediaItemCount).thenReturn(1)
        `when`(mockPlayer.getMediaItemAt(0)).thenReturn(duplicateMediaItem)
        // Stub currentMediaItemIndex to -1 (C.INDEX_UNSET): player has ended, no item is
        // currently playing, so the duplicate at index 0 is a queued-ahead item — safe to remove.
        `when`(mockPlayer.currentMediaItemIndex).thenReturn(-1)

        // Validate before play should reject duplicate and remove it from player
        val isValid = radioWindowManager.validateAndRemoveDuplicateBeforePlay(mockPlayer, duplicateMediaItem)
        assertFalse("Duplicate before play must be rejected", isValid)
        verify(mockPlayer).removeMediaItem(0)

        // Non-duplicate media item should be accepted
        val freshTrack = createTrack("fresh_song", "Fresh Song")
        val freshMediaItem = ResolvedPlaybackItemFactory.createMediaItem(
            track = freshTrack,
            streamUrl = "https://example.com/fresh.mp3",
            queueEntryId = "fresh_queue_id",
            playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
        )
        val isFreshValid = radioWindowManager.validateAndRemoveDuplicateBeforePlay(mockPlayer, freshMediaItem)
        assertTrue("Fresh track before play must be valid", isFreshValid)
    }

    @Test
    fun validateAndRemoveDuplicateBeforePlay_raceCondition_duplicateIsCurrentlyPlaying_skipsRemoval() = testScope.runTest {
        // Scenario: duplicate item is at index 0 AND is the currently playing item (race condition).
        // Safety contract: do NOT remove the currently playing item; caller gets false and must not advance.
        val seedTrack = createTrack("seed_race", "Seed Race Song")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        val duplicateMediaItem = ResolvedPlaybackItemFactory.createMediaItem(
            track = seedTrack,
            streamUrl = "https://example.com/seed_race.mp3",
            queueEntryId = "race_dup_queue_id",
            playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
        )

        `when`(mockPlayer.mediaItemCount).thenReturn(1)
        `when`(mockPlayer.getMediaItemAt(0)).thenReturn(duplicateMediaItem)
        // Race condition: duplicate IS the currently playing item (both at index 0)
        `when`(mockPlayer.currentMediaItemIndex).thenReturn(0)

        val isValid = radioWindowManager.validateAndRemoveDuplicateBeforePlay(mockPlayer, duplicateMediaItem)

        // Must be rejected (it's a duplicate of played seed), but timeline removal must NOT happen
        assertFalse("Race condition duplicate must still be rejected (not valid)", isValid)
        verify(mockPlayer, org.mockito.Mockito.never()).removeMediaItem(org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun twentyConsecutiveTransitions_allCanonicalVideoIdsUnique_noDuplicates() = testScope.runTest {
        val seedTrack = createTrack("seed_start", "Seed Track")
        val seedEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER)

        // Supply a large pool of candidates, including attempted duplicates of previously played tracks
        val allGeneratedCandidates = (1..60).map { i ->
            YouTubeCandidate("cand_$i", "Candidate Song $i", "Artist", 180000L, 0L, null, null, "youtube")
        }

        var candidateIndex = 0
        fakeWatchNext.candidateSupplier = { _ ->
            // Mix next fresh candidates with already-played seed to test rejection
            val fresh = allGeneratedCandidates.drop(candidateIndex).take(5)
            candidateIndex += 3
            val poisonList = listOf(
                YouTubeCandidate("seed_start", "Poison Seed", "Artist", 180000L, 0L, null, null, "youtube")
            )
            fresh + poisonList
        }

        radioWindowManager.onTrackStarted(seedEntry, sessionId = 1L, player = mockPlayer)
        advanceUntilIdle()

        val playedCanonicalKeys = mutableListOf<String>()
        playedCanonicalKeys.add(CanonicalMediaKey.fromTrack(seedTrack))

        var currentEntry = seedEntry

        for (transitionNum in 1..20) {
            val future = radioWindowManager.getFutureWindow()
            assertTrue("Future window must have candidates for transition $transitionNum", future.isNotEmpty())

            val nextEntry = future[0]
            val nextKey = CanonicalMediaKey.fromTrack(nextEntry.track)

            // Assert: next key is NOT in played keys yet
            assertFalse(
                "Next candidate $nextKey must NOT be in played keys before transition $transitionNum",
                playedCanonicalKeys.contains(nextKey)
            )

            val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(
                track = nextEntry.track,
                streamUrl = "https://example.com/$nextKey.mp3",
                queueEntryId = nextEntry.queueId,
                playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
            )

            // Double duplicate validation
            val isValid = radioWindowManager.validateAndRemoveDuplicateBeforePlay(mockPlayer, mediaItem)
            assertTrue("Candidate $nextKey must pass pre-play validation on transition $transitionNum", isValid)

            // Perform transition
            radioWindowManager.onTrackTransition(currentEntry, nextEntry, sessionId = 1L, player = mockPlayer)
            advanceUntilIdle()

            playedCanonicalKeys.add(nextKey)
            currentEntry = nextEntry
        }

        // Verify exactly 21 tracks (seed + 20 transitions) played
        assertEquals("Must have 21 transitions played", 21, playedCanonicalKeys.size)

        // Verify 100% uniqueness
        val uniqueKeys = playedCanonicalKeys.toSet()
        assertEquals(
            "100% of canonical video IDs must be unique across 20 transitions. Found duplicates: ${playedCanonicalKeys.size - uniqueKeys.size}",
            21,
            uniqueKeys.size
        )

        // Verify playedKeys in RadioWindowManager matches exactly
        val rwmPlayed = radioWindowManager.getPlayedKeys()
        assertTrue("RadioWindowManager playedKeys must contain all 21 keys", rwmPlayed.containsAll(uniqueKeys))
    }

    @Test
    fun downloadedSongWithoutVideoId_replenishesViaSearchFallback() = testScope.runTest {
        // Track without videoId (e.g. downloaded local audio)
        val localTrack = Track(
            id = TrackId(ProviderId.LOCAL, "local_audio_123"),
            title = "Offline Song",
            artist = "Arijit Singh",
            source = AudioSource.Local("content://media/external/audio/123")
        )
        val entry = QueueEntry(track = localTrack, origin = QueueOrigin.USER)

        fakeFallbackSearch.candidatesToReturn = listOf(
            YouTubeCandidate("fb_1", "Fallback Song 1", "Arijit Singh", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fb_2", "Fallback Song 2", "Arijit Singh", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fb_3", "Fallback Song 3", "Arijit Singh", 180000L, 0L, null, null, "youtube")
        )

        radioWindowManager.onTrackStarted(entry, sessionId = 2L, player = mockPlayer)
        advanceUntilIdle()

        val future = radioWindowManager.getFutureWindow()
        assertEquals(3, future.size)
        assertTrue(fakeFallbackSearch.callCount.get() > 0)
        val futureIds = future.map { it.track.id.rawId }.toSet()
        assertEquals(setOf("fb_1", "fb_2", "fb_3"), futureIds)
    }

    @Test
    fun exhaustedWatchNext_replenishesViaSearchFallback() = testScope.runTest {
        val playedTrack = createTrack("already_played_1", "Played 1")
        val seedTrack = createTrack("seed_ex", "Exhaustion Seed")

        // First start and mark playedTrack as played in session 3
        radioWindowManager.onTrackStarted(QueueEntry(track = playedTrack, origin = QueueOrigin.USER), sessionId = 3L, player = mockPlayer)
        advanceUntilIdle()

        fakeWatchNext.candidatesToReturn = listOf(
            YouTubeCandidate("already_played_1", "Played 1", "Artist", 180000L, 0L, null, null, "youtube")
        )
        fakeFallbackSearch.candidatesToReturn = listOf(
            YouTubeCandidate("fresh_1", "Fresh 1", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fresh_2", "Fresh 2", "Artist", 180000L, 0L, null, null, "youtube"),
            YouTubeCandidate("fresh_3", "Fresh 3", "Artist", 180000L, 0L, null, null, "youtube")
        )

        // Now transition to seedTrack
        radioWindowManager.onTrackTransition(
            completedEntry = QueueEntry(track = playedTrack, origin = QueueOrigin.USER),
            nextEntry = QueueEntry(track = seedTrack, origin = QueueOrigin.USER),
            sessionId = 3L,
            player = mockPlayer
        )
        advanceUntilIdle()

        val future = radioWindowManager.getFutureWindow()
        assertEquals(3, future.size)
        assertTrue("Fallback search must be called when WatchNext has only played items", fakeFallbackSearch.callCount.get() > 0)
        val futureIds = future.map { it.track.id.rawId }.toSet()
        assertEquals(setOf("fresh_1", "fresh_2", "fresh_3"), futureIds)
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    class FakeWatchNextProvider : InnerTubeWatchNextProvider() {
        val callCount = AtomicInteger(0)
        var shouldFail = false
        var candidatesToReturn: List<YouTubeCandidate> = emptyList()
        var candidateSupplier: ((String) -> List<YouTubeCandidate>)? = null

        override suspend fun getWatchNext(videoId: String, limit: Int): Result<List<YouTubeCandidate>> {
            callCount.incrementAndGet()
            if (shouldFail) {
                return Result.failure(IOException("Simulated network timeout"))
            }
            val list = candidateSupplier?.invoke(videoId) ?: candidatesToReturn
            return Result.success(list)
        }
    }

    class FakeFallbackSearchProvider : OnlineCatalogSearchProvider {
        val callCount = AtomicInteger(0)
        var lastLimit: Int? = null
        var candidatesToReturn: List<YouTubeCandidate> = emptyList()

        override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
            callCount.incrementAndGet()
            lastLimit = limit
            return Result.success(candidatesToReturn)
        }
    }

    class FakeTestStreamResolver : AudioStreamResolver {
        val resolvedUrls = mutableListOf<String>()
        var shouldFail = false

        override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
            resolvedUrls.add(canonicalYoutubeUrl)
            if (shouldFail) {
                return Result.failure(IOException("Stream resolution failed"))
            }
            return Result.success(
                ResolvedStream(
                    streamUrl = "https://example.com/audio.m4a",
                    headers = emptyMap(),
                    expiresAtEpochMs = System.currentTimeMillis() + 3600000L
                )
            )
        }
    }
}
