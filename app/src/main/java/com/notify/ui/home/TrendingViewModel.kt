package com.notify.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.stream.JioSaavnStreamResolver
import com.notify.download.stream.SoundCloudStreamResolver
import com.notify.download.stream.TrendingRepository
import com.notify.download.stream.TrendingTrack
import com.notify.ui.SnackbarEvent
import com.notify.ui.SnackbarManager
import com.notify.core.model.DownloadBucket
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.PlaylistSummary
import com.notify.download.db.TrackEntity
import com.notify.download.engine.OfflineDownloadManager
import com.notify.ui.library.DownloadedTrackItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Possible states for the Trending section. */
sealed class TrendingUiState {
    object Loading : TrendingUiState()
    data class Success(val tracks: List<TrendingTrack>) : TrendingUiState()
    data class Error(val message: String) : TrendingUiState()
    /** Hidden — no cache AND network failed; silently suppress the section. */
    object Hidden : TrendingUiState()
}

/** Possible states for the Curated Artists sections on the Home screen. */
sealed class CuratedArtistsUiState {
    object Loading : CuratedArtistsUiState()
    data class Success(val sections: List<com.notify.download.stream.CuratedArtistSection>) : CuratedArtistsUiState()
    data class Error(val message: String) : CuratedArtistsUiState()
    /** Hidden — no cache AND network failed; silently suppress sections. */
    object Hidden : CuratedArtistsUiState()
}

/**
 * ViewModel for the Trending and Curated Artist sections on the Home screen.
 *
 * Strategy:
 * 1. On init, immediately expose cached data if available.
 * 2. If cache is fresh (< 6-8h), no network call is made.
 * 3. If cache is stale/missing, a background fetch is launched.
 * 4. On fetch failure, the section stays with stale cache or silently hides.
 *
 * Playback:
 * - JioSaavnStreamResolver resolves the stream directly (fast, bypasses resolveDescriptor).
 * - Concurrently, InnerTubeYouTubeMusicSearchProvider searches for the YouTube videoId.
 *   If found, the domain Track is stamped with ProviderId.YOUTUBE so RadioWindowManager.extractVideoId
 *   returns it non-null → WATCH_NEXT is triggered → autoplay/radio works.
 * - Context queue: remaining tracks from the tapped artist section are passed as context tracks so
 *   the artist's songs play in sequence, followed by radio autoplay.
 */

/** Home-screen filter chips */
enum class HomeChip { ALL, DOWNLOADED, PLAYLISTS }

