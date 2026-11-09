package com.notify.playback

import android.content.Context
import android.util.Log
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.LocalArtworkStore
import com.notify.download.db.NotiFyDatabase
import com.notify.download.engine.OfflineDownloadManager
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Manages the "Surprise" cached song insertion feature during offline playlist playback.
 *
 * Rules:
 * - Active ONLY when playing an offline/downloaded playlist AND (device is offline OR playlist source is entirely offline).
 * - Never during normal online playlists, radio, search, or Home playback.
 * - After every 3 or 4 real songs (random gap, recomputed after each surprise), inserts exactly ONE
 *   song from the SMART_OFFLINE pool as the NEXT item.
 * - Pool: status COMPLETED + bucket SMART_OFFLINE + file exists & valid + NOT in current playlist/queue
 *   + not among the last 5 surprises (if pool <= 5, allow repeats except back-to-back).
 * - Re-checks file at pick time using shared [OfflineDownloadManager.resolveOfflineUri].
 * - Refreshes LRU access timestamp when played to protect from eviction.
 * - All decision points and rejection reasons are logged via Log.w with tag "SurpriseDebug".
 */
class SurprisePlaybackManager(
    private val context: Context,
    private val database: NotiFyDatabase,
    private val downloadManager: OfflineDownloadManager,
    private val preferences: DownloadPreferences
) {
    companion object {
        const val TAG = "SurpriseDebug"
        private const val MAX_RECENT_SURPRISES = 5
        const val SURPRISE_QUEUE_ID_PREFIX = "surprise_"
    }

    /** Number of real playlist songs completed since the last surprise was played. */
    var songsPlayedSinceLastSurprise: Int = 0
        private set

    /** Dynamic random gap: 3 or 4, recomputed after each surprise. */
    var currentGap: Int = Random.nextInt(3, 5)
        private set

    /** Identifier of the immediately preceding surprise track to prevent back-to-back repeats. */
    var lastSurpriseTrackKey: String? = null
        private set

    /** FIFO queue of the last 5 surprise tracks played this session. */
    private val recentSurpriseKeys = ArrayDeque<String>()

    /** Set of active surprise QueueEntry IDs. */
    private val surpriseQueueIds = ConcurrentHashMap.newKeySet<String>()

    /** Whether the current active playback context is an authoritative playlist queue. */
    var isPlaylistPlaybackMode: Boolean = false
        private set

    /** Whether the current playlist consists entirely of offline-available files. */
    var isEntirelyOfflinePlaylist: Boolean = false

    /**
     * Resets state for a new playback session.
     */
    fun resetSession(isPlaylist: Boolean) {
        isPlaylistPlaybackMode = isPlaylist
        isEntirelyOfflinePlaylist = false
        songsPlayedSinceLastSurprise = 0
        currentGap = Random.nextInt(3, 5)
        lastSurpriseTrackKey = null
        synchronized(recentSurpriseKeys) {
            recentSurpriseKeys.clear()
        }
        surpriseQueueIds.clear()
        Log.w(TAG, "Decision Point [session reset]: isPlaylistPlaybackMode=$isPlaylist, initialRandomGap=$currentGap")
    }

    /**
     * Checks if a given QueueEntry is a surprise track.
     */
    fun isSurpriseEntry(entry: QueueEntry?): Boolean {
        if (entry == null) return false
        return surpriseQueueIds.contains(entry.queueId) || entry.queueId.startsWith(SURPRISE_QUEUE_ID_PREFIX)
    }

    /**
     * Called when a surprise track finishes playback.
     * Resets the songs-played counter and recomputes the random gap.
     */
    fun markSurprisePlayed(entry: QueueEntry) {
        val trackKey = entry.track.id.rawId
        lastSurpriseTrackKey = trackKey
        synchronized(recentSurpriseKeys) {
            recentSurpriseKeys.remove(trackKey)
            recentSurpriseKeys.addLast(trackKey)
            if (recentSurpriseKeys.size > MAX_RECENT_SURPRISES) {
                recentSurpriseKeys.removeFirst()
            }
        }
        songsPlayedSinceLastSurprise = 0
        currentGap = Random.nextInt(3, 5)
        Log.w(TAG, "Decision Point [surprise completed]: track='${entry.track.title}' ($trackKey). Counter reset to 0, next random gap=$currentGap")
    }

    /**
     * Called when a real playlist song completes playback or is transitioned.
     */
    fun onRealSongCompleted(entry: QueueEntry) {
        songsPlayedSinceLastSurprise++
        Log.w(TAG, "Decision Point [real song completed]: '${entry.track.title}'. songsPlayedCounter=$songsPlayedSinceLastSurprise, currentGap=$currentGap")
    }

    /**
     * Evaluates all gating conditions for inserting a surprise song.
     * Logs every single decision point and reason for early return.
     */
    fun shouldTriggerSurprise(
        isNetworkConnected: Boolean,
        originalPlaylistSize: Int,
        isRepeatOne: Boolean,
        currentEntry: QueueEntry?,
        nextEntry: QueueEntry?,
        hasUpcomingSurprise: Boolean
    ): Boolean {
        Log.w(TAG, "Evaluating surprise trigger conditions...")

        // 1. Feature toggle check
        val isEnabled = preferences.surpriseCachedPlaybackEnabled
        Log.w(TAG, "Decision Point [feature enabled?]: surpriseCachedPlaybackEnabled=$isEnabled")
        if (!isEnabled) {
            Log.w(TAG, "Early return: surprise skipped because surpriseCachedPlaybackEnabled is false")
            return false
        }

        // 2. Playback source type: must be playlist playback mode
        Log.w(TAG, "Decision Point [playback source type]: isPlaylistPlaybackMode=$isPlaylistPlaybackMode")
        if (!isPlaylistPlaybackMode) {
            Log.w(TAG, "Early return: surprise skipped because playback is not a playlist/downloads queue (e.g. search, radio, single stream)")
            return false
        }

        // 3. Offline / Airplane mode condition: device is offline OR the playlist source is entirely offline files
        Log.w(TAG, "Decision Point [offline/airplane state]: isNetworkConnected=$isNetworkConnected, isEntirelyOfflinePlaylist=$isEntirelyOfflinePlaylist")
        if (isNetworkConnected && !isEntirelyOfflinePlaylist) {
            Log.w(TAG, "Early return: surprise skipped because device is online and playlist is not entirely offline")
            return false
        }

        // 4. Playlist size check: minimum 4 songs
        Log.w(TAG, "Decision Point [playlist size]: size=$originalPlaylistSize")
        if (originalPlaylistSize < 4) {
            Log.w(TAG, "Early return: surprise skipped because playlist has fewer than 4 songs ($originalPlaylistSize)")
            return false
        }

        // 5. Single-track repeat check
        Log.w(TAG, "Decision Point [repeat-one active?]: $isRepeatOne")
        if (isRepeatOne) {
            Log.w(TAG, "Early return: surprise skipped because repeat-one mode is active")
            return false
        }

        // 6. No two surprises in a row
        val currentIsSurprise = isSurpriseEntry(currentEntry)
        val nextIsSurprise = isSurpriseEntry(nextEntry)
        Log.w(TAG, "Decision Point [consecutive surprise check]: currentIsSurprise=$currentIsSurprise, nextIsSurprise=$nextIsSurprise")
        if (currentIsSurprise) {
            Log.w(TAG, "Early return: surprise skipped because currently playing song is already a surprise track")
            return false
        }
        if (nextIsSurprise) {
            Log.w(TAG, "Early return: surprise skipped because next item is already a surprise track")
            return false
        }
        if (hasUpcomingSurprise) {
            Log.w(TAG, "Early return: surprise skipped because an upcoming surprise track is already queued in the active timeline")
            return false
        }

        // 7. Songs-played counter vs random gap (3 or 4)
        // Checked when a real song is active: songsPlayedSinceLastSurprise + 1 >= currentGap
        val counterSatisfied = (songsPlayedSinceLastSurprise + 1) >= currentGap
        Log.w(TAG, "Decision Point [counter and random gap]: songsPlayedCounter=$songsPlayedSinceLastSurprise, currentGap=$currentGap, counterSatisfied=$counterSatisfied")
        if (!counterSatisfied) {
            Log.w(TAG, "Early return: surprise skipped because counter (${songsPlayedSinceLastSurprise + 1}) has not reached gap ($currentGap)")
            return false
        }

        Log.w(TAG, "All surprise preconditions passed. Proceeding to pool evaluation.")
        return true
    }

    /**
     * Queries the SMART_OFFLINE pool and picks an eligible surprise track.
     * Re-validates the file at pick time with [OfflineDownloadManager.resolveOfflineUri] (touchAccess = true).
     *
     * Returns null silently if pool is empty or no valid candidate exists.
     */
    suspend fun pickSurpriseTrack(
        currentQueue: List<QueueEntry>,
        originalPlaylistEntries: List<QueueEntry>
    ): Pair<QueueEntry, OfflineDownloadEntity>? {
        Log.w(TAG, "Querying SMART_OFFLINE pool from Room database...")
        val pool = database.offlineDownloadDao().getSmartOfflineEntriesLru()
        Log.w(TAG, "Decision Point [pool size]: ${pool.size}")
        if (pool.isEmpty()) {
            Log.w(TAG, "Early return: pool empty because auto-save never produced COMPLETED SMART_OFFLINE rows")
            return null
        }

        // Build set of all trackIds and sourceIds in current queue and original playlist
        val existingKeys = mutableSetOf<String>()
        for (entry in currentQueue + originalPlaylistEntries) {
            existingKeys.add(entry.track.id.rawId.lowercase())
            val src = entry.track.source
            if (src is AudioSource.Remote) {
                existingKeys.add(src.sourceId.lowercase())
            }
        }

        val eligibleCandidates = mutableListOf<OfflineDownloadEntity>()
        for (candidate in pool) {
            val trackId = candidate.trackId
            val sourceId = candidate.providerSourceId

            // Status and bucket check
            if (candidate.status != "COMPLETED" || candidate.bucket != "SMART_OFFLINE") {
                Log.w(TAG, "Rejected candidate $trackId: bucket=${candidate.bucket}, status=${candidate.status}")
                continue
            }

            // Audio file validity on disk
            if (!downloadManager.isFileValid(candidate.relativeStorageKey)) {
                Log.w(TAG, "Rejected candidate $trackId: audio file missing or invalid on disk (${candidate.relativeStorageKey})")
                continue
            }

            // In queue or playlist check
            val inQueue = existingKeys.contains(trackId.lowercase()) ||
                    (sourceId.isNotBlank() && existingKeys.contains(sourceId.lowercase()))
            if (inQueue) {
                Log.w(TAG, "Rejected candidate $trackId: already in current playlist or queue")
                continue
            }

            // Back-to-back check with immediately preceding surprise
            if (trackId == lastSurpriseTrackKey || (sourceId.isNotBlank() && sourceId == lastSurpriseTrackKey)) {
                Log.w(TAG, "Rejected candidate $trackId: was immediately preceding surprise (back-to-back)")
                continue
            }

            eligibleCandidates.add(candidate)
        }

        Log.w(TAG, "Eligible candidates after initial filtering: ${eligibleCandidates.size}")
        if (eligibleCandidates.isEmpty()) {
            Log.w(TAG, "Early return: no eligible SMART_OFFLINE candidates found after queue/file filtering")
            return null
        }

        // Recent 5 surprises check
        val finalCandidates = if (eligibleCandidates.size > MAX_RECENT_SURPRISES) {
            val recentSet = synchronized(recentSurpriseKeys) { recentSurpriseKeys.toSet() }
            val nonRecent = eligibleCandidates.filter { candidate ->
                val isRecent = recentSet.contains(candidate.trackId) ||
                        (candidate.providerSourceId.isNotBlank() && recentSet.contains(candidate.providerSourceId))
                if (isRecent) {
                    Log.w(TAG, "Rejected candidate ${candidate.trackId}: played recently in session among last 5 surprises")
                }
                !isRecent
            }
            if (nonRecent.isNotEmpty()) nonRecent else eligibleCandidates
        } else {
            Log.w(TAG, "Pool size (${eligibleCandidates.size}) <= $MAX_RECENT_SURPRISES; allowing recent repeats (except back-to-back)")
            eligibleCandidates
        }

        val candidatesToTry = finalCandidates.shuffled()

        // Pick-time re-check with shared helper (resolveOfflineUri with touchAccess = true)
        for (picked in candidatesToTry) {
            Log.w(TAG, "Validating candidate at pick time: ${picked.trackId} (${picked.relativeStorageKey})")
            val verifiedUri = downloadManager.resolveOfflineUri(
                trackId = picked.trackId,
                providerSourceId = picked.providerSourceId,
                touchAccess = true // Refreshes LRU timestamp to protect from eviction!
            )

            if (verifiedUri == null) {
                Log.w(TAG, "Rejected candidate ${picked.trackId} at pick time: resolveOfflineUri returned null (file may have been evicted/deleted)")
                continue
            }

            // Synthesize domain Track
            val trackEntity = database.trackDao().getTrackById(picked.trackId)
            val title = trackEntity?.title ?: "Surprise Track"
            val artist = trackEntity?.artist ?: "Unknown Artist"
            val album = trackEntity?.album
            val durationMs = picked.durationMs ?: trackEntity?.durationMs ?: 0L

            // Artwork lookup using sourceId fallback mapping
            val artworkUri = LocalArtworkStore.getArtworkUri(picked.trackId, picked.providerSourceId, context)?.toString()
                ?: trackEntity?.artworkUrl
                ?: trackEntity?.artworkUri

            val trackIdObj = if (picked.trackId.startsWith("spotify:")) {
                TrackId.spotify(picked.trackId.removePrefix("spotify:"))
            } else if (picked.provider.equals("YOUTUBE", ignoreCase = true) || picked.providerSourceId.isNotBlank()) {
                TrackId.youtube(picked.providerSourceId.ifBlank { picked.trackId })
            } else {
                TrackId.local(picked.trackId)
            }

            val domainTrack = Track(
                id = trackIdObj,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkUri = artworkUri,
                source = AudioSource.Local(verifiedUri.toString())
            )

            val queueId = "${SURPRISE_QUEUE_ID_PREFIX}${picked.downloadId}_${System.currentTimeMillis()}"
            val surpriseEntry = QueueEntry(
                queueId = queueId,
                track = domainTrack,
                origin = QueueOrigin.PLAYLIST,
                playbackOrigin = PlaybackOrigin.UNKNOWN
            )

            surpriseQueueIds.add(queueId)
            Log.w(TAG, "Decision Point [final chosen song]: '${domainTrack.title}' by '${domainTrack.artist}' (trackId=${picked.trackId}, uri=$verifiedUri, queueId=$queueId)")
            return Pair(surpriseEntry, picked)
        }

        Log.w(TAG, "Early return: all candidates failed pick-time resolution")
        return null
    }
}
