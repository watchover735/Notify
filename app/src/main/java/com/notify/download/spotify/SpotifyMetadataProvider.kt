package com.notify.download.spotify

interface SpotifyMetadataProvider {
    /**
     * Fetches metadata for a single Spotify track.
     */
    suspend fun fetchTrack(trackId: String): Result<SpotifyTrackMetadata>

    /**
     * Fetches metadata for a Spotify playlist and its tracks (bounded by limit).
     */
    suspend fun fetchPlaylist(playlistId: String, limit: Int = 50): Result<SpotifyPlaylistMetadata>
}
