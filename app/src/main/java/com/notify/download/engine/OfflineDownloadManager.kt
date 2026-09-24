package com.notify.download.engine

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.notify.core.downloads.db.DownloadQueueDao
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.core.downloads.storage.OfflineStorage
import com.notify.core.downloads.storage.SmartStorageManager
import com.notify.core.model.DownloadBucket
import androidx.room.withTransaction
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

data class StorageStats(
    val completedCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val pinnedSizeBytes: Long = 0L,
    val smartSizeBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L
)

/**
 * High-level coordinator for offline downloads.
 * Enqueues downloads, manages status, and coordinates Room-backed serial execution.
 *
 * INVARIANT: MAX_CONCURRENT_AUDIO_DOWNLOADS = 1.
 * INVARIANT: One GLOBAL unique work chain ("notify_offline_download_queue") across
 *            every playlist and single-track request, chained with .then(...).
 * INVARIANT: One physical file per (provider, providerSourceId).
 * INVARIANT: Download state is global per stable catalogTrackId (trackId).
 */
class OfflineDownloadManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "OfflineDownloadManager"
        const val MAX_CONCURRENT_AUDIO_DOWNLOADS = 1
        const val UNIQUE_WORK_NAME = "notify_offline_download_queue"
        const val STALE_CUTOFF_MS = 15 * 60 * 1000L // 15 minutes stuck timeout
    }

    private val database by lazy { NotiFyDatabase.getInstance(context) }
    private val downloadDao: OfflineDownloadDao by lazy { database.offlineDownloadDao() }
    private val queueDao: DownloadQueueDao by lazy { database.downloadQueueDao() }
    private val storage: OfflineStorage by lazy { OfflineStorage(context) }
    private val preferences by lazy { DownloadPreferences(context) }
    private val storageManager by lazy { SmartStorageManager(storage, preferences, downloadDao) }

    fun getDownloadQueueDao(): DownloadQueueDao = queueDao
    fun getOfflineDownloadDao(): OfflineDownloadDao = downloadDao
    fun getOfflineStorage(): OfflineStorage = storage

    /**
     * Enqueues a single track for offline download (matched or unmatched).
     * Deduplicates: if already downloaded or active in queue, returns immediately.
     */
    suspend fun enqueueTrackDownload(
        trackId: String,
        playlistId: String? = null,
        playlistPosition: Int = 0,
        bucket: DownloadBucket = DownloadBucket.PINNED
    ): Boolean {
        var shouldEnqueueWorker = false
        var canonicalTrackId = trackId
        val enqueued = database.withTransaction {
            val source = database.trackDao().getSelectedSource(trackId)
            val sourceId = source?.sourceId?.takeIf { it.isNotBlank() }
                ?: if (trackId.startsWith("youtube:")) trackId.removePrefix("youtube:").trim() else null

            canonicalTrackId = if (!sourceId.isNullOrBlank()) {
                val candidates = database.trackDao().findCanonicalTrackIdsForYouTubeSource(sourceId)
                candidates.firstOrNull { database.trackDao().getTrackById(it) != null } ?: trackId
            } else {
                trackId
            }
            val effectiveSource = database.trackDao().getSelectedSource(canonicalTrackId) ?: source

            // 1. Skip if already completed with valid file on disk (checking trackId and YouTube sourceId)
            val existingCompleted = downloadDao.getCompletedForTrack(canonicalTrackId)
                ?: (sourceId?.let { downloadDao.getCompletedForYouTubeSource(it) })

            if (existingCompleted != null && storage.isFileValid(existingCompleted.relativeStorageKey)) {
                Log.d(TAG, "Track $canonicalTrackId already downloaded: ${existingCompleted.relativeStorageKey}")
                downloadDao.touchAccessTime(existingCompleted.downloadId)
                val contentUri = storage.getContentUri(existingCompleted.relativeStorageKey).toString()
                database.trackDao().markDownloaded(canonicalTrackId, contentUri)
                if (canonicalTrackId != trackId) {
                    database.trackDao().markDownloaded(trackId, contentUri)
                }
                return@withTransaction false
            }

            // 2. Skip if already active in queue across any alias of this recording
            val candidateTrackIds = mutableListOf(canonicalTrackId, trackId)
            if (!sourceId.isNullOrBlank()) {
                candidateTrackIds.addAll(database.trackDao().findCanonicalTrackIdsForYouTubeSource(sourceId))
                candidateTrackIds.add("youtube:$sourceId")
            }
            for (candId in candidateTrackIds.distinct()) {
                val existingQueue = queueDao.getByTrackId(candId)
                if (existingQueue != null && existingQueue.status in listOf(
                        DownloadQueueStatus.QUEUED,
                        DownloadQueueStatus.RESOLVING,
                        DownloadQueueStatus.DOWNLOADING,
                        DownloadQueueStatus.VALIDATING
                    )
                ) {
                    Log.d(TAG, "Track $canonicalTrackId already active in queue under $candId: ${existingQueue.status}")
                    return@withTransaction false
                }
            }

            val track = database.trackDao().getTrackById(canonicalTrackId) ?: database.trackDao().getTrackById(trackId)
            val maxPos = queueDao.getMaxQueuePosition() ?: 0
            val now = System.currentTimeMillis()

            val queueItem = DownloadQueueEntity(
                trackId = canonicalTrackId,
                status = DownloadQueueStatus.QUEUED,
                progressPercent = 0,
                trackTitle = track?.title ?: "",
                artist = track?.artist ?: "",
                album = track?.album,
                durationMs = track?.durationMs ?: 0L,
                artworkUrl = track?.artworkUrl ?: track?.artworkUri,
                playlistId = playlistId,
                playlistPosition = playlistPosition,
                queuePosition = maxPos + 1,
                attemptCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            queueDao.upsert(queueItem)

            // 3. Pre-create PENDING entity in offline_downloads if source already resolved
            if (effectiveSource != null) {
                insertOrUpdatePendingOfflineEntity(canonicalTrackId, effectiveSource, bucket, now)
            }

            Log.d(TAG, "Enqueued single track download: $canonicalTrackId")
            shouldEnqueueWorker = true
            true
        }

        if (shouldEnqueueWorker) {
            enqueueChain(listOf(canonicalTrackId))
        }
        return enqueued
    }

    /**
     * Backward-compatible enqueue for single track with explicit entities.
     */
    suspend fun enqueueDownload(
        track: TrackEntity,
        source: TrackSourceEntity?,
        bucket: DownloadBucket = DownloadBucket.SMART_OFFLINE
    ): String? {
        val success = enqueueTrackDownload(track.id, bucket = bucket)
        return if (success) track.id else null
    }

    /**
     * Enqueues all tracks in a playlist for download in strict playlist order.
     *
     * - Skips and links tracks that already have a valid OfflineDownloadEntity.
     * - Deduplicates tracks by stable catalogTrackId.
     * - Inserts remaining tracks as QUEUED.
     * - Builds one global unique work chain (notify_offline_download_queue)
     *   using firstRequest.then(secondRequest).then(thirdRequest).
     * - Never enqueues lists of parallel workers.
     */
    suspend fun enqueuePlaylistDownload(
        playlistId: String,
        bucket: DownloadBucket = DownloadBucket.PINNED
    ): Int {
        val entries = database.playlistDao().getPlaylistEntries(playlistId)
        val enqueuedTrackIds = mutableListOf<String>()
        var maxPos = queueDao.getMaxQueuePosition() ?: 0
        val now = System.currentTimeMillis()

        // Deduplicate playlist entries by stable catalogTrackId
        val distinctEntries = entries.distinctBy { it.trackId }

        for (entry in distinctEntries) {
            val trackId = entry.trackId

            // 1. Skip tracks that already have a valid completed file on disk
            val completed = downloadDao.getCompletedForTrack(trackId)
            if (completed != null && storage.isFileValid(completed.relativeStorageKey)) {
                Log.d(TAG, "Track $trackId already downloaded; skipping")
                downloadDao.touchAccessTime(completed.downloadId)
                continue
            }

            // 2. Skip if already active in queue
            val existingQueue = queueDao.getByTrackId(trackId)
            if (existingQueue != null && existingQueue.status in listOf(
                    DownloadQueueStatus.QUEUED,
                    DownloadQueueStatus.RESOLVING,
                    DownloadQueueStatus.DOWNLOADING,
                    DownloadQueueStatus.VALIDATING,
                    DownloadQueueStatus.PAUSED
                )
            ) {
                Log.d(TAG, "Track $trackId already in queue: ${existingQueue.status}; linking")
                continue
            }

            val track = database.trackDao().getTrackById(trackId)
            maxPos++
            val queueItem = DownloadQueueEntity(
                trackId = trackId,
                status = DownloadQueueStatus.QUEUED,
                progressPercent = 0,
                trackTitle = track?.title ?: "",
                artist = track?.artist ?: "",
                album = track?.album,
                durationMs = track?.durationMs ?: 0L,
                artworkUrl = track?.artworkUrl ?: track?.artworkUri,
                playlistId = playlistId,
                playlistPosition = entry.position,
                queuePosition = maxPos,
                attemptCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            queueDao.upsert(queueItem)

            // If source is already known, prepare offline_downloads row
            val source = database.trackDao().getSelectedSource(trackId)
            if (source != null) {
                insertOrUpdatePendingOfflineEntity(trackId, source, bucket, now)
            }

            enqueuedTrackIds.add(trackId)
        }

        if (enqueuedTrackIds.isNotEmpty()) {
            Log.d(TAG, "Enqueuing serial chain of ${enqueuedTrackIds.size} tracks for playlist $playlistId")
            enqueueChain(enqueuedTrackIds)
        }

        return enqueuedTrackIds.size
    }

    private suspend fun insertOrUpdatePendingOfflineEntity(
        trackId: String,
        source: TrackSourceEntity,
        bucket: DownloadBucket,
        now: Long
    ) {
        val bucketName = when (bucket) {
            DownloadBucket.PINNED -> "PINNED"
            DownloadBucket.SMART_OFFLINE -> "SMART_OFFLINE"
            DownloadBucket.TEMPORARY_CACHE -> "SMART_OFFLINE"
        }
        val relativeKey = OfflineStorage.buildRelativeKey(
            bucket = bucket,
            provider = source.provider,
            sourceId = source.sourceId
        )
        val entity = OfflineDownloadEntity(
            downloadId = UUID.randomUUID().toString(),
            trackId = trackId,
            provider = source.provider,
            providerSourceId = source.sourceId,
            canonicalUrl = source.canonicalUrl,
            relativeStorageKey = relativeKey,
            bucket = bucketName,
            status = OfflineDownloadStatus.PENDING,
            qualityProfile = preferences.audioQualityProfile.name,
            durationMs = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastAccessedAtEpochMs = now
        )
        val insertResult = downloadDao.insertIgnore(entity)
        if (insertResult == -1L) {
            downloadDao.upsert(entity)
        }
    }

    /**
     * Builds and enqueues a strict serial WorkManager continuation chain.
     * Uses firstRequest.then(secondRequest).then(thirdRequest).
     * Appends to the global UNIQUE_WORK_NAME chain.
     */
    private fun enqueueChain(trackIds: List<String>) {
        if (trackIds.isEmpty()) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (preferences.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val requests = trackIds.map { trackId ->
            OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .setInputData(workDataOf(TrackDownloadWorker.KEY_TRACK_ID to trackId))
                .addTag(TrackDownloadWorker.TAG)
                .addTag("track_$trackId")
                .build()
        }

        var continuation = WorkManager.getInstance(context).beginUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            requests[0]
        )

        for (i in 1 until requests.size) {
            continuation = continuation.then(requests[i])
        }

        continuation.enqueue()
        Log.d(TAG, "Enqueued serial continuation of ${requests.size} requests under $UNIQUE_WORK_NAME")
    }

    // ── Queue Controls (Pause, Resume, Cancel, Retry) ───────────────────────

    suspend fun pauseQueue() {
        val paused = queueDao.pauseQueue()
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        Log.d(TAG, "Paused queue: $paused items transitioned to PAUSED")
    }

    suspend fun resumeQueue() {
        val resumed = queueDao.resumeQueue()
        Log.d(TAG, "Resumed queue: $resumed items transitioned back to QUEUED")
        val pending = queueDao.observeActiveAndQueued().first()
            .filter { it.status == DownloadQueueStatus.QUEUED }
            .map { it.trackId }
        if (pending.isNotEmpty()) {
            enqueueChain(pending)
        }
    }

    suspend fun cancelQueue() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        val cancelled = queueDao.cancelUncompleted()
        Log.d(TAG, "Cancelled queue: removed $cancelled uncompleted items; completed files preserved")
    }

    suspend fun retryFailed() {
        val retried = queueDao.retryFailed()
        Log.d(TAG, "Retried failed downloads: $retried items reset to QUEUED")
        val pending = queueDao.observeActiveAndQueued().first()
            .filter { it.status == DownloadQueueStatus.QUEUED }
            .map { it.trackId }
        if (pending.isNotEmpty()) {
            enqueueChain(pending)
        }
    }

    suspend fun retrySingleTrack(trackId: String) {
        queueDao.retrySingle(trackId)
        enqueueChain(listOf(trackId))
    }

    /**
     * Cancels an individual pending/in-progress download.
     */
    suspend fun cancelDownload(downloadId: String) {
        val download = downloadDao.getById(downloadId) ?: return
        if (download.status in listOf(
                OfflineDownloadStatus.PENDING, OfflineDownloadStatus.RESOLVING,
                OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.VALIDATING
            )
        ) {
            downloadDao.updateStatus(downloadId, OfflineDownloadStatus.CANCELLED)
            queueDao.deleteByTrackId(download.trackId)
            storage.getPartFile(download.relativeStorageKey).delete()
            Log.d(TAG, "Cancelled download: $downloadId")
        }
    }

    /**
     * Removes a completed download and deletes the physical file.
     */
    suspend fun removeDownload(downloadId: String) {
        val download = downloadDao.getById(downloadId) ?: return
        storage.deleteFile(download.relativeStorageKey)
        downloadDao.delete(downloadId)
        queueDao.deleteByTrackId(download.trackId)
        Log.d(TAG, "Removed download: $downloadId")
    }

    /**
     * Removes download by trackId or associated sourceId across all catalog aliases.
     */
    suspend fun removeDownloadForTrack(trackId: String) {
        val source = database.trackDao().getSelectedSource(trackId)
        val sourceId = source?.sourceId?.takeIf { it.isNotBlank() }
            ?: if (trackId.startsWith("youtube:")) trackId.removePrefix("youtube:").trim() else null

        val download = downloadDao.getCompletedForTrack(trackId)
            ?: (sourceId?.let { downloadDao.getCompletedForYouTubeSource(it) })

        if (download != null) {
            removeDownload(download.downloadId)
            // Reset downloadState and delete queue entries for all aliases of this recording
            val candidateTrackIds = mutableListOf(download.trackId, trackId)
            if (!sourceId.isNullOrBlank()) {
                candidateTrackIds.addAll(database.trackDao().findCanonicalTrackIdsForYouTubeSource(sourceId))
                candidateTrackIds.add("youtube:$sourceId")
            }
            for (tid in candidateTrackIds.distinct()) {
                database.trackDao().updateDownloadState(tid, DownloadState.NOT_DOWNLOADED)
                queueDao.deleteByTrackId(tid)
            }
        } else {
            queueDao.deleteByTrackId(trackId)
            database.trackDao().updateDownloadState(trackId, DownloadState.NOT_DOWNLOADED)
        }
    }

    /**
     * Observes whether this recording is downloaded on disk continuously across all aliases.
     */
    fun observeIsRecordingDownloaded(trackId: String, sourceId: String? = null): Flow<Boolean> {
        val effectiveSourceId = sourceId?.trim() ?: if (trackId.startsWith("youtube:")) trackId.removePrefix("youtube:").trim() else ""
        return downloadDao.observeIsRecordingDownloaded(trackId, effectiveSourceId)
    }

    suspend fun pinDownload(downloadId: String): Boolean {
        val download = downloadDao.getById(downloadId) ?: return false
        if (download.bucket == "PINNED") return true
        if (download.status != OfflineDownloadStatus.COMPLETED) return false

        val newKey = storage.moveFileToBucket(download.relativeStorageKey, DownloadBucket.PINNED)
        if (newKey != null) {
            downloadDao.upsert(download.copy(
                relativeStorageKey = newKey,
                bucket = "PINNED",
                updatedAtEpochMs = System.currentTimeMillis()
            ))
            Log.d(TAG, "Pinned download: $downloadId")
            return true
        }
        return false
    }

    suspend fun unpinDownload(downloadId: String): Boolean {
        val download = downloadDao.getById(downloadId) ?: return false
        if (download.bucket == "SMART_OFFLINE") return true
        if (download.status != OfflineDownloadStatus.COMPLETED) return false

        val newKey = storage.moveFileToBucket(download.relativeStorageKey, DownloadBucket.SMART_OFFLINE)
        if (newKey != null) {
            downloadDao.upsert(download.copy(
                relativeStorageKey = newKey,
                bucket = "SMART_OFFLINE",
                updatedAtEpochMs = System.currentTimeMillis()
            ))
            Log.d(TAG, "Unpinned download: $downloadId")
            return true
        }
        return false
    }

    suspend fun getOfflinePlaybackUri(trackId: String): android.net.Uri? {
        val download = downloadDao.getCompletedForTrack(trackId) ?: return null
        if (!storage.isFileValid(download.relativeStorageKey)) {
            downloadDao.updateStatus(download.downloadId, OfflineDownloadStatus.EVICTED)
            return null
        }
        downloadDao.touchAccessTime(download.downloadId)
        return storage.getContentUri(download.relativeStorageKey)
    }

    suspend fun isTrackAvailableOffline(trackId: String): Boolean {
        val download = downloadDao.getCompletedForTrack(trackId) ?: return false
        return storage.isFileValid(download.relativeStorageKey)
    }

    suspend fun getCompletedDownloadForTrack(trackId: String): OfflineDownloadEntity? {
        return downloadDao.getCompletedForTrack(trackId)
    }

    suspend fun getOfflinePlaybackUriForSource(sourceId: String): android.net.Uri? {
        val download = downloadDao.getCompletedForSource(sourceId) ?: return null
        if (!storage.isFileValid(download.relativeStorageKey)) {
            downloadDao.updateStatus(download.downloadId, OfflineDownloadStatus.EVICTED)
            return null
        }
        downloadDao.touchAccessTime(download.downloadId)
        return storage.getContentUri(download.relativeStorageKey)
    }

    suspend fun clearAllDownloads() {
        val downloads = downloadDao.getCompletedDownloads()
        for (d in downloads) {
            storage.deleteFile(d.relativeStorageKey)
            downloadDao.delete(d.downloadId)
        }
        queueDao.cancelUncompleted()
    }

    fun getStorageStats(): StorageStats {
        return StorageStats(
            pinnedSizeBytes = storage.pinnedSizeBytes(),
            smartSizeBytes = storage.smartSizeBytes(),
            totalSizeBytes = storage.totalSizeBytes(),
            freeSpaceBytes = storage.availableFreeSpaceBytes()
        )
    }

    // ── Live Room Flows ─────────────────────────────────────────────────────

    fun observeCompletedDownloads(): Flow<List<OfflineDownloadEntity>> =
        downloadDao.observeCompletedDownloads()

    fun observeActiveDownloads(): Flow<List<OfflineDownloadEntity>> =
        downloadDao.observeActiveDownloads()

    fun observeNonTerminalDownloads(): Flow<List<OfflineDownloadEntity>> =
        downloadDao.observeNonTerminalDownloads()

    fun observeFailedDownloads(): Flow<List<OfflineDownloadEntity>> =
        downloadDao.observeFailedDownloads()

    fun observeCompletedCount(): Flow<Int> =
        downloadDao.observeCompletedCount()

    fun observeAllQueue(): Flow<List<DownloadQueueEntity>> =
        queueDao.observeAllQueue()

    fun observeQueueForPlaylist(playlistId: String): Flow<List<DownloadQueueEntity>> =
        queueDao.observeQueueForPlaylist(playlistId)

    fun observeActiveDownloading(): Flow<DownloadQueueEntity?> =
        queueDao.observeActiveDownloading()

    fun observeQueuedCount(): Flow<Int> =
        queueDao.observeQueuedCount()

    fun observeQueueFailedCount(): Flow<Int> =
        queueDao.observeFailedCount()

    fun observeQueueCompletedCount(): Flow<Int> =
        queueDao.observeCompletedCount()

    fun observeQueueTotalCount(): Flow<Int> =
        queueDao.observeTotalCount()

    // ── Process-Death & Startup Recovery ────────────────────────────────────

    /**
     * Startup recovery:
     * - Resets any RESOLVING/DOWNLOADING/VALIDATING entries older than 15 minutes back to QUEUED.
     * - Cleans orphan .part files older than 15 minutes safely, never deleting active/paused job files.
     * - Cancels and reconciles existing stale WorkManager jobs.
     * - Resumes serial queue from the first incomplete item.
     */
    suspend fun recoverOnStartup() {
        storage.ensureDirectoriesExist()

        val now = System.currentTimeMillis()
        val cutoffEpochMs = now - STALE_CUTOFF_MS

        // 1. Reset stale queue items older than 15 minutes back to QUEUED
        val queueReset = queueDao.resetStaleQueueItems(cutoffEpochMs, now)
        if (queueReset > 0) {
            Log.d(TAG, "Startup recovery: reset $queueReset stale queue items to QUEUED")
        }

        val offlineReset = downloadDao.resetStaleActiveDownloads(now, staleCutoffMs = STALE_CUTOFF_MS)
        if (offlineReset > 0) {
            Log.d(TAG, "Startup recovery: reset $offlineReset stale offline entries to PENDING")
        }

        val cleaned = downloadDao.cleanupTerminalEntries()
        if (cleaned > 0) {
            Log.d(TAG, "Startup cleanup: removed $cleaned terminal entries")
        }

        // 2. Safe .part cleanup: exclude files belonging to active or paused items
        val activeOrPaused = try {
            queueDao.observeActiveAndQueued().first()
        } catch (_: Exception) {
            emptyList()
        }

        val protectedPartNames = activeOrPaused.mapNotNull { item ->
            val source = database.trackDao().getSelectedSource(item.trackId)
            val sourceId = source?.sourceId ?: item.trackId
            val provider = source?.provider ?: "youtube"
            val relKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, provider, sourceId)
            relKey.replace("/", "_") + OfflineStorage.PART_EXTENSION
        }.toSet()

        storage.cleanupStalePartFiles(
            maxAgeMs = STALE_CUTOFF_MS,
            excludeFileNames = protectedPartNames
        )

        // 3. Reconcile / cancel stale WorkManager jobs to begin a clean continuation
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)

        // 4. Resume the serial queue from the first incomplete item
        val remaining = activeOrPaused
            .filter { it.status == DownloadQueueStatus.QUEUED }
            .map { it.trackId }

        if (remaining.isNotEmpty()) {
            Log.d(TAG, "Startup recovery: resuming serial queue with ${remaining.size} items")
            enqueueChain(remaining)
        }
    }
}
