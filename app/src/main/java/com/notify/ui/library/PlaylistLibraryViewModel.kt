package com.notify.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.PlaylistSummary
import com.notify.download.spotify.PublicSpotifyScraper
import com.notify.ui.SnackbarEvent
import com.notify.ui.SnackbarManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.notify.core.model.DownloadBucket
import com.notify.download.db.NotiFyDatabase
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.engine.StorageStats
import com.notify.download.matcher.YouTubePlaylistExtractor
import com.notify.download.stream.FollowedArtist
import com.notify.download.stream.FollowedArtistsRepository

enum class LibraryFilter {
    ALL,
    PLAYLISTS,
    ARTISTS,
    DOWNLOADS
}

enum class LibraryTab {
    PLAYLISTS,
    DOWNLOADS
}

data class DownloadedTrackItem(
    val downloadId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val fileSizeBytes: Long,
    val bucket: DownloadBucket,
    val qualityProfile: String,
    val downloadedAtEpochMs: Long,
    val contentUriString: String
)

data class PlaylistLibraryUiState(
    val isLoading: Boolean = true,
    val playlists: List<PlaylistSummary> = emptyList(),
    val isImporting: Boolean = false,
    val importError: String? = null,
    val actionMessage: String? = null,
    val selectedTab: LibraryTab = LibraryTab.PLAYLISTS,
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val followedArtists: List<FollowedArtist> = emptyList(),
    val downloadedTracks: List<DownloadedTrackItem> = emptyList(),
    val storageStats: StorageStats = StorageStats()
)

