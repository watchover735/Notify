package com.notify.download.worker

import com.notify.download.db.ArtworkEnrichmentStatus
import com.notify.download.db.ArtworkOrigin
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.spotify.SpotifyTrackMetadata
import java.util.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import kotlinx.coroutines.withTimeoutOrNull

data class EnrichmentResult(
    val completed: Int,
    val total: Int,
    val failed: Int,
    val rateLimited: Boolean = false
)

interface ArtworkEnricher {
    suspend fun enrichPlaylist(
        playlistId: String,
        forceRetry: Boolean = false,
        onProgress: suspend (completed: Int, total: Int, failed: Int) -> Unit = { _, _, _ -> }
    ): EnrichmentResult
}

/**
 * Executes safe background artwork enrichment for a playlist:
 * - Uses fast InnerTube metadata search only (<300ms, no yt-dlp, no stream resolution).
 * - Bounded batch size (max 20 tracks per execution) and bounded concurrency (max 3) with request jitter.
 * - Atomically claims each track before enrichment so concurrent workers never conflict.
 * - Respects per-track enrichment statuses to prevent redundant work.
 * - Updates Room one track at a time for progressive UI fill.
 * - Safe source handling: non-selected source, does not mark resolutionState = MATCHED.
 */
class DefaultArtworkEnricher(
    private val database: NotiFyDatabase,
    private val searchProvider: OnlineCatalogSearchProvider = InnerTubeYouTubeMusicSearchProvider(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val repository: PlaylistRepository = PlaylistRepository(database)
) : ArtworkEnricher {

    companion object {
        const val BATCH_SIZE = 20
        const val MAX_CONCURRENCY = 3
        const val SEARCH_TIMEOUT_MS = 6_000L
    }

    private val random = Random()

    override suspend fun enrichPlaylist(
        playlistId: String,
        forceRetry: Boolean,
        onProgress: suspend (completed: Int, total: Int, failed: Int) -> Unit
    ): EnrichmentResult = withContext(ioDispatcher) {
        if (forceRetry) {
            repository.resetEnrichmentForRetry(playlistId)
        }

        val allEligible = repository.getTracksNeedingEnrichment(playlistId, forceRetry)
        val eligibleTracks = allEligible.take(BATCH_SIZE)
        val total = eligibleTracks.size
        if (total == 0) {
            return@withContext EnrichmentResult(completed = 0, total = 0, failed = 0)
        }

        var completed = 0
        var failed = 0
        var rateLimited = false
        val semaphore = Semaphore(MAX_CONCURRENCY)

        onProgress(completed, total, failed)

        for (track in eligibleTracks) {
            // Check if rate-limited in previous iteration
            if (rateLimited) break

            // Atomic claim: only proceed if this worker successfully acquired the track lease
            val claimed = repository.claimTrackForEnrichment(track.id)
            if (!claimed) {
                // Another worker or thread claimed this track; skip it
                continue
            }

            // Jitter between requests to protect against rate-limiting (100–200ms)
            val jitter = 100L + random.nextInt(100)
            delay(jitter)

            val query = "${track.artist} - ${track.title}"
            val searchResult = semaphore.withPermit {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    searchProvider.search(query, limit = 5)
                } ?: Result.failure(Exception("Search timed out after ${SEARCH_TIMEOUT_MS}ms"))
            }

            if (searchResult.isFailure) {
                val errorMsg = searchResult.exceptionOrNull()?.message ?: ""
                val is429 = errorMsg.contains("429") || errorMsg.contains("rate", ignoreCase = true)

                if (is429) {
                    repository.markTrackEnrichmentRateLimited(track.id)
                    rateLimited = true
                    failed++
                } else {
                    // Mark as retryable failure
                    database.trackDao().updateEnrichmentStatus(
                        trackId = track.id,
                        status = ArtworkEnrichmentStatus.FAILED_RETRYABLE,
                        timestampMs = System.currentTimeMillis()
                    )
                    failed++
                }
            } else {
                val candidates = searchResult.getOrNull().orEmpty()
                val spotifyMeta = SpotifyTrackMetadata(
                    id = track.id,
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    releaseYear = null,
                    durationMs = track.durationMs,
                    artworkUrl = null
                )

                val match = if (candidates.isNotEmpty()) {
                    TrackMatchEngine.findBestMatch(spotifyMeta, candidates)
                } else {
                    null
                }

                val isSafe = if (match != null) {
                    TrackMatchEngine.isSafeEnrichmentMatch(spotifyMeta, match.candidate, match)
                } else {
                    false
                }

                if (isSafe && match != null && !match.candidate.artworkUrl.isNullOrBlank()) {
                    repository.updateTrackEnrichment(
                        trackId = track.id,
                        candidate = match.candidate,
                        isMatchSafe = true
                    )
                    completed++
                } else {
                    repository.updateTrackEnrichment(
                        trackId = track.id,
                        candidate = match?.candidate,
                        isMatchSafe = false
                    )
                    failed++
                }
            }

            onProgress(completed, total, failed)
        }

        EnrichmentResult(
            completed = completed,
            total = total,
            failed = failed,
            rateLimited = rateLimited
        )
    }
}
