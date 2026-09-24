package com.notify.core.downloads.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {

    // ── Inserts & Upserts ───────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: DownloadQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<DownloadQueueEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadQueueEntity)

    // ── Queries ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM download_queue WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): DownloadQueueEntity?

    @Query("SELECT * FROM download_queue WHERE status = 'QUEUED' ORDER BY queuePosition ASC, createdAtEpochMs ASC LIMIT 1")
    suspend fun getNextQueued(): DownloadQueueEntity?

    @Query("SELECT MAX(queuePosition) FROM download_queue")
    suspend fun getMaxQueuePosition(): Int?

    @Query("SELECT * FROM download_queue WHERE status IN ('RESOLVING', 'DOWNLOADING', 'VALIDATING') LIMIT 1")
    suspend fun getActiveDownloading(): DownloadQueueEntity?

    @Query("SELECT * FROM download_queue WHERE status IN ('RESOLVING', 'DOWNLOADING', 'VALIDATING') LIMIT 1")
    fun observeActiveDownloading(): Flow<DownloadQueueEntity?>

    @Query("SELECT * FROM download_queue ORDER BY queuePosition ASC, createdAtEpochMs ASC")
    fun observeAllQueue(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT * FROM download_queue WHERE playlistId = :playlistId ORDER BY playlistPosition ASC, queuePosition ASC")
    fun observeQueueForPlaylist(playlistId: String): Flow<List<DownloadQueueEntity>>

    @Query("SELECT * FROM download_queue WHERE status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'VALIDATING', 'PAUSED') ORDER BY queuePosition ASC")
    fun observeActiveAndQueued(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'QUEUED'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_queue")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'PAUSED'")
    suspend fun getPausedCount(): Int

    // ── Atomic Claim Transactions ───────────────────────────────────────────

    @Query("""
        UPDATE download_queue
        SET status = 'RESOLVING',
            claimedByWorkerId = :workerId,
            attemptCount = attemptCount + 1,
            updatedAtEpochMs = :now,
            lastProgressEpochMs = :now
        WHERE trackId = (
            SELECT trackId FROM download_queue
            WHERE status = 'QUEUED'
            ORDER BY queuePosition ASC, createdAtEpochMs ASC
            LIMIT 1
        )
    """)
    suspend fun markNextQueuedClaimed(workerId: String, now: Long): Int

    @Query("SELECT * FROM download_queue WHERE claimedByWorkerId = :workerId AND status = 'RESOLVING' ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getClaimedByWorker(workerId: String): DownloadQueueEntity?

    @Query("""
        UPDATE download_queue
        SET status = 'RESOLVING',
            claimedByWorkerId = :workerId,
            attemptCount = attemptCount + 1,
            updatedAtEpochMs = :now,
            lastProgressEpochMs = :now
        WHERE trackId = :trackId AND status IN ('QUEUED', 'PAUSED')
    """)
    suspend fun markSpecificTrackClaimed(trackId: String, workerId: String, now: Long): Int

    /**
     * Atomically claims the next eligible QUEUED track in strict serial order.
     * Prevents multiple workers or threads from processing the same track concurrently.
     */
    @Transaction
    suspend fun claimNextQueuedTrack(
        workerInstanceId: String,
        now: Long = System.currentTimeMillis()
    ): DownloadQueueEntity? {
        val updated = markNextQueuedClaimed(workerInstanceId, now)
        if (updated <= 0) return null
        return getClaimedByWorker(workerInstanceId)
    }

    /**
     * Atomically claims a specific track by ID if it is QUEUED or PAUSED.
     */
    @Transaction
    suspend fun claimSpecificTrack(
        trackId: String,
        workerInstanceId: String,
        now: Long = System.currentTimeMillis()
    ): DownloadQueueEntity? {
        val updated = markSpecificTrackClaimed(trackId, workerInstanceId, now)
        if (updated <= 0) return null
        return getByTrackId(trackId)
    }

    @Query("""
        UPDATE download_queue
        SET status = :status,
            claimedByWorkerId = :workerId,
            updatedAtEpochMs = :now,
            lastProgressEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun markClaimed(trackId: String, status: String, workerId: String, now: Long)

    // ── Status & Progress Updates ───────────────────────────────────────────

    @Query("""
        UPDATE download_queue
        SET status = :status,
            updatedAtEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun updateStatus(trackId: String, status: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE download_queue
        SET status = :status,
            progressPercent = :progress,
            lastProgressEpochMs = :now,
            updatedAtEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun updateProgress(
        trackId: String,
        status: String,
        progress: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_queue
        SET status = 'COMPLETED',
            progressPercent = 100,
            claimedByWorkerId = NULL,
            updatedAtEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun markCompleted(trackId: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE download_queue
        SET status = 'FAILED',
            errorMessage = :errorMessage,
            claimedByWorkerId = NULL,
            attemptCount = attemptCount + 1,
            updatedAtEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun markFailed(
        trackId: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis()
    )

    // ── Queue Controls (Pause, Resume, Retry, Cancel) ───────────────────────

    @Query("""
        UPDATE download_queue
        SET status = 'PAUSED',
            updatedAtEpochMs = :now
        WHERE status = 'QUEUED'
    """)
    suspend fun pauseQueue(now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE download_queue
        SET status = 'QUEUED',
            updatedAtEpochMs = :now
        WHERE status = 'PAUSED'
    """)
    suspend fun resumeQueue(now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE download_queue
        SET status = 'QUEUED',
            errorMessage = NULL,
            attemptCount = 0,
            updatedAtEpochMs = :now
        WHERE status = 'FAILED'
    """)
    suspend fun retryFailed(now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE download_queue
        SET status = 'QUEUED',
            errorMessage = NULL,
            attemptCount = 0,
            updatedAtEpochMs = :now
        WHERE trackId = :trackId
    """)
    suspend fun retrySingle(trackId: String, now: Long = System.currentTimeMillis())

    /**
     * Cancels uncompleted items from queue, preserving completed items and downloads.
     */
    @Query("DELETE FROM download_queue WHERE status IN ('QUEUED', 'PAUSED')")
    suspend fun cancelUncompleted(): Int

    @Query("DELETE FROM download_queue WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    @Query("DELETE FROM download_queue WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    // ── Process-Death & Stuck Protection Recovery ───────────────────────────

    /**
     * Resets any stale in-progress downloads older than [cutoffEpochMs] back to QUEUED.
     */
    @Query("""
        UPDATE download_queue
        SET status = 'QUEUED',
            claimedByWorkerId = NULL,
            progressPercent = 0,
            updatedAtEpochMs = :now
        WHERE status IN ('RESOLVING', 'DOWNLOADING', 'VALIDATING')
          AND updatedAtEpochMs < :cutoffEpochMs
    """)
    suspend fun resetStaleQueueItems(cutoffEpochMs: Long, now: Long = System.currentTimeMillis()): Int

    /**
     * Finds active DOWNLOADING tracks whose last progress is older than [stallThresholdEpochMs].
     */
    @Query("""
        SELECT * FROM download_queue
        WHERE status = 'DOWNLOADING'
          AND lastProgressEpochMs > 0
          AND lastProgressEpochMs < :stallThresholdEpochMs
    """)
    suspend fun getStalledDownloads(stallThresholdEpochMs: Long): List<DownloadQueueEntity>
}
