package com.notify.download.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntries(entries: List<PlaylistEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntry(entry: PlaylistEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistEntryIgnore(entry: PlaylistEntryEntity): Long

    @Query("SELECT MAX(position) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int?

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deletePlaylistEntries(playlistId: String)

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY dateModifiedEpochMs DESC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists ORDER BY dateModifiedEpochMs DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("""
        SELECT e.entryId, e.playlistId, e.position,
               t.id AS trackId, t.title, t.artist, t.album, t.durationMs,
               t.artworkUri, t.artworkUrl, t.spotifyId,
               t.resolutionState, t.downloadState, t.localContentUri, t.dateAddedEpochMs,
               ts.artworkUrl AS selectedSourceArtworkUrl,
               t.artworkOrigin, t.artworkEnrichmentStatus
        FROM playlist_entries e
        INNER JOIN tracks t ON e.trackId = t.id
        LEFT JOIN track_sources ts ON ts.trackId = t.id AND ts.selected = 1
        WHERE e.playlistId = :playlistId
        ORDER BY e.position ASC
    """)
    suspend fun getPlaylistEntries(playlistId: String): List<PlaylistEntryWithTrack>

    @Query("""
        SELECT e.entryId, e.playlistId, e.position,
               t.id AS trackId, t.title, t.artist, t.album, t.durationMs,
               t.artworkUri, t.artworkUrl, t.spotifyId,
               t.resolutionState, t.downloadState, t.localContentUri, t.dateAddedEpochMs,
               ts.artworkUrl AS selectedSourceArtworkUrl,
               t.artworkOrigin, t.artworkEnrichmentStatus
        FROM playlist_entries e
        INNER JOIN tracks t ON e.trackId = t.id
        LEFT JOIN track_sources ts ON ts.trackId = t.id AND ts.selected = 1
        WHERE e.playlistId = :playlistId
        ORDER BY e.position ASC
    """)
    fun observePlaylistEntries(playlistId: String): Flow<List<PlaylistEntryWithTrack>>

    @Query("""
        SELECT p.playlistId, p.title, p.sourceUrl, p.artworkUri,
               p.dateCreatedEpochMs, p.dateModifiedEpochMs,
               COUNT(e.entryId) AS trackCount,
               (
                   SELECT COALESCE(t.artworkUrl, t.artworkUri)
                   FROM playlist_entries e2
                   INNER JOIN tracks t ON e2.trackId = t.id
                   WHERE e2.playlistId = p.playlistId 
                     AND (t.artworkUrl IS NOT NULL OR t.artworkUri IS NOT NULL)
                   ORDER BY e2.position ASC
                   LIMIT 1
               ) AS firstTrackArtworkUrl
        FROM playlists p
        LEFT JOIN playlist_entries e ON p.playlistId = e.playlistId
        GROUP BY p.playlistId
        ORDER BY p.dateModifiedEpochMs DESC
    """)
    fun observePlaylistSummaries(): Flow<List<PlaylistSummary>>

    @Query("""
        SELECT p.playlistId, p.title, p.sourceUrl, p.artworkUri,
               p.dateCreatedEpochMs, p.dateModifiedEpochMs,
               COUNT(e.entryId) AS trackCount,
               (
                   SELECT COALESCE(t.artworkUrl, t.artworkUri)
                   FROM playlist_entries e2
                   INNER JOIN tracks t ON e2.trackId = t.id
                   WHERE e2.playlistId = p.playlistId 
                     AND (t.artworkUrl IS NOT NULL OR t.artworkUri IS NOT NULL)
                   ORDER BY e2.position ASC
                   LIMIT 1
               ) AS firstTrackArtworkUrl
        FROM playlists p
        LEFT JOIN playlist_entries e ON p.playlistId = e.playlistId
        GROUP BY p.playlistId
        ORDER BY p.dateModifiedEpochMs DESC
    """)
    suspend fun getPlaylistSummaries(): List<PlaylistSummary>

    @Query("UPDATE playlists SET title = :newTitle, dateModifiedEpochMs = :modifiedTimeMs WHERE playlistId = :playlistId")
    suspend fun updatePlaylistTitle(playlistId: String, newTitle: String, modifiedTimeMs: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("SELECT COUNT(*) > 0 FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean

    /**
     * Cross-provider membership check: determines if the recording (by canonical trackId or confirmed YouTube sourceId)
     * is already present in the playlist under ANY catalog alias.
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM playlist_entries pe
        WHERE pe.playlistId = :playlistId
          AND (
            pe.trackId = :canonicalTrackId
            OR pe.trackId IN (
                SELECT ts.trackId FROM track_sources ts
                WHERE LOWER(ts.provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                  AND ts.sourceId = :sourceId
                  AND ts.selected = 1
            )
            OR pe.trackId IN (
                SELECT od.trackId FROM offline_downloads od
                WHERE LOWER(od.provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                  AND od.providerSourceId = :sourceId
            )
            OR pe.trackId = 'youtube:' || :sourceId
          )
    """)
    suspend fun isRecordingInPlaylist(playlistId: String, canonicalTrackId: String, sourceId: String): Boolean

    @Query("SELECT playlistId FROM playlist_entries WHERE trackId = :trackId")
    fun observePlaylistsContainingTrack(trackId: String): Flow<List<String>>

    /**
     * Observes all playlists containing a recording across all recognized catalog aliases.
     */
    @Query("""
        SELECT DISTINCT pe.playlistId FROM playlist_entries pe
        WHERE pe.trackId = :canonicalTrackId
           OR pe.trackId IN (
               SELECT ts.trackId FROM track_sources ts
               WHERE LOWER(ts.provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                 AND ts.sourceId = :sourceId
                 AND ts.selected = 1
           )
           OR pe.trackId IN (
               SELECT od.trackId FROM offline_downloads od
               WHERE LOWER(od.provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                 AND od.providerSourceId = :sourceId
           )
           OR pe.trackId = 'youtube:' || :sourceId
    """)
    fun observePlaylistsContainingRecording(canonicalTrackId: String, sourceId: String): Flow<List<String>>

    @Query("SELECT playlistId FROM playlist_entries WHERE trackId = :trackId")
    suspend fun getPlaylistIdsForTrack(trackId: String): List<String>

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deletePlaylistEntry(playlistId: String, trackId: String): Int

    @Query("SELECT * FROM playlist_entries WHERE entryId = :entryId")
    suspend fun getPlaylistEntryById(entryId: Long): PlaylistEntryEntity?

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId LIMIT 1")
    suspend fun getPlaylistEntryByTrack(playlistId: String, trackId: String): PlaylistEntryEntity?

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND entryId = :entryId")
    suspend fun deletePlaylistEntryById(playlistId: String, entryId: Long): Int

    @Query("UPDATE playlist_entries SET position = :newPosition WHERE entryId = :entryId")
    suspend fun updateEntryPosition(entryId: Long, newPosition: Int)

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getEntriesForPlaylistRaw(playlistId: String): List<PlaylistEntryEntity>
}

