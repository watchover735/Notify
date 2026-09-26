package com.notify.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.notify.core.model.AudioSource
import com.notify.core.model.CanonicalMediaKey
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.Media3StreamCache
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.matcher.InnerTubeWatchNextProvider
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.StreamUrlCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RadioWindowManager:
 * Manages an invariant rolling recommendation window:
 *   Current track + up to 3 future tracks (total window = 4 tracks).
 *
 * Invariants & Guarantees:
 * 1. Single source of truth for session-scoped deduplication sets:
 *    playedKeys, queuedKeys, inFlightKeys.
 * 2. Session-scoped: reset(newSessionId) clears sets ONLY when a new session begins.
 *    RADIO_AUTOPLAY transitions preserve the existing session.
 * 3. Atomic Mutex Locking: Candidate filtering, in-flight reservation, and transition
 *    from inFlightKeys to queuedKeys occur under windowMutex with ZERO unlocked gap.
 * 4. Current track canonical key is added to playedKeys IMMEDIATELY when confirmed playback starts.
 * 5. Double Duplicate Validation: Checked before timeline insertion and verified again
 *    before playback.
 * 6. Thread Safety: All Player / MediaController timeline mutations run strictly on
 *    Dispatchers.Main.immediate / player looper. Network search & resolution on Dispatchers.IO.
 * 7. Explicit session-bound NextResolutionState tracks lookahead progress.
 */
