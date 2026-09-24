package com.notify.download.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class RecoveryDecision {
    object NoEligibleTracks : RecoveryDecision()
    data class WorkAlreadyActive(val state: WorkInfo.State) : RecoveryDecision()
    data class Enqueued(val policy: ExistingWorkPolicy, val eligibleCount: Int) : RecoveryDecision()
    data class Error(val cause: Throwable) : RecoveryDecision()
}

object EnrichmentRecoveryCoordinator {
    private const val TAG = "EnrichmentRecovery"
    const val STALE_LEASE_CUTOFF_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Inspects all playlists in Room on startup or after interruption:
     * 1. Resets stale IN_PROGRESS leases (>= 5m) to PENDING.
     * 2. Checks WorkInfo states for each playlist.
     * 3. Resumes work with REPLACE only when no unfinished work (RUNNING, ENQUEUED, BLOCKED) exists.
     */
    suspend fun recoverAllPlaylists(context: Context): Map<String, RecoveryDecision> = withContext(Dispatchers.IO) {
        val database = NotiFyDatabase.getInstance(context)
        val repository = PlaylistRepository(database)
        val results = mutableMapOf<String, RecoveryDecision>()

        try {
            val recoveredStaleCount = repository.recoverStaleInProgressTracks(STALE_LEASE_CUTOFF_MS)
            if (recoveredStaleCount > 0) {
                Log.i(TAG, "Reset $recoveredStaleCount stale IN_PROGRESS tracks back to PENDING")
            }

            val playlists = database.playlistDao().getAllPlaylists()
            for (playlist in playlists) {
                results[playlist.playlistId] = recoverPlaylist(context, playlist.playlistId, forceRetry = false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error during recoverAllPlaylists: ${t.message}", t)
        }
        results
    }

    /**
     * Reconciles enrichment for a single playlist upon opening or explicit retry.
     */
    suspend fun recoverPlaylist(
        context: Context,
        playlistId: String,
        forceRetry: Boolean = false
    ): RecoveryDecision = withContext(Dispatchers.IO) {
        val database = NotiFyDatabase.getInstance(context)
        val repository = PlaylistRepository(database)
        val workName = ArtworkEnrichmentWorker.workName(playlistId)

        try {
            // 1. Recover stale leases for tracks
            val recoveredStale = repository.recoverStaleInProgressTracks(STALE_LEASE_CUTOFF_MS)
            if (recoveredStale > 0) {
                Log.d(TAG, "Reset $recoveredStale stale tracks during playlist $playlistId recovery")
            }

            // 2. Check if tracks needing enrichment exist
            val eligibleTracks = repository.getTracksNeedingEnrichment(playlistId, forceRetry)
            if (eligibleTracks.isEmpty()) {
                Log.d(TAG, "Playlist $playlistId has 0 tracks needing enrichment. Skipping.")
                return@withContext RecoveryDecision.NoEligibleTracks
            }

            // 3. Inspect WorkManager WorkInfo state
            val workManager = try {
                WorkManager.getInstance(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "WorkManager not available: ${t.message}")
                return@withContext RecoveryDecision.Error(t)
            }

            val workInfos = try {
                workManager.getWorkInfosForUniqueWork(workName).get()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not fetch work infos for $workName: ${t.message}")
                emptyList()
            }

            // Log the actual WorkInfo state for each work instance
            for (info in workInfos) {
                Log.d(TAG, "Work '$workName' [${info.id}] actual state: ${info.state}, runAttemptCount: ${info.runAttemptCount}")
            }

            // Unfinished work states: RUNNING, ENQUEUED, BLOCKED
            val unfinishedWork = workInfos.firstOrNull {
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
            }

            if (unfinishedWork != null && !forceRetry) {
                Log.i(TAG, "Work '$workName' is currently unfinished in state ${unfinishedWork.state}. Will NOT replace.")
                return@withContext RecoveryDecision.WorkAlreadyActive(unfinishedWork.state)
            }

            // If no unfinished work exists (or forceRetry is true), enqueue resume work using REPLACE
            Log.i(TAG, "Enqueueing resume work for '$workName' with ExistingWorkPolicy.REPLACE for ${eligibleTracks.size} eligible tracks.")
            ArtworkEnrichmentWorker.enqueueWithPolicy(
                context = context,
                playlistId = playlistId,
                forceRetry = forceRetry,
                policy = ExistingWorkPolicy.REPLACE
            )

            RecoveryDecision.Enqueued(ExistingWorkPolicy.REPLACE, eligibleTracks.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Error recovering playlist $playlistId: ${t.message}", t)
            RecoveryDecision.Error(t)
        }
    }
}
