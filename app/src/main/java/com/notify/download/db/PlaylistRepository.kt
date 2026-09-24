package com.notify.download.db

import androidx.room.withTransaction
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.ProviderNormalizer
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.spotify.ScrapedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed class AddTrackResult {
    object Added : AddTrackResult()
    object AlreadyExists : AddTrackResult()
    object PlaylistMissing : AddTrackResult()
    data class Failure(val cause: Throwable) : AddTrackResult()
}

/**
 * Repository coordinating Room persistence for imported Spotify playlists and track states.
 */
class PlaylistRepository(
    private val database: NotiFyDatabase,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val trackDao = database.trackDao()
    private val playlistDao = database.playlistDao()

    /**
     * Persists a scraped Spotify playlist and all its track entries to Room.
     * Preserves original positions (1..N) and supports duplicate track entries across different positions.
     */
    suspend fun saveScrapedPlaylist(
        scraped: ScrapedPlaylist,
        sourceUrl: String? = null
    ): PlaylistEntity = withContext(ioDispatcher) {
        val playlistId = "pl_spotify_${scraped.id}"
        val canonicalSourceUrl = sourceUrl ?: "https://open.spotify.com/playlist/${scraped.id}"
        val now = System.currentTimeMillis()

        val existingPlaylist = playlistDao.getPlaylistById(playlistId)
        val playlistEntity = PlaylistEntity(
            playlistId = playlistId,
            title = scraped.title,
            sourceUrl = canonicalSourceUrl,
            artworkUri = scraped.artworkUrl ?: existingPlaylist?.artworkUri,
            dateCreatedEpochMs = existingPlaylist?.dateCreatedEpochMs ?: now,
            dateModifiedEpochMs = now
        )

        val trackEntities = mutableListOf<TrackEntity>()
        val entryEntities = mutableListOf<PlaylistEntryEntity>()
        val seenTrackIds = mutableSetOf<String>()

        for (track in scraped.tracks) {
            // Stable track ID namespaced by provider and sanitized position hash
            val trackIdStr = "spotify:${scraped.id}_${track.position}_${Math.abs(track.title.hashCode())}"
            if (!seenTrackIds.add(trackIdStr)) {
                continue
            }
            val existingTrack = trackDao.getTrackById(trackIdStr)

            // Artwork priority for tracks:
            //  1. Keep existing genuine YouTube-match artwork (artworkOrigin = YOUTUBE_MATCH)
            //  2. Use Spotify per-track artwork if present (artworkOrigin = SPOTIFY_TRACK)
            //  3. Null — UI will show neutral placeholder until matched
            //  Playlist cover (scraped.artworkUrl) is NEVER used as a track fallback.
            val (resolvedArtworkUrl, resolvedArtworkUri, resolvedOrigin) = when {
                existingTrack?.artworkOrigin == ArtworkOrigin.YOUTUBE_MATCH &&
                        !existingTrack.artworkUrl.isNullOrBlank() -> {
                    Triple(existingTrack.artworkUrl, existingTrack.artworkUri, ArtworkOrigin.YOUTUBE_MATCH)
                }
                !track.artworkUrl.isNullOrBlank() -> {
                    Triple(track.artworkUrl, track.artworkUrl, ArtworkOrigin.SPOTIFY_TRACK)
                }
                else -> {
                    Triple(null, null, ArtworkOrigin.UNKNOWN)
                }
            }

            val trackEntity = TrackEntity(
                id = trackIdStr,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artworkUri = resolvedArtworkUri,
                artworkUrl = resolvedArtworkUrl,
                artworkOrigin = resolvedOrigin,
                spotifyId = "${scraped.id}_${track.position}",
                resolutionState = existingTrack?.resolutionState ?: ResolutionState.METADATA_ONLY,
                downloadState = existingTrack?.downloadState ?: DownloadState.NOT_DOWNLOADED,
                localContentUri = existingTrack?.localContentUri,
                dateAddedEpochMs = existingTrack?.dateAddedEpochMs ?: now
            )
            trackEntities.add(trackEntity)

            val entryEntity = PlaylistEntryEntity(
                playlistId = playlistId,
                trackId = trackIdStr,
                position = track.position
            )
            entryEntities.add(entryEntity)
        }

        database.withTransaction {
            // 1. Upsert tracks preserving state without triggering CASCADE delete on track_sources!
            trackDao.upsertTracksSafely(trackEntities)
            // 2. Insert playlist
            playlistDao.insertPlaylist(playlistEntity)
            // 3. Clear old entries for this playlist and insert fresh entries
            playlistDao.deletePlaylistEntries(playlistId)
            for (entry in entryEntities) {
                playlistDao.insertPlaylistEntryIgnore(entry)
            }
        }

        playlistEntity
    }

    fun observePlaylistSummaries(): Flow<List<PlaylistSummary>> {
        return playlistDao.observePlaylistSummaries()
    }

    suspend fun getPlaylistSummaries(): List<PlaylistSummary> = withContext(ioDispatcher) {
        playlistDao.getPlaylistSummaries()
    }

    suspend fun createPlaylist(title: String): PlaylistEntity = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val playlistId = "pl_local_${now}_${java.util.UUID.randomUUID().toString().take(8)}"
        val playlist = PlaylistEntity(
            playlistId = playlistId,
            title = title.trim(),
            sourceUrl = null,
            artworkUri = null,
            dateCreatedEpochMs = now,
            dateModifiedEpochMs = now
        )
        playlistDao.insertPlaylist(playlist)
        playlist
    }

    suspend fun renamePlaylist(playlistId: String, newTitle: String) = withContext(ioDispatcher) {
        playlistDao.updatePlaylistTitle(playlistId, newTitle.trim(), System.currentTimeMillis())
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(ioDispatcher) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun getPlaylistById(playlistId: String): PlaylistEntity? = withContext(ioDispatcher) {
        playlistDao.getPlaylistById(playlistId)
    }

    suspend fun getPlaylistEntries(playlistId: String): List<PlaylistEntryWithTrack> = withContext(ioDispatcher) {
        playlistDao.getPlaylistEntries(playlistId)
    }

    fun observePlaylistEntries(playlistId: String): Flow<List<PlaylistEntryWithTrack>> {
        return playlistDao.observePlaylistEntries(playlistId)
    }

    suspend fun updateTrackResolution(trackId: String, state: ResolutionState) = withContext(ioDispatcher) {
        trackDao.updateResolutionState(trackId, state)
    }

    suspend fun resetAbandonedSearchingStates(): Int = withContext(ioDispatcher) {
        trackDao.resetAbandonedSearchingStates()
    }

    /**
     * Reconciles persisted Room states on app startup.
     *
     * Actions taken:
     *  A. Clears stale RESOLVE_FAILED badges for any track that already has a valid selected
     *     track_source, and promotes artworkOrigin to YOUTUBE_MATCH.
     *  B. Safe legacy repair: for any remaining track whose artwork equals its playlist's cover
     *     (a bug from before A2.1.1), clears the artwork so the UI shows a neutral placeholder.
     *     INVARIANT: Tracks with artworkOrigin = YOUTUBE_MATCH are NEVER cleared here.
     *
     * Returns the total number of tracks whose state was modified.
     */
    suspend fun reconcilePersistedStates(): Int = withContext(ioDispatcher) {
        var reconciledCount = 0

        // A. Reconcile stale RESOLVE_FAILED tracks that now have a valid source
        val failedTracks = trackDao.getFailedTracks()
        for (track in failedTracks) {
            val source = trackDao.getSelectedSource(track.id)
            if (source != null && source.sourceId.isNotBlank()) {
                trackDao.updateResolutionState(track.id, ResolutionState.MATCHED)
                // Promote artwork to YOUTUBE_MATCH, preserving it
                if (!source.artworkUrl.isNullOrBlank()) {
                    trackDao.updateArtworkWithOrigin(
                        trackId = track.id,
                        artworkUri = source.artworkUrl,
                        artworkUrl = source.artworkUrl,
                        artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH
                    )
                }
                reconciledCount++
            }
        }

        // B. Safe legacy repair: find tracks with stale playlist-cover artwork.
        //    Skip any track already marked YOUTUBE_MATCH (genuine artwork preserved).
        val playlists = playlistDao.getAllPlaylists()
        for (playlist in playlists) {
            val playlistArt = playlist.artworkUri ?: continue
            val entries = playlistDao.getPlaylistEntries(playlist.playlistId)
            for (entry in entries) {
                // Look up full TrackEntity to check artworkOrigin; PlaylistEntryWithTrack
                // does not carry that field.
                val trackEntity = trackDao.getTrackById(entry.trackId) ?: continue
                // Never touch tracks that already carry genuine YouTube match artwork
                if (trackEntity.artworkOrigin == ArtworkOrigin.YOUTUBE_MATCH) continue
                val matchesPlaylistArt = trackEntity.artworkUrl == playlistArt || trackEntity.artworkUri == playlistArt
                if (matchesPlaylistArt) {
                    val source = trackDao.getSelectedSource(entry.trackId)
                    if (source?.artworkUrl?.isNotBlank() == true) {
                        // Restore genuine YouTube match artwork
                        trackDao.updateArtworkWithOrigin(
                            trackId = entry.trackId,
                            artworkUri = source.artworkUrl,
                            artworkUrl = source.artworkUrl,
                            artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH
                        )
                    } else {
                        // No genuine artwork — clear to neutral placeholder
                        trackDao.updateArtworkWithOrigin(
                            trackId = entry.trackId,
                            artworkUri = null,
                            artworkUrl = null,
                            artworkOrigin = ArtworkOrigin.PLAYLIST_FALLBACK_LEGACY
                        )
                    }
                    reconciledCount++
                }
            }
        }

        // C. Safe duplicate playlist entries repair
        reconciledCount += repairDuplicatePlaylistEntries()

        reconciledCount
    }

    /**
     * Persists matched candidate thumbnail / artworkUrl to Room.
     * Marks artworkOrigin as YOUTUBE_MATCH so legacy-repair logic never clears it.
     */
    suspend fun updateTrackArtwork(trackId: String, artworkUrl: String?) = withContext(ioDispatcher) {
        if (!artworkUrl.isNullOrBlank()) {
            trackDao.updateArtworkWithOrigin(
                trackId = trackId,
                artworkUri = artworkUrl,
                artworkUrl = artworkUrl,
                artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH
            )
        }
    }

    suspend fun updateTrackDownload(trackId: String, state: DownloadState) = withContext(ioDispatcher) {
        trackDao.updateDownloadState(trackId, state)
    }

    suspend fun saveSelectedSource(source: TrackSourceEntity) = withContext(ioDispatcher) {
        trackDao.insertSource(source)
    }

    suspend fun getSelectedSource(trackId: String): TrackSourceEntity? = withContext(ioDispatcher) {
        trackDao.getSelectedSource(trackId)
    }

    suspend fun getTrackById(trackId: String): TrackEntity? = withContext(ioDispatcher) {
        trackDao.getTrackById(trackId)
    }

    /**
     * Converts a database entry to a domain [Track] object ready for player consumption.
     */
    fun toDomainTrack(entry: PlaylistEntryWithTrack): Track {
        val source = if (!entry.localContentUri.isNullOrBlank()) {
            AudioSource.Local(entry.localContentUri)
        } else {
            AudioSource.Remote(ProviderId.SPOTIFY, entry.trackId)
        }

        return Track(
            id = TrackId(ProviderId.SPOTIFY, entry.trackId),
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            artworkUri = entry.artworkUrl ?: entry.artworkUri,
            source = source
        )
    }

    companion object {
        const val LIKED_SONGS_PLAYLIST_ID = "liked_songs"

        fun deterministicTrackId(provider: String, sourceId: String): String {
            val normProvider = ProviderNormalizer.normalize(provider)
            val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, sourceId)
            return "$normProvider:$normSourceId"
        }
    }

    private val offlineDownloadDao = database.offlineDownloadDao()
    private val downloadQueueDao = database.downloadQueueDao()

    /**
     * Resolves the canonical catalog trackId deterministically for any incoming provider and sourceId.
     * Recognizes existing Spotify imported tracks and confirmed YouTube matches, avoiding duplicate track creation.
     */
    suspend fun resolveCanonicalTrackId(provider: String, sourceId: String): String = withContext(ioDispatcher) {
        val normProvider = ProviderNormalizer.normalize(provider)
        val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, sourceId)
        resolveCanonicalTrackIdInternal(normProvider, normSourceId)
    }

    private suspend fun resolveCanonicalTrackIdInternal(normProvider: String, normSourceId: String): String {
        if (normProvider == ProviderNormalizer.YOUTUBE) {
            // 1. Check confirmed selected match in track_sources (prioritizing Spotify imported track)
            val candidateTrackIds = trackDao.findCanonicalTrackIdsForYouTubeSource(normSourceId)
            for (candidateId in candidateTrackIds) {
                if (trackDao.getTrackById(candidateId) != null) {
                    return candidateId
                }
            }

            // 2. Check completed offline downloads for this YouTube source
            val downloadedTrackIds = trackDao.findDownloadedTrackIdsForSource(normSourceId)
            for (candidateId in downloadedTrackIds) {
                if (trackDao.getTrackById(candidateId) != null) {
                    return candidateId
                }
            }

            // 3. Check direct youtube:<videoId> track
            val directTrackId = trackDao.findDirectYouTubeTrackId(normSourceId)
            if (directTrackId != null) {
                return directTrackId
            }

            return deterministicTrackId(ProviderNormalizer.YOUTUBE, normSourceId)
        } else if (normProvider == ProviderNormalizer.SPOTIFY) {
            val spotifyTrackId = trackDao.findTrackIdForSpotify(normSourceId)
            if (spotifyTrackId != null) {
                return spotifyTrackId
            }
            return deterministicTrackId(ProviderNormalizer.SPOTIFY, normSourceId)
        } else {
            return deterministicTrackId(normProvider, normSourceId)
        }
    }

    /**
     * Adds an online or catalog track to an existing playlist if not already present.
     * Completely atomic inside db.withTransaction:
     * 1. Resolves canonical trackId deterministically.
     * 2. Checks cross-provider recording membership.
     * 3. Inserts with INSERT IGNORE and updates playlist modification time.
     * Returns [AddTrackResult.Added] if added, [AddTrackResult.AlreadyExists] if entry existed,
     * or [AddTrackResult.PlaylistMissing] if playlistId is invalid.
     */
    suspend fun addTrackToPlaylistIfAbsent(
        playlistId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        artworkUrl: String? = null,
        provider: String = "youtube",
        providerSourceId: String
    ): AddTrackResult = withContext(ioDispatcher) {
        try {
            val normProvider = ProviderNormalizer.normalize(provider)
            val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, providerSourceId)

            database.withTransaction {
                val playlist = playlistDao.getPlaylistById(playlistId)
                    ?: return@withTransaction AddTrackResult.PlaylistMissing

                // 1. Resolve canonical catalog trackId deterministically
                val canonicalTrackId = resolveCanonicalTrackIdInternal(normProvider, normSourceId)

                // 2. Cross-provider membership check
                val alreadyPresent = if (normProvider == ProviderNormalizer.YOUTUBE) {
                    playlistDao.isRecordingInPlaylist(playlistId, canonicalTrackId, normSourceId)
                } else {
                    playlistDao.isTrackInPlaylist(playlistId, canonicalTrackId)
                }

                if (alreadyPresent) {
                    return@withTransaction AddTrackResult.AlreadyExists
                }

                // 3. Ensure TrackEntity exists
                val existingTrack = trackDao.getTrackById(canonicalTrackId)
                val now = System.currentTimeMillis()
                if (existingTrack == null) {
                    val track = TrackEntity(
                        id = canonicalTrackId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        artworkUri = artworkUrl,
                        artworkUrl = artworkUrl,
                        spotifyId = if (canonicalTrackId.startsWith("spotify:")) canonicalTrackId.removePrefix("spotify:") else null,
                        resolutionState = ResolutionState.MATCHED,
                        downloadState = DownloadState.NOT_DOWNLOADED,
                        localContentUri = null,
                        dateAddedEpochMs = now,
                        artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH,
                        artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED,
                        lastArtworkAttemptEpochMs = now
                    )
                    trackDao.insertTrack(track)
                }

                // 4. Ensure TrackSourceEntity exists for canonicalTrackId
                if (normProvider == ProviderNormalizer.YOUTUBE) {
                    val existingSource = trackDao.getSelectedSource(canonicalTrackId)
                    if (existingSource == null) {
                        val source = TrackSourceEntity(
                            sourceKey = "$canonicalTrackId:youtube:$normSourceId",
                            trackId = canonicalTrackId,
                            provider = ProviderNormalizer.YOUTUBE,
                            sourceId = normSourceId,
                            canonicalUrl = "https://www.youtube.com/watch?v=$normSourceId",
                            confidence = 1.0f,
                            durationDeltaMs = 0L,
                            artworkUrl = artworkUrl,
                            selected = true
                        )
                        trackDao.insertSource(source)
                    }
                }

                // 5. Attempt insertion with INSERT IGNORE
                val currentMax = playlistDao.getMaxPosition(playlistId) ?: 0
                val entry = PlaylistEntryEntity(
                    playlistId = playlistId,
                    trackId = canonicalTrackId,
                    position = currentMax + 1
                )
                val rowId = playlistDao.insertPlaylistEntryIgnore(entry)
                if (rowId == -1L) {
                    return@withTransaction AddTrackResult.AlreadyExists
                }

                // 6. Update playlist dateModifiedEpochMs
                playlistDao.updatePlaylistTitle(playlistId, playlist.title, now)
                AddTrackResult.Added
            }
        } catch (e: Exception) {
            AddTrackResult.Failure(e)
        }
    }

    /**
     * Adds an online or catalog track to an existing playlist, returning the inserted or existing entry.
     */
    suspend fun addTrackToPlaylist(
        playlistId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        artworkUrl: String? = null,
        provider: String = "youtube",
        providerSourceId: String
    ): PlaylistEntryEntity = withContext(ioDispatcher) {
        val normProvider = ProviderNormalizer.normalize(provider)
        val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, providerSourceId)

        database.withTransaction {
            val canonicalTrackId = resolveCanonicalTrackIdInternal(normProvider, normSourceId)

            val existingEntry = playlistDao.getPlaylistEntryByTrack(playlistId, canonicalTrackId)
            if (existingEntry != null) {
                return@withTransaction existingEntry
            }

            // Ensure TrackEntity exists
            val existingTrack = trackDao.getTrackById(canonicalTrackId)
            val now = System.currentTimeMillis()
            if (existingTrack == null) {
                val track = TrackEntity(
                    id = canonicalTrackId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    artworkUri = artworkUrl,
                    artworkUrl = artworkUrl,
                    spotifyId = if (canonicalTrackId.startsWith("spotify:")) canonicalTrackId.removePrefix("spotify:") else null,
                    resolutionState = ResolutionState.MATCHED,
                    downloadState = DownloadState.NOT_DOWNLOADED,
                    localContentUri = null,
                    dateAddedEpochMs = now,
                    artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH,
                    artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED,
                    lastArtworkAttemptEpochMs = now
                )
                trackDao.insertTrack(track)
            }

            // Ensure TrackSourceEntity exists
            if (normProvider == ProviderNormalizer.YOUTUBE) {
                val existingSource = trackDao.getSelectedSource(canonicalTrackId)
                if (existingSource == null) {
                    val source = TrackSourceEntity(
                        sourceKey = "$canonicalTrackId:youtube:$normSourceId",
                        trackId = canonicalTrackId,
                        provider = ProviderNormalizer.YOUTUBE,
                        sourceId = normSourceId,
                        canonicalUrl = "https://www.youtube.com/watch?v=$normSourceId",
                        confidence = 1.0f,
                        durationDeltaMs = 0L,
                        artworkUrl = artworkUrl,
                        selected = true
                    )
                    trackDao.insertSource(source)
                }
            }

            val currentMax = playlistDao.getMaxPosition(playlistId) ?: 0
            val entry = PlaylistEntryEntity(
                playlistId = playlistId,
                trackId = canonicalTrackId,
                position = currentMax + 1
            )
            playlistDao.insertPlaylistEntryIgnore(entry)

            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist != null) {
                playlistDao.updatePlaylistTitle(playlistId, playlist.title, now)
            }

            entry
        }
    }

    /**
     * Safely removes a single track membership row from a playlist.
     * Preserves the shared track entity, physical audio file, other playlists, and playback.
     */
    suspend fun removeTrackFromPlaylist(
        playlistId: String,
        entryId: Long
    ): PlaylistEntryEntity? = withContext(ioDispatcher) {
        database.withTransaction {
            val entry = playlistDao.getPlaylistEntryById(entryId) ?: return@withTransaction null
            if (entry.playlistId != playlistId) return@withTransaction null

            playlistDao.deletePlaylistEntryById(playlistId, entryId)

            // Compact positions of remaining entries (two-phase to prevent unique constraint collisions)
            val remaining = playlistDao.getEntriesForPlaylistRaw(playlistId)
            for (i in remaining.indices) {
                playlistDao.updateEntryPosition(remaining[i].entryId, -(i + 1))
            }
            for (i in remaining.indices) {
                playlistDao.updateEntryPosition(remaining[i].entryId, i + 1)
            }

            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist != null) {
                playlistDao.updatePlaylistTitle(playlistId, playlist.title, System.currentTimeMillis())
            }

            entry
        }
    }

    /**
     * Restores a previously removed playlist entry for Undo.
     * Rechecks membership to prevent duplicate entries if already re-added.
     */
    suspend fun undoRemoveTrackFromPlaylist(
        entry: PlaylistEntryEntity
    ): AddTrackResult = withContext(ioDispatcher) {
        database.withTransaction {
            val playlist = playlistDao.getPlaylistById(entry.playlistId)
                ?: return@withTransaction AddTrackResult.PlaylistMissing

            // Recheck membership
            val alreadyPresent = playlistDao.isTrackInPlaylist(entry.playlistId, entry.trackId)
            if (alreadyPresent) {
                return@withTransaction AddTrackResult.AlreadyExists
            }

            val currentMax = playlistDao.getMaxPosition(entry.playlistId) ?: 0
            val restoredEntry = entry.copy(position = currentMax + 1)
            val rowId = playlistDao.insertPlaylistEntryIgnore(restoredEntry)
            if (rowId == -1L) {
                return@withTransaction AddTrackResult.AlreadyExists
            }

            playlistDao.updatePlaylistTitle(entry.playlistId, playlist.title, System.currentTimeMillis())
            AddTrackResult.Added
        }
    }

    /**
     * Safely repairs and merges duplicate playlist entries referencing the same confirmed recording.
     *
     * Invariants:
     * - Idempotent: running multiple times makes no further changes.
     * - Retains the earliest playlist position and preserves relative playlist order.
     * - Preserves and unifies real OfflineDownloadEntity and DownloadQueueEntity references.
     * - Never deletes parent TrackEntity rows or audio files.
     */
    suspend fun repairDuplicatePlaylistEntries(): Int = withContext(ioDispatcher) {
        database.withTransaction {
            var totalMerged = 0
            val playlists = playlistDao.getAllPlaylists()
            for (playlist in playlists) {
                val rawEntries = playlistDao.getEntriesForPlaylistRaw(playlist.playlistId)
                if (rawEntries.size <= 1) continue

                val entriesWithKey = rawEntries.map { entry ->
                    val selectedSource = trackDao.getSelectedSource(entry.trackId)
                    val recKey = when {
                        selectedSource != null && ProviderNormalizer.isYouTube(selectedSource.provider) && selectedSource.sourceId.isNotBlank() -> {
                            "youtube:${selectedSource.sourceId.trim()}"
                        }
                        entry.trackId.startsWith("youtube:") -> {
                            "youtube:${entry.trackId.removePrefix("youtube:").trim()}"
                        }
                        else -> {
                            val od = offlineDownloadDao.getCompletedForTrack(entry.trackId)
                            if (od != null && ProviderNormalizer.isYouTube(od.provider) && od.providerSourceId.isNotBlank()) {
                                "youtube:${od.providerSourceId.trim()}"
                            } else {
                                entry.trackId
                            }
                        }
                    }
                    entry to recKey
                }

                val grouped = entriesWithKey.groupBy { it.second }
                var playlistModified = false

                for ((_, group) in grouped) {
                    if (group.size <= 1) continue

                    val sorted = group.map { it.first }.sortedBy { it.position }
                    val primary = sorted.first()
                    val duplicates = sorted.drop(1)

                    val primaryTrack = trackDao.getTrackById(primary.trackId)

                    for (dup in duplicates) {
                        // 1. Preserve real download references
                        val dupDownload = offlineDownloadDao.getCompletedForTrack(dup.trackId)
                        val primaryDownload = offlineDownloadDao.getCompletedForTrack(primary.trackId)
                        if (dupDownload != null && primaryDownload == null) {
                            val updatedDownload = dupDownload.copy(trackId = primary.trackId)
                            offlineDownloadDao.upsert(updatedDownload)
                        }

                        // Preserve queued download references
                        val dupQueue = downloadQueueDao.getByTrackId(dup.trackId)
                        val primaryQueue = downloadQueueDao.getByTrackId(primary.trackId)
                        if (dupQueue != null && primaryQueue == null) {
                            downloadQueueDao.deleteByTrackId(dup.trackId)
                            val updatedQueue = dupQueue.copy(trackId = primary.trackId)
                            downloadQueueDao.upsert(updatedQueue)
                        }

                        // Copy localContentUri if primary lacked it
                        val dupTrack = trackDao.getTrackById(dup.trackId)
                        if (primaryTrack?.localContentUri.isNullOrBlank() && !dupTrack?.localContentUri.isNullOrBlank()) {
                            trackDao.markDownloaded(primary.trackId, dupTrack!!.localContentUri!!, dupTrack.downloadState)
                        }

                        // 2. Remove redundant playlist_entries row ONLY
                        playlistDao.deletePlaylistEntryById(playlist.playlistId, dup.entryId)
                        totalMerged++
                        playlistModified = true
                    }
                }

                if (playlistModified) {
                    val remaining = playlistDao.getEntriesForPlaylistRaw(playlist.playlistId)
                    for (i in remaining.indices) {
                        playlistDao.updateEntryPosition(remaining[i].entryId, -(i + 1))
                    }
                    for (i in remaining.indices) {
                        playlistDao.updateEntryPosition(remaining[i].entryId, i + 1)
                    }
                    playlistDao.updatePlaylistTitle(playlist.playlistId, playlist.title, System.currentTimeMillis())
                }
            }
            totalMerged
        }
    }


    suspend fun getPlaylistIdsForTrack(trackId: String): List<String> = withContext(ioDispatcher) {
        playlistDao.getPlaylistIdsForTrack(trackId)
    }

    /**
     * Atomically claims a track for background enrichment.
     * Returns true if successfully claimed (rows updated == 1), false otherwise.
     */
    suspend fun claimTrackForEnrichment(
        trackId: String,
        now: Long = System.currentTimeMillis(),
        retryCooldownMs: Long = 30 * 60 * 1000L,
        staleCutoffMs: Long = 5 * 60 * 1000L
    ): Boolean = withContext(ioDispatcher) {
        val rows = trackDao.claimTrackForEnrichment(
            trackId = trackId,
            now = now,
            retryCooldownMs = retryCooldownMs,
            staleCutoffMs = staleCutoffMs
        )
        rows > 0
    }

    /**
     * Resets stale IN_PROGRESS leases (>= 5 minutes) back to PENDING.
     * Active leases (< 5 minutes) are never modified.
     */
    suspend fun recoverStaleInProgressTracks(staleCutoffMs: Long = 5 * 60 * 1000L): Int = withContext(ioDispatcher) {
        trackDao.resetStaleInProgressTracks(System.currentTimeMillis(), staleCutoffMs)
    }

    suspend fun getEligibleEnrichmentTrackCount(playlistId: String, forceRetry: Boolean = false): Int = withContext(ioDispatcher) {
        getTracksNeedingEnrichment(playlistId, forceRetry).size
    }

    /**
     * Finds tracks in the given playlist that need background artwork enrichment.
     * Excludes active IN_PROGRESS leases (< 5 minutes) and respects ENRICHED, NO_CONFIDENT_MATCH, and cooldowns.
     */
    suspend fun getTracksNeedingEnrichment(
        playlistId: String,
        forceRetry: Boolean = false
    ): List<TrackEntity> = withContext(ioDispatcher) {
        val entries = playlistDao.getPlaylistEntries(playlistId)
        val now = System.currentTimeMillis()
        val retryCooldownMs = 30 * 60 * 1000L // 30 minutes cooldown
        val staleCutoffMs = 5 * 60 * 1000L // 5 minutes stale lease cutoff

        val needingEnrichment = mutableListOf<TrackEntity>()
        for (entry in entries) {
            val track = trackDao.getTrackById(entry.trackId) ?: continue

            // Never touch tracks already marked ENRICHED or carrying genuine Spotify/YouTube match artwork
            if (track.artworkEnrichmentStatus == ArtworkEnrichmentStatus.ENRICHED) continue
            if (track.artworkOrigin == ArtworkOrigin.SPOTIFY_TRACK && !track.artworkUrl.isNullOrBlank()) continue
            if (track.artworkOrigin == ArtworkOrigin.YOUTUBE_MATCH && !track.artworkUrl.isNullOrBlank()) continue
            if (!entry.selectedSourceArtworkUrl.isNullOrBlank()) continue

            // If track artwork is present and valid (not fallback legacy or unknown null), consider it enriched
            val hasIndividualArtwork = (!track.artworkUrl.isNullOrBlank() || !track.artworkUri.isNullOrBlank()) &&
                    track.artworkOrigin != ArtworkOrigin.UNKNOWN &&
                    track.artworkOrigin != ArtworkOrigin.PLAYLIST_FALLBACK_LEGACY
            if (hasIndividualArtwork) {
                trackDao.updateEnrichmentStatus(track.id, ArtworkEnrichmentStatus.ENRICHED, now)
                continue
            }

            if (forceRetry) {
                needingEnrichment.add(track)
                continue
            }

            when (track.artworkEnrichmentStatus) {
                ArtworkEnrichmentStatus.PENDING -> {
                    needingEnrichment.add(track)
                }
                ArtworkEnrichmentStatus.IN_PROGRESS -> {
                    // Active leases (< 5 min) are strictly EXCLUDED!
                    // Only include if stale lease (>= 5 min)
                    if (now - track.lastArtworkAttemptEpochMs >= staleCutoffMs) {
                        needingEnrichment.add(track)
                    }
                }
                ArtworkEnrichmentStatus.RATE_LIMITED, ArtworkEnrichmentStatus.FAILED_RETRYABLE -> {
                    if (now - track.lastArtworkAttemptEpochMs > retryCooldownMs) {
                        needingEnrichment.add(track)
                    }
                }
                ArtworkEnrichmentStatus.NO_CONFIDENT_MATCH -> {
                    // Do not automatically retry on every screen open
                }
            }
        }
        needingEnrichment
    }

    /**
     * Resets failed or unconfident enrichment statuses so a manual retry can process them.
     */
    suspend fun resetEnrichmentForRetry(playlistId: String) = withContext(ioDispatcher) {
        trackDao.resetEnrichmentStatusForPlaylist(playlistId)
    }

    /**
     * Saves enrichment outcome for a single track.
     * Persists artwork only if match is confident. Low confidence keeps neutral placeholder.
     */
    suspend fun updateTrackEnrichment(
        trackId: String,
        candidate: YouTubeCandidate?,
        isMatchSafe: Boolean
    ) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val track = trackDao.getTrackById(trackId) ?: return@withContext

        // Safety check: Never overwrite genuine Spotify track artwork or selected source artwork
        if (track.artworkOrigin == ArtworkOrigin.SPOTIFY_TRACK || track.artworkOrigin == ArtworkOrigin.YOUTUBE_MATCH) {
            trackDao.updateEnrichmentStatus(trackId, ArtworkEnrichmentStatus.ENRICHED, now)
            return@withContext
        }

        if (isMatchSafe && candidate != null && !candidate.artworkUrl.isNullOrBlank()) {
            val existingSource = trackDao.getSelectedSource(trackId)
            if (existingSource == null) {
                val nonSelectedSource = TrackSourceEntity(
                    sourceKey = "${trackId}:youtube_enrichment",
                    trackId = trackId,
                    provider = candidate.provider,
                    sourceId = candidate.videoId,
                    canonicalUrl = candidate.canonicalWatchUrl,
                    confidence = 0.75f,
                    durationDeltaMs = 0L,
                    artworkUrl = candidate.artworkUrl,
                    selected = false // Non-selected!
                )
                trackDao.insertSource(nonSelectedSource)
            }

            trackDao.updateArtworkWithEnrichment(
                trackId = trackId,
                artworkUri = candidate.artworkUrl,
                artworkUrl = candidate.artworkUrl,
                artworkOrigin = ArtworkOrigin.YOUTUBE_ENRICHMENT,
                status = ArtworkEnrichmentStatus.ENRICHED,
                timestampMs = now
            )
        } else {
            trackDao.updateEnrichmentStatus(
                trackId = trackId,
                status = ArtworkEnrichmentStatus.NO_CONFIDENT_MATCH,
                timestampMs = now
            )
        }
    }

    suspend fun markTrackEnrichmentRateLimited(trackId: String) = withContext(ioDispatcher) {
        trackDao.updateEnrichmentStatus(trackId, ArtworkEnrichmentStatus.RATE_LIMITED, System.currentTimeMillis())
    }

    suspend fun ensureLikedSongsPlaylist(): PlaylistEntity = withContext(ioDispatcher) {
        val existing = playlistDao.getPlaylistById(LIKED_SONGS_PLAYLIST_ID)
        if (existing != null) return@withContext existing
        val now = System.currentTimeMillis()
        val likedPlaylist = PlaylistEntity(
            playlistId = LIKED_SONGS_PLAYLIST_ID,
            title = "Liked Songs",
            sourceUrl = null,
            artworkUri = null,
            dateCreatedEpochMs = now,
            dateModifiedEpochMs = now
        )
        playlistDao.insertPlaylist(likedPlaylist)
        likedPlaylist
    }

    suspend fun saveTrackToLikedSongs(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        artworkUrl: String? = null,
        provider: String = "youtube",
        providerSourceId: String
    ): AddTrackResult = withContext(ioDispatcher) {
        ensureLikedSongsPlaylist()
        addTrackToPlaylistIfAbsent(
            playlistId = LIKED_SONGS_PLAYLIST_ID,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            provider = provider,
            providerSourceId = providerSourceId
        )
    }

    suspend fun removeTrackFromLikedSongs(
        provider: String = "youtube",
        providerSourceId: String
    ): Boolean = withContext(ioDispatcher) {
        val canonicalTrackId = resolveCanonicalTrackId(provider, providerSourceId)
        val deleted = playlistDao.deletePlaylistEntry(LIKED_SONGS_PLAYLIST_ID, canonicalTrackId)
        deleted > 0
    }

    fun observePlaylistsContainingRecording(
        provider: String,
        sourceId: String
    ): Flow<Set<String>> {
        val normProvider = ProviderNormalizer.normalize(provider)
        val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, sourceId)
        val canonicalTrackId = deterministicTrackId(normProvider, normSourceId)
        return playlistDao.observePlaylistsContainingRecording(canonicalTrackId, normSourceId).map { it.toSet() }
    }

    fun observePlaylistsContainingTrack(trackId: String): Flow<Set<String>> {
        val normTrackId = trackId.trim()
        val sourceId = if (normTrackId.startsWith("youtube:")) {
            ProviderNormalizer.normalizeSourceId(ProviderNormalizer.YOUTUBE, normTrackId.removePrefix("youtube:"))
        } else ""
        return playlistDao.observePlaylistsContainingRecording(normTrackId, sourceId).map { it.toSet() }
    }

    fun observeIsTrackLiked(
        provider: String = "youtube",
        providerSourceId: String
    ): Flow<Boolean> {
        return observePlaylistsContainingRecording(provider, providerSourceId).map { playlistIds ->
            playlistIds.contains(LIKED_SONGS_PLAYLIST_ID)
        }
    }

    suspend fun isTrackLiked(
        provider: String = "youtube",
        providerSourceId: String
    ): Boolean = withContext(ioDispatcher) {
        val normProvider = ProviderNormalizer.normalize(provider)
        val normSourceId = ProviderNormalizer.normalizeSourceId(normProvider, providerSourceId)
        val canonicalTrackId = resolveCanonicalTrackId(normProvider, normSourceId)
        if (normProvider == ProviderNormalizer.YOUTUBE) {
            playlistDao.isRecordingInPlaylist(LIKED_SONGS_PLAYLIST_ID, canonicalTrackId, normSourceId)
        } else {
            playlistDao.isTrackInPlaylist(LIKED_SONGS_PLAYLIST_ID, canonicalTrackId)
        }
    }
}
