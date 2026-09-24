package com.notify.core.model

/**
 * Immutable domain representation of an audio track.
 */
data class Track(
    val id: TrackId,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    val source: AudioSource,
    val isExplicit: Boolean = false
) {
    val isLocal: Boolean
        get() = source is AudioSource.Local

    val isRemote: Boolean
        get() = source is AudioSource.Remote

    /**
     * Human-readable formatted duration string (m:ss or h:mm:ss).
     */
    fun formattedDuration(): String {
        if (durationMs <= 0L) return "--:--"
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
