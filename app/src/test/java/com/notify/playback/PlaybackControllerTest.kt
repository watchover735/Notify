package com.notify.playback

import android.app.Application
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackError
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testInitialUiStateValues() {
        val controller = PlaybackController(app, testScope)

        val state = controller.uiState.value
        assertNull(state.currentTrack)
        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)
        assertEquals(0L, state.currentPositionMs)
        assertEquals(0L, state.durationMs)
        assertTrue(state.queue.isEmpty())
        assertNull(state.currentTrackIndex)
        assertNull(state.playbackError)
        assertFalse(state.isConnected)
        assertTrue(state.isSeekable)
        assertFalse(state.hasTrack)
        assertEquals(0f, state.progressFraction, 0.001f)

        controller.release()
    }

    @Test
    fun testEmptyQueueHandlingDoesNotThrowOrCorruptState() {
        val controller = PlaybackController(app, testScope)

        controller.playQueue(emptyList())

        val state = controller.uiState.value
        assertNull(state.currentTrack)
        assertTrue(state.queue.isEmpty())
        assertNull(state.playbackError)

        controller.release()
    }

    @Test
    fun testProgressFractionCalculatesCorrectly() {
        val track = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/1"),
            title = "Track",
            artist = "Artist",
            durationMs = 200_000L,
            source = AudioSource.Local("content://media/1")
        )

        val state = PlaybackUiState(
            currentTrack = track,
            currentPositionMs = 50_000L,
            durationMs = 200_000L
        )

        assertEquals(0.25f, state.progressFraction, 0.001f)
        assertTrue(state.hasTrack)
    }

    @Test
    fun testClearErrorRemovesErrorFromState() {
        val controller = PlaybackController(app, testScope)

        // Inject error in state
        val error = PlaybackError.MissingFile("content://test", "Missing")
        controller.clearError()

        assertNull(controller.uiState.value.playbackError)
        controller.release()
    }

    @Test
    fun testPlayQueueWithInaccessibleLocalUriGracefullySetsError() {
        val controller = PlaybackController(app, testScope)

        val nonExistentTrack = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/999999999"),
            title = "Missing Track",
            artist = "Artist",
            durationMs = 120_000L,
            source = AudioSource.Local(contentUriString = "content://media/external/audio/media/999999999")
        )

        controller.playQueue(listOf(nonExistentTrack), 0)

        val state = controller.uiState.value
        assertNotNull(state.playbackError)
        assertTrue(state.playbackError is PlaybackError.MissingFile || state.playbackError is PlaybackError.PermissionRevoked)
        assertFalse(state.isPlaying)

        controller.release()
    }

    @Test
    fun testSynchronizeFromControllerHydratesUiStateFromAlreadyPlayingSession() {
        val controller = PlaybackController(app, testScope)

        val track1 = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/101"),
            title = "Song One",
            artist = "Artist A",
            album = "Album A",
            durationMs = 200_000L,
            source = AudioSource.Local("content://media/external/audio/media/101")
        )
        val track2 = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/102"),
            title = "Song Two",
            artist = "Artist B",
            album = "Album B",
            durationMs = 180_000L,
            source = AudioSource.Local("content://media/external/audio/media/102")
        )

        val mediaItem1 = MediaItemMapper.toMediaItem(track1)
        val mediaItem2 = MediaItemMapper.toMediaItem(track2)

        val mockMediaController = org.mockito.Mockito.mock(androidx.media3.session.MediaController::class.java)
        org.mockito.Mockito.`when`(mockMediaController.currentMediaItemIndex).thenReturn(1)
        org.mockito.Mockito.`when`(mockMediaController.duration).thenReturn(180_000L)
        org.mockito.Mockito.`when`(mockMediaController.currentPosition).thenReturn(45_000L)
        org.mockito.Mockito.`when`(mockMediaController.mediaItemCount).thenReturn(2)
        org.mockito.Mockito.`when`(mockMediaController.getMediaItemAt(0)).thenReturn(mediaItem1)
        org.mockito.Mockito.`when`(mockMediaController.getMediaItemAt(1)).thenReturn(mediaItem2)
        org.mockito.Mockito.`when`(mockMediaController.currentMediaItem).thenReturn(mediaItem2)
        org.mockito.Mockito.`when`(mockMediaController.isPlaying).thenReturn(true)
        org.mockito.Mockito.`when`(mockMediaController.playbackState).thenReturn(androidx.media3.common.Player.STATE_READY)
        org.mockito.Mockito.`when`(mockMediaController.isCurrentMediaItemSeekable).thenReturn(true)

        // Immediately read and hydrate state from the connected controller
        controller.synchronizeFromController(mockMediaController)

        val state = controller.uiState.value
        assertTrue(state.isControllerConnected)
        assertTrue(state.isConnected)
        assertNotNull(state.currentTrack)
        assertEquals(track2.id, state.currentTrack?.id)
        assertEquals("Song Two", state.currentTrack?.title)
        assertTrue(state.hasTrack)
        assertTrue(state.isPlaying)
        assertEquals(45_000L, state.currentPositionMs)
        assertEquals(180_000L, state.durationMs)
        assertEquals(2, state.queue.size)
        assertEquals(1, state.currentIndex)
        assertEquals(1, state.currentTrackIndex)
        assertNull(state.playbackError)
        assertEquals(0.25f, state.progressFraction, 0.001f)

        controller.release()
    }
}
