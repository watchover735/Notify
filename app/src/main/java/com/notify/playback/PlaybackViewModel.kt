package com.notify.playback

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notify.core.model.AudioSource
import com.notify.core.model.DownloadBucket
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.engine.OfflineDownloadManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel managing playback interactions for the UI layer.
 * Maintains one MediaController per ViewModel lifecycle and releases it in onCleared.
 * Includes auto-save tracking: after 30s of continuous playback of a remote track,
 * enqueues a SMART_OFFLINE download if auto-save is enabled.
 */
class PlaybackViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PlaybackViewModel"
    }

    private val controller = PlaybackController(
        context = application,
        coroutineScope = viewModelScope
    )

    private val downloadManager = OfflineDownloadManager(application)
    private val downloadPreferences = DownloadPreferences(application)
    private val database by lazy { NotiFyDatabase.getInstance(application) }
    private val playlistRepository by lazy { PlaylistRepository(database) }

    val uiState: StateFlow<PlaybackUiState> = controller.uiState

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentTrackLiked: StateFlow<Boolean> =
        uiState.flatMapLatest { state ->
            val currentTrack = state.currentTrack
            if (currentTrack == null) {
                kotlinx.coroutines.flow.flowOf(false)
            } else {
                val provider = currentTrack.id.provider.name.lowercase()
                val sourceId = when (val src = currentTrack.source) {
                    is AudioSource.Remote -> src.sourceId
                    else -> currentTrack.id.rawId
                }
                playlistRepository.observeIsTrackLiked(provider, sourceId)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentTrackDownloaded: StateFlow<Boolean> =
        uiState.flatMapLatest { state ->
            val currentTrack = state.currentTrack
            if (currentTrack == null) {
                kotlinx.coroutines.flow.flowOf(false)
            } else {
                val trackId = currentTrack.id.rawId
                val sourceId = (currentTrack.source as? AudioSource.Remote)?.sourceId
                val provider = currentTrack.id.provider.name.lowercase()
                val deterministicId = PlaylistRepository.deterministicTrackId(provider, trackId)
                downloadManager.observeCompletedDownloads().map { list ->
                    list.any { dl ->
                        dl.trackId == deterministicId || dl.trackId == trackId || (sourceId != null && dl.providerSourceId == sourceId)
                    }
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    val userPlaylists: StateFlow<List<com.notify.download.db.PlaylistSummary>> =
        playlistRepository.observePlaylistSummaries().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playlistsContainingCurrentTrack: StateFlow<Set<String>> =
        uiState.flatMapLatest { state ->
            val currentTrack = state.currentTrack
            if (currentTrack == null) {
                kotlinx.coroutines.flow.flowOf(emptySet<String>())
            } else {
                val provider = currentTrack.id.provider.name.lowercase()
                val sourceId = when (val src = currentTrack.source) {
                    is AudioSource.Remote -> src.sourceId
                    else -> currentTrack.id.rawId
                }
                playlistRepository.observePlaylistsContainingRecording(provider, sourceId)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet()
        )

    // Auto-save tracking
    private var autoSaveJob: Job? = null
    private var autoSaveTriggeredTrackId: String? = null

    init {
        controller.connect()
        // Start monitoring for auto-save
        startAutoSaveMonitor()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        controller.playQueue(tracks, startIndex)
    }

    fun playQueueEntries(entries: List<com.notify.core.model.QueueEntry>, startIndex: Int = 0) {
        controller.playQueueEntries(entries, startIndex)
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        controller.setAutoplayEnabled(enabled)
    }

    fun toggleAutoplay() {
        controller.toggleAutoplay()
    }

    fun playStream(
        track: Track,
        streamUrl: String,
        origin: PlaybackOrigin,
        requestId: String? = null,
        contextTracks: List<Track> = emptyList()
    ) {
        controller.playStream(track, streamUrl, origin, requestId, contextTracks)
        // Reset auto-save for new track
        resetAutoSave(track)
    }

    fun play() {
        controller.play()
    }

    fun pause() {
        controller.pause()
    }

    fun togglePlayPause() {
        controller.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    fun skipToNext() {
        controller.skipToNext()
    }

    fun skipToPrevious() {
        controller.skipToPrevious()
    }

    fun toggleLikeCurrentTrack() {
        val currentTrack = uiState.value.currentTrack ?: return
        val provider = currentTrack.id.provider.name.lowercase()
        val sourceId = when (val src = currentTrack.source) {
            is AudioSource.Remote -> src.sourceId
            else -> currentTrack.id.rawId
        }
        val isLiked = isCurrentTrackLiked.value
        viewModelScope.launch {
            if (isLiked) {
                val removed = playlistRepository.removeTrackFromLikedSongs(provider, sourceId)
                if (removed) {
                    com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.RemovedFromLikedSongs)
                }
            } else {
                val res = playlistRepository.saveTrackToLikedSongs(
                    title = currentTrack.title,
                    artist = currentTrack.artist,
                    album = currentTrack.album,
                    durationMs = currentTrack.durationMs,
                    artworkUrl = currentTrack.artworkUri,
                    provider = provider,
                    providerSourceId = sourceId
                )
                if (res is com.notify.download.db.AddTrackResult.Added || res is com.notify.download.db.AddTrackResult.AlreadyExists) {
                    com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.AddedToLikedSongs)
                } else if (res is com.notify.download.db.AddTrackResult.Failure) {
                    com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.ActionFailed("Failed to update Liked Songs"))
                }
            }
        }
    }

    fun addCurrentTrackToPlaylist(playlistId: String, onResult: (Boolean) -> Unit = {}) {
        val currentTrack = uiState.value.currentTrack ?: return
        val provider = currentTrack.id.provider.name.lowercase()
        val sourceId = when (val src = currentTrack.source) {
            is AudioSource.Remote -> src.sourceId
            else -> currentTrack.id.rawId
        }
        viewModelScope.launch {
            val playlist = playlistRepository.getPlaylistById(playlistId)
            val playlistName = playlist?.title ?: "playlist"
            val res = playlistRepository.addTrackToPlaylistIfAbsent(
                playlistId = playlistId,
                title = currentTrack.title,
                artist = currentTrack.artist,
                album = currentTrack.album,
                durationMs = currentTrack.durationMs,
                artworkUrl = currentTrack.artworkUri,
                provider = provider,
                providerSourceId = sourceId
            )
            val success = res is com.notify.download.db.AddTrackResult.Added ||
                res is com.notify.download.db.AddTrackResult.AlreadyExists
            onResult(success)

            when (res) {
                is com.notify.download.db.AddTrackResult.Added -> {
                    val canonicalTrackId = playlistRepository.resolveCanonicalTrackId(provider, sourceId)
                    com.notify.ui.SnackbarManager.emit(
                        com.notify.ui.SnackbarEvent.AddedToPlaylist(
                            playlistName = playlistName,
                            onUndo = {
                                viewModelScope.launch {
                                    playlistRepository.removeTrackFromPlaylistByTrackId(playlistId, canonicalTrackId)
                                }
                            }
                        )
                    )
                }
                is com.notify.download.db.AddTrackResult.AlreadyExists -> {
                    com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.AlreadyInPlaylist(playlistName))
                }
                is com.notify.download.db.AddTrackResult.Failure -> {
                    com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.ActionFailed("Failed to add to $playlistName"))
                }
                else -> {}
            }
        }
    }

    fun toggleDownloadCurrentTrack() {
        val currentTrack = uiState.value.currentTrack ?: return
        val provider = currentTrack.id.provider.name.lowercase()
        val sourceId = when (val src = currentTrack.source) {
            is AudioSource.Remote -> src.sourceId
            else -> currentTrack.id.rawId
        }
        viewModelScope.launch {
            val canonicalTrackId = playlistRepository.resolveCanonicalTrackId(provider, sourceId)
            val isDownloaded = isCurrentTrackDownloaded.value
            if (isDownloaded) {
                downloadManager.removeDownloadForTrack(canonicalTrackId)
                com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.Message("Download removed"))
            } else {
                playlistRepository.addTrackToPlaylistIfAbsent(
                    playlistId = PlaylistRepository.LIKED_SONGS_PLAYLIST_ID,
                    title = currentTrack.title,
                    artist = currentTrack.artist,
                    album = currentTrack.album,
                    durationMs = currentTrack.durationMs,
                    artworkUrl = currentTrack.artworkUri,
                    provider = provider,
                    providerSourceId = sourceId
                )
                downloadManager.enqueueTrackDownload(canonicalTrackId, bucket = DownloadBucket.PINNED)
                com.notify.ui.SnackbarManager.emit(com.notify.ui.SnackbarEvent.DownloadStarted(currentTrack.title))
            }
        }
    }

    fun clearError() {
        controller.clearError()
    }

    fun setShuffleMode(mode: com.notify.core.model.ShuffleMode) {
        controller.setShuffleMode(mode)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        controller.setShuffleEnabled(enabled)
    }

    fun toggleShuffle() {
        val nextMode = controller.cycleShuffleMode()
        val message = when (nextMode) {
            com.notify.core.model.ShuffleMode.SHUFFLE -> "Shuffle on"
            com.notify.core.model.ShuffleMode.SMART_SHUFFLE -> "Smart Shuffle on"
            com.notify.core.model.ShuffleMode.OFF -> "Shuffle off"
        }
        com.notify.ui.SnackbarManager.tryEmit(message)
    }

    fun setRepeatMode(mode: com.notify.core.model.RepeatMode) {
        controller.setRepeatMode(mode)
    }

    fun cycleRepeatMode() {
        val nextMode = when (uiState.value.repeatMode) {
            com.notify.core.model.RepeatMode.OFF -> com.notify.core.model.RepeatMode.ALL
            com.notify.core.model.RepeatMode.ALL -> com.notify.core.model.RepeatMode.ONE
            com.notify.core.model.RepeatMode.ONE -> com.notify.core.model.RepeatMode.OFF
        }
        controller.setRepeatMode(nextMode)
    }

    fun seekToQueueIndex(index: Int) {
        controller.seekToQueueIndex(index)
    }

    fun toggleABRepeat() {
        controller.toggleABRepeat()
    }

    fun clearABRepeat() {
        controller.clearABRepeat()
    }

    fun updateABStart(startMs: Long) {
        controller.updateABStart(startMs)
    }

    fun updateABEnd(endMs: Long) {
        controller.updateABEnd(endMs)
    }

    fun dismissABRepeatError() {
        controller.dismissABRepeatError()
    }

    /**
     * Monitors playback state for auto-save: when a remote track has been playing
     * for 30+ seconds continuously, enqueue a SMART_OFFLINE download.
     */
    private fun startAutoSaveMonitor() {
        viewModelScope.launch {
            uiState.collect { state ->
                val currentTrack = state.currentTrack
                val currentTrackIdStr = currentTrack?.id?.toString()

                if (state.isPlaying && currentTrack != null
                    && currentTrack.source is AudioSource.Remote
                    && state.currentPositionMs >= downloadPreferences.autoSaveThresholdMs
                    && currentTrackIdStr != autoSaveTriggeredTrackId
                ) {
                    // DEBUG-TEMP START
                    Log.i("OfflineDebugAutoSave", "Triggering auto-save: track=${currentTrack.title} (id=$currentTrackIdStr, pos=${state.currentPositionMs}ms, threshold=${downloadPreferences.autoSaveThresholdMs}ms)")
                    // DEBUG-TEMP END
                    // Trigger auto-save for this track
                    autoSaveTriggeredTrackId = currentTrackIdStr
                    tryAutoSave(currentTrack)
                }
            }
        }
    }

    private fun resetAutoSave(track: Track) {
        autoSaveTriggeredTrackId = null
    }

    private fun tryAutoSave(track: Track) {
        if (!downloadPreferences.autoSaveEnabled) {
            Log.w(TAG, "Auto-save skipped for '${track.title}': autoSaveEnabled is false")
            return
        }

        val rawId = track.id.rawId.trim()
        if (rawId.isBlank()) {
            Log.w(TAG, "Auto-save skipped for '${track.title}': rawId is blank")
            return
        }

        if (track.source is AudioSource.Local || track.id.provider == ProviderId.LOCAL) {
            Log.w(TAG, "Auto-save skipped for '${track.title}': track is local audio")
            return
        }

        viewModelScope.launch {
            try {
                val providerName = when (track.id.provider) {
                    ProviderId.YOUTUBE -> "YOUTUBE"
                    ProviderId.SPOTIFY -> "SPOTIFY"
                    else -> "CUSTOM_RESOLVER"
                }
                val canonicalTrackId = if (providerName == "YOUTUBE" && !rawId.startsWith("youtube:")) {
                    "youtube:$rawId"
                } else {
                    track.id.toString()
                }

                // Deduplicate by both catalog id and providerSourceId: skip if already active or completed
                val isAlready = database.offlineDownloadDao().hasActiveOrCompletedDownload(canonicalTrackId, rawId)
                    || downloadManager.isTrackAvailableOffline(canonicalTrackId)
                    || downloadManager.isTrackAvailableOffline(rawId)

                if (isAlready) {
                    Log.w(TAG, "Auto-save skipped for '${track.title}': download already active or completed (trackId=$canonicalTrackId, rawId=$rawId)")
                    return@launch
                }

                // Lookup existing TrackEntity and TrackSourceEntity
                var trackEntity = database.trackDao().getTrackById(canonicalTrackId)
                    ?: database.trackDao().getTrackById(rawId)
                var source = database.trackDao().getSelectedSource(canonicalTrackId)
                    ?: database.trackDao().getSelectedSource(rawId)

                // If missing (search / history / trending), synthesize from playing Track metadata
                if (trackEntity == null) {
                    val now = System.currentTimeMillis()
                    val newTrack = TrackEntity(
                        id = canonicalTrackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        artworkUri = track.artworkUri,
                        artworkUrl = track.artworkUri,
                        spotifyId = if (canonicalTrackId.startsWith("spotify:")) rawId else null,
                        resolutionState = ResolutionState.MATCHED,
                        downloadState = DownloadState.NOT_DOWNLOADED,
                        localContentUri = null,
                        dateAddedEpochMs = now
                    )
                    // Insert with IGNORE: never overwrites or triggers CASCADE delete on playlist_entries
                    database.trackDao().insertTracksIgnore(listOf(newTrack))
                    trackEntity = database.trackDao().getTrackById(canonicalTrackId) ?: newTrack
                }

                val effectiveTrack = trackEntity ?: run {
                    Log.w(TAG, "Auto-save skipped for '${track.title}': failed to resolve or synthesize track entity")
                    return@launch
                }

                if (source == null) {
                    val canonicalUrl = when (providerName) {
                        "YOUTUBE" -> "https://www.youtube.com/watch?v=$rawId"
                        else -> (track.source as? AudioSource.Remote)?.sourceId ?: rawId
                    }
                    val newSource = TrackSourceEntity(
                        sourceKey = "$canonicalTrackId:${providerName.lowercase()}:$rawId",
                        trackId = effectiveTrack.id,
                        provider = providerName,
                        sourceId = rawId,
                        canonicalUrl = canonicalUrl,
                        confidence = 1.0f,
                        durationDeltaMs = 0L,
                        artworkUrl = track.artworkUri,
                        selected = true
                    )
                    // Insert with IGNORE: never overwrites or triggers CASCADE delete on playlist_entries
                    database.trackDao().insertSourceIgnore(newSource)
                    source = database.trackDao().getSelectedSource(effectiveTrack.id) ?: newSource
                }

                val enqueued = downloadManager.enqueueTrackDownload(
                    trackId = effectiveTrack.id,
                    bucket = DownloadBucket.SMART_OFFLINE
                )
                if (enqueued) {
                    Log.i(TAG, "Auto-save successfully enqueued for '${track.title}' (trackId=${effectiveTrack.id}, bucket=SMART_OFFLINE)")
                } else {
                    Log.w(TAG, "Auto-save skipped for '${track.title}': enqueueTrackDownload returned false")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Auto-save failed for '${track.title}': ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        autoSaveJob?.cancel()
        controller.release()
        super.onCleared()
    }
}
