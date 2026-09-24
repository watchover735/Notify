package com.notify.download.engine

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notify.core.downloads.db.DownloadQueueDao
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.downloads.storage.AudioValidationResult
import com.notify.core.downloads.storage.AudioValidator
import com.notify.core.downloads.storage.DefaultAudioFileValidator
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.core.downloads.storage.OfflineStorage
import com.notify.core.downloads.storage.SmartStorageManager
import com.notify.core.model.DownloadBucket
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.spotify.SpotifyTrackMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.notify.download.stream.ResolvedStreamProviderChain
import com.notify.download.stream.StreamUrlCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker executing exactly one track download per invocation within the serial chain.
 *
 * INVARIANT: MAX_CONCURRENT_AUDIO_DOWNLOADS = 1. Enforced via [DOWNLOAD_SEMAPHORE].
 * INVARIANT: Atomic claim of the track from Room (prevents dual-claiming).
 * INVARIANT: Download truth rule: DOWNLOADED checkmark is created ONLY after file existence,
 *            readability, non-zero size and MediaMetadataRetriever duration validation pass.
 * INVARIANT: Permanent failure writes FAILED to Room and returns Result.success() to allow
 *            the serial .then(...) continuation chain to proceed without interruption.
 * INVARIANT: Transient network failure returns Result.retry() with backoff up to 3 attempts.
 */
class TrackDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "TrackDownloadWorker"
        const val KEY_TRACK_ID = "track_id"
        const val UNIQUE_WORK_NAME = OfflineDownloadManager.UNIQUE_WORK_NAME

        /** Global concurrency limiter: maximum 1 active audio download at any time. */
        val DOWNLOAD_SEMAPHORE = Semaphore(1)

        /** Minimum valid file size in bytes (10 KB). */
        private const val MIN_VALID_FILE_SIZE = 10_240L

        /** Maximum retry attempts before permanent failure. */
        const val MAX_ATTEMPTS = 3

        /** Timeout for resolving YouTube source (30 seconds). */
        const val RESOLVE_TIMEOUT_MS = 30_000L

        /** Stall timeout: 90 seconds without progress. */
        const val PROGRESS_STALL_TIMEOUT_MS = 90_000L

        const val NOTIFICATION_ID = 4001

        @VisibleForTesting
        var testAudioValidator: AudioValidator? = null

        @VisibleForTesting
        var testDownloadExecutor: (suspend (canonicalUrl: String, partFile: File, onProgress: (Int) -> Unit) -> Unit)? = null

        private val FORMAT_FALLBACK_CHAIN = listOf(
            "ba[ext=m4a]/bestaudio[ext=m4a]",
            "bestaudio[ext=webm]/bestaudio[ext=opus]",
            "bestaudio[acodec=opus]/bestaudio[acodec=aac]",
            "bestaudio/best"
        )

        private val FORMAT_UNAVAILABLE_MARKERS = listOf(
            "Requested format is not available",
            "format is not available",
            "No video formats found",
            "No such format"
        )
    }

    private val validator: AudioValidator
        get() = testAudioValidator ?: DefaultAudioFileValidator()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun createForegroundInfo(title: String, artist: String, progressPercent: Int = 0): ForegroundInfo {
        val channelId = "downloads_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val channel = NotificationChannel(
                channelId,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Track download progress"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progressPercent, progressPercent == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = NotiFyDatabase.getInstance(applicationContext)
        val queueDao = db.downloadQueueDao()
        val downloadDao = db.offlineDownloadDao()
        val storage = OfflineStorage(applicationContext)
        val preferences = DownloadPreferences(applicationContext)
        val storageManager = SmartStorageManager(storage, preferences, downloadDao)

        storage.ensureDirectoriesExist()

        val workerInstanceId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val requestedTrackId = inputData.getString(KEY_TRACK_ID)

        // ── 1. Atomic claim of the track from Room ─────────────────────────
        val queueItem: DownloadQueueEntity = if (!requestedTrackId.isNullOrBlank()) {
            queueDao.claimSpecificTrack(requestedTrackId, workerInstanceId, now)
        } else {
            queueDao.claimNextQueuedTrack(workerInstanceId, now)
        } ?: run {
            Log.d(TAG, "No eligible track to claim for $requestedTrackId; finishing cleanly")
            return@withContext Result.success()
        }

        val trackId = queueItem.trackId
        Log.d(TAG, "Claimed track $trackId for download (attempt ${queueItem.attemptCount + 1})")

        // Check if queue is paused
        if (queueDao.getPausedCount() > 0 && queueItem.status == DownloadQueueStatus.PAUSED) {
            Log.d(TAG, "Queue is paused; halting work for $trackId")
            return@withContext Result.success()
        }

        // ── 2. Check if file is already downloaded and valid on disk ───────
        val existingCompleted = downloadDao.getCompletedForTrack(trackId)
        if (existingCompleted != null && storage.isFileValid(existingCompleted.relativeStorageKey)) {
            Log.d(TAG, "Track $trackId already has valid file on disk; marking COMPLETED")
            queueDao.markCompleted(trackId)
            downloadDao.touchAccessTime(existingCompleted.downloadId)
            return@withContext Result.success()
        }

        // ── 3. Check attempt count ─────────────────────────────────────────
        val effectiveAttemptCount = maxOf(queueItem.attemptCount, runAttemptCount)
        if (effectiveAttemptCount >= MAX_ATTEMPTS) {
            val errorMsg = "Exceeded max retry attempts ($MAX_ATTEMPTS)"
            Log.e(TAG, "Permanent failure for $trackId: $errorMsg")
            queueDao.markFailed(trackId, errorMsg)
            downloadDao.markFailed(trackId, errorMsg)
            return@withContext Result.success() // allow next track in chain to proceed
        }

        // ── 4. Check storage budget ────────────────────────────────────────
        if (!storageManager.canDownload(estimatedSizeBytes = 20_000_000L)) {
            val evicted = storageManager.evictIfOverBudget(requiredFreeBytes = 20_000_000L)
            if (evicted == 0 || !storageManager.canDownload(20_000_000L)) {
                val errorMsg = "Insufficient storage"
                Log.w(TAG, "Insufficient storage for $trackId")
                queueDao.markFailed(trackId, errorMsg)
                downloadDao.markFailed(trackId, errorMsg)
                return@withContext Result.success()
            }
        }

        // ── 5. Resolve YouTube match if missing (e.g. Spotify import) ──────
        var source = db.trackDao().getSelectedSource(trackId)
        if (source == null) {
            queueDao.updateStatus(trackId, DownloadQueueStatus.RESOLVING)
            val resolvedSource = resolveTrackSourceWithTimeout(trackId, db)
            if (resolvedSource == null) {
                val errorMsg = "Failed to resolve audio match for track"
                Log.e(TAG, "Permanent match failure for $trackId")
                queueDao.markFailed(trackId, errorMsg)
                return@withContext Result.success() // allow next track in chain to run
            }
            source = resolvedSource
        }

        // Optional lightweight N+1/N+2 InnerTube pre-resolution
        prefetchNextQueuedCandidates(queueDao, db)

        // Ensure offline_downloads entity exists
        val relativeKey = OfflineStorage.buildRelativeKey(
            bucket = DownloadBucket.PINNED,
            provider = source.provider,
            sourceId = source.sourceId
        )
        val downloadEntity = OfflineDownloadEntity(
            downloadId = UUID.randomUUID().toString(),
            trackId = trackId,
            provider = source.provider,
            providerSourceId = source.sourceId,
            canonicalUrl = source.canonicalUrl,
            relativeStorageKey = relativeKey,
            bucket = "PINNED",
            status = OfflineDownloadStatus.DOWNLOADING,
            qualityProfile = preferences.audioQualityProfile.name,
            durationMs = if (queueItem.durationMs > 0) queueItem.durationMs else null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastAccessedAtEpochMs = now
        )
        val insertRes = downloadDao.insertIgnore(downloadEntity)
        if (insertRes == -1L) {
            downloadDao.upsert(downloadEntity)
        }

        // ── 6. Execute download under single-permit concurrency Semaphore ───
        DOWNLOAD_SEMAPHORE.acquire()
        try {
            queueDao.updateStatus(trackId, DownloadQueueStatus.DOWNLOADING)
            downloadDao.updateStatus(downloadEntity.downloadId, OfflineDownloadStatus.DOWNLOADING)

            val track = db.trackDao().getTrackById(trackId)
            val trackTitle = track?.title ?: queueItem.trackTitle
            val trackArtist = track?.artist ?: queueItem.artist
            try {
                setForeground(createForegroundInfo(trackTitle, trackArtist, 0))
            } catch (e: Throwable) {
                Log.w(TAG, "setForeground failed or not supported in test/environment: ${e.message}")
            }

            val partFile = storage.getPartFile(relativeKey)

            val downloadSuccess = executeDownloadWithProgress(
                trackId = trackId,
                canonicalUrl = source.canonicalUrl,
                partFile = partFile,
                queueDao = queueDao,
                downloadDao = downloadDao,
                downloadId = downloadEntity.downloadId,
                preferences = preferences,
                trackTitle = trackTitle,
                trackArtist = trackArtist
            )

            when (downloadSuccess) {
                is DownloadOutcome.Success -> {
                    // ── 7. Validate according to Download Truth Rule ────────
                    queueDao.updateStatus(trackId, DownloadQueueStatus.VALIDATING)
                    downloadDao.updateStatus(downloadEntity.downloadId, OfflineDownloadStatus.VALIDATING)

                    val actualFile = resolveActualFile(partFile, downloadSuccess.outputFile)
                    if (actualFile == null || !actualFile.exists()) {
                        val msg = "Downloaded output file missing"
                        Log.e(TAG, msg)
                        handlePermanentFailure(trackId, downloadEntity.downloadId, msg, queueDao, downloadDao, partFile)
                        return@withContext Result.success()
                    }

                    if (actualFile.canonicalPath != partFile.canonicalPath) {
                        partFile.delete()
                        val renamed = actualFile.renameTo(partFile)
                        if (!renamed) {
                            actualFile.copyTo(partFile, overwrite = true)
                            actualFile.delete()
                        }
                    }

                    // Promote atomically to final storage
                    val promoted = storage.promotePartFile(relativeKey)
                    if (!promoted) {
                        val msg = "Failed to atomically promote .part file to final storage"
                        Log.e(TAG, msg)
                        handlePermanentFailure(trackId, downloadEntity.downloadId, msg, queueDao, downloadDao, partFile)
                        return@withContext Result.success()
                    }

                    val finalFile = storage.resolveFile(relativeKey)

                    // Strict Download Truth Validation: file exists, readable, size > 0, MediaMetadataRetriever duration > 0
                    val validation = validator.validateAudioFile(finalFile)
                    when (validation) {
                        is AudioValidationResult.Valid -> {
                            // DOWNLOADED is ONLY set here!
                            downloadDao.markCompleted(
                                downloadId = downloadEntity.downloadId,
                                sizeBytes = validation.fileSizeBytes,
                                mimeType = guessMimeType(finalFile),
                                bitrateKbps = null,
                                durationMs = validation.durationMs
                            )
                            queueDao.markCompleted(trackId)
                            Log.d(TAG, "Successfully validated and DOWNLOADED: $trackId (${validation.fileSizeBytes} bytes, ${validation.durationMs}ms)")
                            return@withContext Result.success()
                        }
                        is AudioValidationResult.Invalid -> {
                            Log.e(TAG, "Validation failed for $trackId: ${validation.reason}")
                            finalFile.delete()
                            handlePermanentFailure(trackId, downloadEntity.downloadId, validation.reason, queueDao, downloadDao, partFile)
                            return@withContext Result.success()
                        }
                    }
                }
                is DownloadOutcome.TransientNetworkError -> {
                    partFile.delete()
                    if (effectiveAttemptCount + 1 < MAX_ATTEMPTS) {
                        Log.w(TAG, "Transient network error for $trackId (${downloadSuccess.message}); scheduling retry with backoff")
                        queueDao.updateStatus(trackId, DownloadQueueStatus.QUEUED)
                        return@withContext Result.retry()
                    } else {
                        handlePermanentFailure(trackId, downloadEntity.downloadId, downloadSuccess.message, queueDao, downloadDao, partFile)
                        return@withContext Result.success()
                    }
                }
                is DownloadOutcome.PermanentFailure -> {
                    handlePermanentFailure(trackId, downloadEntity.downloadId, downloadSuccess.message, queueDao, downloadDao, partFile)
                    return@withContext Result.success()
                }
            }
        } finally {
            DOWNLOAD_SEMAPHORE.release()
        }
    }

    private suspend fun handlePermanentFailure(
        trackId: String,
        downloadId: String,
        errorMessage: String,
        queueDao: DownloadQueueDao,
        downloadDao: OfflineDownloadDao,
        partFile: File?
    ) {
        partFile?.delete()
        queueDao.markFailed(trackId, errorMessage)
        downloadDao.markFailed(downloadId, errorMessage)
        Log.e(TAG, "Permanent failure recorded for $trackId: $errorMessage")
    }

    /**
     * Resolves YouTube match for an unmatched track with a strict 30-second timeout.
     */
    private suspend fun resolveTrackSourceWithTimeout(
        trackId: String,
        db: NotiFyDatabase
    ): TrackSourceEntity? {
        val track = db.trackDao().getTrackById(trackId) ?: return null
        val query = "${track.artist} - ${track.title}"

        return try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                val innerTube = InnerTubeYouTubeMusicSearchProvider()
                var candidates = emptyList<YouTubeCandidate>()

                val itResult = innerTube.search(query, limit = 5)
                if (itResult.isSuccess && !itResult.getOrNull().isNullOrEmpty()) {
                    candidates = itResult.getOrThrow()
                }

                val dummyMeta = SpotifyTrackMetadata(
                    id = track.id,
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    releaseYear = null,
                    durationMs = track.durationMs,
                    artworkUrl = track.artworkUrl ?: track.artworkUri
                )

                var best = if (candidates.isNotEmpty()) TrackMatchEngine.findBestMatch(dummyMeta, candidates) else null
                if (best == null || best.matchScore < 0.5f) {
                    val fallback = YtDlpYouTubeSearchProvider(applicationContext)
                    val fbResult = fallback.search(query, limit = 3)
                    if (fbResult.isSuccess && !fbResult.getOrNull().isNullOrEmpty()) {
                        val fbBest = TrackMatchEngine.findBestMatch(dummyMeta, fbResult.getOrThrow())
                        if (fbBest != null && (best == null || fbBest.matchScore > best.matchScore)) {
                            best = fbBest
                        }
                    }
                }

                if (best != null) {
                    val source = TrackSourceEntity(
                        sourceKey = "${track.id}:youtube",
                        trackId = track.id,
                        provider = best.candidate.provider,
                        sourceId = best.candidate.videoId,
                        canonicalUrl = best.canonicalDownloadUrl,
                        confidence = best.matchScore.toFloat(),
                        durationDeltaMs = best.durationDeltaMs,
                        artworkUrl = best.candidate.artworkUrl,
                        selected = true
                    )
                    db.trackDao().insertSource(source)
                    db.trackDao().updateResolutionState(track.id, ResolutionState.MATCHED)
                    if (!best.candidate.artworkUrl.isNullOrBlank()) {
                        db.trackDao().updateArtwork(track.id, artworkUri = best.candidate.artworkUrl, artworkUrl = best.candidate.artworkUrl)
                    }
                    source
                } else {
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Resolve timed out after 30s for $trackId")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Resolve failed for $trackId: ${e.message}")
            null
        }
    }

    /**
     * Optional lightweight InnerTube pre-resolution for N+1 and N+2 tracks.
     * Only does HTTP metadata lookup without starting audio download.
     */
    private suspend fun prefetchNextQueuedCandidates(
        queueDao: DownloadQueueDao,
        db: NotiFyDatabase
    ) {
        try {
            val queued = queueDao.getNextQueued() ?: return
            if (db.trackDao().getSelectedSource(queued.trackId) == null) {
                val track = db.trackDao().getTrackById(queued.trackId) ?: return
                val query = "${track.artist} - ${track.title}"
                val itResult = InnerTubeYouTubeMusicSearchProvider().search(query, limit = 3)
                if (itResult.isSuccess && !itResult.getOrNull().isNullOrEmpty()) {
                    val candidates = itResult.getOrThrow()
                    val dummyMeta = SpotifyTrackMetadata(
                        id = track.id,
                        title = track.title,
                        artists = listOf(track.artist),
                        album = track.album,
                        releaseYear = null,
                        durationMs = track.durationMs,
                        artworkUrl = track.artworkUrl ?: track.artworkUri
                    )
                    val best = TrackMatchEngine.findBestMatch(dummyMeta, candidates)
                    if (best != null && best.matchScore >= 0.5f) {
                        val source = TrackSourceEntity(
                            sourceKey = "${track.id}:youtube",
                            trackId = track.id,
                            provider = best.candidate.provider,
                            sourceId = best.candidate.videoId,
                            canonicalUrl = best.canonicalDownloadUrl,
                            confidence = best.matchScore.toFloat(),
                            durationDeltaMs = best.durationDeltaMs,
                            artworkUrl = best.candidate.artworkUrl,
                            selected = true
                        )
                        db.trackDao().insertSource(source)
                        db.trackDao().updateResolutionState(track.id, ResolutionState.MATCHED)
                        Log.d(TAG, "Prefetched match for next track: ${track.id}")
                    }
                }
            }
        } catch (_: Exception) {
            // Non-blocking optional prefetch
        }
    }

    private suspend fun executeDownloadWithProgress(
        trackId: String,
        canonicalUrl: String,
        partFile: File,
        queueDao: DownloadQueueDao,
        downloadDao: OfflineDownloadDao,
        downloadId: String,
        preferences: DownloadPreferences,
        trackTitle: String,
        trackArtist: String
    ): DownloadOutcome {
        // If testing hook is supplied, run test executor
        testDownloadExecutor?.let { executor ->
            return try {
                executor(canonicalUrl, partFile) { percent ->
                    kotlinx.coroutines.runBlocking {
                        val now = System.currentTimeMillis()
                        queueDao.updateProgress(trackId, DownloadQueueStatus.DOWNLOADING, percent, now)
                        downloadDao.updateProgress(downloadId, OfflineDownloadStatus.DOWNLOADING, percent, now)
                    }
                }
                DownloadOutcome.Success(partFile)
            } catch (e: Exception) {
                DownloadOutcome.PermanentFailure(e.message ?: "Test download failed")
            }
        }

        // 1. Fast direct streaming download using OkHttp with HTTP Range resume
        val directResult = tryDirectStreamingDownload(
            trackId = trackId,
            canonicalUrl = canonicalUrl,
            partFile = partFile,
            queueDao = queueDao,
            downloadDao = downloadDao,
            downloadId = downloadId,
            trackTitle = trackTitle,
            trackArtist = trackArtist
        )
        if (directResult is DownloadOutcome.Success) {
            return directResult
        }
        Log.i(TAG, "Direct streaming unviable or fallback needed for $trackId; checking yt-dlp format chain")

        val qualityFormat = preferences.audioQualityProfile.toYtDlpOption()
        val formatsToTry = buildList {
            add(qualityFormat)
            FORMAT_FALLBACK_CHAIN.forEach { if (!contains(it)) add(it) }
        }

        var lastError: String? = null

        for (format in formatsToTry) {
            val outcome = tryDownloadFormat(
                trackId = trackId,
                canonicalUrl = canonicalUrl,
                format = format,
                partFile = partFile,
                queueDao = queueDao,
                downloadDao = downloadDao,
                downloadId = downloadId
            )
            when (outcome) {
                is DownloadOutcome.Success -> return outcome
                is DownloadOutcome.TransientNetworkError -> return outcome
                is DownloadOutcome.PermanentFailure -> {
                    lastError = outcome.message
                    if (isFormatUnavailable(outcome.message)) {
                        partFile.delete()
                        continue // try next format
                    } else {
                        return outcome
                    }
                }
            }
        }

        return DownloadOutcome.PermanentFailure("All format fallbacks exhausted: $lastError")
    }

    private suspend fun tryDirectStreamingDownload(
        trackId: String,
        canonicalUrl: String,
        partFile: File,
        queueDao: DownloadQueueDao,
        downloadDao: OfflineDownloadDao,
        downloadId: String,
        trackTitle: String,
        trackArtist: String
    ): DownloadOutcome? {
        // ── RESOLVE_START ────────────────────────────────────────────────────
        val resolveStartMs = System.currentTimeMillis()
        Log.i(TAG, "RESOLVE_START trackId=$trackId canonicalUrl=$canonicalUrl")

        val chain = ResolvedStreamProviderChain(applicationContext)
        val resolveRes = chain.resolveStream(canonicalUrl)
        val resolveElapsedMs = System.currentTimeMillis() - resolveStartMs

        if (resolveRes.isFailure) {
            Log.w(TAG, "RESOLVE_COMPLETE trackId=$trackId outcome=FAILED elapsedMs=$resolveElapsedMs error=${resolveRes.exceptionOrNull()?.message}")
            return null
        }
        val resolvedStream = resolveRes.getOrNull() ?: return null
        Log.i(TAG, "RESOLVE_COMPLETE trackId=$trackId outcome=SUCCESS elapsedMs=$resolveElapsedMs format=${resolvedStream.formatId} videoId=${resolvedStream.videoId}")
        val videoId = resolvedStream.videoId ?: canonicalUrl.substringAfter("v=", "").substringBefore("&").ifEmpty { null }

        var attempts = 0
        var currentStream = resolvedStream

        while (attempts < 2) {
            attempts++
            val existingBytes = if (partFile.exists()) partFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(currentStream.streamUrl)
            currentStream.requiredHttpHeaders.forEach { (k, v) ->
                requestBuilder.header(k, v)
            }
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            try {
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 403) {
                        Log.w(TAG, "HTTP 403 during direct streaming for videoId=$videoId (attempt $attempts)")
                        if (attempts == 1 && videoId != null && StreamUrlCache.canRetry403(videoId)) {
                            val retryRes = chain.resolveStream(canonicalUrl)
                            if (retryRes.isSuccess) {
                                currentStream = retryRes.getOrThrow()
                                return@use
                            }
                        }
                        return null
                    }

                    if (code == 416) {
                        Log.i(TAG, "HTTP 416 Range Not Satisfiable for $trackId at $existingBytes bytes")
                        if (existingBytes >= MIN_VALID_FILE_SIZE && validator.validateAudioFile(partFile) is AudioValidationResult.Valid) {
                            return DownloadOutcome.Success(partFile)
                        } else {
                            partFile.delete()
                            return@use
                        }
                    }

                    if (code != 200 && code != 206) {
                        Log.w(TAG, "Direct streaming returned HTTP $code for $trackId")
                        return null
                    }

                    val append = (code == 206)
                    if (!append && partFile.exists()) {
                        partFile.delete()
                    }

                    val body = response.body ?: return null
                    val contentLength = body.contentLength()
                    val totalExpectedBytes = if (append && contentLength > 0) existingBytes + contentLength else contentLength

                    var writtenBytes = if (append) existingBytes else 0L
                    var lastProgressUpdate = System.currentTimeMillis()
                    val transferStartMs = System.currentTimeMillis()
                    var lastLoggedProgressPct = -1

                    // ── TRANSFER_START ───────────────────────────────────────
                    Log.i(TAG, "TRANSFER_START trackId=$trackId resumeAtBytes=$existingBytes totalExpectedBytes=$totalExpectedBytes")

                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(partFile, append).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    writtenBytes += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressUpdate > 500L && totalExpectedBytes > 0) {
                                        lastProgressUpdate = now
                                        val percent = ((writtenBytes * 100) / totalExpectedBytes).toInt().coerceIn(0, 99)
                                        queueDao.updateProgress(trackId, DownloadQueueStatus.DOWNLOADING, percent, now)
                                        downloadDao.updateProgress(downloadId, OfflineDownloadStatus.DOWNLOADING, percent, now)
                                        try {
                                            setForeground(createForegroundInfo(trackTitle, trackArtist, percent))
                                        } catch (_: Throwable) {}

                                        // ── TRANSFER_PROGRESS every 10% ──────
                                        val logPct = (percent / 10) * 10
                                        if (logPct > lastLoggedProgressPct) {
                                            lastLoggedProgressPct = logPct
                                            val elapsedSec = ((now - transferStartMs) / 1000.0).coerceAtLeast(0.001)
                                            val rateKbps = ((writtenBytes / 1024.0) / elapsedSec).toLong()
                                            Log.d(TAG, "TRANSFER_PROGRESS trackId=$trackId percent=$percent writtenBytes=$writtenBytes rateKbps=$rateKbps")
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    } catch (e: Exception) {
                        val transferElapsedMs = System.currentTimeMillis() - transferStartMs
                        // ── TRANSFER_FAILED ──────────────────────────────────
                        Log.e(TAG, "TRANSFER_FAILED trackId=$trackId elapsedMs=$transferElapsedMs writtenBytes=$writtenBytes exception=${e::class.java.simpleName} message=${e.message}")
                        throw e
                    }

                    val transferElapsedMs = System.currentTimeMillis() - transferStartMs
                    if (partFile.length() >= MIN_VALID_FILE_SIZE) {
                        // ── TRANSFER_COMPLETE ────────────────────────────────
                        val elapsedSec = (transferElapsedMs / 1000.0).coerceAtLeast(0.001)
                        val rateKbps = ((partFile.length() / 1024.0) / elapsedSec).toLong()
                        Log.i(TAG, "TRANSFER_COMPLETE trackId=$trackId bytes=${partFile.length()} elapsedMs=$transferElapsedMs avgRateKbps=$rateKbps")
                        return DownloadOutcome.Success(partFile)
                    } else {
                        Log.w(TAG, "TRANSFER_FAILED trackId=$trackId reason=undersized bytes=${partFile.length()} elapsedMs=$transferElapsedMs")
                        return null
                    }
                }
            } catch (e: Exception) {
                val resolveElapsedMs = System.currentTimeMillis() - resolveStartMs
                Log.w(TAG, "TRANSFER_FAILED trackId=$trackId elapsedMs=$resolveElapsedMs exception=${e::class.java.simpleName} message=${e.message}")
                return null
            }
        }
        return null
    }

    private suspend fun tryDownloadFormat(
        trackId: String,
        canonicalUrl: String,
        format: String,
        partFile: File,
        queueDao: DownloadQueueDao,
        downloadDao: OfflineDownloadDao,
        downloadId: String
    ): DownloadOutcome = coroutineScope {
        val ytdlpStartMs = System.currentTimeMillis()
        // ── YTDLP_FORMAT_START ───────────────────────────────────────────────
        Log.i(TAG, "YTDLP_FORMAT_START trackId=$trackId format=$format")

        var lastProgressEpochMs = System.currentTimeMillis()
        val isStalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val taskId = "dl_${trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_${UUID.randomUUID()}"

        val watchdogJob = launch(Dispatchers.Default) {
            while (isActive) {
                delay(5_000L)
                val elapsed = System.currentTimeMillis() - lastProgressEpochMs
                if (elapsed > PROGRESS_STALL_TIMEOUT_MS) {
                    Log.w(TAG, "Download stalled for $trackId ($elapsed ms without progress). Destroying process $taskId")
                    isStalled.set(true)
                    try {
                        YoutubeDL.getInstance().destroyProcessById(taskId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to destroy stalled process $taskId: ${e.message}")
                    }
                    break
                }
            }
        }

        try {
            val request = YoutubeDLRequest(canonicalUrl).apply {
                addOption("-f", format)
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
                addOption("-o", partFile.absolutePath)
                addOption("--no-embed-metadata")
                addOption("--no-embed-thumbnail")
            }

            val response = YoutubeDL.getInstance().execute(request, taskId) { progress, _, _ ->
                val now = System.currentTimeMillis()
                lastProgressEpochMs = now
                val percent = progress.toInt().coerceIn(0, 99)
                kotlinx.coroutines.runBlocking {
                    queueDao.updateProgress(trackId, DownloadQueueStatus.DOWNLOADING, percent, now)
                    downloadDao.updateProgress(downloadId, OfflineDownloadStatus.DOWNLOADING, percent, now)
                }
            }

            watchdogJob.cancel()
            if (isStalled.get()) {
                val stalledElapsedMs = System.currentTimeMillis() - ytdlpStartMs
                Log.w(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format reason=stalled elapsedMs=$stalledElapsedMs")
                return@coroutineScope DownloadOutcome.TransientNetworkError("Download stalled (no progress for 90s)")
            }

            // ── YTDLP_FORMAT_COMPLETE ────────────────────────────────────────
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            Log.i(TAG, "YTDLP_FORMAT_COMPLETE trackId=$trackId format=$format ytdlpElapsedMs=${response.elapsedTime} wallElapsedMs=$ytdlpElapsedMs")
            DownloadOutcome.Success(partFile)
        } catch (e: YoutubeDLException) {
            watchdogJob.cancel()
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            if (isStalled.get()) {
                Log.w(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format reason=stalled elapsedMs=$ytdlpElapsedMs")
                return@coroutineScope DownloadOutcome.TransientNetworkError("Download stalled (no progress for 90s)")
            }
            val msg = e.message ?: "yt-dlp error"
            Log.e(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format elapsedMs=$ytdlpElapsedMs exception=YoutubeDLException message=$msg")
            classifyError(msg)
        } catch (e: SocketTimeoutException) {
            watchdogJob.cancel()
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            Log.e(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format elapsedMs=$ytdlpElapsedMs exception=SocketTimeoutException message=${e.message}")
            DownloadOutcome.TransientNetworkError("Network timeout: ${e.message}")
        } catch (e: UnknownHostException) {
            watchdogJob.cancel()
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            Log.e(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format elapsedMs=$ytdlpElapsedMs exception=UnknownHostException message=${e.message}")
            DownloadOutcome.TransientNetworkError("Network unavailable: ${e.message}")
        } catch (e: IOException) {
            watchdogJob.cancel()
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            Log.e(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format elapsedMs=$ytdlpElapsedMs exception=IOException message=${e.message}")
            DownloadOutcome.TransientNetworkError("Network I/O error: ${e.message}")
        } catch (e: Exception) {
            watchdogJob.cancel()
            val ytdlpElapsedMs = System.currentTimeMillis() - ytdlpStartMs
            if (isStalled.get()) {
                Log.w(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format reason=stalled elapsedMs=$ytdlpElapsedMs")
                return@coroutineScope DownloadOutcome.TransientNetworkError("Download stalled (no progress for 90s)")
            }
            val msg = e.message ?: "Unknown error"
            Log.e(TAG, "YTDLP_FORMAT_FAILED trackId=$trackId format=$format elapsedMs=$ytdlpElapsedMs exception=${e::class.java.simpleName} message=$msg")
            classifyError(msg)
        }
    }

    private fun classifyError(msg: String): DownloadOutcome {
        return when {
            isTransientNetworkError(msg) -> DownloadOutcome.TransientNetworkError(msg)
            else -> DownloadOutcome.PermanentFailure(msg)
        }
    }

    private fun isTransientNetworkError(msg: String): Boolean {
        val markers = listOf(
            "timeout", "timed out", "connection reset", "software caused connection abort",
            "no address associated with hostname", "failed to connect", "network is unreachable"
        )
        return markers.any { msg.contains(it, ignoreCase = true) }
    }

    private fun isFormatUnavailable(msg: String): Boolean {
        return FORMAT_UNAVAILABLE_MARKERS.any { msg.contains(it, ignoreCase = true) }
    }

    private fun resolveActualFile(partFile: File, hintFile: File): File? {
        if (hintFile.exists() && hintFile.length() >= MIN_VALID_FILE_SIZE) return hintFile
        if (partFile.exists() && partFile.length() >= MIN_VALID_FILE_SIZE) return partFile

        val parent = partFile.parentFile ?: return null
        val baseName = partFile.name
        val candidates = parent.listFiles()?.filter { f ->
            f.isFile && f.length() >= MIN_VALID_FILE_SIZE && (
                f.name.startsWith(partFile.nameWithoutExtension) ||
                f.name == baseName ||
                f.name.startsWith(baseName)
            )
        }
        return candidates?.maxByOrNull { it.length() }
    }

    private fun guessMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "webm" -> "audio/webm"
            "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    sealed class DownloadOutcome {
        data class Success(val outputFile: File) : DownloadOutcome()
        data class TransientNetworkError(val message: String) : DownloadOutcome()
        data class PermanentFailure(val message: String) : DownloadOutcome()
    }
}
