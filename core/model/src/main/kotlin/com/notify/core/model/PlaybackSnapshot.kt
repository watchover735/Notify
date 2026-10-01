package com.notify.core.model

/**
 * Repeat modes supported by the player session.
 */
enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

/**
 * Shuffle modes supported by the player session.
 */
enum class ShuffleMode {
    OFF,
    SHUFFLE,
    SMART_SHUFFLE
}

/**
 * Durable snapshot of player state persisted to Room for process death recovery.
 */
data class PlaybackSnapshot(
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val currentPositionMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffled: Boolean = false,
    val isAutoplayEnabled: Boolean = true,
    val radioSeedSourceId: String? = null,
    val shuffleMode: ShuffleMode = if (isShuffled) ShuffleMode.SHUFFLE else ShuffleMode.OFF
) {
    val currentEntry: QueueEntry?
        get() = queue.getOrNull(currentIndex)

    val currentTrack: Track?
        get() = currentEntry?.track

    val hasNext: Boolean
        get() = currentIndex in 0 until (queue.size - 1)

    val hasPrevious: Boolean
        get() = currentIndex > 0

    companion object {
        val EMPTY = PlaybackSnapshot()
    }
}
