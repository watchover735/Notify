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
}
