package com.notify.playback

import androidx.media3.common.MediaItem

/**
 * Explicit, session-bound resolution state for lookahead candidate processing.
 * Replaces heuristic empty/not-empty window checks.
 */
sealed class NextResolutionState {
    object Idle : NextResolutionState()
    data class Resolving(val sessionId: Long, val candidateKey: String) : NextResolutionState()
    data class Ready(val sessionId: Long, val candidateKey: String, val mediaItem: MediaItem) : NextResolutionState()
    data class Failed(val sessionId: Long, val candidateKey: String, val error: String) : NextResolutionState()
}
