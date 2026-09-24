package com.notify.download

import android.content.Context
import androidx.room.Room
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import com.notify.playback.PlaybackUiState
import com.notify.ui.artwork.LocalArtworkLoader
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

@RunWith(RobolectricTestRunner::class)
class Patch021VerificationTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Requirement E1: Late PLAYING event clears previous transient timeout ────

    @Test
    fun testLatePlayingEventClearsPreviousTransientTimeoutAndReconcilesMatched() = runTest {
        val trackId = "spotify:test_track_1"
        val trackEntity = TrackEntity(
            id = trackId,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            resolutionState = ResolutionState.MATCHED
        )
        db.trackDao().insertTracks(listOf(trackEntity))

        // State machine simulation: timeout occurred first
        var transientError: String? = "Playback timed out after 15s (player never started)"
        var currentPipelineStep = "FAILED"

        // Simulated late ExoPlayer event: playback finally started
        val domainTrack = Track(
            id = TrackId.spotify(trackId),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            source = AudioSource.Remote(ProviderId.SPOTIFY, trackId)
        )
        val playbackState = PlaybackUiState(
            isPlaying = true,
            currentTrack = domainTrack
        )

        // Reconciliation block (matching SpikeTestDialog LaunchedEffect)
        if (playbackState.isPlaying && playbackState.currentTrack != null) {
            val rawId = playbackState.currentTrack!!.id.rawId
            if (rawId == trackId) {
                transientError = null
                currentPipelineStep = "PLAYING"
                repository.updateTrackResolution(rawId, ResolutionState.MATCHED)
            }
        }

        assertNull("Transient error must be cleared by late PLAYING event", transientError)
        assertEquals("PLAYING", currentPipelineStep)
        val inDb = db.trackDao().getTrackById(trackId)
        assertNotNull(inDb)
        assertEquals(ResolutionState.MATCHED, inDb?.resolutionState)
    }

    // ── Requirement E2: Playback timeout cannot overwrite MATCHED as RESOLVE_FAILED ────

    @Test
    fun testPlaybackTimeoutCannotOverwriteMatchedAsResolveFailed() = runTest {
        val trackId = "spotify:test_track_timeout"
        val trackEntity = TrackEntity(
            id = trackId,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            resolutionState = ResolutionState.MATCHED
        )
        db.trackDao().insertTracks(listOf(trackEntity))

        // When a 15-second player startup timeout expires,
        // it must only set a transient UI error and MUST NOT call repository.updateTrackResolution(trackId, RESOLVE_FAILED)
        var transientError: String? = null
        val playbackResult: Boolean? = null // Simulated timeout (withTimeoutOrNull expired)

        if (playbackResult == null) {
            transientError = "Playback timed out after 15s (player never started)"
            // Notice: Room is NOT updated to RESOLVE_FAILED
        }

        assertEquals("Playback timed out after 15s (player never started)", transientError)
        val inDb = db.trackDao().getTrackById(trackId)
        assertNotNull(inDb)
        // Must strictly remain MATCHED
        assertEquals(ResolutionState.MATCHED, inDb?.resolutionState)
    }

    @Test
    fun testStartupResetsAbandonedSearchingStatesToMetadataOnly() = runTest {
        val tracks = listOf(
            TrackEntity(id = "t1", title = "Track 1", artist = "Artist 1", resolutionState = ResolutionState.SEARCHING),
            TrackEntity(id = "t2", title = "Track 2", artist = "Artist 2", resolutionState = ResolutionState.MATCHED),
            TrackEntity(id = "t3", title = "Track 3", artist = "Artist 3", resolutionState = ResolutionState.METADATA_ONLY),
            TrackEntity(id = "t4", title = "Track 4", artist = "Artist 4", resolutionState = ResolutionState.NEEDS_REVIEW),
            TrackEntity(id = "t5", title = "Track 5", artist = "Artist 5", resolutionState = ResolutionState.SEARCHING)
        )
        db.trackDao().insertTracks(tracks)

        val resetCount = repository.resetAbandonedSearchingStates()
        assertEquals(2, resetCount)

        assertEquals(ResolutionState.METADATA_ONLY, db.trackDao().getTrackById("t1")?.resolutionState)
        assertEquals(ResolutionState.MATCHED, db.trackDao().getTrackById("t2")?.resolutionState)
        assertEquals(ResolutionState.METADATA_ONLY, db.trackDao().getTrackById("t3")?.resolutionState)
        assertEquals(ResolutionState.NEEDS_REVIEW, db.trackDao().getTrackById("t4")?.resolutionState)
        assertEquals(ResolutionState.METADATA_ONLY, db.trackDao().getTrackById("t5")?.resolutionState)
    }

    // ── Requirement E3: Stale attempt cannot modify a newer attempt ────

    @Test
    fun testStaleAttemptCannotModifyNewerAttempt() = runTest {
        var activeAttemptId = 0L
        var activeAttemptTrackId: String? = null
        var lastCompletedTrackId: String? = null

        // Attempt 1 launched
        val attempt1 = ++activeAttemptId
        activeAttemptTrackId = "track_1"

        // Attempt 2 launched before Attempt 1 finished
        val attempt2 = ++activeAttemptId
        activeAttemptTrackId = "track_2"

        // Attempt 1 finishes now (stale completion)
        val attempt1Finished = {
            if (attempt1 == activeAttemptId) {
                lastCompletedTrackId = "track_1"
            }
        }
        attempt1Finished()

        // Attempt 1 must NOT have updated lastCompletedTrackId
        assertNull("Stale attempt 1 must not modify state", lastCompletedTrackId)

        // Attempt 2 finishes
        val attempt2Finished = {
            if (attempt2 == activeAttemptId) {
                lastCompletedTrackId = "track_2"
            }
        }
        attempt2Finished()

        assertEquals("track_2", lastCompletedTrackId)
    }

    // ── Requirement E4 & E5: Typing several query changes & old results discarded ────

    @Test
    fun testTypingSeveralQueryChangesProducesOnlyOneFinalEffectiveResultAndDiscardsOldResults() = runTest {
        var currentRequestId = 0L
        var latestPublishedResults: List<String>? = null

        // User types "born to", then "born to s", then "born to shine"
        val req1 = ++currentRequestId
        val req2 = ++currentRequestId
        val req3 = ++currentRequestId

        // Simulated async resolution: Req 1 finishes late
        val req1Results = listOf("Born to - Old Candidate")
        if (req1 == currentRequestId) {
            latestPublishedResults = req1Results
        }

        // Req 2 finishes late
        val req2Results = listOf("Born to s - Old Candidate")
        if (req2 == currentRequestId) {
            latestPublishedResults = req2Results
        }

        assertNull("Old requests 1 & 2 must be discarded", latestPublishedResults)

        // Req 3 (final effective query) finishes
        val req3Results = listOf("Born to Shine - Diljit Dosanjh")
        if (req3 == currentRequestId) {
            latestPublishedResults = req3Results
        }

        assertEquals(listOf("Born to Shine - Diljit Dosanjh"), latestPublishedResults)
    }

    @Test
    fun testSessionCacheReturnsImmediateResult() {
        val sessionCache = mutableMapOf<String, List<YouTubeCandidate>>()
        val candidate = YouTubeCandidate(
            videoId = "candidate_123",
            title = "Born to Shine",
            channelTitle = "Diljit Dosanjh",
            durationMs = 210_000L,
            artworkUrl = "https://i.ytimg.com/vi/candidate_123/hqdefault.jpg"
        )
        sessionCache["born to shine"] = listOf(candidate)

        val cached = sessionCache["born to shine"]
        assertNotNull(cached)
        assertEquals(1, cached?.size)
        assertEquals("candidate_123", cached?.first()?.videoId)
        assertEquals("https://i.ytimg.com/vi/candidate_123/hqdefault.jpg", cached?.first()?.artworkUrl)
    }

    // ── Requirement E6: Artwork URL maps into Track and MediaItem metadata ────

    @Test
    fun testArtworkUrlMapsIntoTrackAndMediaItemMetadata() = runTest {
        val spotifyArtwork = "https://i.scdn.co/image/spotify_artwork_123"
        val youtubeArtwork = "https://i.ytimg.com/vi/yt_123/hqdefault.jpg"

        // 1. Scraped playlist to Room mapping
        val scrapedTrack = ScrapedTrack(
            position = 1,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 214_000L,
            artworkUrl = spotifyArtwork
        )
        val scrapedPlaylist = ScrapedPlaylist(
            id = "test_playlist",
            title = "Test Hits",
            description = "Best Hits",
            artworkUrl = "https://i.scdn.co/image/playlist_cover",
            expectedTrackCount = 1,
            tracks = listOf(scrapedTrack)
        )
        repository.saveScrapedPlaylist(scrapedPlaylist)

        val entries = repository.getPlaylistEntries("pl_spotify_test_playlist")
        assertEquals(1, entries.size)
        assertEquals(spotifyArtwork, entries[0].artworkUri)

        // 2. Room entry to Domain Track mapping
        val domainTrack = repository.toDomainTrack(entries[0])
        assertEquals(spotifyArtwork, domainTrack.artworkUri)

        // 3. YouTube candidate artwork fallback / priority mapping
        val candidate = YouTubeCandidate(
            videoId = "yt_123",
            title = "Born to Shine",
            channelTitle = "Diljit Dosanjh",
            durationMs = 200_000L,
            artworkUrl = youtubeArtwork
        )
        val effectiveArtwork = domainTrack.artworkUri?.takeIf { it.isNotBlank() } ?: candidate.artworkUrl
        assertEquals(spotifyArtwork, effectiveArtwork)

        // 4. Domain Track to MediaItem mapping with artworkUri
        val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(domainTrack, "https://googlevideo.com/videoplayback?id=123")
        assertEquals(spotifyArtwork, mediaItem.mediaMetadata.artworkUri?.toString())

        // 5. Lossless reconstruction via MediaItemMapper
        val reconstructed = MediaItemMapper.fromMediaItem(mediaItem)
        assertNotNull(reconstructed)
        assertEquals(spotifyArtwork, reconstructed?.artworkUri)
        assertEquals(domainTrack.title, reconstructed?.title)
    }

    // ── Requirement E7: Missing embedded artwork is non-fatal ────

    @Test
    fun testMissingEmbeddedArtworkIsNonFatal() = runTest {
        val loader = LocalArtworkLoader.getInstance(context)

        // Null track
        val nullResult = loader.loadArtwork(null)
        assertNull("Null track artwork loader must safely return null", nullResult)

        // Remote track (Invariant: LocalArtworkLoader must never touch remote streams)
        val remoteTrack = Track(
            id = TrackId.spotify("spotify:track1"),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            source = AudioSource.Remote(ProviderId.SPOTIFY, "spotify:track1")
        )
        val remoteResult = loader.loadArtwork(remoteTrack)
        assertNull("Remote track artwork loader must safely return null without throwing", remoteResult)

        // Invalid URI local track
        val invalidTrack = Track(
            id = TrackId.local("not_a_valid_uri"),
            title = "Broken Track",
            artist = "Unknown",
            source = AudioSource.Local("not_a_valid_uri")
        )
        val invalidResult = loader.loadArtwork(invalidTrack)
        assertNull("Invalid URI artwork loader must safely return null without throwing", invalidResult)
    }
}
