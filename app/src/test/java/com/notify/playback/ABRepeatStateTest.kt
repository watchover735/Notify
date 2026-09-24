package com.notify.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ABRepeatStateTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testABRepeatStateModelDefaults() {
        val state = ABRepeatState()
        assertNull(state.abStartMs)
        assertNull(state.abEndMs)
        assertFalse(state.isLoopActive)
        assertNull(state.errorMessage)
        assertFalse(state.isStartMarked)
        assertFalse(state.isEndMarked)
        assertFalse(state.isBothMarked)
        assertEquals(1000L, ABRepeatState.MIN_LOOP_GAP_MS)
        assertEquals(250L, ABRepeatState.TRACK_END_BUFFER_MS)
    }

    @Test
    fun testABRepeatStateFlags() {
        val startOnly = ABRepeatState(abStartMs = 5000L)
        assertTrue(startOnly.isStartMarked)
        assertFalse(startOnly.isEndMarked)
        assertFalse(startOnly.isBothMarked)

        val bothSet = ABRepeatState(abStartMs = 5000L, abEndMs = 15000L, isLoopActive = true)
        assertTrue(bothSet.isStartMarked)
        assertTrue(bothSet.isEndMarked)
        assertTrue(bothSet.isBothMarked)
        assertTrue(bothSet.isLoopActive)
    }

    @Test
    fun testToggleTapSequence_SetsA_SetsB_ClearsBoth() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(180_000L)
        `when`(mockMediaController.currentPosition).thenReturn(10_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // 1st Tap: sets Point A
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        val stateAfterTap1 = controller.abRepeatState.value
        assertEquals(10_000L, stateAfterTap1.abStartMs)
        assertNull(stateAfterTap1.abEndMs)
        assertFalse(stateAfterTap1.isLoopActive)
        assertNull(stateAfterTap1.errorMessage)

        // Advance position to 25s
        `when`(mockMediaController.currentPosition).thenReturn(25_000L)

        // 2nd Tap: sets Point B and starts loop
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        val stateAfterTap2 = controller.abRepeatState.value
        assertEquals(10_000L, stateAfterTap2.abStartMs)
        assertEquals(25_000L, stateAfterTap2.abEndMs)
        assertTrue(stateAfterTap2.isLoopActive)
        assertNull(stateAfterTap2.errorMessage)

        // 3rd Tap: clears loop
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        val stateAfterTap3 = controller.abRepeatState.value
        assertNull(stateAfterTap3.abStartMs)
        assertNull(stateAfterTap3.abEndMs)
        assertFalse(stateAfterTap3.isLoopActive)

        controller.release()
    }

    @Test
    fun testToggleTapSequence_TooClose_TriggersErrorMessage() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(180_000L)
        `when`(mockMediaController.currentPosition).thenReturn(10_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // 1st Tap at 10s
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        // 2nd Tap at 10.5s (< 1000ms after Point A)
        `when`(mockMediaController.currentPosition).thenReturn(10_500L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        val errorState = controller.abRepeatState.value
        assertEquals(10_000L, errorState.abStartMs)
        assertNull(errorState.abEndMs)
        assertFalse(errorState.isLoopActive)
        assertNotNull(errorState.errorMessage)
        assertEquals("End must be after start (min 1s)", errorState.errorMessage)

        // Dismiss error
        controller.dismissABRepeatError()
        val dismissedState = controller.abRepeatState.value
        assertNull(dismissedState.errorMessage)
        assertEquals(10_000L, dismissedState.abStartMs)

        controller.release()
    }

    @Test
    fun testUpdateABStartAndEnd_EnforcesClampingAndMinimumGap() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(60_000L)
        `when`(mockMediaController.currentPosition).thenReturn(10_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // Set A at 10s and B at 20s
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(20_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        // Try dragging Handle A beyond B - 1000ms (to 19.5s)
        controller.updateABStart(19_500L)
        assertEquals(19_000L, controller.abRepeatState.value.abStartMs) // Clamped to 20_000 - 1000

        // Try dragging Handle A below 0
        controller.updateABStart(-5_000L)
        assertEquals(0L, controller.abRepeatState.value.abStartMs)

        // Try dragging Handle B below A + 1000ms (A is 0, try setting B to 500ms)
        controller.updateABEnd(500L)
        ShadowLooper.idleMainLooper()
        assertEquals(1000L, controller.abRepeatState.value.abEndMs) // Clamped to 0 + 1000

        // Try dragging Handle B past track duration (duration 60s, try setting B to 65s)
        controller.updateABEnd(65_000L)
        ShadowLooper.idleMainLooper()
        // Should clamp to duration - TRACK_END_BUFFER_MS = 60_000 - 250 = 59_750L
        assertEquals(59_750L, controller.abRepeatState.value.abEndMs)

        controller.release()
    }

    @Test
    fun testClearABRepeat_ResetsAllFields() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(120_000L)
        `when`(mockMediaController.currentPosition).thenReturn(15_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(30_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        assertTrue(controller.abRepeatState.value.isLoopActive)

        controller.clearABRepeat()
        ShadowLooper.idleMainLooper()

        val cleared = controller.abRepeatState.value
        assertNull(cleared.abStartMs)
        assertNull(cleared.abEndMs)
        assertFalse(cleared.isLoopActive)
        assertNull(cleared.errorMessage)

        controller.release()
    }

    @Test
    fun testTrackTransition_ResetsABRepeatPoints() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(120_000L)
        `when`(mockMediaController.currentPosition).thenReturn(15_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // Set Point A and B
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(30_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        assertTrue(controller.abRepeatState.value.isLoopActive)

        // Simulate manual skip or media item transition
        controller.skipToNext()
        ShadowLooper.idleMainLooper()

        // Markers must be reset for the new track
        val resetState = controller.abRepeatState.value
        assertNull(resetState.abStartMs)
        assertNull(resetState.abEndMs)
        assertFalse(resetState.isLoopActive)

        controller.release()
    }

    @Test
    fun testLoopPolling_ShortTrack_SeeksBackToStart() = runTest(testDispatcher) {
        // Short track: 45 seconds (< 1 min)
        val shortTrackDuration = 45_000L
        val controller = PlaybackController(app, backgroundScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(shortTrackDuration)
        `when`(mockMediaController.currentPosition).thenReturn(5_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // Set loop from 5s to 15s
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(15_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        assertTrue(controller.abRepeatState.value.isLoopActive)
        assertEquals(5_000L, controller.abRepeatState.value.abStartMs)
        assertEquals(15_000L, controller.abRepeatState.value.abEndMs)

        // Fast forward time and advance position past end (e.g. 15_100L)
        `when`(mockMediaController.currentPosition).thenReturn(15_100L)
        advanceTimeBy(300L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Verify seekTo(abStartMs) was dispatched on the controller
        verify(mockMediaController).seekTo(5_000L)

        controller.release()
    }

    @Test
    fun testLoopPolling_LongTrack_SeeksBackToStart() = runTest(testDispatcher) {
        // Long track: 6 minutes = 360 seconds (> 5 min)
        val longTrackDuration = 360_000L
        val controller = PlaybackController(app, backgroundScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(longTrackDuration)
        `when`(mockMediaController.currentPosition).thenReturn(120_000L) // 2:00
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        // Set loop from 120s to 240s (4:00)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(240_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        assertTrue(controller.abRepeatState.value.isLoopActive)
        assertEquals(120_000L, controller.abRepeatState.value.abStartMs)
        assertEquals(240_000L, controller.abRepeatState.value.abEndMs)

        // Fast forward position past end (e.g. 240_200L)
        `when`(mockMediaController.currentPosition).thenReturn(240_200L)
        advanceTimeBy(300L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Verify seekTo(120_000L) was dispatched
        verify(mockMediaController).seekTo(120_000L)

        controller.release()
    }

    @Test
    fun testLoopPolling_DebouncePreventsRapidConsecutiveSeeks() = runTest(testDispatcher) {
        val controller = PlaybackController(app, backgroundScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockMediaController.duration).thenReturn(100_000L)
        `when`(mockMediaController.currentPosition).thenReturn(10_000L)
        `when`(mockMediaController.mediaItemCount).thenReturn(1)
        `when`(mockMediaController.isPlaying).thenReturn(true)

        controller.setMediaControllerForTesting(mockMediaController)
        controller.synchronizeFromController(mockMediaController)

        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()
        `when`(mockMediaController.currentPosition).thenReturn(20_000L)
        controller.toggleABRepeat()
        ShadowLooper.idleMainLooper()

        // Simulate crossed position
        `when`(mockMediaController.currentPosition).thenReturn(20_100L)
        advanceTimeBy(210L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        verify(mockMediaController).seekTo(10_000L)

        // Advance only 50ms (less than 300ms debounce buffer)
        advanceTimeBy(50L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        // No additional seekTo should have occurred yet
        org.mockito.Mockito.verify(mockMediaController, org.mockito.Mockito.times(1)).seekTo(10_000L)

        controller.release()
    }

    @Test
    fun testPlaybackQueueCoordinator_OverridesAutoAdvanceWhenLoopActive() {
        val coordinator = PlaybackQueueCoordinator.getInstance(app)
        val mockPlayer = mock(Player::class.java)
        `when`(mockPlayer.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockPlayer.hasNextMediaItem()).thenReturn(true)

        coordinator.attachPlayerForTesting(mockPlayer, testScope)

        // When loop is active at 5000ms
        coordinator.setABRepeatActive(true, 5000L)
        assertTrue(coordinator.isABRepeatActive())

        // Simulate Player.STATE_ENDED
        coordinator.onPlaybackStateChanged(Player.STATE_ENDED)
        ShadowLooper.idleMainLooper()

        // Should seek back to 5000L and play, NOT seekToNextMediaItem()
        verify(mockPlayer).seekTo(5000L)
        verify(mockPlayer).play()
        verify(mockPlayer, never()).seekToNextMediaItem()
    }

    @Test
    fun testPlaybackQueueCoordinator_ResumesAutoAdvanceWhenLoopCleared() {
        val coordinator = PlaybackQueueCoordinator.getInstance(app)
        val mockPlayer = mock(Player::class.java)
        `when`(mockPlayer.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockPlayer.hasNextMediaItem()).thenReturn(true)
        `when`(mockPlayer.currentMediaItemIndex).thenReturn(0)

        coordinator.attachPlayerForTesting(mockPlayer, testScope)

        // Loop active then cleared
        coordinator.setABRepeatActive(true, 5000L)
        coordinator.setABRepeatActive(false, 0L)
        assertFalse(coordinator.isABRepeatActive())

        // Reset invocations
        org.mockito.Mockito.reset(mockPlayer)
        `when`(mockPlayer.applicationLooper).thenReturn(Looper.getMainLooper())
        `when`(mockPlayer.hasNextMediaItem()).thenReturn(true)
        `when`(mockPlayer.currentMediaItemIndex).thenReturn(0)
        `when`(mockPlayer.getMediaItemAt(1)).thenReturn(
            MediaItemMapper.toMediaItem(
                Track(
                    id = TrackId(ProviderId.LOCAL, "content://media/2"),
                    title = "Next Song",
                    artist = "Artist",
                    durationMs = 180_000L,
                    source = com.notify.core.model.AudioSource.Local("content://media/2")
                )
            )
        )

        coordinator.onPlaybackStateChanged(Player.STATE_ENDED)
        ShadowLooper.idleMainLooper()

        // Normal auto-transition proceeds without looping back
        verify(mockPlayer, never()).seekTo(5000L)
    }
}
