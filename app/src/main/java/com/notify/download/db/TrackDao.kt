package com.notify.download.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIgnore(tracks: List<TrackEntity>): List<Long>

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Update
    suspend fun updateTracks(tracks: List<TrackEntity>)

    @Transaction
    suspend fun upsertTracksSafely(tracks: List<TrackEntity>) {
        val insertResults = insertTracksIgnore(tracks)
        val toUpdate = mutableListOf<TrackEntity>()
        for (i in tracks.indices) {
            if (insertResults[i] == -1L) {
                toUpdate.add(tracks[i])
            }
        }
        if (toUpdate.isNotEmpty()) {
            updateTracks(toUpdate)
        }
    }

    @Query("UPDATE tracks SET resolutionState = :resolutionState WHERE id = :trackId")
    suspend fun updateResolutionState(trackId: String, resolutionState: ResolutionState)

    @Query("UPDATE tracks SET resolutionState = 'METADATA_ONLY' WHERE resolutionState = 'SEARCHING'")
    suspend fun resetAbandonedSearchingStates(): Int

    @Query("UPDATE tracks SET downloadState = :downloadState WHERE id = :trackId")
    suspend fun updateDownloadState(trackId: String, downloadState: DownloadState)

    @Query("UPDATE tracks SET localContentUri = :localContentUri, downloadState = :downloadState WHERE id = :trackId")
    suspend fun markDownloaded(
        trackId: String,
        localContentUri: String,
        downloadState: DownloadState = DownloadState.DOWNLOADED
    )

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE spotifyId = :spotifyId LIMIT 1")
    suspend fun getTrackBySpotifyId(spotifyId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: TrackSourceEntity)

    @Query("UPDATE tracks SET artworkUri = :artworkUri, artworkUrl = :artworkUrl WHERE id = :trackId")
    suspend fun updateArtwork(trackId: String, artworkUri: String?, artworkUrl: String?)

    @Query("UPDATE tracks SET artworkUri = :artworkUri, artworkUrl = :artworkUrl, artworkOrigin = :artworkOrigin WHERE id = :trackId")
    suspend fun updateArtworkWithOrigin(trackId: String, artworkUri: String?, artworkUrl: String?, artworkOrigin: String)

    @Query("UPDATE tracks SET artworkUrl = :artworkUrl WHERE id = :trackId")
    suspend fun updateTrackArtworkUrl(trackId: String, artworkUrl: String?)

    @Query("SELECT * FROM tracks WHERE resolutionState = 'RESOLVE_FAILED'")
    suspend fun getFailedTracks(): List<TrackEntity>

    @Query("SELECT * FROM track_sources WHERE trackId = :trackId AND selected = 1 LIMIT 1")
    suspend fun getSelectedSource(trackId: String): TrackSourceEntity?

    @Query("SELECT * FROM track_sources WHERE trackId = :trackId")
    suspend fun getSourcesForTrack(trackId: String): List<TrackSourceEntity>

    /**
     * Deterministically finds all tracks mapping to a confirmed YouTube video ID.
     * Orders imported Spotify records first, then tracks with local audio files, then earliest created.
     */
    @Query("""
        SELECT t.id FROM tracks t
        INNER JOIN track_sources ts ON t.id = ts.trackId
        WHERE LOWER(ts.provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
          AND ts.sourceId = :sourceId
          AND ts.selected = 1
        ORDER BY 
          CASE WHEN t.id LIKE 'spotify:%' OR t.spotifyId IS NOT NULL THEN 0 ELSE 1 END ASC,
          CASE WHEN t.localContentUri IS NOT NULL THEN 0 ELSE 1 END ASC,
          t.dateAddedEpochMs ASC,
          t.id ASC
    """)
    suspend fun findCanonicalTrackIdsForYouTubeSource(sourceId: String): List<String>

    @Query("SELECT id FROM tracks WHERE id = 'youtube:' || :sourceId LIMIT 1")
    suspend fun findDirectYouTubeTrackId(sourceId: String): String?

    @Query("""
        SELECT trackId FROM offline_downloads
        WHERE LOWER(provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
          AND providerSourceId = :sourceId
          AND status = 'COMPLETED'
        ORDER BY 
          CASE WHEN trackId LIKE 'spotify:%' THEN 0 ELSE 1 END ASC,
          createdAtEpochMs ASC,
          trackId ASC
    """)
    suspend fun findDownloadedTrackIdsForSource(sourceId: String): List<String>

    @Query("""
        SELECT id FROM tracks 
        WHERE id = :spotifyId 
           OR id = 'spotify:' || :spotifyId 
           OR spotifyId = :spotifyId 
           OR id LIKE 'spotify:%' || :spotifyId || '%'
        LIMIT 1
    """)
    suspend fun findTrackIdForSpotify(spotifyId: String): String?

    @Query("UPDATE tracks SET artworkUri = :artworkUri, artworkUrl = :artworkUrl, artworkOrigin = :artworkOrigin, artworkEnrichmentStatus = :status, lastArtworkAttemptEpochMs = :timestampMs WHERE id = :trackId")
    suspend fun updateArtworkWithEnrichment(
        trackId: String,
        artworkUri: String?,
        artworkUrl: String?,
        artworkOrigin: String,
        status: String,
        timestampMs: Long
    )

    @Query("UPDATE tracks SET artworkEnrichmentStatus = :status, lastArtworkAttemptEpochMs = :timestampMs WHERE id = :trackId")
    suspend fun updateEnrichmentStatus(
        trackId: String,
        status: String,
        timestampMs: Long
    )

    @Query("""
        UPDATE tracks 
        SET artworkEnrichmentStatus = 'PENDING',
            lastArtworkAttemptEpochMs = 0
        WHERE id IN (
            SELECT trackId FROM playlist_entries WHERE playlistId = :playlistId
        ) AND artworkEnrichmentStatus != 'ENRICHED'
    """)
    suspend fun resetEnrichmentStatusForPlaylist(playlistId: String)

    @Query("""
        UPDATE tracks
        SET artworkEnrichmentStatus = 'IN_PROGRESS',
            lastArtworkAttemptEpochMs = :now,
            artworkAttemptCount = artworkAttemptCount + 1
        WHERE id = :trackId
          AND (
              artworkEnrichmentStatus = 'PENDING'
              OR (artworkEnrichmentStatus = 'FAILED_RETRYABLE' AND (:now - lastArtworkAttemptEpochMs) >= :retryCooldownMs)
              OR (artworkEnrichmentStatus = 'RATE_LIMITED' AND (:now - lastArtworkAttemptEpochMs) >= :retryCooldownMs)
              OR (artworkEnrichmentStatus = 'IN_PROGRESS' AND (:now - lastArtworkAttemptEpochMs) >= :staleCutoffMs)
          )
    """)
    suspend fun claimTrackForEnrichment(
        trackId: String,
        now: Long,
        retryCooldownMs: Long,
        staleCutoffMs: Long
    ): Int

    @Query("""
        UPDATE tracks
        SET artworkEnrichmentStatus = 'PENDING'
        WHERE artworkEnrichmentStatus = 'IN_PROGRESS'
          AND (:now - lastArtworkAttemptEpochMs) >= :staleCutoffMs
    """)
    suspend fun resetStaleInProgressTracks(now: Long, staleCutoffMs: Long): Int
}