/**
 * ViewModel for the Home screen: manages filter chips, trending tracks,
 * curated artists, downloaded songs, playlists, and aggregated track counts.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TrendingRepository(application)
    private val artistsRepository = com.notify.download.stream.CuratedArtistsRepository(application)
    private val followedArtistsRepo = com.notify.download.stream.FollowedArtistsRepository(application)
    private val database by lazy { NotiFyDatabase.getInstance(application) }
    private val downloadManager by lazy { OfflineDownloadManager(application) }
    private val playlistRepository by lazy { PlaylistRepository(database) }

    /** User's followed artists on Home screen. */
    val followedArtists: StateFlow<List<com.notify.download.stream.FollowedArtist>> =
        followedArtistsRepo.followedArtists

    /** Artist search state for Edit Artists bottom sheet. */
    private val _artistSearchQuery = MutableStateFlow("")
    val artistSearchQuery: StateFlow<String> = _artistSearchQuery.asStateFlow()

    private val _artistSearchResults = MutableStateFlow<List<com.notify.download.stream.FollowedArtist>>(emptyList())
    val artistSearchResults: StateFlow<List<com.notify.download.stream.FollowedArtist>> = _artistSearchResults.asStateFlow()

    private val _isSearchingArtists = MutableStateFlow(false)
    val isSearchingArtists: StateFlow<Boolean> = _isSearchingArtists.asStateFlow()

    private var artistSearchJob: kotlinx.coroutines.Job? = null

    fun onArtistSearchQueryChanged(query: String) {
        _artistSearchQuery.value = query
        artistSearchJob?.cancel()
        if (query.isBlank()) {
            _artistSearchResults.value = emptyList()
            _isSearchingArtists.value = false
            return
        }
        artistSearchJob = viewModelScope.launch {
            _isSearchingArtists.value = true
            kotlinx.coroutines.delay(250) // Debounce typing
            val results = artistsRepository.searchArtists(query)
            _artistSearchResults.value = results
            _isSearchingArtists.value = false
        }
    }

    fun clearArtistSearch() {
        _artistSearchQuery.value = ""
        _artistSearchResults.value = emptyList()
        _isSearchingArtists.value = false
        artistSearchJob?.cancel()
    }

    fun followArtist(artist: com.notify.download.stream.FollowedArtist) {
        if (followedArtists.value.any { it.id == artist.id }) {
            SnackbarManager.tryEmit(SnackbarEvent.Message("${artist.name} is already in your artists"))
            return
        }
        if (followedArtists.value.size >= com.notify.download.stream.FollowedArtistsRepository.MAX_FOLLOWED_ARTISTS) {
            SnackbarManager.tryEmit(SnackbarEvent.Message("You can follow up to 10 artists"))
            return
        }
        val added = followedArtistsRepo.followArtist(artist)
        if (added) {
            SnackbarManager.tryEmit(SnackbarEvent.ArtistFollowed(artist.name))
            loadCuratedArtists(forceRefresh = false)
        }
    }

    fun unfollowArtist(artist: com.notify.download.stream.FollowedArtist) {
        val removedPair = followedArtistsRepo.unfollowArtist(artist.id) ?: return
        // Immediately drop section from UI state without network call
        val current = _artistsState.value
        if (current is CuratedArtistsUiState.Success) {
            val updated = current.sections.filter { it.artistId != artist.id }
            _artistsState.value = if (updated.isNotEmpty()) CuratedArtistsUiState.Success(updated) else CuratedArtistsUiState.Hidden
        }
        SnackbarManager.tryEmit(
            SnackbarEvent.ArtistUnfollowed(
                artistName = artist.name,
                onUndo = {
                    restoreArtist(removedPair.first, removedPair.second)
                }
            )
        )
    }

    fun restoreArtist(artist: com.notify.download.stream.FollowedArtist, index: Int) {
        val restored = followedArtistsRepo.restoreArtist(artist, index)
        if (restored) {
            SnackbarManager.tryEmit(SnackbarEvent.ArtistFollowed(artist.name))
            loadCuratedArtists(forceRefresh = false)
        }
    }

    /** Currently active filter chip on Home. */
    private val _selectedChip = MutableStateFlow(HomeChip.ALL)
    val selectedChip: StateFlow<HomeChip> = _selectedChip.asStateFlow()

    fun selectChip(chip: HomeChip) {
        _selectedChip.value = chip
    }

    /** Completed offline downloads for the DOWNLOADED chip. */
    val downloadedTracks: StateFlow<List<DownloadedTrackItem>> =
        downloadManager.observeCompletedDownloads()
            .map { completedList ->
                val trackIds = completedList.map { it.trackId }.distinct()
                val trackMap = database.trackDao().getTracksByIds(trackIds).associateBy { it.id }
                completedList.mapNotNull { dl ->
                    val track = trackMap[dl.trackId]
                    val contentUri = downloadManager.getOfflinePlaybackUri(dl.trackId) ?: return@mapNotNull null
                    DownloadedTrackItem(
                        downloadId = dl.downloadId,
                        trackId = dl.trackId,
                        title = track?.title ?: "Track ${dl.providerSourceId}",
                        artist = track?.artist ?: "Unknown Artist",
                        album = track?.album,
                        durationMs = dl.durationMs ?: track?.durationMs ?: 0L,
                        artworkUri = track?.artworkUrl ?: track?.artworkUri,
                        fileSizeBytes = dl.fileSizeBytes ?: 0L,
                        bucket = if (dl.bucket == "PINNED") DownloadBucket.PINNED else DownloadBucket.SMART_OFFLINE,
                        qualityProfile = dl.qualityProfile,
                        downloadedAtEpochMs = dl.updatedAtEpochMs,
                        contentUriString = contentUri.toString()
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** User playlists for the PLAYLISTS chip. */
    val playlists: StateFlow<List<PlaylistSummary>> =
        playlistRepository.observePlaylistSummaries()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** All persisted tracks from Room across playlists and imports. */
    val allDbTracks: StateFlow<List<TrackEntity>> =
        database.trackDao().observeAllTracks()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** Converts a DownloadedTrackItem to a playable domain Track. */
    fun toDomainTrack(item: DownloadedTrackItem): Track {
        return Track(
            id = TrackId(ProviderId.SPOTIFY, item.trackId),
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            artworkUri = item.artworkUri,
            source = AudioSource.Local(item.contentUriString)
        )
    }

    /**
     * Aggregates device-scanned tracks, downloaded tracks, and Room playlist tracks,
     * deduplicating by normalized title and artist.
     */
    fun getAggregatedTracks(scannedTracks: List<Track>): List<Track> {
        val downloaded = downloadedTracks.value.map { toDomainTrack(it) }
        val downloadedMap = downloadedTracks.value.associateBy { it.trackId }

        val dbTracks = allDbTracks.value.mapNotNull { entity ->
            val localUri = if (!entity.localContentUri.isNullOrBlank()) {
                entity.localContentUri
            } else {
                downloadedMap[entity.id]?.contentUriString
            }
            val source = if (!localUri.isNullOrBlank()) {
                AudioSource.Local(localUri)
            } else {
                AudioSource.Remote(
                    if (entity.id.startsWith("youtube:")) ProviderId.YOUTUBE else ProviderId.SPOTIFY,
                    entity.id
                )
            }
            Track(
                id = TrackId(if (entity.id.startsWith("youtube:")) ProviderId.YOUTUBE else ProviderId.SPOTIFY, entity.id),
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                durationMs = entity.durationMs,
                artworkUri = entity.artworkUrl ?: entity.artworkUri,
                source = source
            )
        }

        val trackMap = LinkedHashMap<String, Track>()

        // 1. Add DB playlist tracks as baseline
        for (t in dbTracks) {
            val key = "${t.title.trim().lowercase()} - ${t.artist.trim().lowercase()}"
            trackMap[key] = t
        }
        // 2. Add scanned local tracks (prefer over remote DB tracks)
        for (t in scannedTracks) {
            val key = "${t.title.trim().lowercase()} - ${t.artist.trim().lowercase()}"
            trackMap[key] = t
        }
        // 3. Add downloaded tracks (highest priority, guaranteed offline playback)
        for (t in downloaded) {
            val key = "${t.title.trim().lowercase()} - ${t.artist.trim().lowercase()}"
            trackMap[key] = t
        }

        return trackMap.values.toList()
    }

    private val authRepository = com.notify.auth.SupabaseAuthRepository(application)

    /** Resolver used for the JioSaavn→CDN race when a trending card is tapped. */
    private val jioSaavnResolver = JioSaavnStreamResolver(authRepository = authRepository)

    /** SoundCloud fallback if JioSaavn edge function is temporarily unavailable. */
    private val soundCloudResolver = SoundCloudStreamResolver(authRepository = authRepository)

    /** InnerTube search: used concurrently to retrieve the real YouTube videoId for radio seeding. */
    private val innerTubeSearchProvider = InnerTubeYouTubeMusicSearchProvider()

    private val _state = MutableStateFlow<TrendingUiState>(TrendingUiState.Loading)
    val state: StateFlow<TrendingUiState> = _state.asStateFlow()

    private val _artistsState = MutableStateFlow<CuratedArtistsUiState>(CuratedArtistsUiState.Loading)
    val artistsState: StateFlow<CuratedArtistsUiState> = _artistsState.asStateFlow()

    /** Track whose stream is currently being resolved (drives per-card loading indicator). */
    private val _resolvingTrackId = MutableStateFlow<String?>(null)
    val resolvingTrackId: StateFlow<String?> = _resolvingTrackId.asStateFlow()

    init {
        loadTrending()
        loadCuratedArtists()
    }

    /** Called on initial load and on explicit refresh for trending tracks. */
    fun loadTrending(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            // Immediately surface cached data if available
            val cached = repository.getCachedTrending()
            if (cached != null && !forceRefresh) {
                _state.value = TrendingUiState.Success(cached)
            }

            // Always ask repository (it will short-circuit if fresh)
            val result = repository.getTrending(forceRefresh)
            result.onSuccess { tracks ->
                _state.value = if (tracks.isNotEmpty()) {
                    TrendingUiState.Success(tracks)
                } else {
                    TrendingUiState.Hidden
                }
            }.onFailure { err ->
                // Only show error if there's no cache already shown
                if (_state.value !is TrendingUiState.Success) {
                    _state.value = TrendingUiState.Error(err.message ?: "Failed to load trending")
                }
            }
        }
    }

    /** Called on initial load and on explicit refresh for curated artist sections. */
    fun loadCuratedArtists(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val currentFollowed = followedArtistsRepo.getFollowedArtists()
            if (currentFollowed.isEmpty()) {
                _artistsState.value = CuratedArtistsUiState.Hidden
                return@launch
            }

            // Immediately surface cached data if available for all followed artists
            val cached = artistsRepository.getCachedSections(currentFollowed)
            if (cached != null && !forceRefresh) {
                _artistsState.value = CuratedArtistsUiState.Success(cached)
            }

            val result = artistsRepository.getCuratedArtists(currentFollowed, forceRefresh)
            result.onSuccess { sections ->
                _artistsState.value = if (sections.isNotEmpty()) {
                    CuratedArtistsUiState.Success(sections)
                } else {
                    CuratedArtistsUiState.Hidden
                }
            }.onFailure { err ->
                if (_artistsState.value !is CuratedArtistsUiState.Success) {
                    _artistsState.value = CuratedArtistsUiState.Error(err.message ?: "Failed to load artists")
                }
            }
        }
    }

    /**
     * Resolves and plays a track from Trending or an Artist section with context queue.
     *
     * @param track                   The TrendingTrack that was tapped.
     * @param contextTracks           All tracks in the section row (for context playback).
     * @param onPlayStream            Fallback single-track stream player.
     * @param onPlayStreamWithContext Context stream player passing the remaining tracks in order.
     * @param onError                 Invoked when both stream resolvers fail.
     */
    fun playTrendingTrack(
        track: TrendingTrack,
        contextTracks: List<TrendingTrack> = emptyList(),
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit = { _, _, _ -> },
        onPlayStreamWithContext: (Track, String, PlaybackOrigin, List<Track>) -> Unit = { t, u, o, _ -> onPlayStream(t, u, o) },
        onError: () -> Unit = {}
    ) {
        val resolveKey = track.songId.ifBlank { track.query }
        viewModelScope.launch {
            _resolvingTrackId.value = resolveKey
            try {
                val query = buildJioSaavnQuery(track)
                Log.i(TAG, "TRENDING_RESOLVE_START key=$resolveKey query=\"$query\"")

                // ── Run JioSaavn stream resolution and InnerTube search concurrently ────────
                val innerTubeDeferred = async {
                    withTimeoutOrNull(3000L) {
                        innerTubeSearchProvider.search(
                            query = "${track.artist} ${track.title}".trim(),
                            limit = 3
                        ).getOrNull()?.firstOrNull()?.videoId
                    }
                }

                val jioResult = jioSaavnResolver.resolveStream(
                    query = query,
                    videoId = track.songId.takeIf { it.isNotBlank() },
                    expectedDurationMs = track.durationMs
                )

                // Collect YouTube videoId (may arrive before or after stream resolves)
                val youtubeVideoId: String? = innerTubeDeferred.await()

                val contextDomainTracks = buildContextTracks(track, contextTracks)

                if (jioResult.isSuccess) {
                    val stream = jioResult.getOrThrow()
                    Log.i(TAG, "TRENDING_RESOLVE_OK stage=JIOSAAVN key=$resolveKey " +
                        "format=${stream.formatId} youtubeId=$youtubeVideoId contextSize=${contextDomainTracks.size}")
                    val domainTrack = track.toDomainTrack(
                        resolvedVideoId = resolveKey,
                        youtubeVideoId = youtubeVideoId
                    )
                    onPlayStreamWithContext(domainTrack, stream.streamUrl, PlaybackOrigin.USER_SEARCH_SELECTION, contextDomainTracks)
                    return@launch
                }

                // ── JioSaavn failed → try SoundCloud ──────────────────────────────────────
                Log.w(TAG, "TRENDING_RESOLVE_JIOSAAVN_FAIL key=$resolveKey " +
                    "reason=${jioResult.exceptionOrNull()?.message}. Trying SoundCloud.")
                val scResult = soundCloudResolver.resolveStream(
                    query = query,
                    videoId = track.songId.takeIf { it.isNotBlank() },
                    expectedDurationMs = track.durationMs
                )

                if (scResult.isSuccess) {
                    val stream = scResult.getOrThrow()
                    Log.i(TAG, "TRENDING_RESOLVE_OK stage=SOUNDCLOUD key=$resolveKey " +
                        "format=${stream.formatId} youtubeId=$youtubeVideoId contextSize=${contextDomainTracks.size}")
                    val domainTrack = track.toDomainTrack(
                        resolvedVideoId = resolveKey,
                        youtubeVideoId = youtubeVideoId
                    )
                    onPlayStreamWithContext(domainTrack, stream.streamUrl, PlaybackOrigin.USER_SEARCH_SELECTION, contextDomainTracks)
                } else {
                    Log.w(TAG, "TRENDING_RESOLVE_FAIL key=$resolveKey — JioSaavn + SoundCloud both failed")
                    SnackbarManager.emit(SnackbarEvent.PlayFailed)
                    onError()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "TRENDING_RESOLVE_EXCEPTION key=$resolveKey: ${e.message}")
                SnackbarManager.emit(SnackbarEvent.PlayFailed)
                onError()
            } finally {
                _resolvingTrackId.value = null
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds domain tracks for the remaining tracks in the section row, ordered circularly.
     */
    private fun buildContextTracks(
        tappedTrack: TrendingTrack,
        contextTracks: List<TrendingTrack>
    ): List<Track> {
        if (contextTracks.size <= 1) return emptyList()

        val tappedIndex = contextTracks.indexOfFirst {
            (it.songId.isNotBlank() && it.songId == tappedTrack.songId) ||
            (it.query.isNotBlank() && it.query == tappedTrack.query) ||
            (it.title == tappedTrack.title && it.artist == tappedTrack.artist)
        }
        val ordered = if (tappedIndex >= 0) {
            contextTracks.drop(tappedIndex + 1) + contextTracks.take(tappedIndex)
        } else {
            contextTracks.filter { it.songId != tappedTrack.songId || it.title != tappedTrack.title }
        }
        return ordered.map { t ->
            t.toDomainTrack(resolvedVideoId = t.songId.ifBlank { t.query })
        }
    }

    /**
     * Builds the best search query for JioSaavn resolution.
     * Prefers "Artist Title" order which the JioSaavn edge function matches better.
     */
    private fun buildJioSaavnQuery(track: TrendingTrack): String {
        val artist = track.artist.trim()
        val title = track.title.trim()
        return if (artist.isNotBlank() && title.isNotBlank()) {
            "$artist $title"
        } else {
            track.query.trim().ifBlank { title }
        }
    }

    /**
     * Converts a [TrendingTrack] into a domain [Track] suitable for playback.
     *
     * Fix 2b: When [youtubeVideoId] is non-null (found via InnerTube search), stamps the Track
     * with ProviderId.YOUTUBE. This makes RadioWindowManager.extractVideoId() return the videoId
     * instead of null, enabling WATCH_NEXT to fire and auto-queue radio recommendations.
     *
     * Without this fix: CUSTOM_RESOLVER tracks → extractVideoId returns null → the
     * `if (neededCandidates > 0 && !seedVideoId.isNullOrBlank())` guard in RadioWindowManager
     * never passes → WATCH_NEXT is never called → no autoplay after trending songs.
     *
     * @param resolvedVideoId  Fallback identifier (JioSaavn songId or query-hash) when no YouTube ID.
     * @param youtubeVideoId   Real YouTube videoId from InnerTube search (null if search timed out).
     */
    private fun TrendingTrack.toDomainTrack(
        resolvedVideoId: String,
        youtubeVideoId: String? = null
    ): Track {
        val effectiveVideoId = youtubeVideoId?.takeIf { it.isNotBlank() }
        // Use YOUTUBE provider when we have a real videoId so extractVideoId() succeeds
        val provider = if (effectiveVideoId != null) ProviderId.YOUTUBE else ProviderId.CUSTOM_RESOLVER
        val sourceId = effectiveVideoId ?: resolvedVideoId
        return Track(
            id = TrackId(provider = provider, rawId = sourceId),
            title = title,
            artist = artist,
            album = null,
            durationMs = durationMs ?: 0L,
            artworkUri = albumArtUrl.ifBlank { null },
            source = AudioSource.Remote(provider = provider, sourceId = sourceId)
        )
    }

    companion object {
        private const val TAG = "TrendingViewModel"
    }
}

typealias TrendingViewModel = HomeViewModel
