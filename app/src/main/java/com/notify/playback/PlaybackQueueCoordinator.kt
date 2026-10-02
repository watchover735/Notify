package com.notify.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.notify.core.local.LocalAudioAvailabilityChecker
import com.notify.core.local.LocalAudioResult
import com.notify.core.model.AudioSource
import com.notify.core.model.CanonicalMediaKey
import com.notify.core.model.PlaybackError
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.PlaybackSnapshot
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.RepeatMode
import com.notify.core.model.ShuffleMode
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.NotiFyPlaybackService
import com.notify.core.playback.PlaybackQueueDelegate
import com.notify.core.playback.PlaybackQueueDelegateFactory
import com.notify.core.playback.PlaybackQueueDelegateRegistry
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.core.playback.SleepTimerManager
import com.notify.core.playback.SleepTimerState
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.RecentSearchItemEntity
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.matcher.InnerTubeWatchNextProvider
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.spotify.SpotifyTrackMetadata
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.OnlineStreamResolver
import com.notify.download.stream.ResolvedStreamProviderChain
import com.notify.download.stream.StreamUrlCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * PlaybackQueueCoordinator:
 * Service-owned queue coordinator and lookahead manager implementing [PlaybackQueueDelegate].
 *
 * Core Guarantees:
 * 1. Single MediaSessionService / ExoPlayer ownership.
 * 2. Lookahead prefetch with LOOKAHEAD_COUNT = 2.
 * 3. Contiguous lookahead ordering: N -> N+1 -> N+2 preserved even if N+2 resolves before N+1.
 * 4. Stable queue tracking by queueEntryId, never by raw list index arithmetic.
 * 5. Immediate Next handling: resolves next descriptor on demand if lookahead is still resolving.
 * 6. Offline mode safety: skips non-downloaded tracks; stops cleanly when exhausted.
 * 7. Threading rules: All ExoPlayer/MediaSession operations run on the player's application Looper.
 */
