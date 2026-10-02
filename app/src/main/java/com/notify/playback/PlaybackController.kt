package com.notify.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.notify.core.local.LocalAudioAvailabilityChecker
import com.notify.core.local.LocalAudioResult
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackError
import com.notify.core.model.RepeatMode
import com.notify.core.model.ShuffleMode
import com.notify.core.model.Track
import com.notify.core.playback.MediaItemMapper
import com.notify.core.playback.NotiFyPlaybackService
import com.notify.core.playback.PlaybackErrorMapper
import com.notify.core.playback.PlaybackStateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log

/**
 * App-side controller managing the lifecycle of a single MediaController instance.
 * Connects to NotiFyPlaybackService via SessionToken without creating a second ExoPlayer instance.
 */
class PlaybackController(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {

    private companion object {
        private const val TAG = "PlaybackController"
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _abRepeatState = MutableStateFlow(ABRepeatState())
    val abRepeatState: StateFlow<ABRepeatState> = _abRepeatState.asStateFlow()
    private var abRepeatLoopJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var tickerJob: Job? = null
    private var coordinatorJob: Job? = null

    init {
        coordinatorJob = coroutineScope.launch {
            PlaybackQueueCoordinator.getInstance(context).coordinatorState.collect { coordState ->
                _uiState.update { current ->
                    current.copy(
                        queueEntries = coordState.queue,
                        queue = if (coordState.queue.isNotEmpty()) coordState.queue.map { it.track } else current.queue,
                        currentTrack = coordState.currentTrack ?: current.currentTrack,
                        currentTrackIndex = if (coordState.currentIndex >= 0) coordState.currentIndex else current.currentTrackIndex,
                        currentIndex = if (coordState.currentIndex >= 0) coordState.currentIndex else current.currentIndex,
                        isAutoplayEnabled = coordState.isAutoplayEnabled,
                        isPreparingNext = coordState.isPreparingNext,
                        shuffleMode = coordState.shuffleMode,
                        shuffleEnabled = coordState.shuffleMode != ShuffleMode.OFF,
                        actionMessage = coordState.message,
                        isResolvingStream = coordState.isResolvingStream
                    )
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val controller = mediaController ?: return
            synchronizeFromController(controller)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            clearABRepeatInternal()
            synchronizeFromController(controller)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val controller = mediaController ?: return
            synchronizeFromController(controller)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController ?: return
            if (isPlaying && _abRepeatState.value.isLoopActive) {
                startABRepeatLoop()
            } else if (!isPlaying) {
                stopABRepeatLoop()
            }
            synchronizeFromController(controller)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController ?: return
            if (playbackState == Player.STATE_READY) {
                _uiState.update { it.copy(playbackError = null) }
            }
            synchronizeFromController(controller)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val controller = mediaController ?: return
            synchronizeFromController(controller)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val controller = mediaController ?: return
            synchronizeFromController(controller)
        }

        override fun onPlayerError(error: PlaybackException) {
            stopProgressTicker()
            val currentTrack = _uiState.value.currentTrack
            val currentUri = (currentTrack?.source as? AudioSource.Local)?.contentUriString
            val mappedError = PlaybackErrorMapper.fromPlaybackException(error, currentUri)

            _uiState.update { current ->
                current.copy(
                    isPlaying = false,
                    isBuffering = false,
                    playbackError = mappedError
                )
            }
        }
    }

    private val pendingControllerActions = mutableListOf<(MediaController) -> Unit>()

    /**
     * Executes an action on MediaController, ALWAYS on the controller's application looper.
     *
     * This is the ONLY safe entry-point for MediaController commands. All caller threads
     * (IO, Default, Main) are funnelled through Handler(controller.applicationLooper) so
     * Media3 never throws "MediaController method is called from a wrong thread".
     *
     * If the controller is not yet connected, the action is enqueued and will be drained
     * (also on applicationLooper) once the controller connects.
     */
    fun withConnectedController(action: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller != null) {
            dispatchOnControllerLooper(controller, action)
        } else {
            synchronized(pendingControllerActions) {
                pendingControllerActions.add(action)
            }
            connect()
        }
    }

    /**
     * Dispatches [action] on [controller]'s application looper, or immediately if already on it.
     * Falls back to the main looper when applicationLooper is null (e.g., in unit test mocks).
     */
    private fun dispatchOnControllerLooper(controller: MediaController, action: (MediaController) -> Unit) {
        val looper = controller.applicationLooper ?: Looper.getMainLooper()
        if (Looper.myLooper() == looper) {
            action(controller)
        } else {
            Handler(looper).post { action(controller) }
        }
    }

    /**
     * Suspends until the MediaController is connected, returning the active instance.
     * Resumes on the controller's applicationLooper.
     */
    suspend fun awaitConnectedController(): MediaController {
        val existing = mediaController
        if (existing != null) return existing
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            withConnectedController { controller ->
                if (cont.isActive) {
                    cont.resume(controller) { _, _, _ -> }
                }
            }
        }
    }

    /**
     * Test-only helper to simulate controller connection in unit tests without IPC.
     */
    internal fun simulateControllerConnected(controller: MediaController) {
        mediaController = controller
        _uiState.update { it.copy(isConnected = true, isControllerConnected = true) }
        PlaybackQueueCoordinator.getInstance(context).attachPlayerForTesting(controller, coroutineScope)
        val pending = synchronized(pendingControllerActions) {
            val list = pendingControllerActions.toList()
            pendingControllerActions.clear()
            list
        }
        pending.forEach { it(controller) }
    }

    /**
     * Connects to the NotiFyPlaybackService.
     * Prevents concurrent connection futures.
     */
    fun connect() {
        if (mediaController != null || controllerFuture != null) {
            return
        }

        val appContext = context.applicationContext
        try {
            val sessionToken = SessionToken(
                appContext,
                ComponentName(appContext, NotiFyPlaybackService::class.java)
            )

            val future = MediaController.Builder(appContext, sessionToken).buildAsync()
            controllerFuture = future

            future.addListener(
                {
                    try {
                        val controller = future.get()
                        mediaController = controller
                        controller.addListener(playerListener)
                        _uiState.update { it.copy(isConnected = true, isControllerConnected = true) }
                        // Immediately read the controller's existing state without waiting for a new event
                        synchronizeFromController(controller)
                        val pending = synchronized(pendingControllerActions) {
                            val list = pendingControllerActions.toList()
                            pendingControllerActions.clear()
                            list
                        }
                        Log.d(TAG, "MediaController connected; draining ${pending.size} pending actions")
                        // Drain pending actions on the applicationLooper — safe for all MediaController calls.
                        pending.forEach { pendingAction ->
                            dispatchOnControllerLooper(controller) { pendingAction(controller) }
                        }

                    } catch (e: Exception) {
                        // Log the full cause chain so it is visible in Logcat
                        val chain = buildCauseChain(e)
                        Log.e(TAG, "MediaController future.get() FAILED: $chain", e)
                        // Publish a visible connection error to the UI
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isControllerConnected = false,
                                playbackError = com.notify.core.model.PlaybackError.Unknown(
                                    message = "MediaController connection failed: $chain",
                                    cause = e
                                )
                            )
                        }
                        // Clear pending queue exactly once — do NOT invoke them (would be recursive)
                        val dropped = synchronized(pendingControllerActions) {
                            val count = pendingControllerActions.size
                            pendingControllerActions.clear()
                            count
                        }
                        if (dropped > 0) {
                            Log.w(TAG, "Dropped $dropped pending playback action(s) — controller never connected")
                        }
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        } catch (e: Exception) {
            val chain = buildCauseChain(e)
            Log.e(TAG, "SessionToken / MediaController.Builder FAILED: $chain", e)
            _uiState.update {
                it.copy(
                    isConnected = false,
                    isControllerConnected = false,
                    playbackError = com.notify.core.model.PlaybackError.Unknown(
                        message = "PlaybackService bind failed: $chain",
                        cause = e
                    )
                )
            }
            // Clear pending queue exactly once (no recursive invocation)
            val dropped = synchronized(pendingControllerActions) {
                val count = pendingControllerActions.size
                pendingControllerActions.clear()
                count
            }
            if (dropped > 0) {
                Log.w(TAG, "Dropped $dropped pending playback action(s) — service bind failed")
            }
        }
    }

    /**
     * Synchronizes and hydrates PlaybackUiState directly from a connected MediaController.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setMediaControllerForTesting(controller: MediaController?) {
        mediaController = controller
    }

    /**
     * Reads the current state of [controller] and mirrors it into [_uiState].
     * Call this on any controller callback to ensure UI state remains synchronized.
     */
    fun synchronizeFromController(controller: MediaController) {
        if (mediaController == null) {
            mediaController = controller
        }
        val coordinator = PlaybackQueueCoordinator.getInstance(context)
        val coordState = coordinator.coordinatorState.value

        val currentIndex = if (controller.currentMediaItemIndex == C.INDEX_UNSET) {
            -1
        } else {
            controller.currentMediaItemIndex
        }

        val duration = if (controller.duration == C.TIME_UNSET || controller.duration < 0) {
            0L
        } else {
            controller.duration
        }

        val position = controller.currentPosition.coerceAtLeast(0L)

        val queue = (0 until controller.mediaItemCount).mapNotNull { index ->
            try {
                MediaItemMapper.fromMediaItem(controller.getMediaItemAt(index))
            } catch (_: Exception) {
                null
            }
        }

        val currentMediaItem = controller.currentMediaItem
        val currentTrack = try {
            currentMediaItem?.let { MediaItemMapper.fromMediaItem(it) }
                ?: if (currentIndex in queue.indices) queue[currentIndex] else null
        } catch (_: Exception) {
            if (currentIndex in queue.indices) queue.getOrNull(currentIndex) else null
        }

        val currentQueueEntryId = MediaItemMapper.getQueueEntryId(currentMediaItem)
        val matchingQueueEntry = if (currentQueueEntryId != null) {
            coordState.queue.firstOrNull { it.queueId == currentQueueEntryId }
        } else null

        val effectiveTrack = matchingQueueEntry?.track
            ?: (if (coordState.currentTrack != null && coordState.queue.isNotEmpty() && currentTrack == null) coordState.currentTrack else currentTrack)
        val effectiveIndex = if (matchingQueueEntry != null && coordState.currentIndex >= 0) {
            coordState.currentIndex
        } else {
            currentIndex
        }
        val effectiveQueue = if (coordState.queue.isNotEmpty()) {
            coordState.queue.map { it.track }
        } else {
            queue
        }
        val effectiveQueueEntries = if (coordState.queue.isNotEmpty()) {
            coordState.queue
        } else if (matchingQueueEntry != null) {
            listOf(matchingQueueEntry)
        } else {
            emptyList()
        }

        val isPlaying = controller.isPlaying
        val isBuffering = controller.playbackState == Player.STATE_BUFFERING
        val isSeekable = controller.isCurrentMediaItemSeekable
        val shuffleMode = if (coordState.shuffleMode != ShuffleMode.OFF) {
            coordState.shuffleMode
        } else if (controller.shuffleModeEnabled) {
            ShuffleMode.SHUFFLE
        } else {
            ShuffleMode.OFF
        }
        val shuffleEnabled = shuffleMode != ShuffleMode.OFF
        val repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        _uiState.update { current ->
            current.copy(
                currentTrack = effectiveTrack,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentPositionMs = position,
                durationMs = duration,
                queue = effectiveQueue,
                queueEntries = effectiveQueueEntries,
                currentTrackIndex = if (effectiveIndex >= 0) effectiveIndex else null,
                currentIndex = effectiveIndex,
                playbackError = null,
                isConnected = true,
                isControllerConnected = true,
                isSeekable = isSeekable,
                shuffleEnabled = shuffleEnabled,
                shuffleMode = shuffleMode,
                repeatMode = repeatMode,
                isAutoplayEnabled = coordState.isAutoplayEnabled,
                isPreparingNext = coordState.isPreparingNext,
                actionMessage = coordState.message ?: current.actionMessage
            )
        }

        if (isPlaying) {
            startProgressTicker()
        } else {
            stopProgressTicker()
        }
    }

    /**
     * Plays a given queue of domain tracks starting at the specified index.
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val entries = tracks.map {
            com.notify.core.model.QueueEntry(
                track = it,
                origin = com.notify.core.model.QueueOrigin.PLAYLIST,
                playbackOrigin = com.notify.core.model.PlaybackOrigin.PLAYLIST
            )
        }
        playQueueEntries(entries, startIndex)
    }

    /**
     * Plays a list of QueueEntry descriptors via PlaybackQueueCoordinator.
     */
    fun playQueueEntries(entries: List<com.notify.core.model.QueueEntry>, startIndex: Int = 0) {
        _uiState.update { it.copy(playbackError = null) }
        if (entries.isEmpty()) return

        val targetTrack = entries.getOrNull(startIndex)?.track
        if (targetTrack?.source is AudioSource.Local) {
            val contentUriString = (targetTrack.source as AudioSource.Local).contentUriString
            val availabilityChecker = LocalAudioAvailabilityChecker(context.contentResolver)
            when (val check = availabilityChecker.checkAvailability(Uri.parse(contentUriString))) {
                is LocalAudioResult.Failure -> {
                    _uiState.update { current ->
                        current.copy(
                            playbackError = check.error,
                            isPlaying = false
                        )
                    }
                    return
                }
                is LocalAudioResult.Success -> {
                    // Available, continue
                }
            }
        }

        withConnectedController { _ ->
            val coordinator = PlaybackQueueCoordinator.getInstance(context)
            coordinator.playQueue(entries, startIndex)
        }
    }

    /**
     * Plays a temporary resolved online audio stream via NotiFyPlaybackService.
     * Ephemeral stream URL is passed in-memory using ResolvedPlaybackItemFactory.
     * Unifies search and single-track playback under PlaybackQueueCoordinator so old playlist context is evicted.
     */
    fun playStream(
        track: Track,
        streamUrl: String,
        origin: com.notify.core.model.PlaybackOrigin,
        requestId: String? = null,
        contextTracks: List<Track> = emptyList()
    ) {
        _uiState.update { it.copy(playbackError = null) }
        val queueEntry = com.notify.core.model.QueueEntry(
            track = track,
            origin = com.notify.core.model.QueueOrigin.USER,
            playbackOrigin = origin
        )
        val mediaItem = com.notify.core.playback.ResolvedPlaybackItemFactory.createMediaItem(
            track = track,
            streamUrl = streamUrl,
            queueEntryId = queueEntry.queueId,
            playbackOrigin = origin
        )
        val contextEntries = contextTracks.map {
            com.notify.core.model.QueueEntry(
                track = it,
                origin = com.notify.core.model.QueueOrigin.PLAYLIST,
                playbackOrigin = origin
            )
        }

        _uiState.update { current ->
            current.copy(
                queue = listOf(track) + contextTracks,
                queueEntries = listOf(queueEntry) + contextEntries,
                currentTrack = track,
                currentTrackIndex = 0,
                currentIndex = 0,
                playbackError = null
            )
        }

        withConnectedController { controller ->
            val coordinator = PlaybackQueueCoordinator.getInstance(context)
            coordinator.playResolvedItem(
                entry = queueEntry,
                mediaItem = mediaItem,
                playImmediately = true,
                requestId = requestId,
                contextEntries = contextEntries
            )
            synchronizeFromController(controller)
        }
    }

    fun play() {
        _uiState.update { it.copy(playbackError = null) }
        val currentTrack = _uiState.value.currentTrack
        if (currentTrack?.source is AudioSource.Local) {
            val contentUriString = (currentTrack.source as AudioSource.Local).contentUriString
            val availabilityChecker = LocalAudioAvailabilityChecker(context.contentResolver)
            when (val check = availabilityChecker.checkAvailability(Uri.parse(contentUriString))) {
                is LocalAudioResult.Failure -> {
                    _uiState.update { current ->
                        current.copy(
                            playbackError = check.error,
                            isPlaying = false
                        )
                    }
                    return
                }
                is LocalAudioResult.Success -> {
                    // Available, continue
                }
            }
        }

        withConnectedController { controller ->
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.play()
            synchronizeFromController(controller)
        }
    }

    fun pause() {
        val controller = mediaController ?: return
        dispatchOnControllerLooper(controller) {
            controller.pause()
            synchronizeFromController(controller)
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        dispatchOnControllerLooper(controller) {
            val duration = PlaybackStateUtils.sanitizeDuration(controller.duration)
            if (PlaybackStateUtils.canSeek(controller.isCurrentMediaItemSeekable, positionMs, duration)) {
                val sanitized = PlaybackStateUtils.sanitizePosition(positionMs, duration)
                controller.seekTo(sanitized)
                _uiState.update { it.copy(currentPositionMs = sanitized) }
            }
        }
    }

    fun skipToNext() {
        clearABRepeatInternal()
        _uiState.update { it.copy(playbackError = null) }
        val coordinator = PlaybackQueueCoordinator.getInstance(context)
        if (coordinator.handleNextAction()) {
            return
        }
        val controller = mediaController ?: return
        dispatchOnControllerLooper(controller) {
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
                synchronizeFromController(controller)
            }
        }
    }

    fun skipToPrevious() {
        clearABRepeatInternal()
        _uiState.update { it.copy(playbackError = null) }
        val coordinator = PlaybackQueueCoordinator.getInstance(context)
        if (coordinator.handlePreviousAction()) {
            return
        }
        val controller = mediaController ?: return
        dispatchOnControllerLooper(controller) {
            val currentPos = controller.currentPosition
            if (currentPos > 3000L) {
                controller.seekTo(0L)
                synchronizeFromController(controller)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPreviousMediaItem()
                synchronizeFromController(controller)
            } else {
                controller.seekTo(0L)
                synchronizeFromController(controller)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(playbackError = null) }
    }

    fun setShuffleMode(mode: ShuffleMode) {
        val coordinator = PlaybackQueueCoordinator.getInstance(context)
        coordinator.setShuffleMode(mode)
        _uiState.update { it.copy(shuffleMode = mode, shuffleEnabled = mode != ShuffleMode.OFF) }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        setShuffleMode(if (enabled) ShuffleMode.SHUFFLE else ShuffleMode.OFF)
    }

    fun cycleShuffleMode(): ShuffleMode {
        val current = _uiState.value.shuffleMode
        val next = when (current) {
            ShuffleMode.OFF -> ShuffleMode.SHUFFLE
            ShuffleMode.SHUFFLE -> ShuffleMode.SMART_SHUFFLE
            ShuffleMode.SMART_SHUFFLE -> ShuffleMode.OFF
        }
        setShuffleMode(next)
        return next
    }

    fun setRepeatMode(mode: RepeatMode) {
        val controller = mediaController ?: return
        val media3Mode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        dispatchOnControllerLooper(controller) {
            controller.repeatMode = media3Mode
            synchronizeFromController(controller)
        }
    }

    fun seekToQueueIndex(index: Int) {
        val coordinator = PlaybackQueueCoordinator.getInstance(context)
        val descriptors = coordinator.getQueueDescriptors()
        if (index in descriptors.indices) {
            coordinator.playQueue(descriptors, index)
            return
        }
        val controller = mediaController ?: return
        dispatchOnControllerLooper(controller) {
            if (index in 0 until controller.mediaItemCount) {
                controller.seekToDefaultPosition(index)
                controller.play()
                synchronizeFromController(controller)
            }
        }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        PlaybackQueueCoordinator.getInstance(context).setAutoplayEnabled(enabled)
    }

    fun toggleAutoplay() {
        PlaybackQueueCoordinator.getInstance(context).toggleAutoplay()
    }

    private fun startProgressTicker() {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch {
            while (isActive) {
                delay(500L)
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val duration = PlaybackStateUtils.sanitizeDuration(controller.duration)
                    val position = PlaybackStateUtils.sanitizePosition(controller.currentPosition, duration)
                    _uiState.update { current ->
                        current.copy(
                            currentPositionMs = position,
                            durationMs = duration
                        )
                    }
                }
            }
        }
    }

    private fun stopProgressTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Releases the active MediaController and connection future.
     */
    fun release() {
        coordinatorJob?.cancel()
        coordinatorJob = null
        stopProgressTicker()
        clearABRepeatInternal()
        mediaController?.removeListener(playerListener)
        mediaController = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null

        _uiState.update { it.copy(isConnected = false, isControllerConnected = false) }
    }

    // ── A-B Repeat (Section Loop) ──────────────────────────────────────────────

    /**
     * Toggles A-B Repeat marking:
     * 1st tap (no A set): captures currentPosition -> sets as abStartMs.
     * 2nd tap (A set, no B set): captures currentPosition -> sets as abEndMs (min 1000ms after A).
     * 3rd tap (both set): clears both and stops the loop.
     */
    fun toggleABRepeat() {
        val controller = mediaController
        if (controller != null) {
            performToggleABRepeat(controller)
        } else {
            withConnectedController { c ->
                performToggleABRepeat(c)
            }
        }
    }

    private fun performToggleABRepeat(controller: MediaController) {
        val duration = PlaybackStateUtils.sanitizeDuration(controller.duration)
        val currentPos = PlaybackStateUtils.sanitizePosition(controller.currentPosition, duration)
        val current = _abRepeatState.value

        when {
            current.abStartMs == null -> {
                val maxStart = if (duration > 0L) {
                    (duration - ABRepeatState.TRACK_END_BUFFER_MS - ABRepeatState.MIN_LOOP_GAP_MS).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
                val start = currentPos.coerceIn(0L, maxStart)
                val updated = ABRepeatState(
                    abStartMs = start,
                    abEndMs = null,
                    isLoopActive = false,
                    errorMessage = null
                )
                _abRepeatState.value = updated
                _uiState.update { it.copy(abRepeatState = updated) }
                PlaybackQueueCoordinator.getInstance(context).setABRepeatActive(false)
                stopABRepeatLoop()
            }
            current.abEndMs == null -> {
                val start = current.abStartMs ?: 0L
                val minEnd = start + ABRepeatState.MIN_LOOP_GAP_MS
                val maxEnd = if (duration > 0L) (duration - ABRepeatState.TRACK_END_BUFFER_MS).coerceAtLeast(minEnd) else Long.MAX_VALUE

                if (currentPos < minEnd) {
                    val errorState = current.copy(errorMessage = "End must be after start (min 1s)")
                    _abRepeatState.value = errorState
                    _uiState.update { it.copy(abRepeatState = errorState) }
                } else {
                    val end = currentPos.coerceAtMost(maxEnd)
                    val updated = ABRepeatState(
                        abStartMs = start,
                        abEndMs = end,
                        isLoopActive = true,
                        errorMessage = null
                    )
                    _abRepeatState.value = updated
                    _uiState.update { it.copy(abRepeatState = updated) }
                    PlaybackQueueCoordinator.getInstance(context).setABRepeatActive(true, start)
                    startABRepeatLoop()
                }
            }
            else -> {
                clearABRepeatInternal()
            }
        }
    }

    /**
     * Clears A-B Repeat points and halts section loop playback.
     */
    fun clearABRepeat() {
        clearABRepeatInternal()
    }

    private fun clearABRepeatInternal() {
        stopABRepeatLoop()
        PlaybackQueueCoordinator.getInstance(context).setABRepeatActive(false)
        val resetState = ABRepeatState()
        _abRepeatState.value = resetState
        _uiState.update { it.copy(abRepeatState = resetState) }
    }

    fun dismissABRepeatError() {
        val current = _abRepeatState.value
        if (current.errorMessage != null) {
            val updated = current.copy(errorMessage = null)
            _abRepeatState.value = updated
            _uiState.update { it.copy(abRepeatState = updated) }
        }
    }

    /**
     * Draggable Handle A update: clamps start position to [0, abEndMs - 1000ms].
     */
    fun updateABStart(startMs: Long) {
        val controller = mediaController
        val duration = if (controller != null) PlaybackStateUtils.sanitizeDuration(controller.duration) else 0L
        val current = _abRepeatState.value
        val end = current.abEndMs
        val maxStart = if (end != null) {
            (end - ABRepeatState.MIN_LOOP_GAP_MS).coerceAtLeast(0L)
        } else if (duration > 0L) {
            (duration - ABRepeatState.TRACK_END_BUFFER_MS - ABRepeatState.MIN_LOOP_GAP_MS).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val clampedStart = startMs.coerceIn(0L, maxStart)

        val updated = current.copy(
            abStartMs = clampedStart,
            errorMessage = null
        )
        _abRepeatState.value = updated
        _uiState.update { it.copy(abRepeatState = updated) }
        if (updated.isLoopActive) {
            PlaybackQueueCoordinator.getInstance(context).setABRepeatActive(true, clampedStart)
            startABRepeatLoop()
        }
    }

    /**
     * Draggable Handle B update: clamps end position to [abStartMs + 1000ms, duration - 250ms].
     */
    fun updateABEnd(endMs: Long) {
        val controller = mediaController
        if (controller != null) {
            performUpdateABEnd(controller, endMs)
        } else {
            withConnectedController { c ->
                performUpdateABEnd(c, endMs)
            }
        }
    }

    private fun performUpdateABEnd(controller: MediaController, endMs: Long) {
        val duration = PlaybackStateUtils.sanitizeDuration(controller.duration)
        val current = _abRepeatState.value
        val start = current.abStartMs ?: 0L
        val minEnd = start + ABRepeatState.MIN_LOOP_GAP_MS
        val maxEnd = if (duration > 0L) (duration - ABRepeatState.TRACK_END_BUFFER_MS).coerceAtLeast(minEnd) else Long.MAX_VALUE

        val clampedEnd = endMs.coerceIn(minEnd, maxEnd)
        val updated = current.copy(
            abEndMs = clampedEnd,
            isLoopActive = true,
            errorMessage = null
        )
        _abRepeatState.value = updated
        _uiState.update { it.copy(abRepeatState = updated) }
        PlaybackQueueCoordinator.getInstance(context).setABRepeatActive(true, start)
        startABRepeatLoop()
    }

    private fun startABRepeatLoop() {
        abRepeatLoopJob?.cancel()
        abRepeatLoopJob = coroutineScope.launch {
            var lastSeekTimestamp = 0L
            while (isActive) {
                delay(200L)
                val controller = mediaController ?: continue

                dispatchOnControllerLooper(controller) { c ->
                    if (!c.isPlaying) return@dispatchOnControllerLooper

                    val state = _abRepeatState.value
                    if (!state.isLoopActive) {
                        stopABRepeatLoop()
                        return@dispatchOnControllerLooper
                    }

                    val start = state.abStartMs ?: return@dispatchOnControllerLooper
                    val end = state.abEndMs ?: return@dispatchOnControllerLooper

                    val currentPos = c.currentPosition
                    val now = System.currentTimeMillis()
                    if (currentPos >= end && (now - lastSeekTimestamp > 300L)) {
                        lastSeekTimestamp = now
                        c.seekTo(start)
                    }
                }
            }
        }
    }

    private fun stopABRepeatLoop() {
        abRepeatLoopJob?.cancel()
        abRepeatLoopJob = null
    }

    private fun buildCauseChain(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        while (t != null) {
            if (sb.isNotEmpty()) sb.append(" → caused by: ")
            sb.append("${t::class.java.simpleName}: ${t.message}")
            t = t.cause
        }
        return sb.toString()
    }
}
