package com.notify.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.PlaylistSummary
import com.notify.download.db.RecentSearchItemEntity
import com.notify.download.db.SearchCandidateCacheEntity
import com.notify.download.db.SearchHistoryDao
import com.notify.download.db.SearchHistoryEntity
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.spike.YtDlpRuntime
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.OnlineStreamResolver
import com.notify.download.stream.ResolvedStreamProviderChain
import com.notify.download.stream.StreamUrlCache
import com.notify.playback.StartupMetricsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages online YouTube Music search state for SearchScreen.
 *
 * Two-stage search process:
 * 1. Primary fast search: InnerTube HTTP/JSON YouTube Music client (<500ms).
 * 2. Compatibility fallback: yt-dlp native extraction (executed under single-flight fallbackMutex)
 *    if InnerTube fails, throws, times out, or returns 0 candidates.
 *
 * Persistent search history:
 * - Stored in Room via [SearchHistoryDao], bounded to 15 entries (atomic transaction).
 * - Candidate cache stored per normalized query with 7-day TTL.
 *   Fresh cache (<7 days): displayed immediately, network skipped.
 *   Stale cache (>=7 days): displayed immediately, refresh runs in background.
 *
 * Invariants:
 * - Monotonically increasing requestId discards stale or superseded results.
 * - Normalized query caching via thread-safe LRU session cache (in-process only).
 * - Search triggers only upon explicit user submission, not on every keystroke.
 * - Signed stream URLs and auth cookies are NEVER persisted to Room.
 */
