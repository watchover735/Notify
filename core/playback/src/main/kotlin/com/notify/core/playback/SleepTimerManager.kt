package com.notify.core.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * State representing the sleep timer status.
 */
sealed class SleepTimerState {
    object Inactive : SleepTimerState()

    data class Active(
        val endTimestampElapsedRealtimeMs: Long,
        val totalDurationMs: Long,
        val remainingMs: Long,
        val formattedRemaining: String
    ) : SleepTimerState()

    object EndOfTrack : SleepTimerState()

    val isActive: Boolean get() = this is Active || this is EndOfTrack

    val displayText: String?
        get() = when (this) {
            is Active -> formattedRemaining
            is EndOfTrack -> "End of track"
            is Inactive -> null
        }
}

/**
 * Service-owned Sleep Timer manager.
 * Runs entirely inside NotiFyPlaybackService (MediaSessionService foreground service) scope,
 * surviving screen-off, app swipe from recents, and deep sleep.
 *
 * Uses absolute end timestamps (SystemClock.elapsedRealtime() + duration) rather than decrementing
 * counters to prevent Doze mode drift.
 *
 * On timer expiry:
 * 1. Fades volume out smoothly over ~5 seconds.
 * 2. Pauses playback.
 * 3. Releases the player and stops the foreground playback service (no leftover notification).
 */
object SleepTimerManager {

    private const val TAG = "SleepTimerManager"

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private var service: NotiFyPlaybackService? = null
    private var player: Player? = null
    private var serviceScope: CoroutineScope? = null
    private var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private var timerJob: Job? = null
    private var isFadingOut: Boolean = false

    /**
     * Attaches to NotiFyPlaybackService. Called on service onCreate.
     */
    fun attach(service: NotiFyPlaybackService, player: Player, scope: CoroutineScope) {
        this.service = service
        this.player = player
        this.serviceScope = scope
        this.mainDispatcher = Dispatchers.Main
    }

    /**
     * Internal testing hook to attach a mock player and scope.
     */
    internal fun attachForTesting(
        player: Player,
        scope: CoroutineScope,
        service: NotiFyPlaybackService? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Main
    ) {
        this.player = player
        this.serviceScope = scope
        this.service = service
        this.mainDispatcher = dispatcher
    }

    /**
     * Detaches from NotiFyPlaybackService. Called on service onDestroy.
     */
    fun detach() {
        timerJob?.cancel()
        timerJob = null
        service = null
        player = null
        serviceScope = null
        mainDispatcher = Dispatchers.Main
        isFadingOut = false
        _sleepTimerState.value = SleepTimerState.Inactive
    }

    /**
     * Sets a sleep timer for the given duration in milliseconds.
     * Replaces any existing timer.
     */
    fun setTimer(durationMs: Long) {
        timerJob?.cancel()
        isFadingOut = false

        val scope = serviceScope
        val targetElapsedRealtime = SystemClock.elapsedRealtime() + durationMs

        _sleepTimerState.value = SleepTimerState.Active(
            endTimestampElapsedRealtimeMs = targetElapsedRealtime,
            totalDurationMs = durationMs,
            remainingMs = durationMs,
            formattedRemaining = formatRemaining(durationMs)
        )

        if (scope == null) return

        timerJob = scope.launch(mainDispatcher) {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val remaining = targetElapsedRealtime - now
                if (remaining <= 0) {
                    _sleepTimerState.value = SleepTimerState.Inactive
                    executeFadeAndStop()
                    break
                }

                _sleepTimerState.value = SleepTimerState.Active(
                    endTimestampElapsedRealtimeMs = targetElapsedRealtime,
                    totalDurationMs = durationMs,
                    remainingMs = remaining,
                    formattedRemaining = formatRemaining(remaining)
                )

                val nextSleep = minOf(1000L, remaining.coerceAtLeast(100L))
                delay(nextSleep)
            }
        }
    }

    /**
     * Configures the timer to stop playback after the current track completes.
     */
    fun setEndOfTrack() {
        timerJob?.cancel()
        isFadingOut = false
        _sleepTimerState.value = SleepTimerState.EndOfTrack
    }

    /**
     * Cancels the active sleep timer.
     */
    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        isFadingOut = false
        _sleepTimerState.value = SleepTimerState.Inactive
    }

    /**
     * Called on media item transition. If "End of track" is set and the transition was automatic,
     * stops playback.
     */
    fun onMediaItemTransition(reason: Int) {
        if (_sleepTimerState.value is SleepTimerState.EndOfTrack) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                serviceScope?.launch(mainDispatcher) {
                    executeFadeAndStop(immediatePause = true)
                }
            }
        }
    }

    /**
     * Called on player playback state change.
     */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (_sleepTimerState.value is SleepTimerState.EndOfTrack) {
            if (playbackState == Player.STATE_ENDED) {
                serviceScope?.launch(mainDispatcher) {
                    executeFadeAndStop(immediatePause = true)
                }
            }
        }
    }

    /**
     * Fades the volume out over ~5 seconds, pauses playback, releases player and stops the service.
     */
    internal suspend fun executeFadeAndStop(immediatePause: Boolean = false) {
        if (isFadingOut) return
        isFadingOut = true

        val activePlayer = player
        val activeService = service

        try {
            if (activePlayer != null) {
                if (immediatePause) {
                    activePlayer.pause()
                } else if (activePlayer.isPlaying) {
                    // Fade out volume over 5 seconds across 25 steps (200ms each)
                    val steps = 25
                    val stepDelayMs = 200L
                    for (step in 1..steps) {
                        val remainingSteps = (steps - step + 1).toFloat()
                        val currentVol = activePlayer.volume
                        // Handles user adjusting volume during fade: scales relative to current volume
                        activePlayer.volume = (currentVol * ((remainingSteps - 1f) / remainingSteps)).coerceAtLeast(0f)
                        delay(stepDelayMs)
                    }
                    activePlayer.pause()
                    // Restore original volume so subsequent sessions don't start muted
                    activePlayer.volume = 1.0f
                } else {
                    activePlayer.pause()
                }
            }
        } catch (_: Exception) {
            // Safe fallback if player is already released/idle
        } finally {
            cancelTimer()
            isFadingOut = false
            try {
                activeService?.stopSelf()
            } catch (_: Exception) {}
        }
    }

    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
