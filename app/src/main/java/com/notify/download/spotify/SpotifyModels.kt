package com.notify.download.spotify

data class SpotifyTrackMetadata(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val releaseYear: String?,
    val durationMs: Long,
    val artworkUrl: String?
) {
    val primaryArtist: String
        get() = artists.firstOrNull() ?: "Unknown Artist"

    val allArtistsDisplay: String
        get() = if (artists.isEmpty()) "Unknown Artist" else artists.joinToString(", ")
}

data class SpotifyPlaylistMetadata(
    val id: String,
    val title: String,
    val description: String?,
    val totalTracks: Int,
    val artworkUrl: String?,
    val tracks: List<SpotifyTrackMetadata>
)
