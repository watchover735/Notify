package com.notify.ui.library

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import com.notify.core.downloads.storage.OfflineStorage
import com.notify.core.model.AudioSource
import com.notify.core.model.DownloadBucket
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackSourceEntity
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.spotify.SpotifyTrackMetadata
import com.notify.download.stream.OnlineStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.notify.download.worker.ArtworkEnrichmentWorker

/**
 * Truthful per-track download display state.
 * Every playlist row MUST ALWAYS display exactly one of these states (never blank).
 */
sealed class TrackDownloadDisplayState {
    object NotDownloaded : TrackDownloadDisplayState()
    object Resolving : TrackDownloadDisplayState()
    data class Queued(val queuePosition: Int) : TrackDownloadDisplayState()
    data class Downloading(val progressPercent: Int) : TrackDownloadDisplayState()
    object Validating : TrackDownloadDisplayState()
    object Downloaded : TrackDownloadDisplayState()
    object Failed : TrackDownloadDisplayState()
    object MissingCorrupt : TrackDownloadDisplayState()
}

data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val playlist: PlaylistEntity? = null,
    val tracks: List<PlaylistEntryWithTrack> = emptyList(),
    val resolvingTrackId: String? = null,
    val playbackError: String? = null,
    val actionMessage: String? = null,
    val artworkProgress: String? = null,

    // Truthful per-track states mapped by trackId:
    val trackStates: Map<String, TrackDownloadDisplayState> = emptyMap(),

    // Live Room-backed Queue Header Stats
    val isQueueActive: Boolean = false,
    val isQueuePaused: Boolean = false,
    val queueCurrentIndex: Int = 0,
    val queueTotalCount: Int = 0,
    val queueCurrentTitle: String? = null,
    val queuePendingCount: Int = 0,
    val queueFailedCount: Int = 0,

    // Backward-compatible fields
    val downloadingTrackIds: Set<String> = emptySet(),
    val isDownloadingPlaylist: Boolean = false,
    val downloadedTrackIds: Set<String> = emptySet(),
    val smartOfflineTrackIds: Set<String> = emptySet(),
    val failedTrackIds: Set<String> = emptySet(),
    val missingTrackIds: Set<String> = emptySet()
)