class OnlineSearchViewModel(
    application: Application,
    private val innerTubeProvider: OnlineCatalogSearchProvider = InnerTubeYouTubeMusicSearchProvider(),
    private val fallbackProvider: OnlineCatalogSearchProvider = YtDlpYouTubeSearchProvider(application),
    private val streamResolver: AudioStreamResolver = ResolvedStreamProviderChain(application),
    private val searchHistoryDao: SearchHistoryDao? = null,
    private val playlistRepository: PlaylistRepository? = null
) : AndroidViewModel(application) {

    enum class AddToPlaylistUiResult {
        ADDED,
        ALREADY_EXISTS,
        FAILED
    }

    /**
     * Explicit ViewModelProvider.Factory for OnlineSearchViewModel.
     * Prevents reflection failures and supports test provider injection.
     */
    class Factory(
        private val application: Application,
        private val innerTubeProvider: OnlineCatalogSearchProvider = InnerTubeYouTubeMusicSearchProvider(),
        private val fallbackProvider: OnlineCatalogSearchProvider = YtDlpYouTubeSearchProvider(application),
        private val streamResolver: AudioStreamResolver = ResolvedStreamProviderChain(application),
        private val searchHistoryDao: SearchHistoryDao? = null,
        private val playlistRepository: PlaylistRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OnlineSearchViewModel::class.java)) {
                return OnlineSearchViewModel(
                    application = application,
                    innerTubeProvider = innerTubeProvider,
                    fallbackProvider = fallbackProvider,
                    streamResolver = streamResolver,
                    searchHistoryDao = searchHistoryDao,
                    playlistRepository = playlistRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        private const val TAG = "OnlineSearchVM"
        private const val SEARCH_LIMIT = 5
        private const val INNERTUBE_TIMEOUT_MS = 8_000L
        private const val FALLBACK_TIMEOUT_MS = 25_000L
        private const val SLOW_WARNING_DELAY_MS = 3_000L

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: Throwable) {}
        }
        private fun logW(msg: String, throwable: Throwable? = null) {
            try { Log.w(TAG, msg, throwable) } catch (_: Throwable) {}
        }
        private fun logE(msg: String, throwable: Throwable? = null) {
            try { Log.e(TAG, msg, throwable) } catch (_: Throwable) {}
        }
    }

    private val _uiState = MutableStateFlow<OnlineSearchUiState>(OnlineSearchUiState.Idle)
    val uiState: StateFlow<OnlineSearchUiState> = _uiState.asStateFlow()

    private val _resolvingVideoId = MutableStateFlow<String?>(null)
    val resolvingVideoId: StateFlow<String?> = _resolvingVideoId.asStateFlow()

    /**
     * Track ID currently selected for adding to playlist (e.g. in the Add-to-Playlist sheet).
     */
    private val _selectedTrackIdForPlaylist = MutableStateFlow<String?>(null)
    val selectedTrackIdForPlaylist: StateFlow<String?> = _selectedTrackIdForPlaylist.asStateFlow()

    fun setSelectedTrackForPlaylist(trackId: String?) {
        _selectedTrackIdForPlaylist.value = trackId
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playlistsContainingSelectedTrack: StateFlow<Set<String>> =
        _selectedTrackIdForPlaylist.flatMapLatest { trackId ->
            if (trackId == null || playlistRepository == null) {
                kotlinx.coroutines.flow.flowOf(emptySet())
            } else {
                playlistRepository.observePlaylistsContainingTrack(trackId)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet()
        )

    /**
     * Emits up to 15 most-recently-submitted unique searches, sorted newest-first.
     * Backed by Room; null when no [searchHistoryDao] is provided (test mode).
     */
    val recentSearches: StateFlow<List<SearchHistoryEntity>> =
        (searchHistoryDao?.observeRecentSearches(15)
            ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /**
     * Emits up to 15 most-recently-tapped/played song items, sorted newest-first.
     * Primary Spotify-style recents experience on idle search.
     */
    val recentMediaItems: StateFlow<List<RecentSearchItemEntity>> =
        (searchHistoryDao?.observeRecentMediaItems(15)
            ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /**
     * User's existing playlists for the Add-to-Playlist bottom sheet / dialog.
     */
    val userPlaylists: StateFlow<List<PlaylistSummary>> =
        (playlistRepository?.observePlaylistSummaries()
            ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    private val fallbackMutex = Mutex()
    private var currentRequestId = 0L
    private var searchJob: Job? = null
    private var lastSubmittedQuery: String = ""
    private var searchPreloadJob: Job? = null

    private fun preloadTopVisibleCandidates(candidates: List<YouTubeCandidate>) {
        searchPreloadJob?.cancel()
        if (candidates.isEmpty()) return
        searchPreloadJob = viewModelScope.launch(Dispatchers.IO) {
            val topCandidates = candidates.take(2)
            for (candidate in topCandidates) {
                if (!isActive) break
                if (StreamUrlCache.get(candidate.videoId) != null) continue
                try {
                    val canonicalUrl = "https://www.youtube.com/watch?v=${candidate.videoId}"
                    streamResolver.resolveStream(canonicalUrl)
                } catch (_: Throwable) {
                    // Non-blocking background pre-resolution
                }
            }
        }
    }

    // In-memory thread-safe LRU session cache (key: normalized query -> candidates)
    private val sessionCache = object {
        private val maxSize = 50
        private val map = object : LinkedHashMap<String, List<YouTubeCandidate>>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<YouTubeCandidate>>?): Boolean {
                return size > maxSize
            }
        }

        @Synchronized fun get(key: String): List<YouTubeCandidate>? = map[key]
        @Synchronized fun put(key: String, value: List<YouTubeCandidate>) { map[key] = value }
        @Synchronized fun clear() { map.clear() }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun List<SearchCandidateCacheEntity>.toYouTubeCandidates(): List<YouTubeCandidate> =
        map { c ->
            YouTubeCandidate(
                videoId = c.providerSourceId,
                title = c.title,
                channelTitle = c.artist,
                album = c.album,
                durationMs = c.durationMs,
                artworkUrl = c.artworkUrl,
                provider = c.provider
            )
        }

    private fun List<YouTubeCandidate>.toCacheEntities(normalizedQuery: String, nowMs: Long): List<SearchCandidateCacheEntity> =
        mapIndexed { index, c ->
            SearchCandidateCacheEntity(
                normalizedQuery = normalizedQuery,
                resultRank = index,
                provider = c.provider ?: "youtube_music_innertube",
                providerSourceId = c.videoId,
                title = c.title,
                artist = c.channelTitle ?: "",
                album = c.album,
                durationMs = c.durationMs,
                artworkUrl = c.artworkUrl,
                canonicalWatchUrl = c.canonicalWatchUrl,
                cachedAtEpochMs = nowMs
            )
        }

    // ── Search ─────────────────────────────────────────────────────────────────

    /**
     * Executes an online search for [query].
     * Checks Room candidate cache first (fresh = immediate; stale = show+refresh).
     * Uses fast InnerTube search as primary, falling back to yt-dlp if needed.
     * Records the query in persistent search history (max 15 entries).
     */
    fun searchOnline(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.value = OnlineSearchUiState.Idle
            return
        }

        lastSubmittedQuery = trimmed
        val normalizedKey = trimmed.lowercase().replace(Regex("\\s+"), " ")

        // 1. In-process session cache hit: return immediately without any IO
        val sessionCached = sessionCache.get(normalizedKey)
        if (sessionCached != null) {
            logD("Session cache hit for \"$trimmed\": ${sessionCached.size} candidates")
            _uiState.value = if (sessionCached.isEmpty()) {
                OnlineSearchUiState.Empty(trimmed)
            } else {
                preloadTopVisibleCandidates(sessionCached)
                OnlineSearchUiState.Results(candidates = sessionCached, query = trimmed)
            }
            recordSearchHistory(trimmed, normalizedKey)
            return
        }

        val requestId = ++currentRequestId
        searchJob?.cancel()
        searchPreloadJob?.cancel()
        searchPreloadJob = null

        searchJob = viewModelScope.launch {
            _uiState.value = OnlineSearchUiState.Searching(
                query = trimmed,
                progressText = "Searching YouTube Music…"
            )

            // 2. Room candidate cache check (background IO)
            val now = System.currentTimeMillis()
            val roomCached = searchHistoryDao?.getCachedCandidatesOrNull(normalizedKey)
            if (roomCached != null) {
                val isFresh = (now - roomCached.first().cachedAtEpochMs) < SearchHistoryDao.CACHE_TTL_MS
                val cachedCandidates = roomCached.toYouTubeCandidates()
                sessionCache.put(normalizedKey, cachedCandidates)

                // Display cached results immediately regardless of freshness
                if (requestId == currentRequestId) {
                    _uiState.value = if (cachedCandidates.isEmpty()) {
                        OnlineSearchUiState.Empty(trimmed)
                    } else {
                        preloadTopVisibleCandidates(cachedCandidates)
                        OnlineSearchUiState.Results(candidates = cachedCandidates, query = trimmed)
                    }
                    recordSearchHistory(trimmed, normalizedKey)
                }

                if (isFresh) {
                    logD("Room cache fresh for \"$trimmed\" (${roomCached.first().cachedAtEpochMs}). Skipping network.")
                    return@launch
                }

                // Stale: refresh in background without blocking the user
                logD("Room cache stale for \"$trimmed\". Refreshing in background.")
                refreshCandidatesInBackground(normalizedKey, trimmed, requestId)
                return@launch
            }

            // 3. Timer for "Taking longer than usual..."
            val slowWarningJob = launch {
                delay(SLOW_WARNING_DELAY_MS)
                if (requestId == currentRequestId && _uiState.value is OnlineSearchUiState.Searching) {
                    _uiState.value = OnlineSearchUiState.Searching(
                        query = trimmed,
                        progressText = "Taking longer than usual…"
                    )
                }
            }

            try {
                val candidates = fetchCandidatesFromNetwork(trimmed, normalizedKey, requestId)
                slowWarningJob.cancel()
                if (requestId != currentRequestId) return@launch

                if (candidates != null) {
                    sessionCache.put(normalizedKey, candidates)
                    persistCandidatesToRoom(normalizedKey, candidates, now)
                    recordSearchHistory(trimmed, normalizedKey)
                    _uiState.value = if (candidates.isEmpty()) {
                        OnlineSearchUiState.Empty(trimmed)
                    } else {
                        preloadTopVisibleCandidates(candidates)
                        OnlineSearchUiState.Results(candidates = candidates, query = trimmed)
                    }
                }
                // If fetchCandidatesFromNetwork already set an error state, do nothing more
            } catch (e: Exception) {
                slowWarningJob.cancel()
                if (requestId != currentRequestId) return@launch
                logE("Unexpected search error on request #$requestId", e)
                _uiState.value = OnlineSearchUiState.Error(
                    message = e.message ?: "Unexpected error",
                    isNetworkError = false,
                    retryableQuery = trimmed
                )
            }
        }
    }

    /**
     * Runs a background network refresh for a stale cache entry.
     * Replaces Room and session cache with fresh results.
     * Does NOT update the UI unless the state is still showing the stale results.
     */
    private fun refreshCandidatesInBackground(normalizedKey: String, displayQuery: String, originalRequestId: Long) {
        viewModelScope.launch {
            try {
                val candidates = fetchCandidatesFromNetwork(displayQuery, normalizedKey, originalRequestId)
                if (candidates != null) {
                    val now = System.currentTimeMillis()
                    sessionCache.put(normalizedKey, candidates)
                    persistCandidatesToRoom(normalizedKey, candidates, now)
                    // Only update UI if this query is still the currently displayed one
                    val current = _uiState.value
                    if (current is OnlineSearchUiState.Results && current.query == displayQuery) {
                        preloadTopVisibleCandidates(candidates)
                        _uiState.value = OnlineSearchUiState.Results(candidates = candidates, query = displayQuery)
                    }
                }
            } catch (_: Exception) {
                // Silent failure: stale cached results remain visible
            }
        }
    }

    /**
     * Performs the two-phase network search (InnerTube → yt-dlp fallback).
     * Returns the list of candidates, or null if an error state was already set.
     * Request staleness is checked; stale requests return null silently.
     */
    private suspend fun fetchCandidatesFromNetwork(
        trimmed: String,
        normalizedKey: String,
        requestId: Long
    ): List<YouTubeCandidate>? {
        // Phase 1: Try fast InnerTube search
        logD("Starting primary InnerTube search for: \"$trimmed\" (req #$requestId)")
        val innerTubeResult = withTimeoutOrNull(INNERTUBE_TIMEOUT_MS) {
            innerTubeProvider.search(trimmed, limit = SEARCH_LIMIT)
        }

        if (requestId != currentRequestId) {
            logD("Discarding stale primary search for request #$requestId (current is #$currentRequestId)")
            return null
        }

        val primaryCandidates = innerTubeResult?.getOrNull()
        if (innerTubeResult != null && innerTubeResult.isSuccess && !primaryCandidates.isNullOrEmpty()) {
            logD("InnerTube succeeded with ${primaryCandidates.size} candidates for \"$trimmed\"")
            return primaryCandidates
        }

        // Phase 2: InnerTube failed → yt-dlp fallback
        val fallbackReason = when {
            innerTubeResult == null -> "Primary search timed out"
            innerTubeResult.isFailure -> "Primary search failed: ${innerTubeResult.exceptionOrNull()?.message}"
            else -> "Primary search returned 0 items"
        }
        logW("InnerTube unsuccessful ($fallbackReason). Transitioning to fallback...")

        _uiState.value = OnlineSearchUiState.TryingFallback(
            query = trimmed,
            reason = "Trying compatibility fallback…"
        )

        val fallbackResult = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            fallbackMutex.withLock {
                if (requestId != currentRequestId) return@withLock null
                logD("Executing fallback search for: \"$trimmed\" (req #$requestId)")
                fallbackProvider.search(trimmed, limit = SEARCH_LIMIT)
            }
        }

        if (requestId != currentRequestId) {
            logD("Discarding stale fallback result for request #$requestId")
            return null
        }

        if (fallbackResult == null) {
            logW("Fallback search request #$requestId timed out after ${FALLBACK_TIMEOUT_MS}ms")
            _uiState.value = OnlineSearchUiState.Error(
                message = "Search timed out. Upstream servers may be slow.",
                isNetworkError = true,
                retryableQuery = trimmed
            )
            return null
        }

        if (fallbackResult.isFailure) {
            val ex = fallbackResult.exceptionOrNull()
            val msg = ex?.message ?: "Search failed"
            val isNet = msg.contains("network", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("unable to resolve", ignoreCase = true) ||
                    msg.contains("connect", ignoreCase = true)
            logE("Fallback search request #$requestId failed: $msg")
            _uiState.value = OnlineSearchUiState.Error(
                message = msg,
                isNetworkError = isNet,
                retryableQuery = trimmed
            )
            return null
        }

        val fallbackCandidates = fallbackResult.getOrThrow()
        logD("Fallback search request #$requestId succeeded with ${fallbackCandidates.size} candidates")
        return fallbackCandidates
    }

    private fun persistCandidatesToRoom(normalizedKey: String, candidates: List<YouTubeCandidate>, nowMs: Long) {
        viewModelScope.launch {
            try {
                val entities = candidates.toCacheEntities(normalizedKey, nowMs)
                searchHistoryDao?.replaceCandidates(normalizedKey, entities)
            } catch (e: Exception) {
                logW("Failed to persist candidate cache for \"$normalizedKey\"", e)
            }
        }
    }

    private fun recordSearchHistory(displayQuery: String, normalizedKey: String) {
        viewModelScope.launch {
            try {
                searchHistoryDao?.recordSearch(displayQuery)
            } catch (e: Exception) {
                logW("Failed to record search history for \"$normalizedKey\"", e)
            }
        }
    }

    // ── Recent Searches ────────────────────────────────────────────────────────

    /** Deletes a single recent search entry and its cached candidates. */
    fun deleteRecentSearch(normalizedQuery: String) {
        viewModelScope.launch {
            try {
                searchHistoryDao?.deleteSearch(normalizedQuery)
            } catch (e: Exception) {
                logW("Failed to delete recent search: $normalizedQuery", e)
            }
        }
    }

    /** Clears all persistent search history and candidate cache. */
    fun clearSearchHistory() {
        viewModelScope.launch {
            try {
                searchHistoryDao?.clearAllHistory()
            } catch (e: Exception) {
                logW("Failed to clear search history", e)
            }
        }
    }

    // ── Query lifecycle ────────────────────────────────────────────────────────

    /**
     * Called when the query text is modified.
     * Clears to Idle if query becomes blank; does NOT trigger search on keystroke.
     */
    fun onQueryChanged(query: String) {
        if (query.isBlank()) {
            clearSearch()
        }
    }

    /** Cancels any active search and sets state to Cancelled. */
    fun cancelSearch() {
        currentRequestId++
        searchJob?.cancel()
        searchJob = null
        searchPreloadJob?.cancel()
        searchPreloadJob = null
        _uiState.value = OnlineSearchUiState.Cancelled
    }

    /** Retries the last submitted query. */
    fun retry() {
        if (lastSubmittedQuery.isNotBlank()) {
            searchOnline(lastSubmittedQuery)
        }
    }

    /**
     * Resolves the playable direct audio stream for a [candidate] using [streamResolver],
     * then dispatches the domain track and stream URL to [onPlayStream].
     * Maps artworkUrl from the candidate into the domain track and Media3 metadata.
     */
    fun playCandidate(
        candidate: YouTubeCandidate,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit
    ) {
        viewModelScope.launch {
            _resolvingVideoId.value = candidate.videoId
            val requestId = "req_${System.currentTimeMillis()}_${candidate.videoId}"
            StartupMetricsLogger.onPlayTap(requestId, candidate.videoId, sessionId = 0L)
            StartupMetricsLogger.onResolveStart(requestId, candidate.videoId, provider = "youtube")
            try {
                // Priority 1: Check if already available in NotiFy offline downloads
                val downloadManager = com.notify.download.engine.OfflineDownloadManager(getApplication())
                val offlineUri = downloadManager.getOfflinePlaybackUriForSource(candidate.videoId)
                if (offlineUri != null) {
                    logD("Candidate ${candidate.videoId} available offline, playing locally: $offlineUri")
                    StartupMetricsLogger.onResolveComplete(requestId)
                    val domainTrack = Track(
                        id = TrackId(provider = ProviderId.YOUTUBE, rawId = candidate.videoId),
                        title = candidate.title,
                        artist = candidate.channelTitle ?: "",
                        album = candidate.album,
                        durationMs = candidate.durationMs,
                        artworkUri = candidate.artworkUrl,
                        source = AudioSource.Local(offlineUri.toString())
                    )
                    onPlayStream(domainTrack, offlineUri.toString(), PlaybackOrigin.USER_SEARCH_SELECTION)
                    return@launch
                }

                val canonicalUrl = "https://www.youtube.com/watch?v=${candidate.videoId}"
                logD("Resolving stream URL for: $canonicalUrl")

                val streamResult = streamResolver.resolveStream(
                    canonicalYoutubeUrl = canonicalUrl,
                    title = candidate.title,
                    artist = candidate.channelTitle
                )
                if (streamResult.isFailure) {
                    val err = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                    logE("Stream resolution failed for ${candidate.videoId}: $err")
                    _uiState.value = OnlineSearchUiState.Error("Stream resolution failed: $err")
                    return@launch
                }

                val resolvedStream = streamResult.getOrThrow()
                StartupMetricsLogger.onResolveComplete(requestId)
                logD("Resolved stream URL for ${candidate.videoId} (format: ${resolvedStream.formatId})")

                val domainTrack = Track(
                    id = TrackId(provider = ProviderId.YOUTUBE, rawId = candidate.videoId),
                    title = candidate.title,
                    artist = candidate.channelTitle ?: "",
                    album = candidate.album,
                    durationMs = candidate.durationMs,
                    artworkUri = candidate.artworkUrl,
                    source = AudioSource.Remote(ProviderId.YOUTUBE, candidate.videoId)
                )

                onPlayStream(domainTrack, resolvedStream.streamUrl, PlaybackOrigin.USER_SEARCH_SELECTION)
            } catch (e: Exception) {
                logE("Failed to resolve and play candidate: ${candidate.videoId}", e)
                _uiState.value = OnlineSearchUiState.Error("Playback error: ${e.message}")
            } finally {
                _resolvingVideoId.value = null
            }
        }
    }

    /**
     * Directly plays a saved recent media item using its stable provider + providerSourceId,
     * bypassing title search and matching.
     */
    fun playRecentMediaItem(
        item: RecentSearchItemEntity,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit
    ) {
        viewModelScope.launch {
            _resolvingVideoId.value = item.providerSourceId
            val requestId = "req_${System.currentTimeMillis()}_${item.providerSourceId}"
            StartupMetricsLogger.onPlayTap(requestId, item.providerSourceId, sessionId = 0L)
            StartupMetricsLogger.onResolveStart(requestId, item.providerSourceId, provider = item.provider)
            try {
                // Priority 1: Check offline availability
                val downloadManager = com.notify.download.engine.OfflineDownloadManager(getApplication())
                val offlineUri = downloadManager.getOfflinePlaybackUriForSource(item.providerSourceId)
                if (offlineUri != null) {
                    StartupMetricsLogger.onResolveComplete(requestId)
                    val providerId = when (item.provider.trim().lowercase()) {
                        "youtube", "youtube_music", "youtube_music_innertube" -> ProviderId.YOUTUBE
                        "spotify" -> ProviderId.SPOTIFY
                        else -> ProviderId.CUSTOM_RESOLVER
                    }
                    val domainTrack = Track(
                        id = TrackId(provider = providerId, rawId = item.providerSourceId),
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs ?: 0L,
                        artworkUri = item.artworkUrl,
                        source = AudioSource.Local(offlineUri.toString())
                    )
                    onPlayStream(domainTrack, offlineUri.toString(), PlaybackOrigin.SEARCH_HISTORY_SELECTION)
                    return@launch
                }

                // Construct canonical URL via provider + sourceId
                val canonicalUrl = when (item.provider.trim().lowercase()) {
                    "youtube", "youtube_music", "youtube_music_innertube" ->
                        "https://www.youtube.com/watch?v=${item.providerSourceId.trim()}"
                    else -> item.providerSourceId.trim()
                }

                val streamResult = streamResolver.resolveStream(
                    canonicalYoutubeUrl = canonicalUrl,
                    title = item.title,
                    artist = item.artist
                )
                if (streamResult.isFailure) {
                    val err = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                    _uiState.value = OnlineSearchUiState.Error("Stream resolution failed: $err")
                    return@launch
                }

                val resolvedStream = streamResult.getOrThrow()
                StartupMetricsLogger.onResolveComplete(requestId)

                val providerId = when (item.provider.trim().lowercase()) {
                    "youtube", "youtube_music", "youtube_music_innertube" -> ProviderId.YOUTUBE
                    "spotify" -> ProviderId.SPOTIFY
                    else -> ProviderId.CUSTOM_RESOLVER
                }

                val domainTrack = Track(
                    id = TrackId(provider = providerId, rawId = item.providerSourceId),
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.durationMs ?: 0L,
                    artworkUri = item.artworkUrl,
                    source = AudioSource.Remote(providerId, item.providerSourceId)
                )

                onPlayStream(domainTrack, resolvedStream.streamUrl, PlaybackOrigin.SEARCH_HISTORY_SELECTION)
            } catch (e: Exception) {
                _uiState.value = OnlineSearchUiState.Error("Playback error: ${e.message}")
            } finally {
                _resolvingVideoId.value = null
            }
        }
    }

    /** Deletes a single recent media item (removes only from Recents). */
    fun deleteRecentMediaItem(id: String) {
        viewModelScope.launch {
            try {
                searchHistoryDao?.deleteRecentMediaItem(id)
            } catch (e: Exception) {
                logW("Failed to delete recent media item: $id", e)
            }
        }
    }

    /** Clears all visible recent media items with confirmation. */
    fun clearAllRecentMediaItems() {
        viewModelScope.launch {
            try {
                searchHistoryDao?.clearAllRecentMediaItems()
            } catch (e: Exception) {
                logW("Failed to clear all recent media items", e)
            }
        }
    }

    /** Adds a recent media item to a user playlist if absent. */
    fun addRecentItemToPlaylist(
        playlistId: String,
        item: RecentSearchItemEntity,
        onResult: (AddToPlaylistUiResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val res = playlistRepository?.addTrackToPlaylistIfAbsent(
                    playlistId = playlistId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.durationMs ?: 0L,
                    artworkUrl = item.artworkUrl,
                    provider = item.provider,
                    providerSourceId = item.providerSourceId
                )
                when (res) {
                    is com.notify.download.db.AddTrackResult.Added -> onResult(AddToPlaylistUiResult.ADDED)
                    is com.notify.download.db.AddTrackResult.AlreadyExists -> onResult(AddToPlaylistUiResult.ALREADY_EXISTS)
                    else -> onResult(AddToPlaylistUiResult.FAILED)
                }
            } catch (_: Exception) {
                onResult(AddToPlaylistUiResult.FAILED)
            }
        }
    }

    /** Adds a search candidate to a user playlist if absent. */
    fun addCandidateToPlaylist(
        playlistId: String,
        candidate: YouTubeCandidate,
        onResult: (AddToPlaylistUiResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val res = playlistRepository?.addTrackToPlaylistIfAbsent(
                    playlistId = playlistId,
                    title = candidate.title,
                    artist = candidate.channelTitle ?: "Unknown Artist",
                    album = candidate.album,
                    durationMs = candidate.durationMs,
                    artworkUrl = candidate.artworkUrl,
                    provider = candidate.provider ?: "youtube",
                    providerSourceId = candidate.videoId
                )
                when (res) {
                    is com.notify.download.db.AddTrackResult.Added -> onResult(AddToPlaylistUiResult.ADDED)
                    is com.notify.download.db.AddTrackResult.AlreadyExists -> onResult(AddToPlaylistUiResult.ALREADY_EXISTS)
                    else -> onResult(AddToPlaylistUiResult.FAILED)
                }
            } catch (_: Exception) {
                onResult(AddToPlaylistUiResult.FAILED)
            }
        }
    }

    /** Creates a new playlist from the Add-to-Playlist chooser. */
    fun createPlaylist(title: String, onComplete: (PlaylistEntity?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(title)
                onComplete(pl)
            } catch (_: Exception) {
                onComplete(null)
            }
        }
    }

    /** Creates a new playlist and immediately adds a recent media item to it. */
    fun createPlaylistAndAddRecentItem(title: String, item: RecentSearchItemEntity) {
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(title)
                if (pl != null) {
                    addRecentItemToPlaylist(pl.playlistId, item)
                }
            } catch (_: Exception) {}
        }
    }

    /** Creates a new playlist and immediately adds a search candidate to it. */
    fun createPlaylistAndAddCandidate(title: String, candidate: YouTubeCandidate) {
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(title)
                if (pl != null) {
                    addCandidateToPlaylist(pl.playlistId, candidate)
                }
            } catch (_: Exception) {}
        }
    }

    /** Clears current search results and resets to Idle. */
    fun clearSearch() {
        currentRequestId++
        searchJob?.cancel()
        searchJob = null
        searchPreloadJob?.cancel()
        searchPreloadJob = null
        _uiState.value = OnlineSearchUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        currentRequestId++
        searchJob?.cancel()
        searchPreloadJob?.cancel()
    }
}
