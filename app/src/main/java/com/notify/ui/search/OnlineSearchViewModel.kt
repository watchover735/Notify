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
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistTrackWithPlaylist
import com.notify.ui.library.DownloadedTrackItem
import com.notify.core.model.DownloadBucket
import com.notify.download.engine.OfflineDownloadManager
import com.notify.ui.SnackbarEvent
import com.notify.ui.SnackbarManager
import kotlinx.coroutines.flow.map

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
 * Result model for unified local search across downloads, playlists, and device tracks.
 */
data class LocalSearchResult(
    val track: Track,
    val badgeText: String,
    val isDownloaded: Boolean = false
)

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

    private val database by lazy { NotiFyDatabase.getInstance(getApplication()) }
    private val downloadManager by lazy { OfflineDownloadManager(getApplication()) }

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

    val playlistTracks: StateFlow<List<PlaylistTrackWithPlaylist>> =
        database.playlistDao().observeAllPlaylistTracks()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /**
     * Unified local search across:
     * - Downloaded tracks (marked with Downloaded badge, plays offline copy)
     * - All playlists including Liked Songs & imported Spotify playlists (marked with playlist title badge)
     * - Scanned device storage (MediaStore / SAF tracks)
     *
     * Deduplicates across playlists and local storage by normalized title & artist.
     */
    fun searchLocalLibrary(
        query: String,
        scannedTracks: List<com.notify.core.local.LocalAudioItem>
    ): List<LocalSearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val qNoSpace = q.replace(" ", "")

        fun matches(title: String, artist: String, album: String?): Boolean {
            val t = title.lowercase()
            val a = artist.lowercase()
            val al = album?.lowercase() ?: ""
            if (t.contains(q) || a.contains(q) || al.contains(q)) return true
            val tClean = t.replace(" ", "")
            val aClean = a.replace(" ", "")
            return tClean.contains(qNoSpace) || aClean.contains(qNoSpace)
        }

        val downloadedList = downloadedTracks.value
        val downloadedMap = downloadedList.associateBy { it.trackId }
        val allPlaylistList = playlistTracks.value

        val rawResults = mutableListOf<LocalSearchResult>()

        // 1. Downloaded tracks (highest fidelity offline playback)
        for (dl in downloadedList) {
            if (matches(dl.title, dl.artist, dl.album)) {
                val track = Track(
                    id = TrackId(ProviderId.SPOTIFY, dl.trackId),
                    title = dl.title,
                    artist = dl.artist,
                    album = dl.album,
                    durationMs = dl.durationMs,
                    artworkUri = dl.artworkUri,
                    source = AudioSource.Local(dl.contentUriString)
                )
                rawResults.add(
                    LocalSearchResult(
                        track = track,
                        badgeText = "Downloaded",
                        isDownloaded = true
                    )
                )
            }
        }

        // 2. Playlists (including Liked Songs & imported Spotify playlists)
        for (pt in allPlaylistList) {
            if (matches(pt.title, pt.artist, pt.album)) {
                val isDl = downloadedMap.containsKey(pt.trackId)
                val dlItem = downloadedMap[pt.trackId]
                val localUri = dlItem?.contentUriString ?: pt.localContentUri
                val source = if (!localUri.isNullOrBlank()) {
                    AudioSource.Local(localUri)
                } else {
                    AudioSource.Remote(
                        if (pt.trackId.startsWith("youtube:")) ProviderId.YOUTUBE else ProviderId.SPOTIFY,
                        pt.trackId
                    )
                }
                val track = Track(
                    id = TrackId(if (pt.trackId.startsWith("youtube:")) ProviderId.YOUTUBE else ProviderId.SPOTIFY, pt.trackId),
                    title = pt.title,
                    artist = pt.artist,
                    album = pt.album,
                    durationMs = pt.durationMs,
                    artworkUri = pt.artworkUrl ?: pt.artworkUri,
                    source = source
                )
                rawResults.add(
                    LocalSearchResult(
                        track = track,
                        badgeText = if (isDl) "Downloaded" else pt.playlistTitle,
                        isDownloaded = isDl
                    )
                )
            }
        }

        // 3. Scanned local MediaStore / SAF tracks
        for (localItem in scannedTracks) {
            val t = localItem.track
            if (matches(t.title, t.artist, t.album)) {
                val isDl = downloadedMap.containsKey(t.id.rawId)
                rawResults.add(
                    LocalSearchResult(
                        track = t,
                        badgeText = if (isDl) "Downloaded" else "Local File",
                        isDownloaded = isDl
                    )
                )
            }
        }

        // Deduplication by normalized title and artist
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<LocalSearchResult>()

        // Sort so that downloaded tracks and liked songs come first before other playlist copies
        val sorted = rawResults.sortedWith(
            compareByDescending<LocalSearchResult> { it.isDownloaded }
                .thenByDescending { it.badgeText == "Liked Songs" }
                .thenByDescending { it.track.source is AudioSource.Local }
        )

        for (item in sorted) {
            val normTitle = item.track.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            val normArtist = item.track.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
            val key = "$normTitle|$normArtist"
            if (seen.add(key) && seen.add(item.track.id.rawId)) {
                deduped.add(item)
            }
        }

        return deduped
    }

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
                    streamResolver.resolveStream(
                        canonicalYoutubeUrl = canonicalUrl,
                        title = candidate.title,
                        artist = candidate.channelTitle,
                        isPrefetch = true,
                        expectedDurationMs = candidate.durationMs
                    )
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
                progressText = "Searching everywhere…"
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

    private var playCandidateJob: Job? = null
    private var playRecentItemJob: Job? = null

    /**
     * Resolves the playable direct audio stream for a [candidate] using [streamResolver],
     * then dispatches the domain track and stream URL to [onPlayStream].
     * Maps artworkUrl from the candidate into the domain track and Media3 metadata.
     */
    fun playCandidate(
        candidate: YouTubeCandidate,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit
    ) {
        playCandidateJob?.cancel()
        playCandidateJob = viewModelScope.launch {
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
                    artist = candidate.channelTitle,
                    isPrefetch = false,
                    expectedDurationMs = candidate.durationMs
                )
                if (streamResult.isFailure) {
                    val err = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                    logE("Stream resolution failed for ${candidate.videoId}: $err")
                    SnackbarManager.tryEmit(SnackbarEvent.PlayFailed)
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                logE("Failed to resolve and play candidate: ${candidate.videoId}", e)
                SnackbarManager.tryEmit(SnackbarEvent.PlayFailed)
                _uiState.value = OnlineSearchUiState.Error("Playback error: ${e.message}")
            } finally {
                if (_resolvingVideoId.value == candidate.videoId) {
                    _resolvingVideoId.value = null
                }
            }
        }
    }

    /**
     * Directly plays a saved recent media item using its stable provider + providerSourceId,
     * bypassing title search and matching.
     *
     * Invariants:
     * - Cancels any in-flight previous recent-item resolve before starting a new one.
     * - Emits "Playing: <title>" snackbar immediately on tap (before network I/O).
     * - Emits PlayFailed snackbar on resolve failure (mirrors playCandidate behavior).
     * - _resolvingVideoId is always cleared in finally (success, failure, and cancellation).
     */
    fun playRecentMediaItem(
        item: RecentSearchItemEntity,
        onPlayStream: (Track, String, PlaybackOrigin) -> Unit
    ) {
        // Cancel any in-flight resolve from a previous tap before starting a new one
        playRecentItemJob?.cancel()
        playRecentItemJob = viewModelScope.launch {
            _resolvingVideoId.value = item.providerSourceId

            // Immediate tap feedback: show snackbar before any network I/O
            val displayTitle = item.title.takeIf { it.isNotBlank() } ?: "Song"
            SnackbarManager.tryEmit(SnackbarEvent.Message("Playing: $displayTitle"))

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
                    artist = item.artist,
                    isPrefetch = false,
                    expectedDurationMs = item.durationMs
                )
                if (streamResult.isFailure) {
                    val err = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                    logE("Stream resolution failed for recent item ${item.providerSourceId}: $err")
                    SnackbarManager.tryEmit(SnackbarEvent.PlayFailed)
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                logE("Failed to play recent media item: ${item.providerSourceId}", e)
                SnackbarManager.tryEmit(SnackbarEvent.PlayFailed)
                _uiState.value = OnlineSearchUiState.Error("Playback error: ${e.message}")
            } finally {
                if (_resolvingVideoId.value == item.providerSourceId) {
                    _resolvingVideoId.value = null
                }
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

    fun toggleLikeCandidate(candidate: YouTubeCandidate) {
        val repo = playlistRepository ?: return
        val provider = candidate.provider ?: "youtube"
        viewModelScope.launch {
            try {
                val isLiked = repo.isTrackLiked(provider, candidate.videoId)
                if (isLiked) {
                    repo.removeTrackFromLikedSongs(provider, candidate.videoId)
                    SnackbarManager.emit(SnackbarEvent.RemovedFromLikedSongs)
                } else {
                    val res = repo.saveTrackToLikedSongs(
                        title = candidate.title,
                        artist = candidate.channelTitle ?: "Unknown Artist",
                        album = candidate.album,
                        durationMs = candidate.durationMs,
                        artworkUrl = candidate.artworkUrl,
                        provider = provider,
                        providerSourceId = candidate.videoId
                    )
                    if (res is com.notify.download.db.AddTrackResult.Added || res is com.notify.download.db.AddTrackResult.AlreadyExists) {
                        SnackbarManager.emit(SnackbarEvent.AddedToLikedSongs)
                    } else if (res is com.notify.download.db.AddTrackResult.Failure) {
                        SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to update Liked Songs"))
                    }
                }
            } catch (e: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to update Liked Songs"))
            }
        }
    }

    fun downloadCandidate(candidate: YouTubeCandidate) {
        val repo = playlistRepository ?: return
        val provider = candidate.provider ?: "youtube"
        viewModelScope.launch {
            try {
                val canonicalTrackId = repo.resolveCanonicalTrackId(provider, candidate.videoId)
                val downloadMgr = OfflineDownloadManager(getApplication())
                val isDownloaded = downloadMgr.isTrackAvailableOffline(canonicalTrackId)
                if (isDownloaded) {
                    downloadMgr.removeDownloadForTrack(canonicalTrackId)
                    SnackbarManager.emit(SnackbarEvent.Message("Download removed"))
                } else {
                    repo.saveTrackToLikedSongs(
                        title = candidate.title,
                        artist = candidate.channelTitle ?: "Unknown Artist",
                        album = candidate.album,
                        durationMs = candidate.durationMs,
                        artworkUrl = candidate.artworkUrl,
                        provider = provider,
                        providerSourceId = candidate.videoId
                    )
                    downloadMgr.enqueueTrackDownload(canonicalTrackId, bucket = DownloadBucket.PINNED)
                    SnackbarManager.emit(SnackbarEvent.DownloadStarted(candidate.title))
                }
            } catch (e: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Download failed to start"))
            }
        }
    }

    fun toggleLikeRecentItem(item: RecentSearchItemEntity) {
        val repo = playlistRepository ?: return
        viewModelScope.launch {
            try {
                val isLiked = repo.isTrackLiked(item.provider, item.providerSourceId)
                if (isLiked) {
                    repo.removeTrackFromLikedSongs(item.provider, item.providerSourceId)
                    SnackbarManager.emit(SnackbarEvent.RemovedFromLikedSongs)
                } else {
                    val res = repo.saveTrackToLikedSongs(
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs ?: 0L,
                        artworkUrl = item.artworkUrl,
                        provider = item.provider,
                        providerSourceId = item.providerSourceId
                    )
                    if (res is com.notify.download.db.AddTrackResult.Added || res is com.notify.download.db.AddTrackResult.AlreadyExists) {
                        SnackbarManager.emit(SnackbarEvent.AddedToLikedSongs)
                    } else if (res is com.notify.download.db.AddTrackResult.Failure) {
                        SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to update Liked Songs"))
                    }
                }
            } catch (e: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to update Liked Songs"))
            }
        }
    }

    fun downloadRecentItem(item: RecentSearchItemEntity) {
        val repo = playlistRepository ?: return
        viewModelScope.launch {
            try {
                val canonicalTrackId = repo.resolveCanonicalTrackId(item.provider, item.providerSourceId)
                val downloadMgr = OfflineDownloadManager(getApplication())
                val isDownloaded = downloadMgr.isTrackAvailableOffline(canonicalTrackId)
                if (isDownloaded) {
                    downloadMgr.removeDownloadForTrack(canonicalTrackId)
                    SnackbarManager.emit(SnackbarEvent.Message("Download removed"))
                } else {
                    repo.saveTrackToLikedSongs(
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs ?: 0L,
                        artworkUrl = item.artworkUrl,
                        provider = item.provider,
                        providerSourceId = item.providerSourceId
                    )
                    downloadMgr.enqueueTrackDownload(canonicalTrackId, bucket = DownloadBucket.PINNED)
                    SnackbarManager.emit(SnackbarEvent.DownloadStarted(item.title))
                }
            } catch (e: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Download failed to start"))
            }
        }
    }

    /** Adds a recent media item to a user playlist if absent. */
    fun addRecentItemToPlaylist(
        playlistId: String,
        playlistTitle: String,
        item: RecentSearchItemEntity,
        onResult: (AddToPlaylistUiResult) -> Unit = {}
    ) {
        val repo = playlistRepository ?: return
        viewModelScope.launch {
            try {
                val res = repo.addTrackToPlaylistIfAbsent(
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
                    is com.notify.download.db.AddTrackResult.Added -> {
                        onResult(AddToPlaylistUiResult.ADDED)
                        val canonicalTrackId = repo.resolveCanonicalTrackId(item.provider, item.providerSourceId)
                        SnackbarManager.emit(
                            SnackbarEvent.AddedToPlaylist(
                                playlistName = playlistTitle,
                                onUndo = {
                                    viewModelScope.launch {
                                        repo.removeTrackFromPlaylistByTrackId(playlistId, canonicalTrackId)
                                    }
                                }
                            )
                        )
                    }
                    is com.notify.download.db.AddTrackResult.AlreadyExists -> {
                        onResult(AddToPlaylistUiResult.ALREADY_EXISTS)
                        SnackbarManager.emit(SnackbarEvent.AlreadyInPlaylist(playlistTitle))
                    }
                    else -> {
                        onResult(AddToPlaylistUiResult.FAILED)
                        SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to add to $playlistTitle"))
                    }
                }
            } catch (_: Exception) {
                onResult(AddToPlaylistUiResult.FAILED)
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to add to $playlistTitle"))
            }
        }
    }

    /** Adds a search candidate to a user playlist if absent. */
    fun addCandidateToPlaylist(
        playlistId: String,
        playlistTitle: String,
        candidate: YouTubeCandidate,
        onResult: (AddToPlaylistUiResult) -> Unit = {}
    ) {
        val repo = playlistRepository ?: return
        val provider = candidate.provider ?: "youtube"
        viewModelScope.launch {
            try {
                val res = repo.addTrackToPlaylistIfAbsent(
                    playlistId = playlistId,
                    title = candidate.title,
                    artist = candidate.channelTitle ?: "Unknown Artist",
                    album = candidate.album,
                    durationMs = candidate.durationMs,
                    artworkUrl = candidate.artworkUrl,
                    provider = provider,
                    providerSourceId = candidate.videoId
                )
                when (res) {
                    is com.notify.download.db.AddTrackResult.Added -> {
                        onResult(AddToPlaylistUiResult.ADDED)
                        val canonicalTrackId = repo.resolveCanonicalTrackId(provider, candidate.videoId)
                        SnackbarManager.emit(
                            SnackbarEvent.AddedToPlaylist(
                                playlistName = playlistTitle,
                                onUndo = {
                                    viewModelScope.launch {
                                        repo.removeTrackFromPlaylistByTrackId(playlistId, canonicalTrackId)
                                    }
                                }
                            )
                        )
                    }
                    is com.notify.download.db.AddTrackResult.AlreadyExists -> {
                        onResult(AddToPlaylistUiResult.ALREADY_EXISTS)
                        SnackbarManager.emit(SnackbarEvent.AlreadyInPlaylist(playlistTitle))
                    }
                    else -> {
                        onResult(AddToPlaylistUiResult.FAILED)
                        SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to add to $playlistTitle"))
                    }
                }
            } catch (_: Exception) {
                onResult(AddToPlaylistUiResult.FAILED)
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to add to $playlistTitle"))
            }
        }
    }

    /** Creates a new playlist from the Add-to-Playlist chooser. */
    fun createPlaylist(title: String, onComplete: (PlaylistEntity?) -> Unit = {}) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(trimmed)
                if (pl != null) {
                    SnackbarManager.emit(SnackbarEvent.PlaylistCreated(trimmed))
                }
                onComplete(pl)
            } catch (_: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to create playlist"))
                onComplete(null)
            }
        }
    }

    /** Creates a new playlist and immediately adds a recent media item to it. */
    fun createPlaylistAndAddRecentItem(title: String, item: RecentSearchItemEntity) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(trimmed)
                if (pl != null) {
                    SnackbarManager.emit(SnackbarEvent.PlaylistCreated(trimmed))
                    addRecentItemToPlaylist(pl.playlistId, trimmed, item)
                }
            } catch (_: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to create playlist"))
            }
        }
    }

    /** Creates a new playlist and immediately adds a search candidate to it. */
    fun createPlaylistAndAddCandidate(title: String, candidate: YouTubeCandidate) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val pl = playlistRepository?.createPlaylist(trimmed)
                if (pl != null) {
                    SnackbarManager.emit(SnackbarEvent.PlaylistCreated(trimmed))
                    addCandidateToPlaylist(pl.playlistId, trimmed, candidate)
                }
            } catch (_: Exception) {
                SnackbarManager.emit(SnackbarEvent.ActionFailed("Failed to create playlist"))
            }
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
