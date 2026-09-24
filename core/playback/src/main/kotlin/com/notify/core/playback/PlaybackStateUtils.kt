package com.notify.core.playback

import androidx.media3.common.C

/**
 * Utility functions to safely sanitize and handle Media3 edge cases:
 * - C.TIME_UNSET (-Long.MIN_VALUE + 1)
 * - C.INDEX_UNSET (-1)
 * - Empty queues and out-of-bounds start indices
 * - Non-seekable media protection
 */
object PlaybackStateUtils {

    /**
     * Sanitizes track duration reported by Media3 Player.
     * Returns 0L if duration is C.TIME_UNSET or negative.
     */
    fun sanitizeDuration(durationMs: Long): Long {
        return if (durationMs == C.TIME_UNSET || durationMs < 0L) {
            0L
        } else {
            durationMs
        }
    }

    /**
     * Sanitizes playback position reported by Media3 Player.
     * Guarantees non-negative position and clamps to known duration if available.
     */
    fun sanitizePosition(positionMs: Long, durationMs: Long = 0L): Long {
        if (positionMs == C.TIME_UNSET || positionMs < 0L) return 0L
        val sanitizedDuration = sanitizeDuration(durationMs)
        return if (sanitizedDuration > 0L) {
            positionMs.coerceAtMost(sanitizedDuration)
        } else {
            positionMs
        }
    }

    /**
     * Clamps a requested start index into a valid range for a queue.
     * Returns -1 if the queue is empty.
     */
    fun clampStartIndex(queueSize: Int, requestedIndex: Int): Int {
        if (queueSize <= 0) return -1
        return requestedIndex.coerceIn(0, queueSize - 1)
    }

    /**
     * Sanitizes current media item index reported by Media3 Player.
     * Returns null if index is C.INDEX_UNSET or out of bounds.
     */
    fun sanitizeMediaItemIndex(index: Int, queueSize: Int): Int? {
        if (index == C.INDEX_UNSET || index < 0 || index >= queueSize) {
            return null
        }
        return index
    }

    /**
     * Checks if a seek operation is valid given seekability and target position.
     */
    fun canSeek(isSeekable: Boolean, targetPositionMs: Long, durationMs: Long): Boolean {
        if (!isSeekable) return false
        if (targetPositionMs < 0L) return false
        val sanitizedDuration = sanitizeDuration(durationMs)
        if (sanitizedDuration > 0L && targetPositionMs > sanitizedDuration) return false
        return true
    }

    /**
     * Formats milliseconds to human-readable M:SS or H:MM:SS string.
     */
    fun formatMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Alias for formatMs.
     */
    fun formatDurationMs(ms: Long): String = formatMs(ms)
}
