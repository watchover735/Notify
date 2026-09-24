package com.notify.download.spotify

data class ScrapedTrack(
    val position: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0L) return "--:--"
            val totalSeconds = durationMs / 1000
            val seconds = totalSeconds % 60
            val minutes = totalSeconds / 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

data class ScrapedPlaylist(
    val id: String,
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val expectedTrackCount: Int?,
    val tracks: List<ScrapedTrack>
) {
    val extractedTrackCount: Int
        get() = tracks.size

    val isComplete: Boolean
        get() = expectedTrackCount == null || tracks.size >= expectedTrackCount
}

data class ScrapeDiagnostics(
    val httpStatusCode: Int,
    val finalHost: String,
    val contentType: String?,
    val payloadSizeBytes: Long,
    val parserStrategy: String,
    val elapsedTimeMs: Long,
    val isComplete: Boolean,
    val warningMessage: String? = null
)

data class ScrapeResult(
    val playlist: ScrapedPlaylist?,
    val diagnostics: ScrapeDiagnostics,
    val error: String? = null
)
