package com.notify.download.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.notify.download.db.NotiFyDatabase
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import java.util.concurrent.TimeUnit

class ArtworkEnrichmentWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_PLAYLIST_ID = "KEY_PLAYLIST_ID"
        const val KEY_FORCE_RETRY = "KEY_FORCE_RETRY"

        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_FAILED = "failed"
        const val PROGRESS_PLAYLIST_ID = "playlistId"

        fun workName(playlistId: String): String = "artwork_enrichment_$playlistId"

        /**
         * Allows test suites to inject a mock/fake enricher.
         */
        @Volatile
        var enricherOverride: ArtworkEnricher? = null

        /**
         * Enqueues unique background artwork enrichment work for a playlist with specified policy.
         */
        fun enqueueWithPolicy(
            context: Context,
            playlistId: String,
            forceRetry: Boolean = false,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ArtworkEnrichmentWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_PLAYLIST_ID to playlistId,
                        KEY_FORCE_RETRY to forceRetry
                    )
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            try {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    workName(playlistId),
                    policy,
                    workRequest
                )
            } catch (t: Throwable) {
                android.util.Log.w("ArtworkEnrichmentWorker", "Failed to enqueue work with policy $policy for playlist $playlistId: ${t.message}")
            }
        }

        /**
         * Enqueues unique background artwork enrichment work for a playlist.
         * Default KEEP policy ensures running work is not disrupted.
         */
        fun enqueue(
            context: Context,
            playlistId: String,
            forceRetry: Boolean = false
        ) {
            enqueueWithPolicy(
                context = context,
                playlistId = playlistId,
                forceRetry = forceRetry,
                policy = if (forceRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            )
        }
    }

    override suspend fun doWork(): Result {
        val playlistId = inputData.getString(KEY_PLAYLIST_ID) ?: return Result.failure()
        val forceRetry = inputData.getBoolean(KEY_FORCE_RETRY, false)

        val database = NotiFyDatabase.getInstance(applicationContext)
        val repository = com.notify.download.db.PlaylistRepository(database)
        val enricher = enricherOverride ?: DefaultArtworkEnricher(
            database = database,
            searchProvider = InnerTubeYouTubeMusicSearchProvider()
        )

        val result = enricher.enrichPlaylist(
            playlistId = playlistId,
            forceRetry = forceRetry
        ) { completed, total, failed ->
            if (isStopped) return@enrichPlaylist
            setProgress(
                workDataOf(
                    PROGRESS_COMPLETED to completed,
                    PROGRESS_TOTAL to total,
                    PROGRESS_FAILED to failed,
                    PROGRESS_PLAYLIST_ID to playlistId
                )
            )
        }

        // Safe continuation: If more eligible tracks remain, schedule next batch using APPEND_OR_REPLACE
        // (NEVER REPLACE own running work from inside worker!)
        if (!result.rateLimited && !isStopped) {
            try {
                val remainingNeeding = repository.getTracksNeedingEnrichment(playlistId, forceRetry = false)
                if (remainingNeeding.isNotEmpty()) {
                    android.util.Log.i("ArtworkEnrichmentWorker", "Scheduling next batch for playlist $playlistId (${remainingNeeding.size} remaining) via APPEND_OR_REPLACE")
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                    val nextRequest = OneTimeWorkRequestBuilder<ArtworkEnrichmentWorker>()
                        .setConstraints(constraints)
                        .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId, KEY_FORCE_RETRY to false))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                        .build()
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        workName(playlistId),
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        nextRequest
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("ArtworkEnrichmentWorker", "Failed to schedule continuation batch: ${t.message}")
            }
        }

        return if (result.rateLimited) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    PROGRESS_COMPLETED to result.completed,
                    PROGRESS_TOTAL to result.total,
                    PROGRESS_FAILED to result.failed
                )
            )
        }
    }
}
