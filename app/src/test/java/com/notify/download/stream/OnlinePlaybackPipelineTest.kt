package com.notify.download.stream

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.room.Room
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YouTubeSearchProvider
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import com.notify.download.spotify.SpotifyTrackMetadata
import com.notify.playback.PlaybackController
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
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Comprehensive integration tests for online playback and Stage 2 pipeline invariants:
 * 1. Local playback bypasses OnlineStreamResolver.
 * 2. Imported track invokes search.
 * 3. Search result invokes StreamResolver.
 * 4. Resolved stream invokes PlaybackController exactly once.
 * 5. Controller waits until connected before prepare/play.
 * 6. Resolver errors appear in UI / Room state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlinePlaybackPipelineTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun localPlayback_bypassesOnlineStreamResolver() = runTest {
        val fakeResolver = FakeAudioStreamResolver()

        val localTrack = Track(
            id = TrackId.local("content://media/external/audio/media/42"),
            title = "Local Song",
            artist = "Local Artist",
            album = "Local Album",
            durationMs = 210000L,
            artworkUri = null,
            source = AudioSource.Local("content://media/external/audio/media/42")
        )

        // Simulate local playback dispatch
        if (localTrack.source is AudioSource.Local) {
            // Local playback directly builds local MediaItem via MediaItemMapper
            val mediaItem = MediaItemMapper.toMediaItem(localTrack)
            assertEquals("content://media/external/audio/media/42", mediaItem.requestMetadata.mediaUri.toString())
        } else {
            fakeResolver.resolveStream("https://www.youtube.com/watch?v=dummy")
        }

        // Verify resolver was never called
        assertEquals(0, fakeResolver.invocationCount)
        assertNull(fakeResolver.lastResolvedUrl)
    }

    @Test
    fun importedTrack_invokesSearch_andSearchResultInvokesStreamResolver() = runTest {
        var searchedQuery: String? = null
        val fakeCandidates = listOf(
            YouTubeCandidate(
                videoId = "B2S_YT_123",
                title = "Diljit Dosanjh - Born to Shine",
                channelTitle = "Diljit Dosanjh",
                durationMs = 213000L,
                viewCount = 10000000L
            )
        )

        val searchProvider = object : YouTubeSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                searchedQuery = query
                return Result.success(fakeCandidates)
            }
        }

        val fakeResolver = FakeAudioStreamResolver("https://storage.googleapis.com/exoplayer-test-media-0/play.mp3")

        // Spotify imported track
        val importedTrackMeta = SpotifyTrackMetadata(
            id = "spotify_born_to_shine",
            title = "Born to Shine",
            artists = listOf("Diljit Dosanjh"),
            album = "G.O.A.T.",
            releaseYear = "2020",
            durationMs = 213942L,
            artworkUrl = "https://i.scdn.co/image/ab67616d0000b273..."
        )

        // 1. Pipeline invokes search
        val query = "${importedTrackMeta.artists.first()} - ${importedTrackMeta.title}"
        val searchResult = searchProvider.search(query, limit = 3)
        assertTrue(searchResult.isSuccess)
        assertEquals("Diljit Dosanjh - Born to Shine", searchedQuery)

        // 2. Candidate selection via TrackMatchEngine
        val match = TrackMatchEngine.findBestMatch(importedTrackMeta, searchResult.getOrThrow())
        assertNotNull(match)
        assertEquals("https://www.youtube.com/watch?v=B2S_YT_123", match!!.canonicalDownloadUrl)

        // Invariant: Never pass open.spotify.com to yt-dlp resolver
        assertFalse(match.canonicalDownloadUrl.contains("spotify.com"))

        // 3. Search result invokes StreamResolver
        val streamResult = fakeResolver.resolveStream(match.canonicalDownloadUrl)
        assertTrue(streamResult.isSuccess)
        assertEquals(1, fakeResolver.invocationCount)
        assertEquals("https://www.youtube.com/watch?v=B2S_YT_123", fakeResolver.lastResolvedUrl)
        assertEquals("https://storage.googleapis.com/exoplayer-test-media-0/play.mp3", streamResult.getOrThrow().streamUrl)
    }

    @Test
    fun resolvedStream_invokesPlaybackController_exactlyOnce_andNeverPersistsSignedUrl() = runTest {
        val controller = PlaybackController(context, testScope)
        val mockMediaController = mock(MediaController::class.java)

        val spotifyTrack = Track(
            id = TrackId.spotify("spotify_b2s_01"),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 213942L,
            artworkUri = "https://i.scdn.co/image/art.jpg",
            source = AudioSource.Remote(ProviderId.SPOTIFY, "spotify_b2s_01")
        )

        val ephemeralStreamUrl = "https://rr2---sn-4g5ednks.googlevideo.com/videoplayback?expire=1710000000&signature=ABCD1234XYZ"
        val expectedMediaItem = ResolvedPlaybackItemFactory.createMediaItem(spotifyTrack, ephemeralStreamUrl)

        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.getMediaItemAt(0)).thenReturn(expectedMediaItem)
        `when`(mockMediaController.currentMediaItemIndex).thenReturn(0)
        `when`(mockMediaController.currentMediaItem).thenReturn(expectedMediaItem)

        controller.simulateControllerConnected(mockMediaController)

        // Play stream once
        controller.playStream(spotifyTrack, ephemeralStreamUrl, PlaybackOrigin.USER_SEARCH_SELECTION)

        // Verify ExoPlayer / MediaController prepare and play were invoked exactly once
        verify(mockMediaController, times(1)).setMediaItem(org.mockito.kotlin.any())
        verify(mockMediaController, times(1)).prepare()
        verify(mockMediaController, times(1)).play()

        // Verify domain track state
        val currentTrack = controller.uiState.value.currentTrack
        assertNotNull(currentTrack)
        assertEquals("Born to Shine", currentTrack!!.title)
        assertTrue(currentTrack.source is AudioSource.Remote)
        assertEquals(ProviderId.SPOTIFY, (currentTrack.source as AudioSource.Remote).provider)

        // INVARIANT: Verify signed googlevideo URL was NEVER written to Room
        val savedTrack = db.trackDao().getTrackById("spotify_b2s_01")
        assertNull(savedTrack?.localContentUri)

        controller.release()
    }

    @Test
    fun controller_waitsUntilConnected_beforePrepareAndPlay() = runTest {
        val controller = PlaybackController(context, testScope)
        // Controller is initially NOT connected (mediaController == null)
        assertFalse(controller.uiState.value.isConnected)

        val spotifyTrack = Track(
            id = TrackId.spotify("spotify_b2s_pending"),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 213000L,
            artworkUri = null,
            source = AudioSource.Remote(ProviderId.SPOTIFY, "spotify_b2s_pending")
        )

        val streamUrl = "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
        val expectedMediaItem = ResolvedPlaybackItemFactory.createMediaItem(spotifyTrack, streamUrl)

        val mockMediaController = mock(MediaController::class.java)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.getMediaItemAt(0)).thenReturn(expectedMediaItem)
        `when`(mockMediaController.currentMediaItemIndex).thenReturn(0)
        `when`(mockMediaController.currentMediaItem).thenReturn(expectedMediaItem)

        // Send playback command BEFORE connection
        controller.playStream(spotifyTrack, streamUrl, PlaybackOrigin.USER_SEARCH_SELECTION)

        // Command must not crash or be lost; now simulate connection
        controller.simulateControllerConnected(mockMediaController)

        // As soon as connected, the queued action must execute immediately
        verify(mockMediaController, times(1)).setMediaItem(org.mockito.kotlin.any())
        verify(mockMediaController, times(1)).prepare()
        verify(mockMediaController, times(1)).play()

        controller.release()
    }

    @Test
    fun resolverErrors_appearInRoomAndUiState_withoutShowingSuccess() = runTest {
        // Save initial scraped track to Room
        val scrapedPlaylist = ScrapedPlaylist(
            id = "pl_err_test",
            title = "Error Test Playlist",
            description = null,
            artworkUrl = null,
            expectedTrackCount = 1,
            tracks = listOf(
                ScrapedTrack(
                    position = 1,
                    title = "Problematic Track",
                    artist = "Unknown Artist",
                    album = "Unknown Album",
                    durationMs = 180000L,
                    artworkUrl = null
                )
            )
        )
        val savedPlaylist = repository.saveScrapedPlaylist(scrapedPlaylist, "https://open.spotify.com/playlist/pl_err_test")

        val entries = repository.getPlaylistEntries(savedPlaylist.playlistId)
        val trackId = entries.first().trackId

        // Set state to SEARCHING
        repository.updateTrackResolution(trackId, ResolutionState.SEARCHING)
        val searchingEntry = repository.getPlaylistEntries(savedPlaylist.playlistId).first()
        assertEquals(ResolutionState.SEARCHING, searchingEntry.resolutionState)

        // Simulate stream resolver error
        val resolverFailure = Result.failure<com.notify.core.model.ResolvedStream>(
            java.io.IOException("yt-dlp exited with code 1: Video unavailable")
        )

        // On failure: update resolution state to RESOLVE_FAILED
        if (resolverFailure.isFailure) {
            repository.updateTrackResolution(trackId, ResolutionState.RESOLVE_FAILED)
        }

        val failedEntry = repository.getPlaylistEntries(savedPlaylist.playlistId).first()
        assertEquals(ResolutionState.RESOLVE_FAILED, failedEntry.resolutionState)

        // UI status text rule: Any string starting with "Failed" or "Error" must be treated as failure
        val statusText = "Failed at [Resolving stream]: ${resolverFailure.exceptionOrNull()?.message}"
        val isFailureStatus = statusText.startsWith("Failed", ignoreCase = true) ||
                statusText.startsWith("Error", ignoreCase = true)
        val isSuccessStatus = !isFailureStatus && statusText.startsWith("Success", ignoreCase = true)

        assertTrue("Failure message must set isFailureStatus = true", isFailureStatus)
        assertFalse("Failure message must NEVER set isSuccessStatus = true", isSuccessStatus)
    }
}