class PlaylistDetailViewModel(
    application: Application,
    private val playlistId: String,
    private val repository: PlaylistRepository,
    private val streamResolver: OnlineStreamResolver = OnlineStreamResolver(application),
    private val innerTubeSearchProvider: InnerTubeYouTubeMusicSearchProvider = InnerTubeYouTubeMusicSearchProvider(),
    private val fallbackSearchProvider: YtDlpYouTubeSearchProvider = YtDlpYouTubeSearchProvider(application),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val downloadManager = OfflineDownloadManager(application)
    private val offlineStorage = downloadManager.getOfflineStorage()

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        loadPlaylist()
    }

    private fun loadPlaylist() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val pl = repository.getPlaylistById(playlistId)
                _uiState.update { it.copy(playlist = pl) }

                com.notify.download.worker.EnrichmentRecoveryCoordinator.recoverPlaylist(
                    context = getApplication(),
                    playlistId = playlistId,
                    forceRetry = false
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("PlaylistDetailViewModel", "Error loading playlist $playlistId: ${e.message}")
            }
        }

        // Observe WorkManager progress for artwork
        try {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(ArtworkEnrichmentWorker.workName(playlistId))
                .onEach { workInfoList ->
                    val runningWork = workInfoList.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    if (runningWork != null) {
                        val completed = runningWork.progress.getInt(ArtworkEnrichmentWorker.PROGRESS_COMPLETED, 0)
                        val total = runningWork.progress.getInt(ArtworkEnrichmentWorker.PROGRESS_TOTAL, 0)
                        if (total > 0) {
                            _uiState.update { it.copy(artworkProgress = "Loading artwork $completed/$total") }
                        } else {
                            _uiState.update { it.copy(artworkProgress = null) }
                        }
                    } else {
                        _uiState.update { it.copy(artworkProgress = null) }
                    }
                }
                .catch { _uiState.update { it.copy(artworkProgress = null) } }
                .launchIn(viewModelScope)
        } catch (_: Throwable) {}

        // Combine playlist entries, completed downloads, and global download queue live from Room
        combine(
            repository.observePlaylistEntries(playlistId),
            downloadManager.observeCompletedDownloads(),
            downloadManager.observeAllQueue()
        ) { entryList, completedList, queueList ->
            val completedMap = completedList.associateBy { it.trackId }
            val completedBySourceId = completedList.associateBy { it.providerSourceId }
            val queueMap = queueList.associateBy { it.trackId }

            val states = mutableMapOf<String, TrackDownloadDisplayState>()
            val pinnedSet = mutableSetOf<String>()
            val smartSet = mutableSetOf<String>()
            val missingSet = mutableSetOf<String>()
            val downloadingSet = mutableSetOf<String>()
            val failedSet = mutableSetOf<String>()

            for (entry in entryList) {
                val trackId = entry.trackId
                val sourceId = if (trackId.startsWith("youtube:")) trackId.removePrefix("youtube:").trim() else null
                val completed = completedMap[trackId] ?: (sourceId?.let { completedBySourceId[it] })
                val queueItem = queueMap[trackId]
                    ?: (if (sourceId != null) queueList.firstOrNull { it.trackId == "youtube:$sourceId" } else null)

                val state: TrackDownloadDisplayState = when {
                    completed != null -> {
                        if (offlineStorage.isFileValid(completed.relativeStorageKey)) {
                            if (completed.bucket == "PINNED") pinnedSet.add(trackId) else smartSet.add(trackId)
                            TrackDownloadDisplayState.Downloaded
                        } else {
                            missingSet.add(trackId)
                            TrackDownloadDisplayState.MissingCorrupt
                        }
                    }
                    queueItem != null -> {
                        when (queueItem.status) {
                            DownloadQueueStatus.RESOLVING -> {
                                downloadingSet.add(trackId)
                                TrackDownloadDisplayState.Resolving
                            }
                            DownloadQueueStatus.DOWNLOADING -> {
                                downloadingSet.add(trackId)
                                TrackDownloadDisplayState.Downloading(queueItem.progressPercent)
                            }
                            DownloadQueueStatus.VALIDATING -> {
                                downloadingSet.add(trackId)
                                TrackDownloadDisplayState.Validating
                            }
                            DownloadQueueStatus.QUEUED, DownloadQueueStatus.PAUSED -> {
                                downloadingSet.add(trackId)
                                TrackDownloadDisplayState.Queued(queueItem.queuePosition)
                            }
                            DownloadQueueStatus.FAILED -> {
                                failedSet.add(trackId)
                                TrackDownloadDisplayState.Failed
                            }
                            DownloadQueueStatus.COMPLETED -> {
                                val dl = completed ?: (sourceId?.let { completedBySourceId[it] })
                                if (dl != null && offlineStorage.isFileValid(dl.relativeStorageKey)) {
                                    pinnedSet.add(trackId)
                                    TrackDownloadDisplayState.Downloaded
                                } else {
                                    missingSet.add(trackId)
                                    TrackDownloadDisplayState.MissingCorrupt
                                }
                            }
                            else -> TrackDownloadDisplayState.NotDownloaded
                        }
                    }
                    else -> TrackDownloadDisplayState.NotDownloaded
                }
                states[trackId] = state
            }

            // Calculate live queue header statistics for this playlist
            val playlistTrackIds = entryList.map { it.trackId }.toSet()
            val relevantQueue = queueList.filter { playlistTrackIds.contains(it.trackId) }
            val activeItem = relevantQueue.firstOrNull {
                it.status in listOf(DownloadQueueStatus.RESOLVING, DownloadQueueStatus.DOWNLOADING, DownloadQueueStatus.VALIDATING)
            }
            val queuedCount = relevantQueue.count { it.status == DownloadQueueStatus.QUEUED }
            val pausedCount = relevantQueue.count { it.status == DownloadQueueStatus.PAUSED }
            val failedCount = relevantQueue.count { it.status == DownloadQueueStatus.FAILED }
            val completedInQueue = relevantQueue.count { it.status == DownloadQueueStatus.COMPLETED }
            val totalInQueue = relevantQueue.size

            val isQueueActive = activeItem != null || queuedCount > 0 || pausedCount > 0
            val currentIndex = if (totalInQueue > 0) {
                (completedInQueue + (if (activeItem != null) 1 else 0)).coerceAtMost(totalInQueue)
            } else 0

            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    tracks = entryList,
                    trackStates = states,
                    isQueueActive = isQueueActive,
                    isQueuePaused = pausedCount > 0 && activeItem == null,
                    queueCurrentIndex = currentIndex,
                    queueTotalCount = totalInQueue,
                    queueCurrentTitle = activeItem?.trackTitle?.takeIf { it.isNotBlank() },
                    queuePendingCount = queuedCount + pausedCount,
                    queueFailedCount = failedCount,
                    downloadedTrackIds = pinnedSet,
                    smartOfflineTrackIds = smartSet,
                    missingTrackIds = missingSet,
                    downloadingTrackIds = downloadingSet,
                    failedTrackIds = failedSet,
                    isDownloadingPlaylist = isQueueActive
                )
            }
        }
            .catch { e ->
                _uiState.update { it.copy(isLoading = false, playbackError = e.message) }
            }
            .launchIn(viewModelScope)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Plays the complete ordered playlist starting at [tappedEntry].
     * Retains all preceding and succeeding entries as QueueEntry descriptors.
     */
    fun playPlaylistFromEntry(
        tappedEntry: PlaylistEntryWithTrack,
        onPlayQueueEntries: (List<com.notify.core.model.QueueEntry>, Int) -> Unit
    ) {
        val entries = _uiState.value.tracks
        val queueEntries = entries.map { item ->
            val effectiveArtwork = item.artworkUrl?.takeIf { it.isNotBlank() }
                ?: item.artworkUri?.takeIf { it.isNotBlank() }
            val source: AudioSource = if (!item.localContentUri.isNullOrBlank()) {
                AudioSource.Local(item.localContentUri)
            } else {
                AudioSource.Remote(ProviderId.SPOTIFY, item.trackId)
            }
            val track = Track(
                id = TrackId.spotify(item.trackId),
                title = item.title,
                artist = item.artist,
                album = item.album,
                durationMs = item.durationMs,
                artworkUri = effectiveArtwork,
                source = source
            )
            com.notify.core.model.QueueEntry(
                queueId = "playlist_${playlistId}_${item.trackId}_${item.position}",
                track = track,
                origin = com.notify.core.model.QueueOrigin.PLAYLIST
            )
        }
        val tappedIndex = entries.indexOfFirst { it.trackId == tappedEntry.trackId }.coerceAtLeast(0)
        onPlayQueueEntries(queueEntries, tappedIndex)
    }

    /**
     * Resolves and plays an individual track.
     * Guaranteed: Downloaded tracks play in airplane mode.
     * Guaranteed: Non-downloaded tracks show clear "Offline: '<title>' is not downloaded" message.
     */
    fun playSingleTrack(
        entry: PlaylistEntryWithTrack,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit,
        onPlayTrack: (Track) -> Unit
    ) {
        viewModelScope.launch(ioDispatcher) {
            // Priority 1: Validated offline download
            val completedRecord = downloadManager.getCompletedDownloadForTrack(entry.trackId)
            if (completedRecord != null) {
                val offlineUri = downloadManager.getOfflinePlaybackUri(entry.trackId)
                if (offlineUri != null) {
                    val effectiveArtwork = entry.artworkUrl?.takeIf { it.isNotBlank() }
                        ?: entry.artworkUri?.takeIf { it.isNotBlank() }
                    val offlineTrack = Track(
                        id = TrackId.spotify(entry.trackId),
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        durationMs = entry.durationMs,
                        artworkUri = effectiveArtwork,
                        source = AudioSource.Local(offlineUri.toString())
                    )
                    android.util.Log.d("PlaylistDetailVM", "Offline playback: ${entry.title} via ${offlineUri.scheme}://…")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        onPlayTrack(offlineTrack)
                    }
                    return@launch
                } else {
                    _uiState.update {
                        it.copy(playbackError = "'${entry.title}' offline file is missing. Tap the download icon to re-download.")
                    }
                    return@launch
                }
            }

            // Priority 2: Legacy local file
            if (!entry.localContentUri.isNullOrBlank()) {
                val localTrack = Track(
                    id = TrackId.local(entry.localContentUri),
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    durationMs = entry.durationMs,
                    artworkUri = entry.artworkUrl ?: entry.artworkUri,
                    source = AudioSource.Local(entry.localContentUri)
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onPlayTrack(localTrack)
                }
                return@launch
            }

            // Priority 3: Non-downloaded track in airplane/offline mode
            if (!isNetworkConnected()) {
                _uiState.update {
                    it.copy(playbackError = "Offline: '${entry.title}' is not downloaded")
                }
                return@launch
            }

            // Priority 4: Online stream resolution
            resolveAndPlayOnline(entry, onPlayStream)
        }
    }

    private suspend fun resolveAndPlayOnline(
        entry: PlaylistEntryWithTrack,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit
    ) {
        _uiState.update { it.copy(resolvingTrackId = entry.trackId, playbackError = null) }
        try {
            var source = repository.getSelectedSource(entry.trackId)

            if (source == null) {
                val query = "${entry.artist} - ${entry.title}"
                var candidates = emptyList<YouTubeCandidate>()

                val innerTubeResult = innerTubeSearchProvider.search(query, limit = 5)
                if (innerTubeResult.isSuccess && !innerTubeResult.getOrNull().isNullOrEmpty()) {
                    candidates = innerTubeResult.getOrThrow()
                }

                val dummyMeta = SpotifyTrackMetadata(
                    id = entry.trackId,
                    title = entry.title,
                    artists = listOf(entry.artist),
                    album = entry.album,
                    releaseYear = null,
                    durationMs = entry.durationMs,
                    artworkUrl = entry.artworkUrl ?: entry.artworkUri
                )

                var match = if (candidates.isNotEmpty()) TrackMatchEngine.findBestMatch(dummyMeta, candidates) else null
                if (match == null || match.matchScore < 0.5f) {
                    val fallbackResult = fallbackSearchProvider.search(query, limit = 3)
                    if (fallbackResult.isSuccess && !fallbackResult.getOrNull().isNullOrEmpty()) {
                        val fallbackCandidates = fallbackResult.getOrThrow()
                        val fallbackMatch = TrackMatchEngine.findBestMatch(dummyMeta, fallbackCandidates)
                        if (fallbackMatch != null && (match == null || fallbackMatch.matchScore > match.matchScore)) {
                            match = fallbackMatch
                        }
                    }
                }

                if (match != null) {
                    val newSource = TrackSourceEntity(
                        sourceKey = "${entry.trackId}:youtube",
                        trackId = entry.trackId,
                        provider = match.candidate.provider,
                        sourceId = match.candidate.videoId,
                        canonicalUrl = match.canonicalDownloadUrl,
                        confidence = match.matchScore.toFloat(),
                        durationDeltaMs = match.durationDeltaMs,
                        artworkUrl = match.candidate.artworkUrl,
                        selected = true
                    )
                    repository.saveSelectedSource(newSource)
                    repository.updateTrackResolution(entry.trackId, ResolutionState.MATCHED)
                    if (!match.candidate.artworkUrl.isNullOrBlank()) {
                        repository.updateTrackArtwork(entry.trackId, match.candidate.artworkUrl)
                    }
                    source = newSource
                }
            }

            if (source == null) {
                _uiState.update {
                    it.copy(resolvingTrackId = null, playbackError = "Could not find a match for '${entry.title}'")
                }
                return
            }

            val streamResult = streamResolver.resolveStream(
                canonicalYoutubeUrl = source.canonicalUrl,
                title = entry.title,
                artist = entry.artist
            )
            if (streamResult.isFailure) {
                val err = streamResult.exceptionOrNull()?.message ?: "Failed to resolve stream"
                _uiState.update { it.copy(resolvingTrackId = null, playbackError = err) }
                return
            }

            val stream = streamResult.getOrThrow()
            val effectiveArtwork = source.artworkUrl?.takeIf { it.isNotBlank() }
                ?: entry.artworkUrl?.takeIf { it.isNotBlank() }
                ?: entry.artworkUri?.takeIf { it.isNotBlank() }

            val domainTrack = Track(
                id = TrackId.spotify(entry.trackId),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                durationMs = entry.durationMs,
                artworkUri = effectiveArtwork,
                source = AudioSource.Remote(ProviderId.SPOTIFY, entry.trackId)
            )

            onPlayStream(domainTrack, stream.streamUrl, PlaybackOrigin.PLAYLIST)
        } catch (e: Exception) {
            val isOffline = !isNetworkConnected() || e is java.net.UnknownHostException || e is java.net.SocketTimeoutException
            val msg = if (isOffline) "Offline: '${entry.title}' is not downloaded" else (e.message ?: "Playback failed")
            _uiState.update { it.copy(playbackError = msg) }
        } finally {
            _uiState.update { it.copy(resolvingTrackId = null) }
        }
    }

    /**
     * Downloads a single track.
     * Tapping on an unmatched Spotify track performs: resolve match -> enqueue -> download -> validate.
     */
    fun downloadTrack(entry: PlaylistEntryWithTrack) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val enqueued = downloadManager.enqueueTrackDownload(
                    trackId = entry.trackId,
                    playlistId = playlistId,
                    playlistPosition = entry.position,
                    bucket = DownloadBucket.PINNED
                )
                if (enqueued) {
                    _uiState.update { it.copy(actionMessage = "Queued '${entry.title}' for download") }
                } else {
                    _uiState.update { it.copy(actionMessage = "'${entry.title}' already downloaded or queued") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(actionMessage = "Download error: ${e.message}") }
            }
        }
    }

    /**
     * Downloads entire playlist in strict playlist order.
     */
    fun downloadPlaylist() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val count = downloadManager.enqueuePlaylistDownload(playlistId, DownloadBucket.PINNED)
                _uiState.update { it.copy(actionMessage = "Queued $count tracks for serial download") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(actionMessage = "Download error: ${e.message}") }
            }
        }
    }

    fun pauseQueue() {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.pauseQueue()
            _uiState.update { it.copy(actionMessage = "Download queue paused") }
        }
    }

    fun resumeQueue() {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.resumeQueue()
            _uiState.update { it.copy(actionMessage = "Download queue resumed") }
        }
    }

    fun cancelQueue() {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.cancelQueue()
            _uiState.update { it.copy(actionMessage = "Download queue cancelled; completed files preserved") }
        }
    }

    fun retryFailed() {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.retryFailed()
            _uiState.update { it.copy(actionMessage = "Retrying failed downloads") }
        }
    }

    fun retrySingleTrack(entry: PlaylistEntryWithTrack) {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.retrySingleTrack(entry.trackId)
            _uiState.update { it.copy(actionMessage = "Retrying '${entry.title}'") }
        }
    }

    fun removeDownload(entry: PlaylistEntryWithTrack) {
        viewModelScope.launch(ioDispatcher) {
            try {
                downloadManager.removeDownloadForTrack(entry.trackId)
                _uiState.update { it.copy(actionMessage = "Removed offline download for '${entry.title}'") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(actionMessage = "Failed to remove download: ${e.message}") }
            }
        }
    }

    private var lastRemovedEntry: com.notify.download.db.PlaylistEntryEntity? = null

    fun removeTrackFromPlaylist(entry: PlaylistEntryWithTrack, onUndoAvailable: (String) -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val removed = repository.removeTrackFromPlaylist(playlistId, entry.entryId)
                if (removed != null) {
                    lastRemovedEntry = removed
                    _uiState.update { it.copy(actionMessage = "Removed '${entry.title}' from playlist") }
                    onUndoAvailable(entry.title)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(actionMessage = "Failed to remove track: ${e.message}") }
            }
        }
    }

    fun undoRemoveTrack() {
        val entryToRestore = lastRemovedEntry ?: return
        lastRemovedEntry = null
        viewModelScope.launch(ioDispatcher) {
            try {
                val res = repository.undoRemoveTrackFromPlaylist(entryToRestore)
                if (res is com.notify.download.db.AddTrackResult.Added) {
                    _uiState.update { it.copy(actionMessage = "Restored track to playlist") }
                } else if (res is com.notify.download.db.AddTrackResult.AlreadyExists) {
                    _uiState.update { it.copy(actionMessage = "Track already present in playlist") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(actionMessage = "Failed to restore track: ${e.message}") }
            }
        }
    }

    fun retryMissingArtwork() {
        viewModelScope.launch(ioDispatcher) {
            repository.resetEnrichmentForRetry(playlistId)
            com.notify.download.worker.EnrichmentRecoveryCoordinator.recoverPlaylist(
                context = getApplication(),
                playlistId = playlistId,
                forceRetry = true
            )
            _uiState.update { it.copy(actionMessage = "Retrying artwork enrichment…") }
        }
    }

    fun renamePlaylist(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            repository.renamePlaylist(playlistId, trimmed)
            val updated = repository.getPlaylistById(playlistId)
            _uiState.update { it.copy(playlist = updated, actionMessage = "Renamed to '$trimmed'") }
        }
    }

    fun deletePlaylist(onDeleted: () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            repository.deletePlaylist(playlistId)
            onDeleted()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(playbackError = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    class Factory(
        private val application: Application,
        private val playlistId: String,
        private val repository: PlaylistRepository,
        private val streamResolver: OnlineStreamResolver = OnlineStreamResolver(application),
        private val innerTubeSearchProvider: InnerTubeYouTubeMusicSearchProvider = InnerTubeYouTubeMusicSearchProvider(),
        private val fallbackSearchProvider: YtDlpYouTubeSearchProvider = YtDlpYouTubeSearchProvider(application)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistDetailViewModel(
                application = application,
                playlistId = playlistId,
                repository = repository,
                streamResolver = streamResolver,
                innerTubeSearchProvider = innerTubeSearchProvider,
                fallbackSearchProvider = fallbackSearchProvider
            ) as T
        }
    }
}
