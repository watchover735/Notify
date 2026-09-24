package com.notify.download.spotify

import java.io.IOException

/**
 * ExperimentalAnonymousSpotifyProvider:
 * Deprecated legacy provider. The unofficial anonymous token endpoint returned HTTP 403 on physical devices.
 * Completely replaced by [PublicSpotifyMetadataProvider] and [PublicSpotifyScraper].
 */
class ExperimentalAnonymousSpotifyProvider : SpotifyMetadataProvider {

    companion object {
        const val DISABLED_MESSAGE = "Legacy anonymous Spotify provider disabled. Use Public Spotify Import."
    }

    override suspend fun fetchTrack(trackId: String): Result<SpotifyTrackMetadata> {
        return Result.failure(IOException(DISABLED_MESSAGE))
    }

    override suspend fun fetchPlaylist(playlistId: String, limit: Int): Result<SpotifyPlaylistMetadata> {
        return Result.failure(IOException(DISABLED_MESSAGE))
    }
}
