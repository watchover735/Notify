package com.notify.core.downloads.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDownloadDao {

    // ── Inserts ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: OfflineDownloadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineDownloadEntity)

    // ── Queries by ID / track ────────────────────────────────────────────────

    @Query("SELECT * FROM offline_downloads WHERE downloadId = :downloadId")
    suspend fun getById(downloadId: String): OfflineDownloadEntity?

    @Query("SELECT * FROM offline_downloads WHERE trackId = :trackId AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedForTrack(trackId: String): OfflineDownloadEntity?

    @Query("SELECT * FROM offline_downloads WHERE trackId = :trackId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestForTrack(trackId: String): OfflineDownloadEntity?

    @Query("SELECT * FROM offline_downloads WHERE trackId = :trackId AND status IN ('PENDING', 'RESOLVING', 'DOWNLOADING', 'VALIDATING') LIMIT 1")
    suspend fun getActiveForTrack(trackId: String): OfflineDownloadEntity?

    @Query("SELECT COUNT(*) > 0 FROM offline_downloads WHERE trackId = :trackId AND status = 'COMPLETED'")
    suspend fun isTrackDownloaded(trackId: String): Boolean

    @Query("SELECT * FROM offline_downloads WHERE providerSourceId = :sourceId AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedForSource(sourceId: String): OfflineDownloadEntity?

    @Query("""
        SELECT * FROM offline_downloads 
        WHERE LOWER(provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
          AND providerSourceId = :sourceId 
          AND status = 'COMPLETED' 
        LIMIT 1
    """)
    suspend fun getCompletedForYouTubeSource(sourceId: String): OfflineDownloadEntity?

    @Query("""
        SELECT * FROM offline_downloads 
        WHERE LOWER(provider) = :provider
          AND providerSourceId = :sourceId 
          AND status = 'COMPLETED' 
        LIMIT 1
    """)
    suspend fun getCompletedForProviderAndSource(provider: String, sourceId: String): OfflineDownloadEntity?

    @Query("""
        SELECT COUNT(*) > 0 FROM offline_downloads 
        WHERE (
            trackId = :trackId 
            OR (
                LOWER(provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                AND providerSourceId = :sourceId
            )
        ) AND status = 'COMPLETED'
    """)
    suspend fun isRecordingDownloaded(trackId: String, sourceId: String): Boolean

    @Query("""
        SELECT COUNT(*) > 0 FROM offline_downloads 
        WHERE (
            trackId = :trackId 
            OR (
                LOWER(provider) IN ('youtube', 'youtubemusic', 'youtube_music', 'yt', 'ytm')
                AND providerSourceId = :sourceId
            )
        ) AND status = 'COMPLETED'
    """)
    fun observeIsRecordingDownloaded(trackId: String, sourceId: String): Flow<Boolean>

    // ── Status updates ───────────────────────────────────────────────────────

    @Query("UPDATE offline_downloads SET status = :status, updatedAtEpochMs = :updatedAt WHERE downloadId = :downloadId")
    suspend fun updateStatus(downloadId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE offline_downloads SET status = :status, progressPercent = :progress, updatedAtEpochMs = :updatedAt WHERE downloadId = :downloadId")
    suspend fun updateProgress(downloadId: String, status: String, progress: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE offline_downloads 
        SET status = 'COMPLETED', 
            fileSizeBytes = :sizeBytes, 
            mimeType = :mimeType,
            bitrateKbps = :bitrateKbps,
            durationMs = :durationMs,
            progressPercent = 100, 
            updatedAtEpochMs = :updatedAt
        WHERE downloadId = :downloadId
    """)
    suspend fun markCompleted(
        downloadId: String,
        sizeBytes: Long,
        mimeType: String?,
        bitrateKbps: Int?,
        durationMs: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE offline_downloads 
        SET status = 'FAILED', 
            errorMessage = :errorMessage, 
            attemptCount = attemptCount + 1,
            updatedAtEpochMs = :updatedAt
        WHERE downloadId = :downloadId
    """)
    suspend fun markFailed(downloadId: String, errorMessage: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE offline_downloads SET lastAccessedAtEpochMs = :accessedAt WHERE downloadId = :downloadId")
    suspend fun touchAccessTime(downloadId: String, accessedAt: Long = System.currentTimeMillis())

    @Query("UPDATE offline_downloads SET bucket = :bucket, updatedAtEpochMs = :updatedAt WHERE downloadId = :downloadId")
    suspend fun updateBucket(downloadId: String, bucket: String, updatedAt: Long = System.currentTimeMillis())

    // ── Bulk queries ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM offline_downloads WHERE status = 'COMPLETED' ORDER BY updatedAtEpochMs DESC, downloadId ASC")
    fun observeCompletedDownloads(): Flow<List<OfflineDownloadEntity>>

    @Query("SELECT * FROM offline_downloads WHERE status = 'COMPLETED' ORDER BY updatedAtEpochMs DESC, downloadId ASC")
    suspend fun getCompletedDownloads(): List<OfflineDownloadEntity>

    @Query("SELECT * FROM offline_downloads WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING', 'VALIDATING') ORDER BY createdAtEpochMs ASC")
    suspend fun getActiveDownloads(): List<OfflineDownloadEntity>

    @Query("SELECT * FROM offline_downloads WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING', 'VALIDATING') ORDER BY createdAtEpochMs ASC")
    fun observeActiveDownloads(): Flow<List<OfflineDownloadEntity>>

    /** Observe all downloads that are not in a terminal completed/evicted state, including FAILED. */
    @Query("SELECT * FROM offline_downloads WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING', 'VALIDATING', 'FAILED') ORDER BY createdAtEpochMs ASC")
    fun observeNonTerminalDownloads(): Flow<List<OfflineDownloadEntity>>

    /** Observe downloads in FAILED status for UI error indicators. */
    @Query("SELECT * FROM offline_downloads WHERE status = 'FAILED' ORDER BY updatedAtEpochMs DESC")
    fun observeFailedDownloads(): Flow<List<OfflineDownloadEntity>>

    @Query("SELECT * FROM offline_downloads WHERE status = 'PENDING' ORDER BY createdAtEpochMs ASC LIMIT 1")
    suspend fun getNextPending(): OfflineDownloadEntity?

    /** LRU ordering for smart offline eviction (oldest accessed first). */
    @Query("SELECT * FROM offline_downloads WHERE bucket = 'SMART_OFFLINE' AND status = 'COMPLETED' ORDER BY lastAccessedAtEpochMs ASC")
    suspend fun getSmartOfflineEntriesLru(): List<OfflineDownloadEntity>

    @Query("SELECT COUNT(*) FROM offline_downloads WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM offline_downloads WHERE status = 'COMPLETED'")
    suspend fun getTotalDownloadedBytes(): Long

    // ── Cleanup ──────────────────────────────────────────────────────────────

    @Query("DELETE FROM offline_downloads WHERE downloadId = :downloadId")
    suspend fun delete(downloadId: String)

    @Query("DELETE FROM offline_downloads WHERE status IN ('FAILED', 'CANCELLED', 'EVICTED')")
    suspend fun cleanupTerminalEntries(): Int

    /**
     * Reset stale in-progress downloads back to PENDING on app startup.
     * Uses a cutoff to avoid resetting genuinely running downloads.
     */
    @Query("""
        UPDATE offline_downloads 
        SET status = 'PENDING', progressPercent = 0, updatedAtEpochMs = :now
        WHERE status IN ('RESOLVING', 'DOWNLOADING', 'VALIDATING')
          AND (:now - updatedAtEpochMs) >= :staleCutoffMs
    """)
    suspend fun resetStaleActiveDownloads(now: Long, staleCutoffMs: Long): Int
}
