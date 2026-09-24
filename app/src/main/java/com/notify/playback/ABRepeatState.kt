package com.notify.playback

/**
 * State representation for A-B Repeat (section loop) playback.
 *
 * Invariants:
 * - Scoped strictly to the active track session in memory.
 * - Reset on every track change (onMediaItemTransition).
 * - Minimum loop gap: abEndMs - abStartMs >= 1000ms.
 * - Upper bound: abEndMs clamped to durationMs - 250ms.
 */
data class ABRepeatState(
    val abStartMs: Long? = null,
    val abEndMs: Long? = null,
    val isLoopActive: Boolean = false,
    val errorMessage: String? = null
) {
    val isStartMarked: Boolean get() = abStartMs != null
    val isEndMarked: Boolean get() = abEndMs != null
    val isBothMarked: Boolean get() = abStartMs != null && abEndMs != null

    companion object {
        const val MIN_LOOP_GAP_MS = 1000L
        const val TRACK_END_BUFFER_MS = 250L
    }
}