class PlaybackQueueCoordinator(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val database: NotiFyDatabase = NotiFyDatabase.getInstance(context)
) : PlaybackQueueDelegate {

    companion object {
        private const val TAG = "PlaybackQueueCoord"
        const val LOOKAHEAD_COUNT = 1
        const val SMART_SHUFFLE_INTERVAL = 5

        @Volatile
        private var instance: PlaybackQueueCoordinator? = null

        fun getInstance(context: Context): PlaybackQueueCoordinator {
            return instance ?: synchronized(this) {
                instance ?: PlaybackQueueCoordinator(context.applicationContext).also { instance = it }
            }
        }

        fun install(context: Context) {
            val coordinator = getInstance(context)
            PlaybackQueueDelegateRegistry.factory = PlaybackQueueDelegateFactory { coordinator }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }

    private val downloadManager = OfflineDownloadManager(context)
    private val streamResolver: AudioStreamResolver = ResolvedStreamProviderChain(context)
    private val innerTubeSearchProvider = InnerTubeYouTubeMusicSearchProvider()
    private val fallbackSearchProvider = YtDlpYouTubeSearchProvider(context)
    private val watchNextProvider = InnerTubeWatchNextProvider()
    private val snapshotStore = PlaybackSnapshotStore(context)
    private val mediaTreeProvider = AndroidAutoMediaTreeProvider(context, database, ioDispatcher)

    val radioWindowManager by lazy {
        RadioWindowManager(
            context = context,
            scope = serviceScope ?: CoroutineScope(ioDispatcher),
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher,
            watchNextProvider = watchNextProvider,
            fallbackSearchProvider = fallbackSearchProvider,
            streamResolver = streamResolver,
            dispatchOnPlayerLooper = { action -> dispatchOnPlayerLooper(action) }
        ).apply {
            onCandidatesUpdated = { candidates ->
                synchronized(queueDescriptors) {
                    for (c in candidates) {
                        if (queueDescriptors.none { it.queueId == c.queueId }) {
                            queueDescriptors.add(c)
                        }
                    }
                }
                _coordinatorState.update { it.copy(queue = queueDescriptors.toList()) }
            }
            onItemAppendedToTimeline = { entry, mediaItem ->
                synchronized(queueDescriptors) {
                    if (queueDescriptors.none { it.queueId == entry.queueId }) {
                        queueDescriptors.add(entry)
                    }
                }
                _coordinatorState.update { it.copy(queue = queueDescriptors.toList()) }
                handleDelayedItemAppended(entry, mediaItem)
            }
        }
    }

    // Service & Player attachments
    private var service: NotiFyPlaybackService? = null
    private var player: Player? = null
    private var session: MediaSession? = null
    private var serviceScope: CoroutineScope? = null

    // Session guards & delayed append state
    @Volatile private var pendingEndedSessionId: Long = -1L
    @Volatile private var pendingEndedCompletedKey: String? = null
    @Volatile private var lastTransitionedKey: String? = null
    @Volatile private var lastTransitionedSessionId: Long = -1L
    @Volatile private var lastHandledQueueEntryId: String? = null
    @Volatile private var lastSideEffectProcessedKey: String? = null
    @Volatile private var activeRequestId: String? = null

    // Queue State
    private var playbackSessionId = 0L
    private val originalPlaylistEntries = mutableListOf<QueueEntry>()
    private val queueDescriptors = mutableListOf<QueueEntry>()
    private var activeShuffleMode: ShuffleMode = ShuffleMode.OFF
    private val smartShuffleInsertedKeys = mutableSetOf<String>()
    private var currentIndex = -1
    private var isAutoplayEnabled = true
    private var radioSeedSourceId: String? = null

    // Lookahead Order Preservation Buffer
    // Key: descriptor position in queueDescriptors -> Value: resolved playable MediaItem
    private val resolvedLookaheadMap = ConcurrentHashMap<Int, MediaItem>()
    private var nextContiguousAppendIndex = 0
    private var prefetchJob: Job? = null
    private var progressCheckJob: Job? = null

    // Failure tracking for Fix 4 (User-facing timeout & auto-skip after 2 consecutive failures)
    @Volatile private var consecutiveResolveFailures = 0
    @Volatile private var failedTrackKey: String? = null

    /**
     * Single-flight guard for triggerLookaheadReplenishment.
     * Format: "<sessionId>:<radioSeed>". Only ONE replenishment job may run per unique key.
     * Reset to null whenever a new session starts or when the job completes.
     */
    @Volatile private var activeReplenishmentKey: String? = null

    /**
     * Single-flight guard for WatchNext network requests.
     * Format: "<sessionId>:<radioSeed>". Prevents parallel network calls for the same seed.
     */
    @Volatile private var activeWatchNextKey: String? = null

    // State Flow for UI reflection
    data class CoordinatorState(
        val queue: List<QueueEntry> = emptyList(),
        val currentIndex: Int = -1,
        val currentTrack: Track? = null,
        val isAutoplayEnabled: Boolean = true,
        val shuffleMode: ShuffleMode = ShuffleMode.OFF,
        val message: String? = null,
        val isPreparingNext: Boolean = false,
        val isOffline: Boolean = false,
        /** True while stream URL is being resolved — drives MiniPlayer spinner. */
        val isResolvingStream: Boolean = false,
        /** Restored position in milliseconds from persistent snapshot prior to active playback. */
        val restoredPositionMs: Long = 0L
    )

    private val _coordinatorState = MutableStateFlow(CoordinatorState())
    val coordinatorState: StateFlow<CoordinatorState> = _coordinatorState.asStateFlow()

    @Volatile
    private var isResumptionInProgress: Boolean = false
    private var lastSnapshotPersistJob: Job? = null

    // ── Lifecycle Hooks ────────────────────────────────────────────────────────

    override fun attach(
        service: NotiFyPlaybackService,
        player: Player,
        session: MediaSession,
        serviceScope: CoroutineScope
    ) {
        this.service = service
        this.player = player
        this.session = session
        this.serviceScope = serviceScope

        player.repeatMode = Player.REPEAT_MODE_OFF
        Log.d(TAG, "Attached to NotiFyPlaybackService with player and session (REPEAT_MODE_OFF)")

        // Restore snapshot on service creation if player is idle
        serviceScope.launch(ioDispatcher) {
            restoreSnapshotIfAvailable()
        }
    }

    internal fun attachPlayerForTesting(testPlayer: Player, testScope: CoroutineScope) {
        this.player = testPlayer
        this.serviceScope = testScope
        try {
            testPlayer.repeatMode = Player.REPEAT_MODE_OFF
        } catch (_: Throwable) {}
    }

    @androidx.annotation.VisibleForTesting
    internal fun setPlayerForTesting(testPlayer: Player) {
        this.player = testPlayer
    }

    private fun handleDelayedItemAppended(entry: QueueEntry, mediaItem: MediaItem) {
        val p = player ?: return
        val currentSessionId = playbackSessionId
        val neededSessionId = pendingEndedSessionId
        val completedKey = pendingEndedCompletedKey

        if (neededSessionId != -1L &&
            neededSessionId == currentSessionId &&
            completedKey != null &&
            p.playbackState == Player.STATE_ENDED
        ) {
            // One-time consumption
            pendingEndedSessionId = -1L
            pendingEndedCompletedKey = null
            _coordinatorState.update { it.copy(isPreparingNext = false, message = null) }

            val appendedKey = CanonicalMediaKey.fromTrack(entry.track)
            Log.i(TAG, "AUTO_TRANSITION from=$completedKey to=$appendedKey")
            dispatchOnPlayerLooper { activePlayer ->
                if (activePlayer.hasNextMediaItem()) {
                    activePlayer.seekToNextMediaItem()
                    activePlayer.play()
                }
            }
        }
    }

    override fun detach() {
        Log.d(TAG, "Detached from NotiFyPlaybackService")
        if (!isResumptionInProgress) {
            persistSnapshot()
        }
        progressCheckJob?.cancel()
        progressCheckJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        service = null
        player = null
        session = null
        serviceScope = null
    }

    private fun dispatchOnPlayerLooper(action: (Player) -> Unit) {
        val p = player ?: return
        val looper = try {
            p.applicationLooper
        } catch (_: Throwable) {
            null
        } ?: Looper.getMainLooper()
        if (Looper.myLooper() == looper) {
            action(p)
        } else {
            Handler(looper).post {
                val activePlayer = player ?: return@post
                action(activePlayer)
            }
        }
    }

    fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Queue Management & Playback Initiation ─────────────────────────────────

    /**
     * Sets the active playlist queue from complete ordered entries, starting from [startIndex].
     * Preserves all preceding and succeeding tracks.
     */
    fun playQueue(
        entries: List<QueueEntry>,
        startIndex: Int = 0,
        playImmediately: Boolean = true
    ) {
        val scope = serviceScope ?: CoroutineScope(Dispatchers.Main)
        val sessionId = synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = null
            activeReplenishmentKey = null
            activeWatchNextKey = null
            resolvedLookaheadMap.clear()
            originalPlaylistEntries.clear()
            originalPlaylistEntries.addAll(entries)
            smartShuffleInsertedKeys.clear()

            val clampedIndex = startIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            if (activeShuffleMode == ShuffleMode.OFF) {
                queueDescriptors.clear()
                queueDescriptors.addAll(entries)
                currentIndex = clampedIndex
            } else {
                val startEntry = entries.getOrNull(clampedIndex)
                val remaining = entries.filterIndexed { index, _ -> index != clampedIndex }.shuffled()
                queueDescriptors.clear()
                if (startEntry != null) {
                    queueDescriptors.add(startEntry)
                }
                queueDescriptors.addAll(remaining)
                currentIndex = 0
            }
            nextContiguousAppendIndex = currentIndex + 1
            val currentEntry = queueDescriptors.getOrNull(currentIndex)
            val src = currentEntry?.track?.source
            radioSeedSourceId = when (src) {
                is AudioSource.Remote -> src.sourceId
                else -> currentEntry?.track?.id?.rawId
            }
            consecutiveResolveFailures = 0
            failedTrackKey = null
            progressCheckJob?.cancel()
            progressCheckJob = null
            ++playbackSessionId
        }

        radioWindowManager.reset(sessionId)

        if (entries.isEmpty()) {
            _coordinatorState.update { CoordinatorState() }
            dispatchOnPlayerLooper { p ->
                p.stop()
                p.clearMediaItems()
            }
            return
        }

        _coordinatorState.update {
            it.copy(
                queue = queueDescriptors.toList(),
                currentIndex = currentIndex,
                currentTrack = queueDescriptors.getOrNull(currentIndex)?.track,
                isAutoplayEnabled = isAutoplayEnabled,
                shuffleMode = activeShuffleMode,
                message = null,
                isPreparingNext = false,
                isOffline = !isNetworkConnected(),
                // Show spinner immediately — user tapped, resolution is about to start
                isResolvingStream = true
            )
        }

        if (activeShuffleMode == ShuffleMode.SMART_SHUFFLE) {
            scheduleSmartShuffleRecommendations(sessionId)
        }

        scope.launch(ioDispatcher) {
            initiateQueuePlayback(sessionId, playImmediately)
        }
    }

    /**
     * Starts playback of an already resolved MediaItem as a new authoritative queue context.
     * Atomically increments playbackSessionId to cancel any previous playlist or search prefetch jobs.
     */
    fun playResolvedItem(
        entry: QueueEntry,
        mediaItem: MediaItem,
        playImmediately: Boolean = true,
        requestId: String? = null,
        contextEntries: List<QueueEntry> = emptyList()
    ) {
        val reqId = requestId ?: activeRequestId ?: "req_${System.currentTimeMillis()}_${entry.track.id.rawId}"
        activeRequestId = reqId
        // Deduplication guard: if the same providerSourceId is already the active entry,
        // do NOT reset the queue. Simply restart the player from position 0 and return.
        val stableKey = "${entry.track.id.provider.name}:${entry.track.id.rawId}"
        val alreadyCurrent = synchronized(this) {
            val existingEntry = queueDescriptors.getOrNull(currentIndex)
            val existingKey = existingEntry?.let { "${it.track.id.provider.name}:${it.track.id.rawId}" }
            existingKey == stableKey
        }
        if (alreadyCurrent) {
            Log.d(TAG, "playResolvedItem: same track already active ($stableKey). Seeking to start.")
            dispatchOnPlayerLooper { p ->
                p.seekTo(0L)
                if (playImmediately && !p.isPlaying) p.play()
            }
            return
        }

        val canonicalKey = CanonicalMediaKey.fromTrack(entry.track)
        val sessionId = synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = null
            activeReplenishmentKey = null
            activeWatchNextKey = null
            resolvedLookaheadMap.clear()
            queueDescriptors.clear()
            queueDescriptors.add(entry)
            queueDescriptors.addAll(contextEntries)
            originalPlaylistEntries.clear()
            originalPlaylistEntries.add(entry)
            originalPlaylistEntries.addAll(contextEntries)
            currentIndex = 0
            nextContiguousAppendIndex = 1
            lastTransitionedKey = canonicalKey
            lastTransitionedSessionId = playbackSessionId + 1
            lastHandledQueueEntryId = entry.queueId
            lastSideEffectProcessedKey = "${playbackSessionId + 1}:${entry.queueId}"
            val src = entry.track.source
            radioSeedSourceId = when (src) {
                is AudioSource.Remote -> src.sourceId
                else -> entry.track.id.rawId
            }
            consecutiveResolveFailures = 0
            failedTrackKey = null
            progressCheckJob?.cancel()
            progressCheckJob = null
            ++playbackSessionId
        }

        radioWindowManager.reset(sessionId)

        _coordinatorState.update {
            it.copy(
                queue = listOf(entry) + contextEntries,
                currentIndex = 0,
                currentTrack = entry.track,
                isAutoplayEnabled = isAutoplayEnabled,
                message = null,
                isPreparingNext = false,
                isOffline = !isNetworkConnected()
            )
        }

        dispatchOnPlayerLooper { p ->
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.stop()
            p.setMediaItem(mediaItem)
            p.prepare()
            if (playImmediately) {
                p.play()
            }
            Log.i(TAG, "PLAY_REQUEST session=$sessionId request=$reqId mediaId=${mediaItem.mediaId} timelineSize=${p.mediaItemCount}")
            radioWindowManager.onTrackStarted(entry, sessionId, p)
        }

        persistSnapshot()
        recordSearchRecentIfEligible(entry.track, mediaItem, entry.playbackOrigin)
        triggerLookaheadReplenishment(sessionId)
    }

    private suspend fun initiateQueuePlayback(sessionId: Long, playImmediately: Boolean) {
        val networkAvailable = isNetworkConnected()

        // Show resolving spinner immediately
        _coordinatorState.update { it.copy(isResolvingStream = true) }

        if (!networkAvailable) {
            // Emit no-internet snackbar only if the track has a remote source
            val firstEntry = queueDescriptors.getOrNull(currentIndex)
            val hasRemoteSource = firstEntry?.track?.source is AudioSource.Remote
            if (hasRemoteSource) {
                com.notify.ui.SnackbarManager.tryEmit(com.notify.ui.SnackbarEvent.NoInternet)
            }
        }

        // Find the first playable descriptor starting from currentIndex
        var activeIndex = currentIndex
        var resolvedItem: MediaItem? = null

        while (activeIndex in queueDescriptors.indices) {
            if (sessionId != playbackSessionId) {
                _coordinatorState.update { it.copy(isResolvingStream = false) }
                return
            }
            val entry = queueDescriptors[activeIndex]

            // If offline, check if entry is available offline before attempting
            if (!networkAvailable && !isEntryAvailableOffline(entry)) {
                Log.d(TAG, "Offline skipping non-downloaded entry: ${entry.track.title} ($activeIndex)")
                activeIndex++
                continue
            }

            resolvedItem = resolveDescriptor(entry, sessionId)
            if (resolvedItem != null) {
                break
            } else {
                Log.w(TAG, "Failed resolving descriptor at $activeIndex: ${entry.track.title}. Skipping to next.")
                activeIndex++
            }
        }

        if (sessionId != playbackSessionId) {
            _coordinatorState.update { it.copy(isResolvingStream = false) }
            return
        }

        if (resolvedItem == null) {
            // No playable tracks remaining
            _coordinatorState.update { it.copy(isResolvingStream = false) }
            withContext(Dispatchers.Main) {
                val errorMsg = if (!networkAvailable) {
                    "No more downloaded songs available offline."
                } else {
                    com.notify.ui.SnackbarManager.tryEmit(com.notify.ui.SnackbarEvent.PlayFailed)
                    "Unable to play any selected songs."
                }
                _coordinatorState.update { it.copy(message = errorMsg) }
                dispatchOnPlayerLooper { p ->
                    p.stop()
                    p.clearMediaItems()
                }
            }
            return
        }

        // Successfully resolved initial track
        currentIndex = activeIndex
        nextContiguousAppendIndex = currentIndex + 1
        val currentEntry = queueDescriptors[currentIndex]
        val initialKey = CanonicalMediaKey.fromTrack(currentEntry.track)
        lastTransitionedKey = initialKey
        lastTransitionedSessionId = sessionId
        lastHandledQueueEntryId = currentEntry.queueId
        lastSideEffectProcessedKey = "$sessionId:${currentEntry.queueId}"

        withContext(Dispatchers.Main) {
            _coordinatorState.update {
                it.copy(
                    currentIndex = currentIndex,
                    currentTrack = currentEntry.track,
                    message = null
                )
            }
            dispatchOnPlayerLooper { p ->
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.stop()
                p.clearMediaItems()
                p.setMediaItems(listOf(resolvedItem), 0, 0L)
                p.prepare()
                if (playImmediately) {
                    p.play()
                }
                Log.i(TAG, "PLAY_REQUEST session=$sessionId request=$activeRequestId mediaId=${resolvedItem.mediaId} timelineSize=${p.mediaItemCount}")
                val isSingleTrack = queueDescriptors.size <= 1
                radioWindowManager.onTrackStarted(currentEntry, sessionId, p, shouldReplenish = isSingleTrack)
            }
        }

        // Persist snapshot and start lookahead prefetch
        _coordinatorState.update { it.copy(isResolvingStream = false) }
        persistSnapshot()
        triggerLookaheadReplenishment(sessionId)
    }

    // ── Lookahead Resolution & Order Preservation ──────────────────────────────

    /**
     * Replenishes the lookahead window to ensure up to LOOKAHEAD_COUNT playable items
     * are queued contiguously in ExoPlayer ahead of currentIndex.
     */
    /**
     * Replenishes the lookahead window. Single-flight per (sessionId, radioSeed) pair:
     * if an identical replenishment job is already running, this call is a no-op.
     * Cancel+restart only happens when playbackSessionId advances (new context).
     */
    fun triggerLookaheadReplenishment(targetSessionId: Long = playbackSessionId) {
        val scope = serviceScope ?: return

        val seed = radioSeedSourceId
        val replenishKey = "$targetSessionId:${seed.orEmpty()}"

        synchronized(this) {
            if (targetSessionId != playbackSessionId) return
            // If the SAME key is already running, skip — single-flight guard.
            if (activeReplenishmentKey == replenishKey && prefetchJob?.isActive == true) {
                Log.d(TAG, "triggerLookaheadReplenishment: already running for key=$replenishKey. Skipping.")
                return
            }
            // Cancel previous job (different key = different session or seed changed)
            prefetchJob?.cancel()
            activeReplenishmentKey = replenishKey
        }

        prefetchJob = scope.launch(ioDispatcher) {
            try {
                if (targetSessionId != playbackSessionId) return@launch

                val networkAvailable = isNetworkConnected()
                var lookaheadTargetIndex = nextContiguousAppendIndex
                var resolvedCount = 0

                while (lookaheadTargetIndex in queueDescriptors.indices && resolvedCount < LOOKAHEAD_COUNT) {
                    if (targetSessionId != playbackSessionId || !isActive) return@launch
                    val entry = queueDescriptors[lookaheadTargetIndex]
                    val thisIndex = lookaheadTargetIndex

                    if (!networkAvailable && !isEntryAvailableOffline(entry)) {
                        Log.d(TAG, "Lookahead skipping non-downloaded: ${entry.track.title} ($thisIndex)")
                        lookaheadTargetIndex++
                        continue
                    }

                    // If not already resolved in buffer
                    if (!resolvedLookaheadMap.containsKey(thisIndex)) {
                        val mediaItem = resolveDescriptor(entry, targetSessionId)
                        if (targetSessionId != playbackSessionId || !isActive) return@launch
                        if (mediaItem != null) {
                            resolvedLookaheadMap[thisIndex] = mediaItem
                            resolvedCount++
                            drainContiguousLookaheadToPlayer(targetSessionId)
                        } else {
                            Log.w(TAG, "Lookahead failed for: ${entry.track.title} ($thisIndex). Will skip.")
                        }
                    } else {
                        resolvedCount++
                    }

                    lookaheadTargetIndex++
                }

                // Exhausted known playlist descriptors — engage RadioWindowManager if autoplay enabled
                if (lookaheadTargetIndex >= queueDescriptors.size && isAutoplayEnabled && networkAvailable) {
                    val currentEntry = synchronized(queueDescriptors) { queueDescriptors.getOrNull(currentIndex) }
                    val p = player
                    if (currentEntry != null && p != null && targetSessionId == playbackSessionId) {
                        radioWindowManager.triggerReplenish(currentEntry.track, targetSessionId, p)
                    }
                }
            } finally {
                // Clear the replenishment key only if we are the active owner
                synchronized(this@PlaybackQueueCoordinator) {
                    if (activeReplenishmentKey == replenishKey) {
                        activeReplenishmentKey = null
                    }
                }
            }
        }
    }

    /**
     * Drains resolved items from [resolvedLookaheadMap] strictly in contiguous order:
     * nextContiguousAppendIndex, nextContiguousAppendIndex + 1, ...
     * Guarantees Media3 timeline is strictly N -> N+1 -> N+2 even if N+2 resolved earlier.
     */
    private fun drainContiguousLookaheadToPlayer(targetSessionId: Long) {
        while (true) {
            if (targetSessionId != playbackSessionId) return
            val nextItem = resolvedLookaheadMap.remove(nextContiguousAppendIndex) ?: break
            val itemKey = MediaItemMapper.getCanonicalMediaKey(nextItem)

            Log.d(TAG, "Appending contiguous lookahead item at index $nextContiguousAppendIndex: ${nextItem.mediaId}")
            dispatchOnPlayerLooper { p ->
                // Defense-in-depth: scan timeline for duplicate before inserting
                val alreadyInTimeline = itemKey != null && (0 until p.mediaItemCount).any {
                    MediaItemMapper.getCanonicalMediaKey(p.getMediaItemAt(it)) == itemKey
                }
                if (alreadyInTimeline) {
                    Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$itemKey reason=drain_lookahead_in_timeline")
                } else {
                    p.addMediaItem(nextItem)
                }
            }
            nextContiguousAppendIndex++
        }
    }

    // ── Delegate Navigation & Transitions ──────────────────────────────────────

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) return
        isABRepeatActive = false
        abRepeatStartMs = 0L

        val queueEntryId = MediaItemMapper.getQueueEntryId(mediaItem)
        val canonicalMediaKey = MediaItemMapper.getCanonicalMediaKey(mediaItem)
        var matchedIndex = queueDescriptors.indexOfFirst { it.queueId == queueEntryId }

        if (matchedIndex < 0) {
            val reconstructed = MediaItemMapper.fromMediaItem(mediaItem)
            if (reconstructed != null) {
                val origin = MediaItemMapper.getPlaybackOrigin(mediaItem)
                val newEntry = QueueEntry(
                    track = reconstructed,
                    origin = QueueOrigin.RADIO,
                    playbackOrigin = origin
                )
                synchronized(queueDescriptors) {
                    queueDescriptors.add(newEntry)
                    matchedIndex = queueDescriptors.size - 1
                }
            }
        }

        if (matchedIndex >= 0) {
            val transitionKey = canonicalMediaKey ?: queueDescriptors[matchedIndex].let { CanonicalMediaKey.fromTrack(it.track) }
            val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val isSeek = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK

            // Guard against duplicate transition to the same track/entry in the same session
            if (playbackSessionId == lastTransitionedSessionId &&
                (queueEntryId == lastHandledQueueEntryId || transitionKey == lastTransitionedKey) &&
                !isRepeat && !isSeek
            ) {
                Log.w(TAG, "DUPLICATE_TRANSITION_IGNORED key=$transitionKey queueEntryId=$queueEntryId reason=$reason")
                return
            }

            val previousEntry = if (currentIndex in queueDescriptors.indices) queueDescriptors[currentIndex] else null
            val matchedEntry = queueDescriptors[matchedIndex]
            val completedKey = previousEntry?.let { CanonicalMediaKey.fromTrack(it.track) } ?: "none"
            val nextKey = CanonicalMediaKey.fromTrack(matchedEntry.track)

            // Idempotency guard: skip same-to-same track transitions unless explicitly repeating or seeking
            if (completedKey == nextKey && !isRepeat && !isSeek) {
                Log.w(TAG, "DUPLICATE_SAME_TRACK_TRANSITION_IGNORED from=$completedKey to=$nextKey reason=$reason")
                return
            }

            lastTransitionedKey = transitionKey
            lastTransitionedSessionId = playbackSessionId
            lastHandledQueueEntryId = queueEntryId

            Log.d(TAG, "onMediaItemTransition to entryId: $queueEntryId (index $matchedIndex, reason=$reason)")
            currentIndex = matchedIndex

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                Log.i(TAG, "AUTO_TRANSITION from=$completedKey to=$nextKey")
            } else {
                Log.i(TAG, "TRACK_TRANSITION reason=$reason from=$completedKey to=$nextKey")
            }

            // Advance the radio seed to the now-playing track so WatchNext always seeds
            // from the most recently played song.
            val src = matchedEntry.track.source
            radioSeedSourceId = when (src) {
                is AudioSource.Remote -> src.sourceId
                else -> matchedEntry.track.id.rawId
            }

            consecutiveResolveFailures = 0
            failedTrackKey = null

            _coordinatorState.update {
                it.copy(
                    currentIndex = matchedIndex,
                    currentTrack = matchedEntry.track,
                    message = null,
                    isPreparingNext = false
                )
            }

            val p = player
            val sideEffectKey = "$playbackSessionId:${matchedEntry.queueId}"
            if (sideEffectKey != lastSideEffectProcessedKey) {
                lastSideEffectProcessedKey = sideEffectKey
                if (p != null) {
                    radioWindowManager.onTrackTransition(previousEntry, matchedEntry, playbackSessionId, p)
                }
                val playbackOrigin = MediaItemMapper.getPlaybackOrigin(mediaItem)
                recordSearchRecentIfEligible(matchedEntry.track, mediaItem, playbackOrigin)
            } else {
                Log.d(TAG, "SIDE_EFFECTS_ALREADY_PROCESSED for $sideEffectKey")
            }

            if (!isResumptionInProgress) {
                persistSnapshot()
            } else {
                Log.d(TAG, "onMediaItemTransition: persistSnapshot skipped during resumption")
            }

            // Pre-emptively append next shuffled batch if near end of queue
            if (activeShuffleMode != ShuffleMode.OFF && originalPlaylistEntries.size > 1 && currentIndex + 2 >= queueDescriptors.size) {
                val nextBatch = originalPlaylistEntries.filter {
                    CanonicalMediaKey.fromTrack(it.track) != nextKey
                }.shuffled()
                synchronized(queueDescriptors) {
                    queueDescriptors.addAll(nextBatch)
                }
                _coordinatorState.update { it.copy(queue = queueDescriptors.toList()) }
                if (activeShuffleMode == ShuffleMode.SMART_SHUFFLE) {
                    scheduleSmartShuffleRecommendations(playbackSessionId)
                }
            }

            // Low-watermark refill: when upcoming remaining tracks are <= 2, trigger proactive radio refill
            val upcomingTracks = (queueDescriptors.size - 1) - currentIndex
            if (upcomingTracks <= 2 && isAutoplayEnabled && isNetworkConnected()) {
                if (p != null) {
                    radioWindowManager.triggerReplenish(matchedEntry.track, playbackSessionId, p)
                }
            }

            if (SleepTimerManager.sleepTimerState.value !is SleepTimerState.EndOfTrack) {
                triggerLookaheadReplenishment(playbackSessionId)
                startProgressPrefetchTicker(playbackSessionId)
            }
        } else {
            Log.w(TAG, "onMediaItemTransition: no matching descriptor for ${mediaItem.mediaId}")
        }
    }

    private fun startProgressPrefetchTicker(targetSessionId: Long) {
        progressCheckJob?.cancel()
        val scope = serviceScope ?: return
        progressCheckJob = scope.launch(Dispatchers.Main) {
            var hasPrefetchedAt70 = false
            var lastSavedSecond = -1L
            while (isActive && targetSessionId == playbackSessionId) {
                kotlinx.coroutines.delay(1000L)
                val p = player ?: break
                if (!p.isPlaying) continue
                val duration = p.duration
                val position = p.currentPosition
                if (duration > 0L) {
                    val progress = position.toFloat() / duration.toFloat()
                    if (progress in 0.70f..0.85f && !hasPrefetchedAt70) {
                        hasPrefetchedAt70 = true
                        Log.d(TAG, "Progress reached ${(progress * 100).toInt()}%; verifying next track prefetch")
                        triggerLookaheadReplenishment(targetSessionId)
                    }
                }
                // Periodic snapshot position persistence (1s cadence, deduplicated)
                if (position > 0L && !isResumptionInProgress) {
                    val currentSec = position / 1000L
                    if (currentSec != lastSavedSecond) {
                        lastSavedSecond = currentSec
                        persistSnapshot()
                    }
                }
            }
        }
    }

    @Volatile
    private var isABRepeatActive: Boolean = false
    @Volatile
    private var abRepeatStartMs: Long = 0L

    fun setABRepeatActive(active: Boolean, startMs: Long = 0L) {
        isABRepeatActive = active
        abRepeatStartMs = startMs
        Log.d(TAG, "setABRepeatActive: active=$active, startMs=$startMs")
    }

    fun isABRepeatActive(): Boolean = isABRepeatActive
    fun getABRepeatStartMs(): Long = abRepeatStartMs

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) {
            activeRequestId?.let { StartupMetricsLogger.onPlayerBuffering(it) }
        } else if (playbackState == Player.STATE_READY) {
            activeRequestId?.let { StartupMetricsLogger.onPlayerReady(it) }
            if (isResumptionInProgress) {
                Log.d(TAG, "STATE_READY: Resumption completed, resetting isResumptionInProgress")
                isResumptionInProgress = false
                _coordinatorState.update { it.copy(restoredPositionMs = 0L) }
            }
        } else if (playbackState == Player.STATE_ENDED) {
            if (isABRepeatActive) {
                Log.i(TAG, "TRACK_ENDED intercepted by active A-B repeat loop; looping back to $abRepeatStartMs")
                dispatchOnPlayerLooper { activePlayer ->
                    activePlayer.seekTo(abRepeatStartMs)
                    activePlayer.play()
                }
                return
            }
            if (SleepTimerManager.sleepTimerState.value is SleepTimerState.EndOfTrack) {
                Log.i(TAG, "TRACK_ENDED intercepted by SleepTimer EndOfTrack; intentionally stopping playback")
                return
            }
            val p = player
            if (p == null || isResumptionInProgress || lastResumptionJob?.isActive == true) {
                Log.d(TAG, "TRACK_ENDED ignored: player is null or resumption is in progress")
                return
            }
            val completedKey = coordinatorState.value.currentTrack?.let { CanonicalMediaKey.fromTrack(it) } ?: "unknown"
            Log.i(TAG, "TRACK_ENDED key=$completedKey")

            // If we reached the end of the shuffled list, reshuffle so playback continues without repeats until all played
            if (activeShuffleMode != ShuffleMode.OFF && originalPlaylistEntries.size > 1 && currentIndex + 1 >= queueDescriptors.size) {
                val nextBatch = originalPlaylistEntries.filter {
                    CanonicalMediaKey.fromTrack(it.track) != completedKey
                }.shuffled()
                synchronized(queueDescriptors) {
                    queueDescriptors.addAll(nextBatch)
                }
                _coordinatorState.update { it.copy(queue = queueDescriptors.toList()) }
                if (activeShuffleMode == ShuffleMode.SMART_SHUFFLE) {
                    scheduleSmartShuffleRecommendations(playbackSessionId)
                }
            }

            if (p != null && p.hasNextMediaItem()) {
                val nextIndex = p.currentMediaItemIndex + 1
                val nextItem = p.getMediaItemAt(nextIndex)
                val nextKey = MediaItemMapper.getCanonicalMediaKey(nextItem) ?: "unknown"
                Log.i(TAG, "NEXT_READY key=$nextKey")

                // Assert: next.canonicalMediaKey != completed.canonicalMediaKey
                if (nextKey == completedKey) {
                    Log.w(TAG, "DUPLICATE_BEFORE_PLAY_REMOVED key=$nextKey (matches completed track)")
                    dispatchOnPlayerLooper { activePlayer ->
                        activePlayer.removeMediaItem(nextIndex)
                    }
                    return
                }

                val scope = serviceScope ?: CoroutineScope(Dispatchers.Main)
                scope.launch(mainDispatcher) {
                    val isValid = radioWindowManager.validateAndRemoveDuplicateBeforePlay(p, nextItem)
                    if (isValid) {
                        Log.i(TAG, "AUTO_TRANSITION from=$completedKey to=$nextKey")
                        dispatchOnPlayerLooper { activePlayer ->
                            activePlayer.seekToNextMediaItem()
                            activePlayer.play()
                        }
                    }
                }
            } else {
                // FIX 1 & FIX 3: Check StreamUrlCache first for immediate zero-gap transition
                val sessionId = playbackSessionId
                val nextEntry = synchronized(queueDescriptors) {
                    if (currentIndex + 1 in queueDescriptors.indices) {
                        queueDescriptors[currentIndex + 1]
                    } else null
                }

                val nextCanonicalKey = nextEntry?.let { CanonicalMediaKey.fromTrack(it.track) }
                val nextVideoId = nextEntry?.let { extractVideoId(it.track) }
                val cachedStream = if (nextCanonicalKey != null) {
                    StreamUrlCache.get(nextCanonicalKey) ?: (nextVideoId?.let { StreamUrlCache.get(it) })
                } else null

                if (cachedStream != null && nextEntry != null) {
                    Log.i(TAG, "TRACK_ENDED CACHE_HIT key=$nextCanonicalKey -> immediate zero-gap transition")
                    val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(
                        track = nextEntry.track,
                        streamUrl = cachedStream.streamUrl,
                        queueEntryId = nextEntry.queueId,
                        canonicalMediaKey = nextCanonicalKey,
                        sessionId = sessionId,
                        sourceContext = if (nextEntry.origin == QueueOrigin.RADIO) "RADIO" else "PLAYLIST",
                        playbackOrigin = nextEntry.playbackOrigin,
                        formatId = cachedStream.formatId
                    )
                    _coordinatorState.update { it.copy(isPreparingNext = false, message = null) }

                    // Fix 3: Guard against duplicate append — RadioWindowManager.resolveAndAppendImmediateNext
                    // may have already appended this track. isKeyQueued is suspend, so we launch a coroutine
                    // (matching the pattern used for validateAndRemoveDuplicateBeforePlay above).
                    val isRadioEntry = nextEntry.origin == QueueOrigin.RADIO
                    val scopeForCacheHit = serviceScope ?: CoroutineScope(Dispatchers.Main)
                    scopeForCacheHit.launch(mainDispatcher) {
                        if (sessionId != playbackSessionId) return@launch // stale session guard

                        val alreadyQueuedByRadio = isRadioEntry && nextCanonicalKey != null &&
                            radioWindowManager.isKeyQueued(nextCanonicalKey)

                        if (alreadyQueuedByRadio) {
                            Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$nextCanonicalKey reason=cache_hit_path_already_queued")
                            // Item is already in timeline; just seek to it and play
                            dispatchOnPlayerLooper { activePlayer ->
                                if (activePlayer.hasNextMediaItem()) {
                                    activePlayer.seekToNextMediaItem()
                                } else if (activePlayer.mediaItemCount > 0) {
                                    activePlayer.seekTo(activePlayer.mediaItemCount - 1, 0L)
                                }
                                activePlayer.play()
                            }
                        } else {
                            dispatchOnPlayerLooper { activePlayer ->
                                // Defense-in-depth: also scan timeline for duplicate before inserting
                                val alreadyInTimeline = (0 until activePlayer.mediaItemCount).any {
                                    MediaItemMapper.getCanonicalMediaKey(activePlayer.getMediaItemAt(it)) == nextCanonicalKey
                                }
                                if (alreadyInTimeline) {
                                    Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$nextCanonicalKey reason=cache_hit_path_in_timeline")
                                    if (activePlayer.hasNextMediaItem()) activePlayer.seekToNextMediaItem()
                                    else if (activePlayer.mediaItemCount > 0) activePlayer.seekTo(activePlayer.mediaItemCount - 1, 0L)
                                } else {
                                    activePlayer.addMediaItem(mediaItem)
                                    if (activePlayer.hasNextMediaItem()) {
                                        activePlayer.seekToNextMediaItem()
                                    } else if (activePlayer.mediaItemCount > 0) {
                                        activePlayer.seekTo(activePlayer.mediaItemCount - 1, 0L)
                                    }
                                }
                                activePlayer.play()
                            }
                        }
                    }
                    return
                }

                val resolutionState = radioWindowManager.getResolutionState()
                if (resolutionState is NextResolutionState.Resolving) {
                    Log.i(TAG, "NEXT_PENDING key=${resolutionState.candidateKey}")
                    _coordinatorState.update {
                        it.copy(isPreparingNext = true, message = "Preparing next song…")
                    }
                    pendingEndedSessionId = playbackSessionId
                    pendingEndedCompletedKey = completedKey
                } else if (resolutionState is NextResolutionState.Failed) {
                    Log.w(TAG, "Next resolution failed: ${resolutionState.error}. Stopping playback.")
                    _coordinatorState.update {
                        it.copy(isPreparingNext = false, message = "Playback finished.")
                    }
                } else {
                    // Try subsequent from queueDescriptors if available
                    handleNextAction()
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            activeRequestId?.let { StartupMetricsLogger.onPlaying(it) }
        } else {
            // When paused, immediately persist exact position
            if (!isResumptionInProgress) {
                persistSnapshot()
            }
        }
    }

    private fun isHttp403(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                return true
            }
            if (cause.message?.contains("403") == true) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Returns true if ExoPlayer error is an HTTP source error (any non-2xx response)
     * which might indicate a missing CDN variant (e.g., _320.mp4 404 for JioSaavn).
     */
    private fun isHttpSourceError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return true
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return true
            cause = cause.cause
        }
        return false
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "onPlayerError from ExoPlayer: ${error.message}", error)
        if (isResumptionInProgress) {
            isResumptionInProgress = false
            _coordinatorState.update { it.copy(restoredPositionMs = 0L) }
        }
        val is403 = isHttp403(error)
        val isSourceError = isHttpSourceError(error)
        val currentEntry = queueDescriptors.getOrNull(currentIndex)
        val videoId = currentEntry?.track?.let { t ->
            if (t.id.provider == ProviderId.YOUTUBE) t.id.rawId
            else (t.source as? AudioSource.Remote)?.sourceId
        }

        // ── JioSaavn Quality Fallback ───────────────────────────────────────────
        // If ExoPlayer fails with an HTTP source error (e.g., 404 because _320.mp4 CDN variant
        // doesn't exist), transparently retry with the next lower-quality URL from fallbackUrls.
        // This is the playback-time complement of the optimistic _320.mp4 strategy in JioSaavnStreamResolver.
        // Zero resolve latency cost — fires only for songs that actually lack 320kbps on CDN (rare).
        if (isSourceError && currentEntry != null) {
            val p = player
            val currentItem = try { p?.currentMediaItem } catch (_: Throwable) { null }
            val remainingFallbacks = MediaItemMapper.getFallbackUrls(currentItem)

            if (remainingFallbacks.isNotEmpty()) {
                val nextUrl = remainingFallbacks.first()
                val stillRemaining = remainingFallbacks.drop(1)
                val currentPos = p?.currentPosition ?: 0L
                val currentSession = playbackSessionId

                Log.w(TAG, "JIOSAAVN_QUALITY_FALLBACK attempting nextUrl=${nextUrl.takeLast(20)} remainingFallbacks=${stillRemaining.size}")

                val fallbackItem = ResolvedPlaybackItemFactory.createMediaItem(
                    track = currentEntry.track,
                    streamUrl = nextUrl,
                    queueEntryId = currentEntry.queueId,
                    canonicalMediaKey = CanonicalMediaKey.fromTrack(currentEntry.track),
                    sessionId = currentSession,
                    sourceContext = "JIOSAAVN_QUALITY_FALLBACK",
                    playbackOrigin = currentEntry.playbackOrigin,
                    // Detect quality from URL suffix for accurate metadata
                    formatId = when {
                        nextUrl.contains("_160.mp4") -> "jiosaavn_aac_160"
                        nextUrl.contains("_96.mp4")  -> "jiosaavn_aac_96"
                        else -> null
                    },
                    fallbackUrls = stillRemaining
                )
                dispatchOnPlayerLooper { activePlayer ->
                    activePlayer.setMediaItem(fallbackItem, currentPos)
                    activePlayer.prepare()
                    activePlayer.play()
                }
                return
            }
        }

        if (is403 && videoId != null && StreamUrlCache.canRetry403(videoId)) {
            Log.w(TAG, "HTTP 403 encountered for videoId=$videoId. Attempting single retry with fresh stream URL.")

            val p = player
            val currentPos = p?.currentPosition ?: 0L
            val currentSession = playbackSessionId
            val scope = serviceScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch(ioDispatcher) {
                val freshResult = streamResolver.resolveStream(
                    canonicalYoutubeUrl = "https://www.youtube.com/watch?v=$videoId",
                    title = currentEntry.track.title,
                    artist = currentEntry.track.artist,
                    isPrefetch = false,
                    expectedDurationMs = currentEntry.track.durationMs
                )
                if (currentSession != playbackSessionId) return@launch
                if (freshResult.isSuccess) {
                    val freshStream = freshResult.getOrThrow()
                    val freshItem = ResolvedPlaybackItemFactory.createMediaItem(
                        track = currentEntry.track,
                        streamUrl = freshStream.streamUrl,
                        queueEntryId = currentEntry.queueId,
                        canonicalMediaKey = CanonicalMediaKey.fromTrack(currentEntry.track),
                        sessionId = currentSession,
                        sourceContext = "RETRY_403",
                        playbackOrigin = currentEntry.playbackOrigin,
                        formatId = freshStream.formatId
                    )
                    withContext(mainDispatcher) {
                        dispatchOnPlayerLooper { activePlayer ->
                            activePlayer.setMediaItem(freshItem, currentPos)
                            activePlayer.prepare()
                            activePlayer.play()
                        }
                    }
                    return@launch
                }
                Log.e(TAG, "HTTP 403 retry resolution failed for $videoId. Skipping to next.")
                withContext(mainDispatcher) {
                    handleNextAction()
                }
            }
            return
        }

        // Skip current failed track and advance safely
        handleNextAction()
    }

    override fun handleNextAction(): Boolean {
        isABRepeatActive = false
        abRepeatStartMs = 0L
        if (SleepTimerManager.sleepTimerState.value is SleepTimerState.EndOfTrack) {
            Log.i(TAG, "handleNextAction intercepted by SleepTimer EndOfTrack; intentionally stopping playback")
            return false
        }
        val p = player
        val sessionId = playbackSessionId

        // If player already has the next item loaded in its timeline
        if (p != null && p.hasNextMediaItem()) {
            dispatchOnPlayerLooper {
                it.seekToNextMediaItem()
                it.play()
            }
            return true
        }

        // If at the end and shuffle is on, reshuffle without repeats
        if (activeShuffleMode != ShuffleMode.OFF && originalPlaylistEntries.size > 1 && currentIndex + 1 >= queueDescriptors.size) {
            val currentEntry = queueDescriptors.getOrNull(currentIndex)
            val currentKey = currentEntry?.let { CanonicalMediaKey.fromTrack(it.track) }
            val nextBatch = originalPlaylistEntries.filter {
                CanonicalMediaKey.fromTrack(it.track) != currentKey
            }.shuffled()
            synchronized(queueDescriptors) {
                queueDescriptors.addAll(nextBatch)
            }
            _coordinatorState.update { it.copy(queue = queueDescriptors.toList()) }
            if (activeShuffleMode == ShuffleMode.SMART_SHUFFLE) {
                scheduleSmartShuffleRecommendations(sessionId)
            }
        }

        // If not in timeline, check if there are remaining descriptors in queue
        if (currentIndex + 1 >= queueDescriptors.size) {
            if (isAutoplayEnabled && isNetworkConnected()) {
                val currentEntry = synchronized(queueDescriptors) { queueDescriptors.getOrNull(currentIndex) }
                if (currentEntry != null && p != null) {
                    _coordinatorState.update {
                        it.copy(isPreparingNext = true, message = "Preparing next song…")
                    }
                    pendingEndedSessionId = sessionId
                    radioWindowManager.triggerReplenish(currentEntry.track, sessionId, p)
                    return true
                }
            }
            // Truly exhausted queue
            if (!isNetworkConnected()) {
                _coordinatorState.update { it.copy(message = "No more downloaded songs available offline.") }
            } else {
                com.notify.ui.SnackbarManager.tryEmit(com.notify.ui.SnackbarEvent.QueueExhausted)
            }
            return false
        }

        // Successor exists in descriptors, but lookahead hasn't finished appending it!
        _coordinatorState.update {
            it.copy(isPreparingNext = true, message = "Preparing next song…")
        }

        val scope = serviceScope ?: CoroutineScope(Dispatchers.Main)
        scope.launch(ioDispatcher) {
            val nextDescriptorIndex = findNextPlayableDescriptorIndex(currentIndex + 1)
            if (nextDescriptorIndex in queueDescriptors.indices) {
                val entry = queueDescriptors[nextDescriptorIndex]
                val item = resolveDescriptor(entry, sessionId)

                if (sessionId != playbackSessionId) return@launch

                if (item != null) {
                    consecutiveResolveFailures = 0
                    failedTrackKey = null
                    withContext(Dispatchers.Main) {
                        _coordinatorState.update {
                            it.copy(isPreparingNext = false, message = null)
                        }
                        // Fix 3: Guard against double-append for radio entries.
                        // RadioWindowManager.resolveAndAppendImmediateNext may have already
                        // appended this track; queuedKeys is the single source of truth.
                        val itemKey = MediaItemMapper.getCanonicalMediaKey(item)
                        val isRadioItem = entry.origin == QueueOrigin.RADIO
                        val alreadyQueuedByRadio = isRadioItem && itemKey != null &&
                            radioWindowManager.isKeyQueued(itemKey)

                        if (alreadyQueuedByRadio) {
                            Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$itemKey reason=handleNextAction_already_queued")
                            dispatchOnPlayerLooper { activePlayer ->
                                if (activePlayer.hasNextMediaItem()) {
                                    activePlayer.seekToNextMediaItem()
                                    activePlayer.play()
                                }
                            }
                        } else {
                            dispatchOnPlayerLooper { activePlayer ->
                                // Defense-in-depth: also scan timeline for duplicate before inserting
                                val existingIndex = if (itemKey != null) {
                                    (0 until activePlayer.mediaItemCount).firstOrNull {
                                        MediaItemMapper.getCanonicalMediaKey(activePlayer.getMediaItemAt(it)) == itemKey
                                    }
                                } else null

                                if (existingIndex != null) {
                                    Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$itemKey reason=handleNextAction_in_timeline")
                                    if (existingIndex != activePlayer.currentMediaItemIndex) {
                                        activePlayer.seekTo(existingIndex, 0L)
                                    } else if (activePlayer.hasNextMediaItem()) {
                                        activePlayer.seekToNextMediaItem()
                                    }
                                } else {
                                    activePlayer.addMediaItem(item)
                                    activePlayer.seekTo(activePlayer.mediaItemCount - 1, 0L)
                                }
                                activePlayer.play()
                            }
                        }
                    }
                    nextContiguousAppendIndex = nextDescriptorIndex + 1
                    triggerLookaheadReplenishment(sessionId)
                } else {
                    // FIX 4: User-facing retry and auto-skip after 2 consecutive failures
                    val entryKey = CanonicalMediaKey.fromTrack(entry.track)
                    val failures = if (failedTrackKey == entryKey) consecutiveResolveFailures + 1 else 1
                    failedTrackKey = entryKey
                    consecutiveResolveFailures = failures

                    if (failures < 2) {
                        Log.w(TAG, "Resolution failed for ${entry.track.title} (attempt 1/2). Retrying once...")
                        withContext(Dispatchers.Main) {
                            _coordinatorState.update {
                                it.copy(isPreparingNext = true, message = "Couldn't load track. Retrying…")
                            }
                        }
                        val retryItem = resolveDescriptor(entry, sessionId)
                        if (sessionId != playbackSessionId) return@launch
                        if (retryItem != null) {
                            consecutiveResolveFailures = 0
                            failedTrackKey = null
                            withContext(Dispatchers.Main) {
                                _coordinatorState.update {
                                    it.copy(isPreparingNext = false, message = null)
                                }
                                // Fix 3: Guard against double-append for radio entries (retry path).
                                val retryKey = MediaItemMapper.getCanonicalMediaKey(retryItem)
                                val isRadioEntry2 = entry.origin == QueueOrigin.RADIO
                                val alreadyQueuedRetry = isRadioEntry2 && retryKey != null &&
                                    radioWindowManager.isKeyQueued(retryKey)

                                if (alreadyQueuedRetry) {
                                    Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$retryKey reason=retry_already_queued")
                                    dispatchOnPlayerLooper { activePlayer ->
                                        if (activePlayer.hasNextMediaItem()) {
                                            activePlayer.seekToNextMediaItem()
                                            activePlayer.play()
                                        }
                                    }
                                } else {
                                    dispatchOnPlayerLooper { activePlayer ->
                                        val existingIndex = if (retryKey != null) {
                                            (0 until activePlayer.mediaItemCount).firstOrNull {
                                                MediaItemMapper.getCanonicalMediaKey(activePlayer.getMediaItemAt(it)) == retryKey
                                            }
                                        } else null

                                        if (existingIndex != null) {
                                            Log.w(TAG, "RADIO_APPEND_SKIPPED_DUPLICATE key=$retryKey reason=retry_in_timeline")
                                            if (existingIndex != activePlayer.currentMediaItemIndex) {
                                                activePlayer.seekTo(existingIndex, 0L)
                                            } else if (activePlayer.hasNextMediaItem()) {
                                                activePlayer.seekToNextMediaItem()
                                            }
                                        } else {
                                            activePlayer.addMediaItem(retryItem)
                                            activePlayer.seekTo(activePlayer.mediaItemCount - 1, 0L)
                                        }
                                        activePlayer.play()
                                    }
                                }
                            }
                            nextContiguousAppendIndex = nextDescriptorIndex + 1
                            triggerLookaheadReplenishment(sessionId)
                            return@launch
                        }
                    }

                    // Auto-skip after 2 consecutive failures
                    Log.w(TAG, "AUTO_SKIP_CONSECUTIVE_FAILURE key=$entryKey title=\"${entry.track.title}\" (consecutive=$failures). Skipping to next track.")
                    withContext(Dispatchers.Main) {
                        _coordinatorState.update {
                            it.copy(isPreparingNext = false, message = "Couldn't load track. Skipping to next…")
                        }
                        currentIndex = nextDescriptorIndex
                        handleNextAction()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    val msg = if (!isNetworkConnected()) "No more downloaded songs available offline." else null
                    _coordinatorState.update { it.copy(isPreparingNext = false, message = msg) }
                }
            }
        }
        return true
    }

    override fun handlePreviousAction(): Boolean {
        isABRepeatActive = false
        abRepeatStartMs = 0L
        val p = player

        // If played more than 3 seconds, restart current track
        if (p != null && p.currentPosition > 3000L) {
            dispatchOnPlayerLooper { it.seekTo(0L) }
            return true
        }

        if (p != null && p.hasPreviousMediaItem()) {
            dispatchOnPlayerLooper { it.seekToPreviousMediaItem() }
            return true
        }

        // If predecessor descriptor exists in queueDescriptors (e.g. user started at track 2)
        if (currentIndex > 0) {
            val prevIndex = currentIndex - 1
            val prevEntry = queueDescriptors.getOrNull(prevIndex)
            if (prevEntry != null) {
                val scope = serviceScope ?: CoroutineScope(Dispatchers.Main)
                val sessionId = playbackSessionId
                scope.launch(ioDispatcher) {
                    val item = resolveDescriptor(prevEntry, sessionId)
                    if (sessionId != playbackSessionId) return@launch

                    if (item != null) {
                        withContext(Dispatchers.Main) {
                            dispatchOnPlayerLooper { activePlayer ->
                                activePlayer.stop()
                                activePlayer.clearMediaItems()
                                activePlayer.setMediaItems(listOf(item), 0, 0L)
                                activePlayer.prepare()
                                activePlayer.play()
                            }
                            currentIndex = prevIndex
                            nextContiguousAppendIndex = prevIndex + 1
                            _coordinatorState.update {
                                it.copy(currentIndex = prevIndex, currentTrack = prevEntry.track)
                            }
                        }
                        persistSnapshot()
                        triggerLookaheadReplenishment(sessionId)
                    }
                }
                return true
            }
        }

        dispatchOnPlayerLooper { it.seekTo(0L) }
        return true
    }

    // ── Shuffle Mode & Smart Shuffle Pipeline ──────────────────────────────────

    override fun setShuffleMode(mode: ShuffleMode) {
        if (activeShuffleMode == mode) return
        val oldMode = activeShuffleMode
        activeShuffleMode = mode

        val sessionId = playbackSessionId
        synchronized(this) {
            val currentEntry = queueDescriptors.getOrNull(currentIndex)
            val currentKey = currentEntry?.let { CanonicalMediaKey.fromTrack(it.track) }

            when (mode) {
                ShuffleMode.OFF -> {
                    // Restore original playlist ordering
                    if (originalPlaylistEntries.isNotEmpty()) {
                        val origIndex = originalPlaylistEntries.indexOfFirst {
                            val k = CanonicalMediaKey.fromTrack(it.track)
                            k == currentKey || it.queueId == currentEntry?.queueId
                        }.coerceAtLeast(0)

                        queueDescriptors.clear()
                        queueDescriptors.addAll(originalPlaylistEntries)
                        currentIndex = origIndex
                    }
                    smartShuffleInsertedKeys.clear()
                }
                ShuffleMode.SHUFFLE -> {
                    val baseEntries = if (originalPlaylistEntries.isNotEmpty()) originalPlaylistEntries else queueDescriptors.toList()
                    val remaining = baseEntries.filter {
                        val k = CanonicalMediaKey.fromTrack(it.track)
                        k != currentKey && !smartShuffleInsertedKeys.contains(k)
                    }.shuffled()

                    queueDescriptors.clear()
                    if (currentEntry != null) {
                        queueDescriptors.add(currentEntry)
                    }
                    queueDescriptors.addAll(remaining)
                    currentIndex = 0
                    smartShuffleInsertedKeys.clear()
                }
                ShuffleMode.SMART_SHUFFLE -> {
                    if (oldMode == ShuffleMode.OFF) {
                        val baseEntries = if (originalPlaylistEntries.isNotEmpty()) originalPlaylistEntries else queueDescriptors.toList()
                        val remaining = baseEntries.filter {
                            val k = CanonicalMediaKey.fromTrack(it.track)
                            k != currentKey && !smartShuffleInsertedKeys.contains(k)
                        }.shuffled()

                        queueDescriptors.clear()
                        if (currentEntry != null) {
                            queueDescriptors.add(currentEntry)
                        }
                        queueDescriptors.addAll(remaining)
                        currentIndex = 0
                    }
                    scheduleSmartShuffleRecommendations(sessionId)
                }
            }

            nextContiguousAppendIndex = currentIndex + 1

            // Purge lookahead buffer and remove queued items from ExoPlayer beyond current
            resolvedLookaheadMap.clear()
            val p = player
            if (p != null) {
                dispatchOnPlayerLooper { activePlayer ->
                    val count = activePlayer.mediaItemCount
                    val cur = activePlayer.currentMediaItemIndex
                    for (i in count - 1 downTo cur + 1) {
                        activePlayer.removeMediaItem(i)
                    }
                }
            }
        }

        _coordinatorState.update {
            it.copy(
                queue = queueDescriptors.toList(),
                currentIndex = currentIndex,
                shuffleMode = mode
            )
        }

        persistSnapshot()
        triggerLookaheadReplenishment(sessionId)
    }

    fun getShuffleMode(): ShuffleMode = activeShuffleMode

    override fun toggleShuffleMode(): Boolean {
        val nextMode = when (activeShuffleMode) {
            ShuffleMode.OFF -> ShuffleMode.SHUFFLE
            ShuffleMode.SHUFFLE -> ShuffleMode.SMART_SHUFFLE
            ShuffleMode.SMART_SHUFFLE -> ShuffleMode.OFF
        }
        setShuffleMode(nextMode)
        return true
    }

    private fun scheduleSmartShuffleRecommendations(targetSessionId: Long) {
        if (activeShuffleMode != ShuffleMode.SMART_SHUFFLE) return
        val scope = serviceScope ?: CoroutineScope(ioDispatcher)
        val currentEntry = synchronized(queueDescriptors) { queueDescriptors.getOrNull(currentIndex) } ?: return

        scope.launch(ioDispatcher) {
            if (targetSessionId != playbackSessionId || activeShuffleMode != ShuffleMode.SMART_SHUFFLE) return@launch
            if (!isNetworkConnected()) {
                Log.d(TAG, "Offline: Smart Shuffle skipping recommendation fetch (fallback to regular shuffle)")
                return@launch
            }

            val playlistKeys = originalPlaylistEntries.map { CanonicalMediaKey.fromTrack(it.track) }.toSet()
            val allExcludeKeys = playlistKeys + smartShuffleInsertedKeys

            val recommendations = radioWindowManager.getRecommendationsForSmartShuffle(
                seedTrack = currentEntry.track,
                excludeKeys = allExcludeKeys,
                count = 3
            )
            if (recommendations.isEmpty() || targetSessionId != playbackSessionId || activeShuffleMode != ShuffleMode.SMART_SHUFFLE) return@launch

            synchronized(this@PlaybackQueueCoordinator) {
                if (targetSessionId != playbackSessionId || activeShuffleMode != ShuffleMode.SMART_SHUFFLE) return@synchronized

                var insertPos = currentIndex + SMART_SHUFFLE_INTERVAL
                for (rec in recommendations) {
                    if (insertPos <= queueDescriptors.size) {
                        val key = CanonicalMediaKey.fromTrack(rec.track)
                        smartShuffleInsertedKeys.add(key)
                        queueDescriptors.add(insertPos, rec)
                        insertPos += (SMART_SHUFFLE_INTERVAL + 1)
                    } else {
                        val key = CanonicalMediaKey.fromTrack(rec.track)
                        smartShuffleInsertedKeys.add(key)
                        queueDescriptors.add(rec)
                        break
                    }
                }
            }

            _coordinatorState.update {
                it.copy(queue = queueDescriptors.toList())
            }
        }
    }

    // ── Descriptor Resolution Pipeline (Priorities 1 to 6) ─────────────────────

    private suspend fun resolveDescriptor(entry: QueueEntry, sessionId: Long): MediaItem? {
        val track = entry.track
        val queueEntryId = entry.queueId

        // Priority 1: Valid NotiFy Offline Download
        val completedDownload = downloadManager.getCompletedDownloadForTrack(track.id.rawId)
        val offlineUri = downloadManager.getOfflinePlaybackUri(track.id.rawId)
            ?: downloadManager.getOfflinePlaybackUriForSource(track.id.rawId)

        if (offlineUri != null) {
            Log.d(TAG, "Priority 1 hit: Offline file found for ${track.title} -> $offlineUri")
            val offlineTrack = track.copy(source = AudioSource.Local(offlineUri.toString()))
            val offlineKey = CanonicalMediaKey.fromTrack(offlineTrack)
            return MediaItemMapper.toMediaItem(
                track = offlineTrack,
                queueEntryId = queueEntryId,
                canonicalMediaKey = offlineKey,
                sessionId = sessionId,
                sourceContext = "OFFLINE"
            )
        }

        // Priority 2: Valid explicitly linked content URI
        val localSource = track.source
        if (localSource is AudioSource.Local) {
            val contentUriString = localSource.contentUriString
            val checker = LocalAudioAvailabilityChecker(context.contentResolver)
            when (checker.checkAvailability(Uri.parse(contentUriString))) {
                is LocalAudioResult.Success -> {
                    Log.d(TAG, "Priority 2 hit: Valid local content URI for ${track.title}")
                    val localKey = CanonicalMediaKey.fromTrack(track)
                    return MediaItemMapper.toMediaItem(
                        track = track,
                        queueEntryId = queueEntryId,
                        canonicalMediaKey = localKey,
                        sessionId = sessionId,
                        sourceContext = "LOCAL"
                    )
                }
                is LocalAudioResult.Failure -> {
                    Log.w(TAG, "Priority 2 miss: Local content URI revoked or missing for ${track.title}")
                }
            }
        }

        // In Airplane / Offline Mode: NEVER contact online resolver!
        if (!isNetworkConnected()) {
            Log.d(TAG, "Offline mode active: skipping online resolution for ${track.title}")
            return null
        }

        // Priority 3 & 4: Resolve through YouTube Music
        if (sessionId != playbackSessionId) return null
        try {
            var canonicalWatchUrl: String? = null
            var artworkUrl: String? = track.artworkUri
            var resolvedVideoId: String? = null

            val remoteSource = track.source
            if (remoteSource is AudioSource.Remote && remoteSource.provider == ProviderId.YOUTUBE) {
                canonicalWatchUrl = "https://www.youtube.com/watch?v=${remoteSource.sourceId}"
                resolvedVideoId = remoteSource.sourceId
            } else {
                // Check if Room has a selected source for this track
                val selectedSource = database.trackDao().getSelectedSource(track.id.rawId)
                if (selectedSource != null) {
                    canonicalWatchUrl = selectedSource.canonicalUrl
                    resolvedVideoId = selectedSource.sourceId
                    if (artworkUrl.isNullOrBlank()) artworkUrl = selectedSource.artworkUrl
                } else {
                    // Match via InnerTube
                    val query = "${track.artist} - ${track.title}"
                    val searchResult = innerTubeSearchProvider.search(query, limit = 5)
                    val candidates = searchResult.getOrNull().orEmpty()

                    val meta = SpotifyTrackMetadata(
                        id = track.id.rawId,
                        title = track.title,
                        artists = listOf(track.artist),
                        album = track.album,
                        releaseYear = null,
                        durationMs = track.durationMs,
                        artworkUrl = track.artworkUri
                    )

                    var match = if (candidates.isNotEmpty()) TrackMatchEngine.findBestMatch(meta, candidates) else null

                    // Priority 5: YouTube fallback with yt-dlp search if match confidence is low
                    if (match == null || match.matchScore < 0.5f) {
                        val fallbackRes = fallbackSearchProvider.search(query, limit = 3)
                        val fallbackCandidates = fallbackRes.getOrNull().orEmpty()
                        if (fallbackCandidates.isNotEmpty()) {
                            val fallbackMatch = TrackMatchEngine.findBestMatch(meta, fallbackCandidates)
                            if (fallbackMatch != null && (match == null || fallbackMatch.matchScore > match.matchScore)) {
                                match = fallbackMatch
                            }
                        }
                    }

                    if (match != null) {
                        canonicalWatchUrl = match.canonicalDownloadUrl
                        resolvedVideoId = match.candidate.videoId
                        if (artworkUrl.isNullOrBlank()) artworkUrl = match.candidate.artworkUrl

                        // Defensive upsert: ensure a tracks row exists before insertSource
                        // references it via FK. Covers non-YouTube tracks (CUSTOM_RESOLVER, etc.)
                        // that reach this path — insertTrack uses REPLACE so it's idempotent.
                        val existingTrack = database.trackDao().getTrackById(track.id.rawId)
                        if (existingTrack == null) {
                            database.trackDao().insertTrack(
                                TrackEntity(
                                    id = track.id.rawId,
                                    title = track.title,
                                    artist = track.artist,
                                    album = track.album,
                                    durationMs = track.durationMs,
                                    artworkUri = track.artworkUri,
                                    artworkUrl = track.artworkUri,
                                    resolutionState = ResolutionState.MATCHED,
                                    downloadState = DownloadState.NOT_DOWNLOADED
                                )
                            )
                        }

                        // Save selected source
                        val newSource = TrackSourceEntity(
                            sourceKey = "${track.id.rawId}:youtube",
                            trackId = track.id.rawId,
                            provider = match.candidate.provider,
                            sourceId = match.candidate.videoId,
                            canonicalUrl = match.canonicalDownloadUrl,
                            confidence = match.matchScore.toFloat(),
                            durationDeltaMs = match.durationDeltaMs,
                            artworkUrl = match.candidate.artworkUrl,
                            selected = true
                        )
                        database.trackDao().insertSource(newSource)
                        database.trackDao().updateResolutionState(track.id.rawId, ResolutionState.MATCHED)
                    }
                }
            }

            if (canonicalWatchUrl != null) {
                if (sessionId != playbackSessionId) return null
                val streamResult = streamResolver.resolveStream(
                    canonicalYoutubeUrl = canonicalWatchUrl,
                    title = track.title,
                    artist = track.artist,
                    isPrefetch = false,
                    expectedDurationMs = track.durationMs
                )
                if (streamResult.isSuccess) {
                    val stream = streamResult.getOrThrow()
                    val resolvedTrack = track.copy(artworkUri = artworkUrl)
                    val canonicalKey = if (!resolvedVideoId.isNullOrBlank()) {
                        CanonicalMediaKey.fromResolved("youtube", resolvedVideoId)
                    } else {
                        CanonicalMediaKey.fromTrack(resolvedTrack)
                    }
                    StreamUrlCache.put(canonicalKey, stream)
                    Log.d(TAG, "Priority 4/5 hit: Online stream resolved for ${track.title} (key=$canonicalKey)")
                    return ResolvedPlaybackItemFactory.createMediaItem(
                        track = resolvedTrack,
                        streamUrl = stream.streamUrl,
                        queueEntryId = queueEntryId,
                        canonicalMediaKey = canonicalKey,
                        sessionId = sessionId,
                        sourceContext = "PLAYLIST",
                        formatId = stream.formatId,
                        fallbackUrls = stream.fallbackUrls
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream resolution error for ${track.title}: ${e.message}")
        }

        // Priority 6: Typed failure
        return null
    }

    private suspend fun isEntryAvailableOffline(entry: QueueEntry): Boolean {
        if (entry.track.source is AudioSource.Local) return true
        val offlineUri = downloadManager.getOfflinePlaybackUri(entry.track.id.rawId)
            ?: downloadManager.getOfflinePlaybackUriForSource(entry.track.id.rawId)
        return offlineUri != null
    }

    private suspend fun findNextPlayableDescriptorIndex(startIndex: Int): Int {
        val networkAvailable = isNetworkConnected()
        for (i in startIndex until queueDescriptors.size) {
            val entry = queueDescriptors[i]
            if (networkAvailable || isEntryAvailableOffline(entry)) {
                return i
            }
        }
        return -1
    }

    // ── Snapshot & History ─────────────────────────────────────────────────────

    private fun persistSnapshot() {
        if (isResumptionInProgress) {
            Log.d(TAG, "persistSnapshot skipped: resumption in progress")
            return
        }
        val p = player
        if (p != null) {
            dispatchOnPlayerLooper { activePlayer ->
                if (isResumptionInProgress) return@dispatchOnPlayerLooper
                val pos = activePlayer.currentPosition.coerceAtLeast(0L)
                val repMode = when (activePlayer.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
                val isShuffled = activeShuffleMode != ShuffleMode.OFF
                val shuffleMode = activeShuffleMode
                val currentDescriptors = synchronized(queueDescriptors) { queueDescriptors.toList() }
                val currentIdx = currentIndex
                val autoplay = isAutoplayEnabled
                val seed = radioSeedSourceId

                val scope = serviceScope ?: CoroutineScope(ioDispatcher)
                synchronized(this) {
                    lastSnapshotPersistJob?.cancel()
                    lastSnapshotPersistJob = scope.launch(ioDispatcher) {
                        val snapshot = PlaybackSnapshot(
                            queue = currentDescriptors,
                            currentIndex = currentIdx,
                            currentPositionMs = pos,
                            repeatMode = repMode,
                            isShuffled = isShuffled,
                            isAutoplayEnabled = autoplay,
                            radioSeedSourceId = seed,
                            shuffleMode = shuffleMode
                        )
                        snapshotStore.saveSnapshot(snapshot)
                    }
                }
            }
        } else {
            val currentDescriptors = synchronized(queueDescriptors) { queueDescriptors.toList() }
            if (currentDescriptors.isEmpty()) return
            val currentIdx = currentIndex
            val autoplay = isAutoplayEnabled
            val seed = radioSeedSourceId
            val isShuffled = activeShuffleMode != ShuffleMode.OFF
            val shuffleMode = activeShuffleMode

            val scope = serviceScope ?: CoroutineScope(ioDispatcher)
            synchronized(this) {
                lastSnapshotPersistJob?.cancel()
                lastSnapshotPersistJob = scope.launch(ioDispatcher) {
                    val existing = snapshotStore.loadSnapshot()
                    val preservedPos = existing?.currentPositionMs ?: 0L
                    val snapshot = PlaybackSnapshot(
                        queue = currentDescriptors,
                        currentIndex = currentIdx,
                        currentPositionMs = preservedPos,
                        repeatMode = RepeatMode.OFF,
                        isShuffled = isShuffled,
                        isAutoplayEnabled = autoplay,
                        radioSeedSourceId = seed,
                        shuffleMode = shuffleMode
                    )
                    snapshotStore.saveSnapshot(snapshot)
                }
            }
        }
    }

    private suspend fun restoreSnapshotIfAvailable() {
        val snapshot = snapshotStore.loadSnapshot() ?: return
        if (snapshot.queue.isEmpty()) return

        val targetIndex = snapshot.currentIndex.coerceIn(0, snapshot.queue.size - 1)
        val rawPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L)
        val targetEntry = snapshot.queue.getOrNull(targetIndex)
        var targetPositionMs = rawPositionMs
        val trackDuration = targetEntry?.track?.durationMs ?: 0L
        if (trackDuration > 0L && (targetPositionMs >= trackDuration - 5000L || targetPositionMs >= trackDuration)) {
            targetPositionMs = 0L
        }

        // Attempt pre-resolution of target item (offline or local)
        val resolvedTargetItem = if (targetEntry != null) {
            val offlineUri = downloadManager.getOfflinePlaybackUri(targetEntry.track.id.rawId)
                ?: downloadManager.getOfflinePlaybackUriForSource(targetEntry.track.id.rawId)

            if (offlineUri != null) {
                val offlineTrack = targetEntry.track.copy(source = AudioSource.Local(offlineUri.toString()))
                val offlineKey = CanonicalMediaKey.fromTrack(offlineTrack)
                MediaItemMapper.toMediaItem(
                    track = offlineTrack,
                    queueEntryId = targetEntry.queueId,
                    canonicalMediaKey = offlineKey,
                    sessionId = 0L,
                    sourceContext = "OFFLINE"
                )
            } else if (targetEntry.track.source is AudioSource.Local) {
                val localUriString = (targetEntry.track.source as AudioSource.Local).contentUriString
                val checker = LocalAudioAvailabilityChecker(context.contentResolver)
                if (checker.checkAvailability(Uri.parse(localUriString)) is LocalAudioResult.Success) {
                    val localKey = CanonicalMediaKey.fromTrack(targetEntry.track)
                    MediaItemMapper.toMediaItem(
                        track = targetEntry.track,
                        queueEntryId = targetEntry.queueId,
                        canonicalMediaKey = localKey,
                        sessionId = 0L,
                        sourceContext = "LOCAL"
                    )
                } else null
            } else null
        } else null

        dispatchOnPlayerLooper { p ->
            // A late restore must not replace a newer user-selected song!
            if (playbackSessionId != 0L || p.currentMediaItem != null || queueDescriptors.isNotEmpty()) {
                Log.d(TAG, "restoreSnapshotIfAvailable: superseded by active user playback")
                return@dispatchOnPlayerLooper
            }

            Log.d(TAG, "Restoring playback snapshot with ${snapshot.queue.size} descriptors at index ${snapshot.currentIndex}")

            synchronized(this@PlaybackQueueCoordinator) {
                queueDescriptors.clear()
                queueDescriptors.addAll(snapshot.queue)
                originalPlaylistEntries.clear()
                originalPlaylistEntries.addAll(snapshot.queue)
                currentIndex = snapshot.currentIndex
                isAutoplayEnabled = snapshot.isAutoplayEnabled
                radioSeedSourceId = snapshot.radioSeedSourceId
                activeShuffleMode = snapshot.shuffleMode
            }

            _coordinatorState.update {
                it.copy(
                    queue = snapshot.queue,
                    currentIndex = snapshot.currentIndex,
                    currentTrack = snapshot.currentTrack,
                    isAutoplayEnabled = snapshot.isAutoplayEnabled,
                    shuffleMode = snapshot.shuffleMode,
                    isOffline = !isNetworkConnected(),
                    restoredPositionMs = targetPositionMs
                )
            }

            if (resolvedTargetItem != null && p.currentMediaItem == null && playbackSessionId == 0L) {
                p.setMediaItems(listOf(resolvedTargetItem), 0, targetPositionMs)
                p.prepare()
                p.pause()
                Log.i(TAG, "SNAPSHOT_PREPARED_ON_PLAYER index=$targetIndex pos=$targetPositionMs")
            }
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val startSessionId = playbackSessionId

        val activePlayer = player
        if (activePlayer != null && activePlayer.currentMediaItem != null && activePlayer.mediaItemCount > 0) {
            Log.d(TAG, "onPlaybackResumption: active playback already present on player (${activePlayer.currentMediaItem?.mediaId}). Re-using existing item.")
            future.set(MediaSession.MediaItemsWithStartPosition(
                listOf(activePlayer.currentMediaItem!!),
                activePlayer.currentMediaItemIndex,
                activePlayer.currentPosition
            ))
            return future
        }

        isResumptionInProgress = true
        val scope = serviceScope ?: CoroutineScope(ioDispatcher)
        val job = scope.launch(ioDispatcher) {
            try {
                val snapshot = snapshotStore.loadSnapshot()
                if (snapshot == null || snapshot.queue.isEmpty()) {
                    Log.d(TAG, "onPlaybackResumption: no valid snapshot found")
                    isResumptionInProgress = false
                    future.setException(NoSuchElementException("No playback snapshot found"))
                    return@launch
                }

                val targetIndex = snapshot.currentIndex.coerceIn(0, snapshot.queue.size - 1)
                val targetPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L)
                val targetEntry = snapshot.queue.getOrNull(targetIndex)
                if (targetEntry == null) {
                    Log.w(TAG, "onPlaybackResumption: target entry at index $targetIndex is null")
                    isResumptionInProgress = false
                    future.setException(NoSuchElementException("Target snapshot entry is null"))
                    return@launch
                }

                Log.d(TAG, "onPlaybackResumption: snapshot targetIndex=$targetIndex, track=\"${targetEntry.track.title}\" (${targetEntry.track.id.rawId}), posMs=$targetPositionMs, queueSize=${snapshot.queue.size}")

                val restoredEntries = mutableListOf<QueueEntry>()
                val restoredMediaItems = mutableListOf<MediaItem>()
                var resolvedTargetTimelineIndex = -1

                for ((idx, entry) in snapshot.queue.withIndex()) {
                    if (idx == targetIndex) {
                        // The track that was actually playing when snapshot was saved
                        val resolvedTarget = resolveDescriptor(entry, startSessionId)
                        if (resolvedTarget != null) {
                            resolvedTargetTimelineIndex = restoredMediaItems.size
                            restoredEntries.add(entry)
                            restoredMediaItems.add(resolvedTarget)
                        } else {
                            Log.w(TAG, "onPlaybackResumption: failed to resolve target playing track: ${entry.track.title}")
                        }
                    } else {
                        // Other tracks in queue: restore if offline or local
                        val track = entry.track
                        val offlineUri = downloadManager.getOfflinePlaybackUri(track.id.rawId)
                            ?: downloadManager.getOfflinePlaybackUriForSource(track.id.rawId)

                        if (offlineUri != null) {
                            val offlineTrack = track.copy(source = AudioSource.Local(offlineUri.toString()))
                            val offlineKey = CanonicalMediaKey.fromTrack(offlineTrack)
                            val item = MediaItemMapper.toMediaItem(
                                track = offlineTrack,
                                queueEntryId = entry.queueId,
                                canonicalMediaKey = offlineKey,
                                sessionId = startSessionId,
                                sourceContext = "OFFLINE"
                            )
                            restoredEntries.add(entry.copy(track = offlineTrack))
                            restoredMediaItems.add(item)
                        } else if (track.source is AudioSource.Local) {
                            val localUriString = (track.source as AudioSource.Local).contentUriString
                            val checker = LocalAudioAvailabilityChecker(context.contentResolver)
                            if (checker.checkAvailability(Uri.parse(localUriString)) is LocalAudioResult.Success) {
                                val localKey = CanonicalMediaKey.fromTrack(track)
                                val item = MediaItemMapper.toMediaItem(
                                    track = track,
                                    queueEntryId = entry.queueId,
                                    canonicalMediaKey = localKey,
                                    sessionId = startSessionId,
                                    sourceContext = "LOCAL"
                                )
                                restoredEntries.add(entry)
                                restoredMediaItems.add(item)
                            }
                        }
                    }
                }

                // If target track failed to resolve, do NOT apply its positionMs to a different track!
                if (resolvedTargetTimelineIndex < 0 || restoredMediaItems.isEmpty()) {
                    Log.w(TAG, "onPlaybackResumption: target track could not be resolved; aborting resumption to avoid playing incorrect track")
                    isResumptionInProgress = false
                    future.setException(IllegalStateException("Target playing track failed to resolve"))
                    return@launch
                }

                val finalTargetIndex = resolvedTargetTimelineIndex

                val finishAction = {
                    val p = player
                    // Guard: A late restore must not replace a newer user-selected song!
                    // Only supersede if the user explicitly started a NEW session (new track/queue selection)
                    if (playbackSessionId != startSessionId ||
                        (p != null && p.currentMediaItem != null && p.mediaItemCount > 0 && !isResumptionInProgress)
                    ) {
                        Log.w(TAG, "onPlaybackResumption: late restore superseded by active user selection")
                        isResumptionInProgress = false
                        future.setException(IllegalStateException("Superseded by user selection"))
                    } else {
                        val restoredEntry = restoredEntries[finalTargetIndex]
                        val initialKey = CanonicalMediaKey.fromTrack(restoredEntry.track)

                        val fullQueue = snapshot.queue.map { qEntry ->
                            restoredEntries.firstOrNull { it.queueId == qEntry.queueId } ?: qEntry
                        }
                        val fullQueueCurrentIndex = fullQueue.indexOfFirst { it.queueId == restoredEntry.queueId }.coerceAtLeast(0)

                        synchronized(this@PlaybackQueueCoordinator) {
                            queueDescriptors.clear()
                            queueDescriptors.addAll(fullQueue)
                            currentIndex = fullQueueCurrentIndex
                            nextContiguousAppendIndex = fullQueueCurrentIndex + 1
                            isAutoplayEnabled = snapshot.isAutoplayEnabled
                            radioSeedSourceId = snapshot.radioSeedSourceId
                            lastTransitionedKey = initialKey
                            lastTransitionedSessionId = startSessionId
                            lastHandledQueueEntryId = restoredEntry.queueId
                            lastSideEffectProcessedKey = "$startSessionId:${restoredEntry.queueId}"
                        }

                        _coordinatorState.update {
                            it.copy(
                                queue = fullQueue,
                                currentIndex = fullQueueCurrentIndex,
                                currentTrack = restoredEntry.track,
                                isAutoplayEnabled = snapshot.isAutoplayEnabled,
                                message = null,
                                isPreparingNext = false,
                                isOffline = !isNetworkConnected()
                            )
                        }

                        val restoredItem = restoredMediaItems[finalTargetIndex]
                        var effectivePositionMs = targetPositionMs
                        val targetTrackDuration = restoredEntry.track.durationMs
                        if (targetTrackDuration > 0L) {
                            if (effectivePositionMs >= targetTrackDuration - 5000L || effectivePositionMs >= targetTrackDuration) {
                                Log.i(TAG, "onPlaybackResumption: targetPositionMs=$effectivePositionMs is near or past track duration=$targetTrackDuration; starting from 0:00")
                                effectivePositionMs = 0L
                            }
                        }

                        // Check against re-resolved stream duration if available
                        val resolvedStream = StreamUrlCache.get(initialKey)
                        val resolvedStreamDuration = resolvedStream?.durationMs
                        if (resolvedStreamDuration != null && resolvedStreamDuration > 0L) {
                            if (targetTrackDuration > 0L && Math.abs(resolvedStreamDuration - targetTrackDuration) > 15000L) {
                                Log.w(TAG, "onPlaybackResumption: resolved stream duration ($resolvedStreamDuration ms) deviates significantly from expected track duration ($targetTrackDuration ms); starting from 0:00")
                                effectivePositionMs = 0L
                            } else if (effectivePositionMs >= resolvedStreamDuration - 5000L || effectivePositionMs >= resolvedStreamDuration) {
                                Log.i(TAG, "onPlaybackResumption: targetPositionMs=$effectivePositionMs is near or past resolved stream duration=$resolvedStreamDuration; starting from 0:00")
                                effectivePositionMs = 0L
                            }
                        }

                        Log.i(TAG, "RESUMPTION_RESOLVED session=$startSessionId mediaId=${restoredItem.mediaId} timelineSize=${restoredMediaItems.size} startIndex=$finalTargetIndex posMs=$effectivePositionMs track=\"${restoredEntry.track.title}\"")

                        // Re-arm RadioWindowManager for the resumed session
                        val activeP = player ?: p
                        if (activeP != null) {
                            radioWindowManager.onTrackStarted(restoredEntry, startSessionId, activeP, shouldReplenish = false)
                            val remainingUpcoming = (fullQueue.size - 1) - fullQueueCurrentIndex
                            if (remainingUpcoming <= 2 && snapshot.isAutoplayEnabled && isNetworkConnected()) {
                                radioWindowManager.triggerReplenish(restoredEntry.track, startSessionId, activeP)
                            }
                        }

                        future.set(MediaSession.MediaItemsWithStartPosition(restoredMediaItems, finalTargetIndex, effectivePositionMs))
                        // Safety timeout to prevent permanently blocking snapshot saves if player never reaches STATE_READY
                        scope.launch(mainDispatcher) {
                            kotlinx.coroutines.delay(10000L)
                            if (isResumptionInProgress) {
                                Log.d(TAG, "Resumption timeout guard fired; clearing isResumptionInProgress")
                                isResumptionInProgress = false
                            }
                        }
                    }
                }

                val p = player
                if (p != null) {
                    val looper = try {
                        p.applicationLooper
                    } catch (_: Throwable) {
                        null
                    }
                    if (looper != null && Looper.myLooper() != looper) {
                        Handler(looper).post { finishAction() }
                    } else {
                        finishAction()
                    }
                } else {
                    finishAction()
                }
            } catch (e: Exception) {
                Log.w(TAG, "onPlaybackResumption failed: ${e.message}", e)
                isResumptionInProgress = false
                future.setException(e)
            }
        }
        lastResumptionJob = job

        return future
    }

    @get:androidx.annotation.VisibleForTesting
    var lastResumptionJob: Job? = null
        private set

    @get:androidx.annotation.VisibleForTesting
    var lastRecentJob: Job? = null
        private set

    private fun recordSearchRecentIfEligible(
        track: Track,
        mediaItem: MediaItem? = null,
        origin: PlaybackOrigin
    ): Job? {
        if (origin != PlaybackOrigin.USER_SEARCH_SELECTION && origin != PlaybackOrigin.SEARCH_HISTORY_SELECTION) {
            Log.d(TAG, "RECENT_SKIPPED origin=$origin")
            return null
        }

        val scope = serviceScope ?: CoroutineScope(ioDispatcher)
        val job = scope.launch(ioDispatcher) {
            try {
                // Priority 1: Canonical media key from MediaItem extras
                var canonicalKey = MediaItemMapper.getCanonicalMediaKey(mediaItem)
                var canonicalProvider = "YOUTUBE"
                var canonicalSourceId: String? = null

                if (canonicalKey != null) {
                    val parts = canonicalKey.split(":", limit = 2)
                    canonicalProvider = parts[0].uppercase()
                    canonicalSourceId = parts.getOrNull(1)
                }

                // Priority 2: Selected source in Room track_sources
                if (canonicalSourceId.isNullOrBlank()) {
                    val selectedSource = database.trackDao().getSelectedSource(track.id.rawId)
                    if (selectedSource != null && selectedSource.sourceId.isNotBlank()) {
                        canonicalProvider = selectedSource.provider.uppercase()
                        canonicalSourceId = selectedSource.sourceId
                        canonicalKey = CanonicalMediaKey.fromResolved(canonicalProvider, canonicalSourceId)
                    }
                }

                // Priority 3: Native YouTube or Remote source
                if (canonicalSourceId.isNullOrBlank()) {
                    val src = track.source
                    if (src is AudioSource.Remote) {
                        canonicalProvider = src.provider.name
                        canonicalSourceId = src.sourceId
                        canonicalKey = CanonicalMediaKey.fromResolved(canonicalProvider, canonicalSourceId)
                    } else if (track.id.provider == ProviderId.YOUTUBE) {
                        canonicalProvider = ProviderId.YOUTUBE.name
                        canonicalSourceId = track.id.rawId
                        canonicalKey = CanonicalMediaKey.fromResolved(canonicalProvider, canonicalSourceId)
                    }
                }

                // Priority 4: Fallback to CanonicalMediaKey.fromTrack
                if (canonicalSourceId.isNullOrBlank() || canonicalKey.isNullOrBlank()) {
                    canonicalKey = CanonicalMediaKey.fromTrack(track)
                    canonicalProvider = track.id.provider.name
                    canonicalSourceId = track.id.rawId
                }

                val finalId = canonicalKey
                val recentItem = RecentSearchItemEntity(
                    id = finalId,
                    provider = canonicalProvider,
                    providerSourceId = canonicalSourceId,
                    catalogTrackId = if (track.id.provider != ProviderId.YOUTUBE) track.id.rawId else null,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = if (track.durationMs > 0L) track.durationMs else null,
                    artworkUrl = track.artworkUri,
                    itemType = "TRACK",
                    lastInteractedAtEpochMs = System.currentTimeMillis(),
                    sourceContext = "SEARCH"
                )

                database.searchHistoryDao().recordRecentItem(recentItem)
                Log.i(TAG, "RECENT_UPSERT origin=$origin key=$finalId")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed recording recent search item: ${e.message}", e)
            }
        }
        lastRecentJob = job
        return job
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        isAutoplayEnabled = enabled
        _coordinatorState.update { it.copy(isAutoplayEnabled = enabled) }
        persistSnapshot()
    }

    fun toggleAutoplay() {
        setAutoplayEnabled(!isAutoplayEnabled)
    }

    private fun extractVideoId(track: Track): String? {
        val source = track.source
        return when {
            source is AudioSource.Remote && source.provider == ProviderId.YOUTUBE -> source.sourceId
            track.id.provider == ProviderId.YOUTUBE -> track.id.rawId
            else -> null
        }
    }

    fun getQueueDescriptors(): List<QueueEntry> = queueDescriptors.toList()
    fun getCurrentIndex(): Int = currentIndex
    fun getCurrentPlaybackSessionId(): Long = playbackSessionId

    // ── Android Auto / MediaBrowser Callbacks ───────────────────────────────────

    override fun onGetLibraryRoot(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return mediaTreeProvider.getRoot(params)
    }

    override fun onGetChildren(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val scope = serviceScope ?: CoroutineScope(ioDispatcher)
        return mediaTreeProvider.getChildren(parentId, page, pageSize, params, scope)
    }

    override fun onGetItem(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val scope = serviceScope ?: CoroutineScope(ioDispatcher)
        return mediaTreeProvider.getItem(mediaId, scope)
    }

    override fun onSubscribe(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition>? {
        if (mediaItems.isEmpty()) return null
        val targetItem = mediaItems.firstOrNull() ?: return null
        if (!mediaTreeProvider.isContentTreeMediaId(targetItem.mediaId)) {
            return null
        }

        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val scope = serviceScope ?: CoroutineScope(ioDispatcher)

        scope.launch(ioDispatcher) {
            try {
                val (queueEntries, targetIndex) = mediaTreeProvider.buildQueueForMediaId(targetItem.mediaId)
                if (queueEntries.isEmpty()) {
                    future.setException(NoSuchElementException("No playable items for ${targetItem.mediaId}"))
                    return@launch
                }

                val clampedIndex = targetIndex.coerceIn(0, queueEntries.size - 1)
                val targetEntry = queueEntries[clampedIndex]

                val startSessionId = synchronized(this@PlaybackQueueCoordinator) {
                    playbackSessionId++
                    playbackSessionId
                }

                synchronized(this@PlaybackQueueCoordinator) {
                    prefetchJob?.cancel()
                    prefetchJob = null
                    activeReplenishmentKey = null
                    activeWatchNextKey = null
                    resolvedLookaheadMap.clear()
                    originalPlaylistEntries.clear()
                    originalPlaylistEntries.addAll(queueEntries)
                    smartShuffleInsertedKeys.clear()

                    queueDescriptors.clear()
                    queueDescriptors.addAll(queueEntries)
                    currentIndex = clampedIndex
                    nextContiguousAppendIndex = clampedIndex + 1

                    val src = targetEntry.track.source
                    radioSeedSourceId = when (src) {
                        is AudioSource.Remote -> src.sourceId
                        else -> targetEntry.track.id.rawId
                    }
                }

                val resolvedItem = resolveDescriptor(targetEntry, startSessionId)
                if (resolvedItem == null) {
                    future.setException(IllegalStateException("Could not resolve track ${targetEntry.track.title}"))
                    return@launch
                }

                val initialKey = CanonicalMediaKey.fromTrack(targetEntry.track)
                synchronized(this@PlaybackQueueCoordinator) {
                    lastTransitionedKey = initialKey
                    lastTransitionedSessionId = startSessionId
                    lastHandledQueueEntryId = targetEntry.queueId
                    lastSideEffectProcessedKey = "$startSessionId:${targetEntry.queueId}"
                }

                _coordinatorState.update {
                    it.copy(
                        queue = queueEntries,
                        currentIndex = clampedIndex,
                        currentTrack = targetEntry.track,
                        message = null,
                        isPreparingNext = false,
                        isOffline = !isNetworkConnected()
                    )
                }

                Log.i(TAG, "ANDROID_AUTO_PLAY session=$startSessionId mediaId=${resolvedItem.mediaId} title=\"${targetEntry.track.title}\"")

                val finishAction = {
                    future.set(MediaSession.MediaItemsWithStartPosition(listOf(resolvedItem), 0, 0L))
                }

                val p = player
                if (p != null) {
                    val looper = try { p.applicationLooper } catch (_: Throwable) { null }
                    if (looper != null && Looper.myLooper() != looper) {
                        Handler(looper).post { finishAction() }
                    } else {
                        finishAction()
                    }
                } else {
                    finishAction()
                }

                persistSnapshot()
                triggerLookaheadReplenishment(startSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed onSetMediaItems for ${targetItem.mediaId}: ${e.message}", e)
                future.setException(e)
            }
        }

        return future
    }
}
