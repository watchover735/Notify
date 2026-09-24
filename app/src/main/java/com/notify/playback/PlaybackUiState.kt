package com.notify.playback

import com.notify.core.model.PlaybackError
import com.notify.core.model.QueueEntry
import com.notify.core.model.RepeatMode
import com.notify.core.model.Track

/**
 * UI State representing the current playback engine state.
 */
data class PlaybackUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val queueEntries: List<QueueEntry> = emptyList(),
    val currentTrackIndex: Int? = null,
    val currentIndex: Int? = currentTrackIndex,
    val playbackError: PlaybackError? = null,
    val isConnected: Boolean = false,
    val isControllerConnected: Boolean = isConnected,
    val isSeekable: Boolean = true,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isAutoplayEnabled: Boolean = true,
    val isPreparingNext: Boolean = false,
    val actionMessage: String? = null,
    val abRepeatState: ABRepeatState = ABRepeatState()
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val hasTrack: Boolean
        get() = currentTrack != null
}