class PlaylistLibraryViewModel(
    application: Application,
    private val repository: PlaylistRepository,
    private val spotifyScraper: PublicSpotifyScraper = PublicSpotifyScraper(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val downloadManager = OfflineDownloadManager(application)
    private val database by lazy { NotiFyDatabase.getInstance(application) }
    private val followedArtistsRepository by lazy { FollowedArtistsRepository(application) }
    private val cloudSyncRepo by lazy { com.notify.sync.CloudPlaylistSyncRepository.getInstance(application) }

    private val _uiState = MutableStateFlow(PlaylistLibraryUiState())
    val uiState: StateFlow<PlaylistLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            repository.ensureLikedSongsPlaylist()
            cloudSyncRepo.syncDownPlaylists()
        }
        observePlaylists()
        observeDownloads()
        observeArtists()
    }

    private fun observeArtists() {
        followedArtistsRepository.followedArtists
            .onEach { artists ->
                _uiState.update { it.copy(followedArtists = artists) }
            }
            .launchIn(viewModelScope)
    }

    private fun observePlaylists() {
        repository.observePlaylistSummaries()
            .onEach { list ->
                _uiState.update { it.copy(isLoading = false, playlists = list) }
            }
            .catch { e ->
                _uiState.update { it.copy(isLoading = false, importError = e.message) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeDownloads() {
        downloadManager.observeCompletedDownloads()
            .distinctUntilChanged()
            .onEach { completedList ->
                val trackIds = completedList.map { it.trackId }.distinct()
                val trackMap = database.trackDao().getTracksByIds(trackIds).associateBy { it.id }
                val items = completedList.mapNotNull { dl ->
                    if (!downloadManager.isFileValid(dl.relativeStorageKey)) return@mapNotNull null
                    val track = trackMap[dl.trackId]
                    val contentUri = downloadManager.getContentUri(dl.relativeStorageKey) ?: return@mapNotNull null
                    DownloadedTrackItem(
                        downloadId = dl.downloadId,
                        trackId = dl.trackId,
                        title = track?.title ?: "Track ${dl.providerSourceId}",
                        artist = track?.artist ?: "Unknown Artist",
                        album = track?.album,
                        durationMs = dl.durationMs ?: track?.durationMs ?: 0L,
                        artworkUri = com.notify.core.playback.LocalArtworkStore.getArtworkUri(dl.trackId, getApplication())?.toString()
                            ?: (track?.artworkUrl ?: track?.artworkUri),
                        fileSizeBytes = dl.fileSizeBytes ?: 0L,
                        bucket = if (dl.bucket == "PINNED") DownloadBucket.PINNED else DownloadBucket.SMART_OFFLINE,
                        qualityProfile = dl.qualityProfile,
                        downloadedAtEpochMs = dl.updatedAtEpochMs,
                        contentUriString = contentUri.toString()
                    )
                }
                val stats = downloadManager.getStorageStats()
                _uiState.update { it.copy(downloadedTracks = items, storageStats = stats) }
            }
            .catch { }
            .launchIn(viewModelScope)
    }

    fun setLibraryTab(tab: LibraryTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setFilter(filter: LibraryFilter) {
        _uiState.update { current ->
            val nextFilter = if (current.selectedFilter == filter) LibraryFilter.ALL else filter
            val nextTab = if (nextFilter == LibraryFilter.DOWNLOADS) LibraryTab.DOWNLOADS else LibraryTab.PLAYLISTS
            current.copy(selectedFilter = nextFilter, selectedTab = nextTab)
        }
    }

    fun deleteDownload(downloadId: String) {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.removeDownload(downloadId)
            _uiState.update { it.copy(actionMessage = "Download deleted") }
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch(ioDispatcher) {
            downloadManager.clearAllDownloads()
            _uiState.update { it.copy(actionMessage = "All offline downloads cleared") }
        }
    }

    fun pinDownload(downloadId: String) {
        viewModelScope.launch(ioDispatcher) {
            val success = downloadManager.pinDownload(downloadId)
            if (success) {
                _uiState.update { it.copy(actionMessage = "Pinned download (kept permanently)") }
            }
        }
    }

    fun unpinDownload(downloadId: String) {
        viewModelScope.launch(ioDispatcher) {
            val success = downloadManager.unpinDownload(downloadId)
            if (success) {
                _uiState.update { it.copy(actionMessage = "Unpinned download") }
            }
        }
    }

    fun createPlaylist(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            val created = repository.createPlaylist(trimmed)
            cloudSyncRepo.syncPlaylistToCloud(created.playlistId)
            _uiState.update { it.copy(actionMessage = "Created playlist '$trimmed'") }
            SnackbarManager.emit(SnackbarEvent.PlaylistCreated(trimmed))
        }
    }

    fun renamePlaylist(playlistId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            repository.renamePlaylist(playlistId, trimmed)
            cloudSyncRepo.syncPlaylistToCloud(playlistId)
            _uiState.update { it.copy(actionMessage = "Renamed to '$trimmed'") }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(ioDispatcher) {
            repository.deletePlaylist(playlistId)
            cloudSyncRepo.deletePlaylistFromCloud(playlistId)
            _uiState.update { it.copy(actionMessage = "Playlist deleted") }
        }
    }

    fun importSpotifyPlaylist(rawUrl: String, onComplete: (Boolean) -> Unit = {}) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(importError = "Please enter a Spotify playlist URL") }
            onComplete(false)
            return
        }

        // Show spinner immediately so user knows import is in progress
        _uiState.update { it.copy(isImporting = true, importError = null) }

        viewModelScope.launch(ioDispatcher) {
            val result = spotifyScraper.scrapePlaylist(url)
            if (result.playlist != null && result.playlist.tracks.isNotEmpty()) {
                val saved = repository.saveScrapedPlaylist(result.playlist, url)
                cloudSyncRepo.syncPlaylistToCloud(saved.playlistId)
                // Trigger background artwork pre-enrichment immediately
                com.notify.download.worker.ArtworkEnrichmentWorker.enqueue(getApplication(), saved.playlistId)
                val successMsg = "Imported '${saved.title}' (${result.playlist.tracks.size} tracks)"
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importError = null,
                        actionMessage = successMsg
                    )
                }
                SnackbarManager.emit(SnackbarEvent.ImportSucceeded(saved.title, result.playlist.tracks.size))
                onComplete(true)
            } else {
                val errorMsg = result.error ?: "Failed to extract playlist. Verify the playlist is public."
                _uiState.update {
                    it.copy(isImporting = false, importError = errorMsg)
                }
                SnackbarManager.emit(SnackbarEvent.ImportFailed(errorMsg))
                onComplete(false)
            }
        }
    }

    fun importYouTubePlaylist(rawUrl: String, onComplete: (Boolean) -> Unit = {}) {
        val playlistId = YouTubePlaylistExtractor.extractPlaylistId(rawUrl)
        if (playlistId.isNullOrBlank()) {
            val errorMsg = "Invalid YouTube playlist URL. Make sure it contains 'list='."
            _uiState.update { it.copy(importError = errorMsg) }
            viewModelScope.launch {
                SnackbarManager.emit(SnackbarEvent.ImportFailed(errorMsg))
            }
            onComplete(false)
            return
        }

        _uiState.update { it.copy(isImporting = true, importError = null) }

        viewModelScope.launch(ioDispatcher) {
            val result = YouTubePlaylistExtractor.extractPlaylist(getApplication(), playlistId)
            result.fold(
                onSuccess = { extracted ->
                    val saved = repository.saveYouTubePlaylist(
                        playlistTitle = extracted.title,
                        youtubePlaylistId = extracted.id,
                        tracks = extracted.tracks,
                        artworkUrl = extracted.artworkUrl,
                        sourceUrl = "https://www.youtube.com/playlist?list=${extracted.id}"
                    )
                    cloudSyncRepo.syncPlaylistToCloud(saved.playlistId)
                    val count = extracted.tracks.size
                    val successMsg = "Imported $count songs"
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importError = null,
                            actionMessage = successMsg
                        )
                    }
                    SnackbarManager.emit(SnackbarEvent.Message(successMsg))
                    SnackbarManager.emit(SnackbarEvent.ImportSucceeded(saved.title, count))
                    onComplete(true)
                },
                onFailure = { err ->
                    val errorMsg = err.message ?: "Failed to import YouTube playlist"
                    _uiState.update {
                        it.copy(isImporting = false, importError = errorMsg)
                    }
                    SnackbarManager.emit(SnackbarEvent.ImportFailed(errorMsg))
                    onComplete(false)
                }
            )
        }
    }

    fun clearImportError() {
        _uiState.update { it.copy(importError = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    class Factory(
        private val application: Application,
        private val repository: PlaylistRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistLibraryViewModel(application, repository) as T
        }
    }
}
