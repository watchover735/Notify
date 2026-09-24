package com.notify.playback

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notify.core.model.AudioSource
import com.notify.core.model.DownloadBucket
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
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

    fun playStream(track: Track, streamUrl: String, origin: PlaybackOrigin, requestId: String? = null) {
        controller.playStream(track, streamUrl, origin, requestId)
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
                playlistRepository.removeTrackFromLikedSongs(provider, sourceId)
            } else {
                playlistRepository.saveTrackToLikedSongs(
                    title = currentTrack.title,
                    artist = currentTrack.artist,
                    album = currentTrack.album,
                    durationMs = currentTrack.durationMs,
                    artworkUrl = currentTrack.artworkUri,
                    provider = provider,
                    providerSourceId = sourceId
                )
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
            onResult(res is com.notify.download.db.AddTrackResult.Added || res is com.notify.download.db.AddTrackResult.AlreadyExists)
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
            }
        }
    }

    fun clearError() {
        controller.clearError()
    }

    fun setShuffleEnabled(enabled: Boolean) {
        controller.setShuffleEnabled(enabled)
    }

    fun toggleShuffle() {
        controller.setShuffleEnabled(!uiState.value.shuffleEnabled)
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
        if (!downloadPreferences.autoSaveEnabled) return

        viewModelScope.launch {
            try {
                val trackIdStr = track.id.toString()
                val isAlready = downloadManager.isTrackAvailableOffline(trackIdStr)
                if (isAlready) return@launch

                // Find the track entity and source
                val trackEntity = database.trackDao().getTrackById(trackIdStr)
                val source = database.trackDao().getSelectedSource(trackIdStr)
                if (trackEntity != null && source != null) {
                    downloadManager.enqueueDownload(trackEntity, source, DownloadBucket.SMART_OFFLINE)
                    Log.d(TAG, "Auto-save triggered for: ${track.title} (${track.id})")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Auto-save failed for ${track.title}: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        autoSaveJob?.cancel()
        controller.release()
        super.onCleared()
    }
}
