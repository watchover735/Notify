package com.notify.download.spotify

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PublicSpotifyMetadataProvider:
 * Uses PublicSpotifyScraper to fetch public Spotify metadata without tokens or authentication.
 * Caches scraped playlists so that already-extracted metadata is never requested a second time.
 */
class PublicSpotifyMetadataProvider(
    private val scraper: PublicSpotifyScraper = PublicSpotifyScraper()
) : SpotifyMetadataProvider {

    private val playlistCache = ConcurrentHashMap<String, ScrapedPlaylist>()

    /**
     * Pre-populates or caches an already scraped playlist (e.g. from Stage 0).
     */
    fun cacheScrapedPlaylist(playlist: ScrapedPlaylist) {
        playlistCache[playlist.id] = playlist
    }

    fun getCachedPlaylist(playlistId: String): ScrapedPlaylist? {
        return playlistCache[playlistId]
    }

    override suspend fun fetchTrack(trackId: String): Result<SpotifyTrackMetadata> = withContext(Dispatchers.IO) {
        // First check in-memory cached playlists for this track
        for (playlist in playlistCache.values) {
            val matchingTrack = playlist.tracks.find { it.title.hashCode().toString() == trackId || it.position.toString() == trackId }
            if (matchingTrack != null) {
                return@withContext Result.success(
                    SpotifyTrackMetadata(
                        id = trackId,
                        title = matchingTrack.title,
                        artists = listOf(matchingTrack.artist),
                        album = matchingTrack.album,
                        releaseYear = null,
                        durationMs = matchingTrack.durationMs,
                        artworkUrl = matchingTrack.artworkUrl ?: playlist.artworkUrl
                    )
                )
            }
        }

        // Fallback: Scrape track directly from public web/embed page
        try {
            val trackUrl = "https://open.spotify.com/track/$trackId"
            val res = scraper.scrapePlaylist(trackUrl)
            val firstTrack = res.playlist?.tracks?.firstOrNull()
            if (firstTrack != null) {
                Result.success(
                    SpotifyTrackMetadata(
                        id = trackId,
                        title = firstTrack.title,
                        artists = listOf(firstTrack.artist),
                        album = firstTrack.album,
                        releaseYear = null,
                        durationMs = firstTrack.durationMs,
                        artworkUrl = firstTrack.artworkUrl ?: res.playlist.artworkUrl
                    )
                )
            } else {
                Result.failure(IOException(res.error ?: "Could not extract public track metadata for $trackId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchPlaylist(playlistId: String, limit: Int): Result<SpotifyPlaylistMetadata> = withContext(Dispatchers.IO) {
        // Check cache first to avoid duplicate network fetching
        val cached = playlistCache[playlistId]
        if (cached != null) {
            val count = minOf(cached.tracks.size, limit)
            val mappedTracks = cached.tracks.take(count).map { track ->
                SpotifyTrackMetadata(
                    id = "${cached.id}_${track.position}",
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    releaseYear = null,
                    durationMs = track.durationMs,
                    artworkUrl = track.artworkUrl ?: cached.artworkUrl
                )
            }

            return@withContext Result.success(
                SpotifyPlaylistMetadata(
                    id = cached.id,
                    title = cached.title,
                    description = cached.description,
                    totalTracks = cached.expectedTrackCount ?: cached.tracks.size,
                    artworkUrl = cached.artworkUrl,
                    tracks = mappedTracks
                )
            )
        }

        // Scrape public embed/web page
        try {
            val playlistUrl = "https://open.spotify.com/playlist/$playlistId"
            val scrapeRes = scraper.scrapePlaylist(playlistUrl)
            val scraped = scrapeRes.playlist
                ?: return@withContext Result.failure(
                    IOException(scrapeRes.error ?: "Failed to extract public playlist metadata")
                )

            cacheScrapedPlaylist(scraped)

            val count = minOf(scraped.tracks.size, limit)
            val mappedTracks = scraped.tracks.take(count).map { track ->
                SpotifyTrackMetadata(
                    id = "${scraped.id}_${track.position}",
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    releaseYear = null,
                    durationMs = track.durationMs,
                    artworkUrl = track.artworkUrl ?: scraped.artworkUrl
                )
            }

            Result.success(
                SpotifyPlaylistMetadata(
                    id = scraped.id,
                    title = scraped.title,
                    description = scraped.description,
                    totalTracks = scraped.expectedTrackCount ?: scraped.tracks.size,
                    artworkUrl = scraped.artworkUrl,
                    tracks = mappedTracks
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
