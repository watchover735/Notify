package com.notify.core.model

/**
 * A single timestamped line in a synchronized lyric file.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * Represents the result of lyric retrieval and synchronization.
 */
sealed interface LyricsResult {
    /** Time-synchronized lyrics with sorted lines. */
    data class Synced(
        val lines: List<LyricLine>
    ) : LyricsResult {
        /**
         * Finds the active lyric line for the given position using binary search.
         */
        fun getActiveLine(positionMs: Long, displayToleranceMs: Long = 150L): LyricLine? {
            if (lines.isEmpty()) return null
            val target = positionMs + displayToleranceMs

            var low = 0
            var high = lines.size - 1
            var resultIndex = -1

            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].timeMs <= target) {
                    resultIndex = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            return if (resultIndex in lines.indices) lines[resultIndex] else null
        }
    }

    /** Unsynchronized plain text lyrics. */
    data class Plain(
        val text: String
    ) : LyricsResult

    /** Song is explicitly marked instrumental (no lyrics). */
    data object Instrumental : LyricsResult

    /** Lyrics not found or provider unavailable. */
    data object Unavailable : LyricsResult
}