class RadioWindowManager(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val watchNextProvider: InnerTubeWatchNextProvider,
    private val fallbackSearchProvider: OnlineCatalogSearchProvider,
    private val streamResolver: AudioStreamResolver,
    private val dispatchOnPlayerLooper: ((Player) -> Unit) -> Unit
) {
    companion object {
        private const val TAG = "RadioWindowManager"
        const val MAX_FUTURE_TRACKS = 3
        const val CANDIDATE_BATCH_SIZE = 10
        const val FALLBACK_SEARCH_BATCH_SIZE = 15
    }

    private val windowMutex = Mutex()

    private var activeSessionId: Long = -1L
    private var currentTrackKey: String? = null

    // Explicit session-bound resolution state
    @Volatile
    private var resolutionState: NextResolutionState = NextResolutionState.Idle

    var onCandidatesUpdated: ((List<QueueEntry>) -> Unit)? = null
    var onItemAppendedToTimeline: ((QueueEntry, MediaItem) -> Unit)? = null

    // Session-scoped deduplication sets (owned exclusively by RadioWindowManager)
    private val playedKeys = LinkedHashSet<String>()
    private val queuedKeys = LinkedHashSet<String>()
    private val inFlightKeys = HashSet<String>()

    // Future candidate window (logical entries, max size 3)
    private val futureWindow = ArrayDeque<QueueEntry>()

    // Active replenishment coroutine job
    private var replenishJob: Job? = null
    private var preloadJob: Job? = null

    /**
     * Resets all internal state, sets, and queued candidates for a genuinely new playback session.
     */
    fun reset(newSessionId: Long) {
        resetBlocking(newSessionId)
    }

    /**
     * Synchronous reset for testing or immediate service resets.
     */
    fun resetBlocking(newSessionId: Long) {
        replenishJob?.cancel()
        replenishJob = null
        preloadJob?.cancel()
        preloadJob = null
        activeSessionId = newSessionId
        currentTrackKey = null
        resolutionState = NextResolutionState.Idle
        playedKeys.clear()
        queuedKeys.clear()
        inFlightKeys.clear()
        futureWindow.clear()
        Log.d(TAG, "RADIO_WINDOW resetBlocking for session=$newSessionId")
    }

    /**
     * Signals that a track has begun playback in the given session.
     * Updates currentTrackKey, adds to playedKeys immediately, and initiates window replenishment.
     */
    fun onTrackStarted(entry: QueueEntry, sessionId: Long, player: Player) {
        if (sessionId != activeSessionId) {
            resetBlocking(sessionId)
        }

        val key = CanonicalMediaKey.fromTrack(entry.track)
        scope.launch {
            windowMutex.withLock {
                currentTrackKey = key
                // Contract: Add current track to playedKeys immediately when confirmed playback starts
                playedKeys.add(key)
                queuedKeys.remove(key)
                inFlightKeys.remove(key)
            }
            logWindowState()
            triggerReplenish(entry.track, sessionId, player)
        }
    }

    /**
     * Signals an automatic or user-initiated transition from [completedEntry] to [nextEntry].
     * Advances played history, pops the completed item from the future window, and refills tail.
     */
    fun onTrackTransition(
        completedEntry: QueueEntry?,
        nextEntry: QueueEntry,
        sessionId: Long,
        player: Player
    ) {
        if (sessionId != activeSessionId) {
            return
        }

        val nextKey = CanonicalMediaKey.fromTrack(nextEntry.track)

        scope.launch {
            var isDuplicate = false
            windowMutex.withLock {
                if (nextKey == currentTrackKey) {
                    Log.w(TAG, "DUPLICATE_TRANSITION_IGNORED key=$nextKey")
                    isDuplicate = true
                    return@withLock
                }

                if (completedEntry != null) {
                    val completedKey = CanonicalMediaKey.fromTrack(completedEntry.track)
                    playedKeys.add(completedKey)
                    queuedKeys.remove(completedKey)
                }

                currentTrackKey = nextKey
                playedKeys.add(nextKey)
                queuedKeys.remove(nextKey)
                inFlightKeys.remove(nextKey)

                // If nextEntry was in futureWindow, pop it
                if (futureWindow.isNotEmpty()) {
                    val headKey = CanonicalMediaKey.fromTrack(futureWindow.first().track)
                    if (headKey == nextKey) {
                        futureWindow.removeFirst()
                    }
                }
            }

            if (!isDuplicate) {
                logWindowState()
                triggerReplenish(nextEntry.track, sessionId, player)
            }
        }
    }

    /**
     * Triggers asynchronous replenishment of the 3-ahead window if slots are available
     * or if the immediate next item has not been resolved.
     */
    fun triggerReplenish(seedTrack: Track, sessionId: Long, player: Player) {
        if (sessionId != activeSessionId) return

        if (replenishJob?.isActive == true) {
            Log.d(TAG, "Replenish already running for session=$sessionId. Skipping duplicate call.")
            return
        }

        replenishJob = scope.launch(ioDispatcher) {
            val seedKey = CanonicalMediaKey.fromTrack(seedTrack)
            try {
                performReplenish(seedTrack, seedKey, sessionId, player)
            } finally {
                windowMutex.withLock {
                    if (sessionId == activeSessionId) {
                        inFlightKeys.clear()
                        replenishJob = null
                    }
                }
            }
        }
    }

    private suspend fun performReplenish(
        seedTrack: Track,
        seedKey: String,
        sessionId: Long,
        player: Player
    ) {
        if (sessionId != activeSessionId || !scope.isActive) return

        val seedVideoId = extractVideoId(seedTrack)
        val neededCandidates = windowMutex.withLock {
            MAX_FUTURE_TRACKS - futureWindow.size
        }

        if (neededCandidates > 0 && !seedVideoId.isNullOrBlank()) {
            val candidates = fetchCandidatesWithFallback(seedTrack, seedVideoId, sessionId)
            val filtered = filterAndSelectCandidates(candidates, neededCandidates)

            if (filtered.isEmpty()) {
                Log.w(TAG, "RADIO_CANDIDATES_EXHAUSTED no unique recommendations found for seed=$seedKey")
                windowMutex.withLock {
                    if (futureWindow.isEmpty()) {
                        resolutionState = NextResolutionState.Failed(sessionId, seedKey, "No unique recommendations found")
                    }
                }
            } else {
                val updatedWindow = windowMutex.withLock {
                    if (sessionId == activeSessionId) {
                        // Pre-append deduplication: Ensure no candidate enters futureWindow
                        // if already played, queued, in-flight, or already present in futureWindow
                        val trulyNew = filtered.filter { entry ->
                            val k = CanonicalMediaKey.fromTrack(entry.track)
                            !playedKeys.contains(k) &&
                            !queuedKeys.contains(k) &&
                            !inFlightKeys.contains(k) &&
                            futureWindow.none { CanonicalMediaKey.fromTrack(it.track) == k }
                        }
                        futureWindow.addAll(trulyNew)
                        futureWindow.toList()
                    } else emptyList()
                }
                if (updatedWindow.isNotEmpty()) {
                    onCandidatesUpdated?.invoke(updatedWindow)
                }
                logWindowState()
            }
        }

        // Resolve and append the immediate next item to Media3 timeline if needed
        resolveAndAppendImmediateNext(sessionId, player)
    }

    private suspend fun fetchCandidatesWithFallback(
        seedTrack: Track,
        seedVideoId: String,
        sessionId: Long
    ): List<YouTubeCandidate> {
        val candidates = mutableListOf<YouTubeCandidate>()

        // 1. Primary: InnerTube WatchNext
        Log.d(TAG, "WATCH_NEXT request count=1 seed=$seedVideoId")
        val startTime = System.currentTimeMillis()
        var watchNextResult = watchNextProvider.getWatchNext(seedVideoId, limit = CANDIDATE_BATCH_SIZE)

        // Bounded retry: retry WatchNext only once on failure or empty
        if (watchNextResult.isFailure || watchNextResult.getOrNull().isNullOrEmpty()) {
            Log.w(TAG, "WatchNext primary returned 0 candidates. Retrying once...")
            watchNextResult = watchNextProvider.getWatchNext(seedVideoId, limit = CANDIDATE_BATCH_SIZE)
        }

        val watchNextList = watchNextResult.getOrNull().orEmpty()
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "WATCH_NEXT count=1 elapsed=${elapsed}ms result=${watchNextList.size} candidates")
        candidates.addAll(watchNextList)

        // 2. Fallback: YouTube Music search by artist if candidates < 3
        if (candidates.size < 3 && sessionId == activeSessionId) {
            val fallbackQuery = if (seedTrack.artist.isNotBlank()) "${seedTrack.artist} songs" else seedTrack.title
            Log.d(TAG, "WatchNext candidates low (${candidates.size}). Querying fallback search for: \"$fallbackQuery\"")
            val fallbackResult = fallbackSearchProvider.search(fallbackQuery, limit = FALLBACK_SEARCH_BATCH_SIZE)
            val fallbackList = fallbackResult.getOrNull().orEmpty().shuffled()
            for (c in fallbackList) {
                if (candidates.none { it.videoId == c.videoId }) {
                    candidates.add(c)
                }
            }
        }

        return candidates
    }

    private suspend fun filterAndSelectCandidates(
        candidates: List<YouTubeCandidate>,
        limit: Int
    ): List<QueueEntry> {
        val selected = mutableListOf<QueueEntry>()

        windowMutex.withLock {
            for (candidate in candidates) {
                if (selected.size >= limit) break

                val canonicalKey = CanonicalMediaKey.fromResolved("youtube", candidate.videoId)

                val rejectionReason = when {
                    canonicalKey == currentTrackKey -> "current_playing"
                    playedKeys.contains(canonicalKey) -> "already_played"
                    queuedKeys.contains(canonicalKey) -> "already_queued"
                    inFlightKeys.contains(canonicalKey) -> "already_in_flight"
                    futureWindow.any { CanonicalMediaKey.fromTrack(it.track) == canonicalKey } -> "in_future_window"
                    else -> null
                }

                if (rejectionReason != null) {
                    Log.d(TAG, "CANDIDATE_REJECTED key=$canonicalKey reason=$rejectionReason")
                } else {
                    val track = Track(
                        id = TrackId(ProviderId.YOUTUBE, candidate.videoId),
                        title = candidate.title,
                        artist = candidate.channelTitle ?: "YouTube Music",
                        album = candidate.album,
                        durationMs = candidate.durationMs,
                        artworkUri = candidate.artworkUrl,
                        source = AudioSource.Remote(ProviderId.YOUTUBE, candidate.videoId)
                    )
                    selected.add(
                        QueueEntry(
                            track = track,
                            origin = QueueOrigin.RADIO,
                            playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY
                        )
                    )
                }
            }
        }

        return selected
    }

    private suspend fun resolveAndAppendImmediateNext(
        sessionId: Long,
        player: Player
    ) {
        val immediateNextEntry: QueueEntry
        val nextKey: String

        val needsResolution = windowMutex.withLock {
            val head = futureWindow.firstOrNull() ?: return@withLock false
            val key = CanonicalMediaKey.fromTrack(head.track)
            if (queuedKeys.contains(key) || inFlightKeys.contains(key)) {
                return@withLock false
            }
            // Atomically reserve in inFlightKeys before starting network resolution
            inFlightKeys.add(key)
            resolutionState = NextResolutionState.Resolving(sessionId, key)
            true
        }

        if (!needsResolution) return

        immediateNextEntry = windowMutex.withLock { futureWindow.first() }
        nextKey = CanonicalMediaKey.fromTrack(immediateNextEntry.track)

        try {
            if (sessionId != activeSessionId || !scope.isActive) {
                windowMutex.withLock {
                    inFlightKeys.remove(nextKey)
                }
                return
            }

            val videoId = extractVideoId(immediateNextEntry.track)
            if (videoId.isNullOrBlank()) {
                windowMutex.withLock {
                    inFlightKeys.remove(nextKey)
                    resolutionState = NextResolutionState.Failed(sessionId, nextKey, "Invalid video ID")
                }
                return
            }

            val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
            Log.i(TAG, "RADIO_RESOLVING_IMMEDIATE_NEXT key=$nextKey videoId=$videoId title=\"${immediateNextEntry.track.title}\" artist=\"${immediateNextEntry.track.artist}\"")
            val streamResult = streamResolver.resolveStream(
                canonicalYoutubeUrl = canonicalUrl,
                title = immediateNextEntry.track.title,
                artist = immediateNextEntry.track.artist,
                isPrefetch = true,
                expectedDurationMs = immediateNextEntry.track.durationMs
            )

            if (streamResult.isFailure) {
                val err = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                Log.w(TAG, "Stream resolution failed for immediate next: $nextKey ($err)")
                windowMutex.withLock {
                    inFlightKeys.remove(nextKey)
                    resolutionState = NextResolutionState.Failed(sessionId, nextKey, err)
                }
                return
            }

            val resolvedStream = streamResult.getOrThrow()
            StreamUrlCache.put(nextKey, resolvedStream)
            val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(
                track = immediateNextEntry.track,
                streamUrl = resolvedStream.streamUrl,
                queueEntryId = immediateNextEntry.queueId,
                canonicalMediaKey = nextKey,
                sessionId = sessionId,
                sourceContext = "RADIO",
                playbackOrigin = PlaybackOrigin.RADIO_AUTOPLAY,
                formatId = resolvedStream.formatId
            )

            // Double duplicate validation before timeline insertion
            var canAppend = false
            windowMutex.withLock {
                if (sessionId == activeSessionId && scope.isActive &&
                    !playedKeys.contains(nextKey) && !queuedKeys.contains(nextKey)
                ) {
                    // ZERO UNLOCKED GAP: atomically move from inFlightKeys to queuedKeys
                    inFlightKeys.remove(nextKey)
                    queuedKeys.add(nextKey)
                    resolutionState = NextResolutionState.Ready(sessionId, nextKey, mediaItem)
                    Log.i(TAG, "NEXT_READY key=$nextKey")
                    canAppend = true
                } else {
                    inFlightKeys.remove(nextKey)
                }
            }

            if (canAppend) {
                // Thread Safety: Dispatch timeline insertion to player looper / Main.immediate
                withContext(mainDispatcher) {
                    dispatchOnPlayerLooper { activePlayer ->
                        // Defense-in-depth: check whether this media item is already present in player timeline
                        val count = activePlayer.mediaItemCount
                        var alreadyInTimeline = false
                        for (i in 0 until count) {
                            if (activePlayer.getMediaItemAt(i).mediaId == mediaItem.mediaId) {
                                alreadyInTimeline = true
                                break
                            }
                        }
                        if (alreadyInTimeline) {
                            Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$nextKey reason=already_in_timeline")
                        } else {
                            activePlayer.addMediaItem(mediaItem)
                            Log.i(TAG, "RADIO_APPENDED key=$nextKey title=\"${immediateNextEntry.track.title}\"")
                            onItemAppendedToTimeline?.invoke(immediateNextEntry, mediaItem)
                        }
                    }
                }

                // Preload immediate next (candidate 1) audio bytes to singleton cache
                if (context != null) {
                    val bitrate = resolvedStream.bitrate ?: 128_000
                    val preloadBytes = ((bitrate / 8) * 8).toLong().coerceIn(128_000L, 512_000L)
                    preloadJob?.cancel()
                    preloadJob = scope.launch(ioDispatcher) {
                        try {
                            val cacheDataSource = Media3StreamCache.getCacheDataSourceFactory(context).createDataSource()
                            val dataSpec = DataSpec.Builder()
                                .setUri(Uri.parse(resolvedStream.streamUrl))
                                .setKey(Media3StreamCache.buildCacheKey(videoId, resolvedStream.formatId))
                                .setLength(preloadBytes)
                                .build()
                            val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
                            writer.cache()
                            Log.i(TAG, "PRELOAD_COMPLETE key=$nextKey bytes=$preloadBytes")
                        } catch (e: Exception) {
                            Log.d(TAG, "Preload interrupted for $nextKey: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            windowMutex.withLock {
                inFlightKeys.remove(nextKey)
                resolutionState = NextResolutionState.Failed(sessionId, nextKey, e.message ?: "Unknown error")
            }
            throw e
        }
    }

    /**
     * Pre-play safety validation: checks whether [nextItem] is already played or is duplicate.
     * If so, removes it from [player] timeline and triggers fresh candidate replenishment.
     * Returns true if valid, false if duplicate was removed.
     *
     * Safety contract:
     * 1. Confirm item is duplicate via canonicalKey match BEFORE any removal.
     * 2. NEVER remove the currently playing media item (index == player.currentMediaItemIndex).
     * 3. Race condition: if the duplicate IS the currently playing item, log and return false
     *    without removal — the caller must NOT advance to play it again.
     */
    suspend fun validateAndRemoveDuplicateBeforePlay(player: Player, nextItem: MediaItem): Boolean {
        val nextKey = MediaItemMapper.getCanonicalMediaKey(nextItem) ?: return true
        val isDuplicate = windowMutex.withLock {
            nextKey == currentTrackKey || playedKeys.contains(nextKey)
        }

        if (isDuplicate) {
            Log.w(TAG, "DUPLICATE_BEFORE_PLAY_REMOVED key=$nextKey")
            withContext(mainDispatcher) {
                dispatchOnPlayerLooper { activePlayer ->
                    val currentPlayingIndex = activePlayer.currentMediaItemIndex
                    var duplicateFoundAt = -1

                    // Step 1: Scan timeline to find the duplicate by canonicalKey
                    for (i in 0 until activePlayer.mediaItemCount) {
                        if (MediaItemMapper.getCanonicalMediaKey(activePlayer.getMediaItemAt(i)) == nextKey) {
                            duplicateFoundAt = i
                            break
                        }
                    }

                    when {
                        duplicateFoundAt < 0 -> {
                            // Already gone (race: removed by another path)
                            Log.d(TAG, "DUPLICATE_BEFORE_PLAY: key=$nextKey not found in timeline; already cleaned up")
                        }
                        duplicateFoundAt == currentPlayingIndex -> {
                            // Race condition: duplicate IS the currently playing item.
                            // Safety: do NOT remove it — it is actively being played.
                            // Caller receives false and must NOT advance playback to it again.
                            Log.w(TAG, "DUPLICATE_BEFORE_PLAY_RACE key=$nextKey is currently playing (index=$duplicateFoundAt). Skipping removal to avoid disruption.")
                        }
                        else -> {
                            // Safe to remove: confirmed duplicate, not currently playing
                            Log.i(TAG, "DUPLICATE_BEFORE_PLAY_REMOVING key=$nextKey at index=$duplicateFoundAt (currentPlaying=$currentPlayingIndex)")
                            activePlayer.removeMediaItem(duplicateFoundAt)
                        }
                    }
                }
            }
            windowMutex.withLock {
                queuedKeys.remove(nextKey)
                inFlightKeys.remove(nextKey)
            }
            currentTrackKey?.let {
                val seedTrack = Track(
                    id = TrackId(ProviderId.YOUTUBE, nextKey.removePrefix("youtube:")),
                    title = "Seed",
                    artist = "",
                    source = AudioSource.Remote(ProviderId.YOUTUBE, nextKey.removePrefix("youtube:"))
                )
                triggerReplenish(seedTrack, activeSessionId, player)
            }
            return false
        }
        return true
    }

    private fun extractVideoId(track: Track): String? {
        val source = track.source
        return when {
            source is AudioSource.Remote && source.provider == ProviderId.YOUTUBE -> source.sourceId
            track.id.provider == ProviderId.YOUTUBE -> track.id.rawId
            else -> null
        }
    }

    private suspend fun logWindowState() {
        val queuedCount = windowMutex.withLock { futureWindow.size }
        Log.i(TAG, "RADIO_WINDOW current=$currentTrackKey queued=$queuedCount")
    }

    // Single source of truth query methods
    suspend fun isKeyPlayed(key: String): Boolean = windowMutex.withLock { playedKeys.contains(key) }
    suspend fun isKeyQueued(key: String): Boolean = windowMutex.withLock { queuedKeys.contains(key) }
    suspend fun isKeyInFlight(key: String): Boolean = windowMutex.withLock { inFlightKeys.contains(key) }

    fun getResolutionState(): NextResolutionState = resolutionState

    /**
     * Peeks at the immediate next candidate in the future window without removing it.
     */
    suspend fun peekNextCandidate(): QueueEntry? = windowMutex.withLock { futureWindow.firstOrNull() }

    // Exposed for unit testing
    suspend fun getFutureWindow(): List<QueueEntry> = windowMutex.withLock { futureWindow.toList() }
    suspend fun getPlayedKeys(): Set<String> = windowMutex.withLock { playedKeys.toSet() }
    suspend fun getQueuedKeys(): Set<String> = windowMutex.withLock { queuedKeys.toSet() }
    suspend fun getInFlightKeys(): Set<String> = windowMutex.withLock { inFlightKeys.toSet() }
    fun getCurrentTrackKey(): String? = currentTrackKey
}
