package com.notify.core.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SleepTimerManagerTest {

    private class FakePlayerHandler : InvocationHandler {
        var pauseCount = 0
        var isPlayingValue = false
        var currentVolume = 1.0f

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "pause" -> {
                    pauseCount++
                    Unit
                }
                "isPlaying" -> isPlayingValue
                "getVolume" -> currentVolume
                "setVolume" -> {
                    currentVolume = args?.get(0) as Float
                    Unit
                }
                "toString" -> "FakePlayer"
                "hashCode" -> 42
                "equals" -> proxy === args?.get(0)
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == Boolean::class.javaPrimitiveType -> false
                        returnType == Int::class.javaPrimitiveType -> 0
                        returnType == Long::class.javaPrimitiveType -> 0L
                        returnType == Float::class.javaPrimitiveType -> 0f
                        returnType == Double::class.javaPrimitiveType -> 0.0
                        returnType == Void.TYPE -> Unit
                        else -> null
                    }
                }
            }
        }
    }

    private lateinit var handler: FakePlayerHandler
    private lateinit var mockPlayer: Player
    private val testScope = TestScope()

    @Before
    fun setUp() {
        handler = FakePlayerHandler()
        mockPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler
        ) as Player
        SleepTimerManager.attachForTesting(
            player = mockPlayer,
            scope = testScope,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScope.testScheduler)
        )
    }

    @After
    fun tearDown() {
        SleepTimerManager.detach()
    }

    @Test
    fun initialState_isInactive() {
        assertEquals(SleepTimerState.Inactive, SleepTimerManager.sleepTimerState.value)
        assertFalse(SleepTimerManager.sleepTimerState.value.isActive)
        assertNull(SleepTimerManager.sleepTimerState.value.displayText)
    }

    @Test
    fun setTimer_activatesWithFormattedTime() {
        // 15 minutes = 900,000 ms
        SleepTimerManager.setTimer(15 * 60 * 1000L)

        val state = SleepTimerManager.sleepTimerState.value
        assertTrue(state is SleepTimerState.Active)
        assertTrue(state.isActive)
        val active = state as SleepTimerState.Active
        assertEquals(15 * 60 * 1000L, active.totalDurationMs)
        assertEquals("15:00", active.formattedRemaining)
    }

    @Test
    fun setTimer_replacingCancelsOldTimer() {
        SleepTimerManager.setTimer(15 * 60 * 1000L)
        SleepTimerManager.setTimer(5 * 60 * 1000L)

        val state = SleepTimerManager.sleepTimerState.value as SleepTimerState.Active
        assertEquals(5 * 60 * 1000L, state.totalDurationMs)
        assertEquals("05:00", state.formattedRemaining)
    }

    @Test
    fun cancelTimer_resetsToInactive() {
        SleepTimerManager.setTimer(10 * 60 * 1000L)
        assertTrue(SleepTimerManager.sleepTimerState.value.isActive)

        SleepTimerManager.cancelTimer()
        assertEquals(SleepTimerState.Inactive, SleepTimerManager.sleepTimerState.value)
        assertFalse(SleepTimerManager.sleepTimerState.value.isActive)
    }

    @Test
    fun setEndOfTrack_activatesEndOfTrackState() {
        SleepTimerManager.setEndOfTrack()

        val state = SleepTimerManager.sleepTimerState.value
        assertEquals(SleepTimerState.EndOfTrack, state)
        assertTrue(state.isActive)
        assertEquals("End of track", state.displayText)
    }

    @Test
    fun onMediaItemTransition_auto_triggersPauseWhenEndOfTrack() = runTest {
        SleepTimerManager.setEndOfTrack()
        handler.isPlayingValue = true

        SleepTimerManager.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, handler.pauseCount)
    }

    @Test
    fun onMediaItemTransition_seek_doesNotTriggerPause() = runTest {
        SleepTimerManager.setEndOfTrack()

        SleepTimerManager.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(0, handler.pauseCount)
    }

    @Test
    fun onPlaybackStateChanged_stateEnded_triggersPauseWhenEndOfTrack() = runTest {
        SleepTimerManager.setEndOfTrack()

        SleepTimerManager.onPlaybackStateChanged(Player.STATE_ENDED)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, handler.pauseCount)
    }

    @Test
    fun formatRemaining_formatsCorrectly() {
        assertEquals("00:05", SleepTimerManager.formatRemaining(5000L))
        assertEquals("01:30", SleepTimerManager.formatRemaining(90000L))
        assertEquals("15:00", SleepTimerManager.formatRemaining(15 * 60 * 1000L))
        assertEquals("1:00:00", SleepTimerManager.formatRemaining(60 * 60 * 1000L))
        assertEquals("1:15:30", SleepTimerManager.formatRemaining((75 * 60 + 30) * 1000L))
    }
}
