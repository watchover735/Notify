package com.notify.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.notify.core.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackShuffleRepeatTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testDefaultShuffleAndRepeatMode() {
        val controller = PlaybackController(app, testScope)
        assertFalse(controller.uiState.value.shuffleEnabled)
        assertEquals(RepeatMode.OFF, controller.uiState.value.repeatMode)
        controller.release()
    }

    @Test
    fun testSynchronizeExtractsShuffleAndRepeatFromMediaController() {
        val controller = PlaybackController(app, testScope)
        val mockMediaController = mock(MediaController::class.java)

        `when`(mockMediaController.shuffleModeEnabled).thenReturn(true)
        `when`(mockMediaController.repeatMode).thenReturn(Player.REPEAT_MODE_ALL)
        `when`(mockMediaController.mediaItemCount).thenReturn(0)

        controller.synchronizeFromController(mockMediaController)

        assertTrue(controller.uiState.value.shuffleEnabled)
        assertEquals(RepeatMode.ALL, controller.uiState.value.repeatMode)

        // Test repeat one
        `when`(mockMediaController.repeatMode).thenReturn(Player.REPEAT_MODE_ONE)
        controller.synchronizeFromController(mockMediaController)
        assertEquals(RepeatMode.ONE, controller.uiState.value.repeatMode)

        controller.release()
    }

    @Test
    fun testCycleShuffleModeTransitionsThroughThreeStates() {
        val controller = PlaybackController(app, testScope)

        assertEquals(com.notify.core.model.ShuffleMode.OFF, controller.uiState.value.shuffleMode)
        assertFalse(controller.uiState.value.shuffleEnabled)

        // 1st cycle: OFF -> SHUFFLE
        val state1 = controller.cycleShuffleMode()
        assertEquals(com.notify.core.model.ShuffleMode.SHUFFLE, state1)
        assertEquals(com.notify.core.model.ShuffleMode.SHUFFLE, controller.uiState.value.shuffleMode)
        assertTrue(controller.uiState.value.shuffleEnabled)

        // 2nd cycle: SHUFFLE -> SMART_SHUFFLE
        val state2 = controller.cycleShuffleMode()
        assertEquals(com.notify.core.model.ShuffleMode.SMART_SHUFFLE, state2)
        assertEquals(com.notify.core.model.ShuffleMode.SMART_SHUFFLE, controller.uiState.value.shuffleMode)
        assertTrue(controller.uiState.value.shuffleEnabled)

        // 3rd cycle: SMART_SHUFFLE -> OFF
        val state3 = controller.cycleShuffleMode()
        assertEquals(com.notify.core.model.ShuffleMode.OFF, state3)
        assertEquals(com.notify.core.model.ShuffleMode.OFF, controller.uiState.value.shuffleMode)
        assertFalse(controller.uiState.value.shuffleEnabled)

        controller.release()
    }

    @Test
    fun testSnapshotStoreSavesAndRestoresSmartShuffle() {
        val store = PlaybackSnapshotStore(app)
        val snapshot = com.notify.core.model.PlaybackSnapshot(
            queue = emptyList(),
            currentIndex = 0,
            currentPositionMs = 1000L,
            repeatMode = RepeatMode.OFF,
            isShuffled = true,
            shuffleMode = com.notify.core.model.ShuffleMode.SMART_SHUFFLE,
            isAutoplayEnabled = false,
            radioSeedSourceId = null
        )

        store.saveSnapshot(snapshot)
        val restored = store.loadSnapshot()

        org.junit.Assert.assertNotNull(restored)
        assertEquals(com.notify.core.model.ShuffleMode.SMART_SHUFFLE, restored!!.shuffleMode)
        assertTrue(restored.isShuffled)
    }
}
