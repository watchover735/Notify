package com.notify.download.spike

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notify.BuildConfig
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.MediaItemMapper
import com.notify.ui.components.AlbumArtwork
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackSourceEntity
import com.notify.download.ingest.TrackIngestor
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.MatchResult
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.spike.YtDlpRuntime
import com.notify.download.spotify.PublicSpotifyMetadataProvider
import com.notify.download.spotify.PublicSpotifyScraper
import com.notify.download.spotify.ResolvedUrl
import com.notify.download.spotify.ScrapeResult
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import com.notify.download.spotify.SpotifyPlaylistMetadata
import com.notify.download.spotify.SpotifyTrackMetadata
import com.notify.download.spotify.SpotifyUrlResolver
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.FakeAudioStreamResolver
import com.notify.download.stream.OnlineStreamResolver
import com.notify.playback.PlaybackUiState
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.EmeraldLight
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SpikeLogEntry(
    val timestamp: String,
    val text: String,
    val type: LogType = LogType.INFO
) {
    enum class LogType { INFO, SUCCESS, WARNING, ERROR }
}

enum class PipelineStage {
    IDLE,
    STAGE_1_METADATA,
    STAGE_2_PLAYLIST,
    STAGE_3_MATCHING,
    STAGE_4_DOWNLOADING,
    STAGE_5_INGESTED
}

enum class PipelineStep(val label: String) {
    IDLE("Idle"),
    SEARCHING("Searching YouTube Music"),
    FOUND_CANDIDATES("Found candidates"),
    MATCH_SELECTED("Best match selected"),
    RESOLVING_STREAM("Resolving stream"),
    PLAYER_CONNECTED("Player connected"),
    PLAYING("Playing"),
    FAILED("Failed")
}

@Composable
fun SpikeTestDialog(
    onDismiss: () -> Unit,
    playbackState: PlaybackUiState = PlaybackUiState(),
    onPlayTrack: (Track) -> Unit = {},
    onPlayStream: (Track, String, PlaybackOrigin) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Engines & Database
    val spike = remember { YtDlpFeasibilitySpike(context) }
    val publicScraper = remember { PublicSpotifyScraper() }
    val publicSpotifyProvider = remember { PublicSpotifyMetadataProvider(publicScraper) }
    val innerTubeSearchProvider = remember { InnerTubeYouTubeMusicSearchProvider() }
    val ytDlpSearchProvider = remember { YtDlpYouTubeSearchProvider(context) }
    val searchProvider = ytDlpSearchProvider
    val streamResolver = remember { OnlineStreamResolver(context) }
    val fakeResolver = remember { FakeAudioStreamResolver() }
    val ingestor = remember { TrackIngestor(context) }
    val db = remember { NotiFyDatabase.getInstance(context) }
    val repository = remember { PlaylistRepository(db) }

    // Active Tab: 0 = Stage 0 & Stage 1/2 Spotify Public & Streaming, 1 = SpotDL Spike Engine
    var selectedTab by remember { mutableIntStateOf(0) }

    // Stage 0 / 1 / 2 State
    var stage0Url by remember { mutableStateOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") }
    var isStage0Running by remember { mutableStateOf(false) }
    var stage0Result by remember { mutableStateOf<ScrapeResult?>(null) }
    var stage0StatusText by remember { mutableStateOf("Ready. Paste a public Spotify playlist URL to test extraction.") }

    // Room Persistence State
    var isSavingToRoom by remember { mutableStateOf(false) }
    var savedPlaylistEntity by remember { mutableStateOf<PlaylistEntity?>(null) }
    val savedPlaylistEntries = remember { mutableStateListOf<PlaylistEntryWithTrack>() }

    fun updateLocalEntryState(trackId: String, resState: ResolutionState) {
        val idx = savedPlaylistEntries.indexOfFirst { it.trackId == trackId }
        if (idx >= 0) {
            val old = savedPlaylistEntries[idx]
            savedPlaylistEntries[idx] = old.copy(resolutionState = resState)
        }
    }

    val currentPlaybackState by rememberUpdatedState(playbackState)
    var activeAttemptId by remember { mutableLongStateOf(0L) }
    var activeAttemptTrackId by remember { mutableStateOf<String?>(null) }
    var transientPlaybackError by remember { mutableStateOf<String?>(null) }

    // Stage 2 Single-Track Online Streaming State
    var isResolvingSingleTrack by remember { mutableStateOf(false) }
    var resolvingTrackId by remember { mutableStateOf<String?>(null) }
    var activeMatchedCandidate by remember { mutableStateOf<MatchResult?>(null) }
    var currentPipelineStep by remember { mutableStateOf(PipelineStep.IDLE) }

    // Reconcile: If matching mediaId later starts playing, clear stale errors and reconcile to MATCHED
    LaunchedEffect(currentPlaybackState.isPlaying, currentPlaybackState.currentTrack) {
        val track = currentPlaybackState.currentTrack
        if (currentPlaybackState.isPlaying && track != null) {
            val rawId = track.id.rawId
            if (activeAttemptTrackId == rawId || resolvingTrackId == rawId) {
                transientPlaybackError = null
                currentPipelineStep = PipelineStep.PLAYING
                stage0StatusText = "Playing: '${track.title}' (verified in ExoPlayer)"
            }
            // Broaden track matching: match by trackId, rawId substring, or title + artist
            val entry = savedPlaylistEntries.find { e ->
                e.trackId == rawId ||
                rawId.contains(e.trackId) ||
                (e.title.equals(track.title, ignoreCase = true) && e.artist.equals(track.artist, ignoreCase = true))
            }
            if (entry != null && entry.resolutionState != ResolutionState.MATCHED) {
                repository.updateTrackResolution(entry.trackId, ResolutionState.MATCHED)
                updateLocalEntryState(entry.trackId, ResolutionState.MATCHED)
            }
        }
    }

    // SpotDL Spike State
    var inputUrl by remember { mutableStateOf("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT") }
    var ytDlpVersion by remember { mutableStateOf("Querying...") }
    var ytDlpRuntimeLabel by remember { mutableStateOf("INITIALIZING") }  // INITIALIZING | READY | FAILED
    var ytDlpRuntimeLabelColor by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFFFBBF24)) } // amber while init
    var clickCounter by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }
    var currentStage by remember { mutableStateOf(PipelineStage.IDLE) }
    var resolvedTrack by remember { mutableStateOf<SpotifyTrackMetadata?>(null) }
    var resolvedPlaylist by remember { mutableStateOf<SpotifyPlaylistMetadata?>(null) }
    var matchedResult by remember { mutableStateOf<MatchResult?>(null) }
    var downloadedAudio by remember { mutableStateOf<YtDlpFeasibilitySpike.DownloadedAudio?>(null) }
    var ingestedTrack by remember { mutableStateOf<Track?>(null) }
    var userFacingStatus by remember { mutableStateOf("Ready. Select an action below.") }
    var showTechnicalDetails by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var progressStatusText by remember { mutableStateOf("") }

    val logs = remember { mutableStateListOf<SpikeLogEntry>() }
    val listState = rememberLazyListState()

    fun log(message: String, type: SpikeLogEntry.LogType = SpikeLogEntry.LogType.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(SpikeLogEntry(time, message, type))
    }

    // Hydrate existing Room database records on dialog launch
    LaunchedEffect(Unit) {
        scope.launch {
            // Initialize yt-dlp runtime (Mutex-protected, suspend-safe)
            ytDlpRuntimeLabel = "INITIALIZING"
            val initResult = YtDlpRuntime.ensureReady(context)
            if (initResult.isSuccess) {
                val v = initResult.getOrThrow().ytDlpVersion
                ytDlpVersion = v
                ytDlpRuntimeLabel = "READY"
                ytDlpRuntimeLabelColor = EmeraldAccent
                log("yt-dlp runtime READY. Version: $v", SpikeLogEntry.LogType.SUCCESS)
            } else {
                val err = initResult.exceptionOrNull()?.message ?: "Unknown error"
                ytDlpVersion = "FAILED"
                ytDlpRuntimeLabel = "FAILED"
                ytDlpRuntimeLabelColor = ErrorRed
                log("yt-dlp runtime FAILED: $err", SpikeLogEntry.LogType.ERROR)
            }

            // Check if any playlist was previously saved in Room
            val playlists = db.playlistDao().getAllPlaylists()
            val firstPl = playlists.firstOrNull()
            if (firstPl != null) {
                savedPlaylistEntity = firstPl
                val entries = repository.getPlaylistEntries(firstPl.playlistId)
                savedPlaylistEntries.clear()
                savedPlaylistEntries.addAll(entries)
                log("Room Hydration: Loaded playlist '${firstPl.title}' with ${entries.size} entries from local DB.", SpikeLogEntry.LogType.SUCCESS)
                stage0StatusText = "Loaded saved playlist '${firstPl.title}' (${entries.size} tracks) from Room DB."
            }
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Executes Stage 2: Single-Track Stream Resolution without downloading
    fun resolveAndStreamTrack(
        trackId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        artworkUri: String?,
        localUri: String?
    ) {
        // CLICK_RECEIVED — first synchronous line, before coroutine, always visible in Logcat
        android.util.Log.d("NotiFyOnlinePlayback", "CLICK_RECEIVED: trackId=$trackId title=$title artist=$artist")
        clickCounter++
        val thisAttemptId = ++activeAttemptId
        activeAttemptTrackId = trackId
        transientPlaybackError = null

        scope.launch {
            if (thisAttemptId != activeAttemptId) return@launch

            isResolvingSingleTrack = true
            resolvingTrackId = trackId
            stage0StatusText = "Resolving online stream for '$title' by $artist..."
            log("Stage 2: Resolving online playback for '$title' - '$artist'  [click #$clickCounter]", SpikeLogEntry.LogType.INFO)

            // Gate 0: Ensure yt-dlp runtime is ready before any network operation
            log("Stage 0/6 [YTDLP_READY]: Checking yt-dlp runtime...", SpikeLogEntry.LogType.INFO)
            val initResult = YtDlpRuntime.ensureReady(context)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.message ?: "YtDlpRuntime failed"
                currentPipelineStep = PipelineStep.FAILED
                stage0StatusText = "yt-dlp not ready: $err"
                log("ERROR [YTDLP_READY]: $err", SpikeLogEntry.LogType.ERROR)
                isResolvingSingleTrack = false
                resolvingTrackId = null
                return@launch
            }
            val version = initResult.getOrThrow().ytDlpVersion
            log("Stage 0/6 [YTDLP_READY]: yt-dlp version $version", SpikeLogEntry.LogType.SUCCESS)

            // Step 1: Check if permanent local content URI already exists and is accessible
            if (!localUri.isNullOrBlank()) {
                val availabilityChecker = com.notify.core.local.LocalAudioAvailabilityChecker(context.contentResolver)
                val checkRes = availabilityChecker.checkAvailability(android.net.Uri.parse(localUri))
                if (checkRes is com.notify.core.local.LocalAudioResult.Success) {
                    log("Local downloaded file found! Playing directly from local storage.", SpikeLogEntry.LogType.SUCCESS)
                    val localTrack = Track(
                        id = TrackId.local(localUri),
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        artworkUri = artworkUri,
                        source = AudioSource.Local(localUri)
                    )
                    onPlayTrack(localTrack)
                    currentPipelineStep = PipelineStep.PLAYING
                    stage0StatusText = "Playing local download: '$title'"
                    isResolvingSingleTrack = false
                    resolvingTrackId = null
                    return@launch
                }
            }

            // Step 2: Set ResolutionState to SEARCHING
            repository.updateTrackResolution(trackId, ResolutionState.SEARCHING)
            updateLocalEntryState(trackId, ResolutionState.SEARCHING)
            currentPipelineStep = PipelineStep.SEARCHING
            stage0StatusText = "Searching YouTube Music for '$artist - $title'..."
            log("Stage 1/5 [Searching YouTube Music]: '$artist - $title' (Primary: InnerTube)...", SpikeLogEntry.LogType.INFO)

            // Step 3: Fast InnerTube search with yt-dlp fallback
            val query = "$artist - $title"
            var candidates = emptyList<YouTubeCandidate>()
            val innerTubeRes = innerTubeSearchProvider.search(query, limit = 5)
            if (innerTubeRes.isSuccess && innerTubeRes.getOrNull()?.isNotEmpty() == true) {
                candidates = innerTubeRes.getOrThrow()
                log("InnerTube fast search found ${candidates.size} candidates: ${candidates.joinToString { "'${it.title}'" }}", SpikeLogEntry.LogType.SUCCESS)
            }

            val dummyMeta = SpotifyTrackMetadata(
                id = trackId,
                title = title,
                artists = listOf(artist),
                album = album,
                releaseYear = null,
                durationMs = durationMs,
                artworkUrl = artworkUri
            )

            var match = if (candidates.isNotEmpty()) TrackMatchEngine.findBestMatch(dummyMeta, candidates) else null
            if (match == null || match.matchScore < 0.5f) {
                log("InnerTube match insufficient (or empty). Trying yt-dlp compatibility fallback...", SpikeLogEntry.LogType.WARNING)
                val fallbackRes = ytDlpSearchProvider.search(query, limit = 3)
                if (fallbackRes.isSuccess && fallbackRes.getOrNull()?.isNotEmpty() == true) {
                    val fallbackCandidates = fallbackRes.getOrThrow()
                    val fallbackMatch = TrackMatchEngine.findBestMatch(dummyMeta, fallbackCandidates)
                    if (fallbackMatch != null && (match == null || fallbackMatch.matchScore > match.matchScore)) {
                        match = fallbackMatch
                        candidates = fallbackCandidates
                        log("yt-dlp fallback match found: '${match.candidate.title}'", SpikeLogEntry.LogType.SUCCESS)
                    }
                }
            }

            if (match == null) {
                if (thisAttemptId != activeAttemptId) return@launch
                currentPipelineStep = PipelineStep.FAILED
                val topTitle = candidates.firstOrNull()?.title ?: "none"
                val err = "No candidate exceeded match confidence threshold for '$title' (Top candidate: '$topTitle')"
                stage0StatusText = "Failed at [Best match selected]: $err"
                log("ERROR at [Best match selected]: $err", SpikeLogEntry.LogType.ERROR)
                repository.updateTrackResolution(trackId, ResolutionState.RESOLVE_FAILED)
                updateLocalEntryState(trackId, ResolutionState.RESOLVE_FAILED)
                isResolvingSingleTrack = false
                resolvingTrackId = null
                return@launch
            }

            currentPipelineStep = PipelineStep.FOUND_CANDIDATES
            stage0StatusText = "Found ${candidates.size} YouTube candidates for '$title'"

            activeMatchedCandidate = match
            currentPipelineStep = PipelineStep.MATCH_SELECTED
            val matchPct = (match.matchScore * 100).toInt()
            stage0StatusText = "Best match: '${match.candidate.title}' on '${match.candidate.channelTitle}' (${matchPct}%, Δ${match.durationDeltaMs / 1000}s)"
            log("Stage 3/5 [Best match selected]: '${match.candidate.title}' (channel: '${match.candidate.channelTitle}', score: ${matchPct}%, duration delta: ${match.durationDeltaMs / 1000}s, canonical: ${match.canonicalDownloadUrl})", SpikeLogEntry.LogType.SUCCESS)

            // Step 4.1: Persist match & candidate thumbnail immediately in Room
            repository.updateTrackResolution(trackId, ResolutionState.MATCHED)
            updateLocalEntryState(trackId, ResolutionState.MATCHED)
            repository.updateTrackArtwork(trackId, match.candidate.artworkUrl)
            repository.saveSelectedSource(
                TrackSourceEntity(
                    sourceKey = "${trackId}:youtube",
                    trackId = trackId,
                    provider = match.candidate.provider,
                    sourceId = match.candidate.videoId,
                    canonicalUrl = match.canonicalDownloadUrl,
                    confidence = match.matchScore.toFloat(),
                    durationDeltaMs = match.durationDeltaMs,
                    artworkUrl = match.candidate.artworkUrl,
                    selected = true
                )
            )

            // Step 5: Resolve playable stream
            currentPipelineStep = PipelineStep.RESOLVING_STREAM
            stage0StatusText = "Resolving audio stream from ${match.canonicalDownloadUrl}..."
            log("Stage 4/5 [Resolving stream]: Calling yt-dlp on ${match.canonicalDownloadUrl}...", SpikeLogEntry.LogType.INFO)
            val streamRes = streamResolver.resolveStream(match.canonicalDownloadUrl)
            if (streamRes.isFailure) {
                if (thisAttemptId != activeAttemptId) return@launch
                currentPipelineStep = PipelineStep.FAILED
                val err = streamRes.exceptionOrNull()?.message ?: "Unknown stream resolution error"
                stage0StatusText = "Failed at [Resolving stream]: $err"
                log("ERROR at [Resolving stream]: $err", SpikeLogEntry.LogType.ERROR)
                repository.updateTrackResolution(trackId, ResolutionState.RESOLVE_FAILED)
                updateLocalEntryState(trackId, ResolutionState.RESOLVE_FAILED)
                isResolvingSingleTrack = false
                resolvingTrackId = null
                return@launch
            }

            val stream = streamRes.getOrThrow()
            log("Stage 4/5: Playable stream URL resolved! (Never persisted in Room)", SpikeLogEntry.LogType.SUCCESS)

            // Step 6: Send resolved in-memory stream to NotiFyPlaybackService
            currentPipelineStep = PipelineStep.PLAYER_CONNECTED
            stage0StatusText = "Connecting player and dispatching stream to NotiFyPlaybackService..."
            log("Stage 5/5 [Player connected]: Dispatching in-memory stream to NotiFyPlaybackService...", SpikeLogEntry.LogType.INFO)

            // Artwork priority: Spotify imported track/album artwork -> selected YouTube candidate thumbnail -> placeholder
            val effectiveArtworkUri = artworkUri?.takeIf { it.isNotBlank() } ?: match.candidate.artworkUrl
            val domainTrack = Track(
                id = TrackId.spotify(trackId),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkUri = effectiveArtworkUri,
                source = AudioSource.Remote(ProviderId.SPOTIFY, trackId)
            )
            onPlayStream(domainTrack, stream.streamUrl, PlaybackOrigin.UNKNOWN)

            // Step 7: Await genuine ExoPlayer playback observation (15-second timeout)
            log("Stage 5/5: Awaiting real ExoPlayer playback (15s timeout)...", SpikeLogEntry.LogType.INFO)
            val targetRawId = domainTrack.id.rawId
            val playbackResult = withTimeoutOrNull(15_000L) {
                snapshotFlow { currentPlaybackState }
                    .filter { state ->
                        val isCurrent = state.currentTrack?.id?.rawId == targetRawId ||
                                state.currentTrack?.id?.rawId == trackId ||
                                state.currentTrack?.let { MediaItemMapper.buildNamespacedMediaId(it.id) } == MediaItemMapper.buildNamespacedMediaId(domainTrack.id)
                        if (isCurrent && state.isBuffering) {
                            stage0StatusText = "Buffering stream in ExoPlayer..."
                        }
                        isCurrent && (state.isPlaying || state.playbackError != null)
                    }
                    .first()
            }

            if (thisAttemptId != activeAttemptId) {
                log("Discarding completed observation for superseded attempt #$thisAttemptId", SpikeLogEntry.LogType.INFO)
                return@launch
            }

            if (playbackResult == null) {
                currentPipelineStep = PipelineStep.FAILED
                val timeoutMsg = "Playback timed out after 15s (player never started)"
                transientPlaybackError = timeoutMsg
                stage0StatusText = timeoutMsg
                log("WARNING [Playing]: 15-second timeout waiting for ExoPlayer playback. Track remains MATCHED in Room.", SpikeLogEntry.LogType.WARNING)
                // Invariant: Player-start timeout must NOT overwrite a successfully matched track as RESOLVE_FAILED in Room!
            } else if (playbackResult.playbackError != null) {
                currentPipelineStep = PipelineStep.FAILED
                val err = playbackResult.playbackError?.message ?: "Playback error"
                transientPlaybackError = err
                stage0StatusText = "Playback failed: $err"
                log("ERROR [Playing]: Player error: $err. Track remains MATCHED in Room.", SpikeLogEntry.LogType.ERROR)
                // Invariant: Transient player error must NOT overwrite MATCHED as RESOLVE_FAILED in Room!
            } else {
                currentPipelineStep = PipelineStep.PLAYING
                transientPlaybackError = null
                stage0StatusText = "Playing: '$title' via YouTube Music (${matchPct}% match)"
                log("Stage 6/6 [Playing]: '$title' verified PLAYING in ExoPlayer! MediaSession notification active.", SpikeLogEntry.LogType.SUCCESS)
            }

            isResolvingSingleTrack = false
            resolvingTrackId = null
        }
    }

    // Isolated test with fake resolver to verify Media3/controller wiring independently of yt-dlp
    fun testFakeAudioStream(entry: PlaylistEntryWithTrack) {
        val thisAttemptId = ++activeAttemptId
        activeAttemptTrackId = entry.trackId
        transientPlaybackError = null

        scope.launch {
            if (thisAttemptId != activeAttemptId) return@launch

            isResolvingSingleTrack = true
            resolvingTrackId = entry.trackId
            currentPipelineStep = PipelineStep.RESOLVING_STREAM
            stage0StatusText = "Resolving fake test stream (play.mp3)..."
            log("Isolated Test: Resolving fake HTTPS audio stream for '${entry.title}'...", SpikeLogEntry.LogType.INFO)

            val streamRes = fakeResolver.resolveStream("https://www.youtube.com/watch?v=fake_test_id")
            val stream = streamRes.getOrThrow()

            currentPipelineStep = PipelineStep.PLAYER_CONNECTED
            stage0StatusText = "Player connected. Dispatching play.mp3 via NotiFyPlaybackService..."
            log("Isolated Test: Stream resolved. Dispatching to NotiFyPlaybackService via MediaController...", SpikeLogEntry.LogType.INFO)

            val domainTrack = Track(
                id = TrackId.spotify(entry.trackId),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                durationMs = entry.durationMs,
                artworkUri = entry.artworkUri,
                source = AudioSource.Remote(ProviderId.SPOTIFY, entry.trackId)
            )
            onPlayStream(domainTrack, stream.streamUrl, PlaybackOrigin.UNKNOWN)

            // Await genuine ExoPlayer playback observation (15-second timeout)
            log("Isolated Test: Awaiting real ExoPlayer playback (15s timeout)...", SpikeLogEntry.LogType.INFO)
            val targetRawId = domainTrack.id.rawId
            val playbackResult = withTimeoutOrNull(15_000L) {
                snapshotFlow { currentPlaybackState }
                    .filter { state ->
                        val isCurrent = state.currentTrack?.id?.rawId == targetRawId ||
                                state.currentTrack?.id?.rawId == entry.trackId ||
                                state.currentTrack?.let { MediaItemMapper.buildNamespacedMediaId(it.id) } == MediaItemMapper.buildNamespacedMediaId(domainTrack.id)
                        if (isCurrent && state.isBuffering) {
                            stage0StatusText = "Buffering fake test stream in ExoPlayer..."
                        }
                        isCurrent && (state.isPlaying || state.playbackError != null)
                    }
                    .first()
            }

            if (thisAttemptId != activeAttemptId) return@launch

            if (playbackResult == null) {
                currentPipelineStep = PipelineStep.FAILED
                stage0StatusText = "Isolated test timed out after 15s (player never started)"
                log("Isolated Test WARNING: 15-second timeout waiting for ExoPlayer playback", SpikeLogEntry.LogType.WARNING)
            } else if (playbackResult.playbackError != null) {
                currentPipelineStep = PipelineStep.FAILED
                val err = playbackResult.playbackError?.message ?: "Playback error"
                stage0StatusText = "Isolated test failed: $err"
                log("Isolated Test ERROR: Player error: $err", SpikeLogEntry.LogType.ERROR)
            } else {
                currentPipelineStep = PipelineStep.PLAYING
                stage0StatusText = "Playing '${entry.title}' (Isolated HTTPS test stream) verified PLAYING!"
                log("Isolated Test SUCCESS: Remote MediaItem verified PLAYING in ExoPlayer! Notification and lock screen controls are active.", SpikeLogEntry.LogType.SUCCESS)
            }

            isResolvingSingleTrack = false
            resolvingTrackId = null
        }
    }

    Dialog(
        onDismissRequest = { if (!isRunning && !isStage0Running && !isResolvingSingleTrack) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = DarkBackground,
            border = BorderStroke(1.dp, DarkSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NotiFy Pipeline Testing",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // YtDlpRuntime state pill
                            Box(
                                modifier = Modifier
                                    .background(ytDlpRuntimeLabelColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .border(1.dp, ytDlpRuntimeLabelColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("spike_ytdlp_runtime_pill")
                            ) {
                                Text(
                                    text = ytDlpRuntimeLabel,
                                    color = ytDlpRuntimeLabelColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Click counter
                            Text(
                                text = "Clicks: $clickCounter",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("spike_click_counter")
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        // Build stamp header
                        val buildTimeStr = remember {
                            try {
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(BuildConfig.BUILD_TIME))
                            } catch (_: Exception) {
                                "N/A"
                            }
                        }
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • $buildTimeStr • yt-dlp: $ytDlpVersion",
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("spike_header_build_stamp")
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isRunning && !isStage0Running && !isResolvingSingleTrack
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = if (!isRunning && !isStage0Running && !isResolvingSingleTrack) TextSecondary else TextTertiary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tab Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedTab == 0) EmeraldAccent else Color.Transparent
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "Spotify Import & Streaming",
                                color = if (selectedTab == 0) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedTab == 1) EmeraldAccent else Color.Transparent
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "SpotDL Spike Engine",
                                color = if (selectedTab == 1) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tab Contents
                if (selectedTab == 0) {
                    Stage0AndStreamingSection(
                        url = stage0Url,
                        onUrlChange = { stage0Url = it },
                        isRunning = isStage0Running,
                        isSaving = isSavingToRoom,
                        isResolvingStream = isResolvingSingleTrack,
                        resolvingTrackId = resolvingTrackId,
                        result = stage0Result,
                        savedEntries = savedPlaylistEntries,
                        statusText = stage0StatusText,
                        matchedCandidate = activeMatchedCandidate,
                        onPasteClipboard = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                stage0Url = clipText.trim()
                            }
                        },
                        onRunExtraction = {
                            scope.launch {
                                isStage0Running = true
                                stage0StatusText = "Resolving redirects & fetching public Spotify embed..."
                                log("Stage 0: Testing public metadata extraction for: $stage0Url", SpikeLogEntry.LogType.INFO)

                                val res = publicScraper.scrapePlaylist(stage0Url)
                                stage0Result = res

                                if (res.playlist != null) {
                                    publicSpotifyProvider.cacheScrapedPlaylist(res.playlist)
                                    val count = res.playlist.extractedTrackCount
                                    val expected = res.playlist.expectedTrackCount ?: count
                                    if (res.diagnostics.isComplete) {
                                        stage0StatusText = "Success! Extracted all $count tracks via ${res.diagnostics.parserStrategy}."
                                        log("Stage 0 SUCCESS: Extracted $count tracks ('${res.playlist.title}') in ${res.diagnostics.elapsedTimeMs}ms via ${res.diagnostics.parserStrategy}", SpikeLogEntry.LogType.SUCCESS)
                                    } else {
                                        stage0StatusText = "Partial Extraction: Extracted $count of $expected tracks (${res.diagnostics.parserStrategy})."
                                        log("Stage 0 NOTICE: Extracted $count of $expected tracks (Spotify embed paginated).", SpikeLogEntry.LogType.WARNING)
                                    }
                                } else {
                                    stage0StatusText = "Failed: ${res.error ?: "Unknown error"}"
                                    log("Stage 0 ERROR: ${res.error} (HTTP ${res.diagnostics.httpStatusCode}, host: ${res.diagnostics.finalHost})", SpikeLogEntry.LogType.ERROR)
                                }
                                isStage0Running = false
                            }
                        },
                        onSaveToRoom = {
                            scope.launch {
                                val scraped = stage0Result?.playlist ?: return@launch
                                isSavingToRoom = true
                                stage0StatusText = "Saving ${scraped.tracks.size} tracks to Room database..."
                                log("Stage 1: Persisting playlist '${scraped.title}' and ${scraped.tracks.size} entries to Room DB...", SpikeLogEntry.LogType.INFO)

                                val plEntity = repository.saveScrapedPlaylist(scraped, stage0Url)
                                savedPlaylistEntity = plEntity
                                val entries = repository.getPlaylistEntries(plEntity.playlistId)
                                savedPlaylistEntries.clear()
                                savedPlaylistEntries.addAll(entries)

                                stage0StatusText = "Saved ${entries.size} tracks to Room database! (Metadata-only)"
                                log("Stage 1 SUCCESS: Saved ${entries.size} tracks to Room with original order/positions (1..${entries.size}).", SpikeLogEntry.LogType.SUCCESS)
                                isSavingToRoom = false
                            }
                        },
                        onStreamTrack = { entry ->
                            resolveAndStreamTrack(
                                trackId = entry.trackId,
                                title = entry.title,
                                artist = entry.artist,
                                album = entry.album,
                                durationMs = entry.durationMs,
                                artworkUri = entry.artworkUri,
                                localUri = entry.localContentUri
                            )
                        },
                        currentPipelineStep = currentPipelineStep,
                        playbackState = currentPlaybackState,
                        transientError = transientPlaybackError,
                        onTestFakeStream = { entry ->
                            testFakeAudioStream(entry)
                        }
                    )
                } else {
                    SpotDlSpikeSection(
                        inputUrl = inputUrl,
                        onUrlChange = { inputUrl = it },
                        isRunning = isRunning,
                        ytDlpVersion = ytDlpVersion,
                        currentStage = currentStage,
                        resolvedTrack = resolvedTrack,
                        resolvedPlaylist = resolvedPlaylist,
                        matchedResult = matchedResult,
                        downloadedAudio = downloadedAudio,
                        ingestedTrack = ingestedTrack,
                        userFacingStatus = userFacingStatus,
                        currentProgress = currentProgress,
                        progressStatusText = progressStatusText,
                        hasCachedStage0Tracks = stage0Result?.playlist != null,
                        onUpdateYtDlp = {
                            scope.launch {
                                isRunning = true
                                userFacingStatus = "Updating yt-dlp to latest stable release..."
                                log("Running YoutubeDL.getInstance().updateYoutubeDL(UpdateChannel.STABLE)...")
                                val res = spike.updateYtDlp()
                                when (res) {
                                    is YtDlpFeasibilitySpike.SpikeStatus.Success -> {
                                        userFacingStatus = res.message
                                        log("SUCCESS: ${res.message}", SpikeLogEntry.LogType.SUCCESS)
                                        res.details.forEach { (k, v) -> log("  • $k: $v") }
                                    }
                                    is YtDlpFeasibilitySpike.SpikeStatus.Failure -> {
                                        userFacingStatus = "Update failed: ${res.error}"
                                        log("ERROR: ${res.error}", SpikeLogEntry.LogType.ERROR)
                                    }
                                    else -> Unit
                                }
                                ytDlpVersion = spike.queryActualVersion()
                                isRunning = false
                            }
                        },
                        onTestSingleTrack = {
                            inputUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
                            scope.launch {
                                executeSingleTrackFlow(
                                    url = inputUrl,
                                    spike = spike,
                                    spotifyProvider = publicSpotifyProvider,
                                    searchProvider = searchProvider,
                                    ingestor = ingestor,
                                    onLog = { m, t -> log(m, t) },
                                    onProgress = { p, s -> currentProgress = p; progressStatusText = s },
                                    onStageChange = { currentStage = it },
                                    onTrackResolved = { resolvedTrack = it },
                                    onMatchResolved = { matchedResult = it },
                                    onAudioDownloaded = { downloadedAudio = it },
                                    onTrackIngested = { ingestedTrack = it },
                                    onStatus = { userFacingStatus = it },
                                    onRunning = { isRunning = it }
                                )
                            }
                        },
                        onTestPlaylist = {
                            inputUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
                            scope.launch {
                                executePlaylistFlow(
                                    url = inputUrl,
                                    limit = 3,
                                    spike = spike,
                                    spotifyProvider = publicSpotifyProvider,
                                    searchProvider = searchProvider,
                                    ingestor = ingestor,
                                    cachedPlaylist = stage0Result?.playlist,
                                    onLog = { m, t -> log(m, t) },
                                    onProgress = { p, s -> currentProgress = p; progressStatusText = s },
                                    onStageChange = { currentStage = it },
                                    onPlaylistResolved = { resolvedPlaylist = it },
                                    onTrackResolved = { resolvedTrack = it },
                                    onMatchResolved = { matchedResult = it },
                                    onAudioDownloaded = { downloadedAudio = it },
                                    onTrackIngested = { ingestedTrack = it },
                                    onStatus = { userFacingStatus = it },
                                    onRunning = { isRunning = it }
                                )
                            }
                        },
                        onPlayTrack = onPlayTrack
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Technical Logs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTechnicalDetails = !showTechnicalDetails }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Technical Logs (${logs.size})",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Clear",
                            color = EmeraldAccent,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickable { logs.clear() }
                                .padding(end = 8.dp)
                        )
                        Icon(
                            imageVector = if (showTechnicalDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = showTechnicalDetails) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(8.dp))
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                        ) {
                            items(logs) { entry ->
                                val color = when (entry.type) {
                                    SpikeLogEntry.LogType.INFO -> TextSecondary
                                    SpikeLogEntry.LogType.SUCCESS -> EmeraldAccent
                                    SpikeLogEntry.LogType.WARNING -> Color(0xFFFBBF24)
                                    SpikeLogEntry.LogType.ERROR -> ErrorRed
                                }
                                Text(
                                    text = "[${entry.timestamp}] ${entry.text}",
                                    color = color,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stage 0, 1 & 2 Combined Section:
 * - Public metadata extraction (Stage 0)
 * - Room database persistence with duplicate support (Stage 1)
 * - Single-track online audio stream resolution & playback (Stage 2)
 */
@Composable
private fun Stage0AndStreamingSection(
    url: String,
    onUrlChange: (String) -> Unit,
    isRunning: Boolean,
    isSaving: Boolean,
    isResolvingStream: Boolean,
    resolvingTrackId: String?,
    result: ScrapeResult?,
    savedEntries: List<PlaylistEntryWithTrack>,
    statusText: String,
    matchedCandidate: MatchResult?,
    currentPipelineStep: PipelineStep,
    playbackState: PlaybackUiState = PlaybackUiState(),
    transientError: String? = null,
    onPasteClipboard: () -> Unit,
    onRunExtraction: () -> Unit,
    onSaveToRoom: () -> Unit,
    onStreamTrack: (PlaylistEntryWithTrack) -> Unit,
    onTestFakeStream: (PlaylistEntryWithTrack) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Presets Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { onUrlChange("https://open.spotify.com/playlist/7x62r1h8Kx8r2sXn4QYx9A") },
                enabled = !isRunning && !isSaving,
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("3-Track Sample", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Surface(
                onClick = { onUrlChange("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") },
                enabled = !isRunning && !isSaving,
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder),
                modifier = Modifier.weight(1.2f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("20+ Track (Hits)", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Surface(
                onClick = onPasteClipboard,
                enabled = !isRunning && !isSaving,
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Paste", tint = EmeraldAccent, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Paste", color = TextPrimary, fontSize = 10.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // URL Input
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Public Spotify Playlist URL", fontSize = 11.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EmeraldAccent,
                unfocusedBorderColor = DarkSurfaceBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons: Extract & Save to Room
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRunExtraction,
                enabled = !isRunning && !isSaving && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.1f)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Extracting...", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.Public, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("1. Extract", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = onSaveToRoom,
                enabled = !isRunning && !isSaving && result?.playlist != null,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (result?.playlist != null) EmeraldAccent else DarkSurfaceBorder),
                modifier = Modifier.weight(1.2f)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = EmeraldAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Saving...", color = TextPrimary, fontSize = 11.sp)
                } else {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("2. Save to Room", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Card with STRICT Error/Success UI Representation
        val isFailureStatus = statusText.startsWith("Failed", ignoreCase = true) ||
                statusText.startsWith("Error", ignoreCase = true) ||
                (result != null && result.error != null)

        val isSuccessStatus = !isFailureStatus && (
                (result != null && result.playlist != null) ||
                        statusText.startsWith("Success", ignoreCase = true) ||
                        statusText.startsWith("Saved", ignoreCase = true) ||
                        statusText.startsWith("Streaming", ignoreCase = true) ||
                        statusText.startsWith("Loaded", ignoreCase = true)
                )

        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning || isSaving || isResolvingStream) {
                    CircularProgressIndicator(color = EmeraldAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else if (isFailureStatus) {
                    Icon(imageVector = Icons.Default.Error, contentDescription = "Error", tint = ErrorRed, modifier = Modifier.size(16.dp))
                } else if (isSuccessStatus) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    color = if (isFailureStatus) ErrorRed else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Visual Pipeline Stages Tracker
        if (currentPipelineStep != PipelineStep.IDLE) {
            PipelineStagesTracker(currentStep = currentPipelineStep)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Tracks & Diagnostic List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .height(310.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Diagnostic Metrics Card (if extraction has been run)
            result?.let { res ->
                val diag = res.diagnostics
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DarkSurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = "DIAGNOSTIC METRICS", color = TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("HTTP Status: ${diag.httpStatusCode}", color = if (diag.httpStatusCode in 200..299) EmeraldAccent else ErrorRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text("Host: ${diag.finalHost}", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Strategy: ${diag.parserStrategy}", color = EmeraldAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text("Latency: ${diag.elapsedTimeMs}ms", color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // Display Saved Room Playlist Header & Test Actions
            if (savedEntries.isNotEmpty()) {
                val firstTrack = savedEntries.first()
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ROOM DB: ${savedEntries.size} TRACKS SAVED",
                                    color = EmeraldAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Playlist metadata saved — audio not downloaded",
                                    color = TextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Test Action 1: Isolated Media3 / MediaController Verification (Fake play.mp3 Stream)
                            OutlinedButton(
                                onClick = { onTestFakeStream(firstTrack) },
                                enabled = !isResolvingStream,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, EmeraldAccent),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("spike_test_isolated_stream")
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Isolated Stream: '${firstTrack.title}' (play.mp3)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Test Action 2: Real YouTube Music 6-Stage Streaming Pipeline
                            Button(
                                onClick = { onStreamTrack(firstTrack) },
                                enabled = !isResolvingStream,
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("spike_test_real_pipeline")
                            ) {
                                if (isResolvingStream && resolvingTrackId == firstTrack.trackId) {
                                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Resolving '${firstTrack.title}'...", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Real Pipeline: '${firstTrack.title}'", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ALL TRACKS IN ROOM (${savedEntries.size})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("STATE / STREAM", color = TextTertiary, fontSize = 9.sp)
                    }
                }

                items(savedEntries) { entry ->
                    SavedTrackEntryRow(
                        entry = entry,
                        isResolving = isResolvingStream && resolvingTrackId == entry.trackId,
                        playbackState = playbackState,
                        transientError = if (resolvingTrackId == entry.trackId) transientError else null,
                        onStream = { onStreamTrack(entry) }
                    )
                }
            } else if (result?.playlist != null) {
                // If extracted but not saved yet, show extracted tracks with prompt to save
                items(result.playlist.tracks) { track ->
                    ScrapedTrackRow(track)
                }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(text = "Stage 1 & Stage 2 Instructions:", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("1. Extract 93 tracks via '1. Extract' button.", color = TextSecondary, fontSize = 10.sp)
                            Text("2. Save playlist to Room via '2. Save to Room'.", color = TextSecondary, fontSize = 10.sp)
                            Text("3. Tap 'Test First Track' to resolve and stream without downloading.", color = TextSecondary, fontSize = 10.sp)
                            Text("4. Force-stop NotiFy and reopen to verify Room persistence.", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineStagesTracker(
    currentStep: PipelineStep,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        PipelineStep.SEARCHING to "1. Search",
        PipelineStep.FOUND_CANDIDATES to "2. Found",
        PipelineStep.MATCH_SELECTED to "3. Match",
        PipelineStep.RESOLVING_STREAM to "4. Stream",
        PipelineStep.PLAYER_CONNECTED to "5. Connect",
        PipelineStep.PLAYING to "6. Play"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (currentStep == PipelineStep.FAILED) ErrorRed.copy(alpha = 0.5f) else DarkSurfaceBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STREAMING PIPELINE STAGES",
                    color = if (currentStep == PipelineStep.FAILED) ErrorRed else TextTertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                if (currentStep == PipelineStep.FAILED) {
                    Text("FAILED", color = ErrorRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                } else if (currentStep == PipelineStep.PLAYING) {
                    Text("ACTIVE", color = EmeraldAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEach { (step, label) ->
                    val isCurrent = currentStep == step
                    val isPast = currentStep.ordinal > step.ordinal && currentStep != PipelineStep.FAILED
                    val isFailed = currentStep == PipelineStep.FAILED && currentStep.ordinal >= step.ordinal

                    val bgColor = when {
                        isCurrent -> EmeraldAccent
                        isPast -> EmeraldAccent.copy(alpha = 0.25f)
                        isFailed -> ErrorRed.copy(alpha = 0.2f)
                        else -> DarkSurfaceVariant
                    }
                    val textColor = when {
                        isCurrent -> Color.Black
                        isPast -> EmeraldLight
                        isFailed -> ErrorRed
                        else -> TextSecondary
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(bgColor, RoundedCornerShape(4.dp))
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            fontSize = 8.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedTrackEntryRow(
    entry: PlaylistEntryWithTrack,
    isResolving: Boolean,
    playbackState: PlaybackUiState = PlaybackUiState(),
    transientError: String? = null,
    onStream: () -> Unit
) {
    val isCurrentTrack = playbackState.currentTrack?.id?.rawId == entry.trackId
    val isPlaying = isCurrentTrack && playbackState.isPlaying
    val isBuffering = isCurrentTrack && playbackState.isBuffering

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(
            1.dp,
            when {
                isPlaying -> EmeraldAccent
                entry.resolutionState == ResolutionState.MATCHED -> EmeraldAccent.copy(alpha = 0.5f)
                entry.resolutionState == ResolutionState.RESOLVE_FAILED -> ErrorRed.copy(alpha = 0.5f)
                else -> DarkSurfaceBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_track_row_${entry.trackId}")
            .clickable(
                enabled = !isResolving,
                onClick = onStream
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Position or Artwork
                val effectiveArt = entry.artworkUrl ?: entry.artworkUri
                if (!effectiveArt.isNullOrBlank()) {
                    AlbumArtwork(
                        artworkUri = effectiveArt,
                        contentDescription = entry.title,
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(4.dp),
                        targetSizePx = 128
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(DarkSurfaceVariant, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "${entry.position}", color = EmeraldAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "${entry.artist}${if (!entry.album.isNullOrBlank()) " • ${entry.album}" else ""}",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // State Badge:
                // Badges: Metadata, Searching, Matched, Buffering (transient), Playing (transient), Failed (genuine)
                val (badgeText, badgeColor) = when {
                    isPlaying -> "PLAYING" to EmeraldAccent
                    isBuffering -> "BUFFERING" to Color(0xFF60A5FA)
                    entry.resolutionState == ResolutionState.SEARCHING || isResolving -> "SEARCHING" to Color(0xFF60A5FA)
                    entry.resolutionState == ResolutionState.MATCHED -> "MATCHED" to EmeraldAccent
                    entry.resolutionState == ResolutionState.NEEDS_REVIEW -> "REVIEW" to Color(0xFFFBBF24)
                    entry.resolutionState == ResolutionState.RESOLVE_FAILED -> "FAILED" to ErrorRed
                    else -> "METADATA" to TextTertiary
                }

                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Stream / Retry Icon
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("stream_button_${entry.trackId}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(color = EmeraldAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else if (entry.resolutionState == ResolutionState.RESOLVE_FAILED) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Stream",
                            tint = if (isPlaying) EmeraldAccent else EmeraldLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Failure reason / transient error display
            if (transientError != null && isResolving) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = transientError,
                    color = ErrorRed,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ScrapedTrackRow(track: ScrapedTrack) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, DarkSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(DarkSurfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "${track.position}", color = EmeraldAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = track.title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(text = "${track.artist}${if (!track.album.isNullOrBlank()) " • ${track.album}" else ""}", color = TextSecondary, fontSize = 9.sp, maxLines = 1)
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(text = track.formattedDuration, color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * SpotDL Spike Section (Tab 1)
 */
@Composable
private fun SpotDlSpikeSection(
    inputUrl: String,
    onUrlChange: (String) -> Unit,
    isRunning: Boolean,
    ytDlpVersion: String,
    currentStage: PipelineStage,
    resolvedTrack: SpotifyTrackMetadata?,
    resolvedPlaylist: SpotifyPlaylistMetadata?,
    matchedResult: MatchResult?,
    downloadedAudio: YtDlpFeasibilitySpike.DownloadedAudio?,
    ingestedTrack: Track?,
    userFacingStatus: String,
    currentProgress: Float,
    progressStatusText: String,
    hasCachedStage0Tracks: Boolean,
    onUpdateYtDlp: () -> Unit,
    onTestSingleTrack: () -> Unit,
    onTestPlaylist: () -> Unit,
    onPlayTrack: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Updater row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("yt-dlp Engine Tools", color = TextSecondary, fontSize = 11.sp)
            Surface(
                onClick = onUpdateYtDlp,
                enabled = !isRunning,
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Update yt-dlp", color = TextPrimary, fontSize = 10.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Input URL Field
        OutlinedTextField(
            value = inputUrl,
            onValueChange = onUrlChange,
            label = { Text("Spotify track/playlist URL or YouTube URL", fontSize = 11.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EmeraldAccent,
                unfocusedBorderColor = DarkSurfaceBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action Presets: 1 Track vs 3 Tracks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestSingleTrack,
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("1 Track Spike", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onTestPlaylist,
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (hasCachedStage0Tracks) "3 Tracks (Cached)" else "3 Tracks Spike", color = TextPrimary, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Card with STRICT Failure/Success Icon
        val isSpikeFailed = userFacingStatus.startsWith("Failed", ignoreCase = true) ||
                userFacingStatus.startsWith("Error", ignoreCase = true) ||
                userFacingStatus.contains("HTTP 403")

        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        CircularProgressIndicator(color = EmeraldAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else if (isSpikeFailed) {
                        Icon(imageVector = Icons.Default.Error, contentDescription = "Error", tint = ErrorRed, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = EmeraldAccent, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = userFacingStatus,
                        color = if (isSpikeFailed) ErrorRed else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isRunning && currentProgress > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { currentProgress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = EmeraldAccent,
                        trackColor = DarkSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = progressStatusText, color = TextSecondary, fontSize = 9.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5-Stage Live Information Card
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .height(310.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            resolvedTrack?.let { track ->
                item {
                    StageCard(stageNumber = 1, stageTitle = "Spotify Metadata", isCompleted = true) {
                        Column {
                            Text(text = "Title: ${track.title}", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Artist: ${track.allArtistsDisplay}", color = EmeraldAccent, fontSize = 10.sp)
                            Text(text = "Album: ${track.album ?: "Single"} (${track.releaseYear ?: ""})", color = TextSecondary, fontSize = 10.sp)
                            Text(text = "Duration: ${track.durationMs / 1000}s", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            resolvedPlaylist?.let { playlist ->
                item {
                    StageCard(stageNumber = 2, stageTitle = "Playlist Tracks", isCompleted = true) {
                        Column {
                            Text(text = playlist.title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Extracted ${playlist.tracks.size} of ${playlist.totalTracks} tracks for testing", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            matchedResult?.let { match ->
                item {
                    StageCard(stageNumber = 3, stageTitle = "YouTube Match (SpotDL Engine)", isCompleted = true) {
                        Column {
                            Text(text = "Matched: ${match.candidate.title}", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Channel: ${match.candidate.channelTitle}", color = TextSecondary, fontSize = 10.sp)
                            Text(text = "Confidence: ${(match.matchScore * 100).toInt()}% • Delta: ${match.durationDeltaMs / 1000}s", color = EmeraldAccent, fontSize = 10.sp)
                            Text(text = "Canonical URL: ${match.canonicalDownloadUrl}", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            downloadedAudio?.let { audio ->
                item {
                    StageCard(stageNumber = 4, stageTitle = "Download & FFmpeg Extraction", isCompleted = true) {
                        Column {
                            Text(text = "File: ${audio.file.name} (${audio.file.length() / 1024} KB)", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "Validated Duration: ${audio.durationMs / 1000}s via MediaMetadataRetriever", color = EmeraldAccent, fontSize = 10.sp)
                            if (audio.warnings.isNotEmpty()) {
                                Text(text = "Notices: ${audio.warnings.size} captured (e.g. 90-day version warning)", color = TextSecondary, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            ingestedTrack?.let { track ->
                item {
                    StageCard(stageNumber = 5, stageTitle = "MediaStore Ingest & Offline Playback", isCompleted = true) {
                        Column {
                            Text(text = "Permanent URI: ${track.id.rawId}", color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { onPlayTrack(track) },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play Now in NotiFy", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    stageNumber: Int,
    stageTitle: String,
    isCompleted: Boolean,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isCompleted) EmeraldAccent.copy(alpha = 0.5f) else DarkSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(if (isCompleted) EmeraldAccent else DarkSurfaceVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "$stageNumber", color = if (isCompleted) Color.Black else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stageTitle, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

private suspend fun executeSingleTrackFlow(
    url: String,
    spike: YtDlpFeasibilitySpike,
    spotifyProvider: PublicSpotifyMetadataProvider,
    searchProvider: YtDlpYouTubeSearchProvider,
    ingestor: TrackIngestor,
    onLog: (String, SpikeLogEntry.LogType) -> Unit,
    onProgress: (Float, String) -> Unit,
    onStageChange: (PipelineStage) -> Unit,
    onTrackResolved: (SpotifyTrackMetadata) -> Unit,
    onMatchResolved: (MatchResult) -> Unit,
    onAudioDownloaded: (YtDlpFeasibilitySpike.DownloadedAudio) -> Unit,
    onTrackIngested: (Track) -> Unit,
    onStatus: (String) -> Unit,
    onRunning: (Boolean) -> Unit
) {
    onRunning(true)
    try {
        onStatus("Resolving Spotify URL...")
        onLog("Resolving URL: $url", SpikeLogEntry.LogType.INFO)
        val resolved = SpotifyUrlResolver.resolveAndClassify(url)
        val trackId = when (resolved) {
            is ResolvedUrl.SpotifyTrack -> resolved.trackId
            else -> {
                onStatus("Failed: Please enter a valid Spotify track URL")
                onLog("Invalid Spotify track URL: $resolved", SpikeLogEntry.LogType.ERROR)
                onRunning(false)
                return
            }
        }

        onStageChange(PipelineStage.STAGE_1_METADATA)
        onStatus("Stage 1: Fetching Spotify metadata via PublicSpotifyMetadataProvider...")
        val trackMetaRes = spotifyProvider.fetchTrack(trackId)
        if (trackMetaRes.isFailure) {
            val err = trackMetaRes.exceptionOrNull()?.message ?: "Unknown metadata failure"
            onStatus("Failed: $err")
            onLog("Spotify error: $err", SpikeLogEntry.LogType.ERROR)
            onRunning(false)
            return
        }
        val trackMeta = trackMetaRes.getOrThrow()
        onTrackResolved(trackMeta)
        onLog("Stage 1 Complete: '${trackMeta.title}' by ${trackMeta.allArtistsDisplay}", SpikeLogEntry.LogType.SUCCESS)

        onStageChange(PipelineStage.STAGE_3_MATCHING)
        onStatus("Stage 3: Searching YouTube and matching...")
        val query = "${trackMeta.primaryArtist} - ${trackMeta.title}"
        onLog("Searching YouTube for '$query'...", SpikeLogEntry.LogType.INFO)
        val searchRes = searchProvider.search(query, limit = 3)
        if (searchRes.isFailure) {
            val err = searchRes.exceptionOrNull()?.message ?: "YouTube search failed"
            onStatus("Failed: $err")
            onLog("Search error: $err", SpikeLogEntry.LogType.ERROR)
            onRunning(false)
            return
        }
        val candidates = searchRes.getOrThrow()
        val matchResult = TrackMatchEngine.findBestMatch(trackMeta, candidates)
        if (matchResult == null) {
            onStatus("Failed: No matching YouTube video found")
            onLog("No match found", SpikeLogEntry.LogType.ERROR)
            onRunning(false)
            return
        }
        onMatchResolved(matchResult)
        onLog("Stage 3 Complete: Matched '${matchResult.candidate.title}' (${(matchResult.matchScore * 100).toInt()}% match)", SpikeLogEntry.LogType.SUCCESS)
        onLog("Canonical download URL: ${matchResult.canonicalDownloadUrl}", SpikeLogEntry.LogType.INFO)

        onStageChange(PipelineStage.STAGE_4_DOWNLOADING)
        onStatus("Stage 4: Downloading audio via yt-dlp...")
        val downloadRes = spike.downloadAudioWithValidation(
            canonicalYoutubeUrl = matchResult.canonicalDownloadUrl,
            taskId = "sp_${trackMeta.id}",
            format = "m4a",
            onProgress = onProgress
        )
        if (downloadRes.isFailure) {
            val err = downloadRes.exceptionOrNull()?.message ?: "Download failed"
            onStatus("Failed: $err")
            onLog("Download error: $err", SpikeLogEntry.LogType.ERROR)
            onRunning(false)
            return
        }
        val downloadedAudio = downloadRes.getOrThrow()
        onAudioDownloaded(downloadedAudio)
        onLog("Stage 4 Complete: Downloaded ${downloadedAudio.file.name}, duration: ${downloadedAudio.durationMs / 1000}s", SpikeLogEntry.LogType.SUCCESS)

        onStageChange(PipelineStage.STAGE_5_INGESTED)
        onStatus("Stage 5: Ingesting into MediaStore...")
        val ingestRes = ingestor.ingestTrack(downloadedAudio, trackMeta)
        if (ingestRes.isFailure) {
            val err = ingestRes.exceptionOrNull()?.message ?: "Ingestion failed"
            onStatus("Failed: $err")
            onLog("Ingestion error: $err", SpikeLogEntry.LogType.ERROR)
            onRunning(false)
            return
        }
        val domainTrack = ingestRes.getOrThrow()
        onTrackIngested(domainTrack)
        onStatus("Success: '${domainTrack.title}' ingested and ready for offline playback.")
        onLog("Stage 5 Complete: Ingested to ${domainTrack.id.rawId}.", SpikeLogEntry.LogType.SUCCESS)

    } catch (e: Exception) {
        onStatus("Failed: ${e.message}")
        onLog("Unexpected error: ${e.message}", SpikeLogEntry.LogType.ERROR)
    } finally {
        onRunning(false)
    }
}

private suspend fun executePlaylistFlow(
    url: String,
    limit: Int,
    spike: YtDlpFeasibilitySpike,
    spotifyProvider: PublicSpotifyMetadataProvider,
    searchProvider: YtDlpYouTubeSearchProvider,
    ingestor: TrackIngestor,
    cachedPlaylist: ScrapedPlaylist? = null,
    onLog: (String, SpikeLogEntry.LogType) -> Unit,
    onProgress: (Float, String) -> Unit,
    onStageChange: (PipelineStage) -> Unit,
    onPlaylistResolved: (SpotifyPlaylistMetadata) -> Unit,
    onTrackResolved: (SpotifyTrackMetadata) -> Unit,
    onMatchResolved: (MatchResult) -> Unit,
    onAudioDownloaded: (YtDlpFeasibilitySpike.DownloadedAudio) -> Unit,
    onTrackIngested: (Track) -> Unit,
    onStatus: (String) -> Unit,
    onRunning: (Boolean) -> Unit
) {
    onRunning(true)
    try {
        val playlist: SpotifyPlaylistMetadata = if (cachedPlaylist != null) {
            onStatus("Using already-extracted Stage 0 tracks (Zero re-fetch)...")
            onLog("Consuming Stage 0 playlist '${cachedPlaylist.title}' with ${cachedPlaylist.tracks.size} tracks.", SpikeLogEntry.LogType.INFO)
            val count = minOf(cachedPlaylist.tracks.size, limit)
            SpotifyPlaylistMetadata(
                id = cachedPlaylist.id,
                title = cachedPlaylist.title,
                description = cachedPlaylist.description,
                totalTracks = cachedPlaylist.tracks.size,
                artworkUrl = cachedPlaylist.artworkUrl,
                tracks = cachedPlaylist.tracks.take(count).map {
                    SpotifyTrackMetadata(
                        id = "${cachedPlaylist.id}_${it.position}",
                        title = it.title,
                        artists = listOf(it.artist),
                        album = it.album,
                        releaseYear = null,
                        durationMs = it.durationMs,
                        artworkUrl = it.artworkUrl ?: cachedPlaylist.artworkUrl
                    )
                }
            )
        } else {
            onStatus("Resolving Spotify Playlist via PublicSpotifyMetadataProvider...")
            val resolved = SpotifyUrlResolver.resolveAndClassify(url)
            val playlistId = when (resolved) {
                is ResolvedUrl.SpotifyPlaylist -> resolved.playlistId
                else -> {
                    onStatus("Failed: Please enter a valid Spotify playlist URL")
                    onLog("Invalid playlist URL: $resolved", SpikeLogEntry.LogType.ERROR)
                    onRunning(false)
                    return
                }
            }

            val playlistRes = spotifyProvider.fetchPlaylist(playlistId, limit)
            if (playlistRes.isFailure) {
                val err = playlistRes.exceptionOrNull()?.message ?: "Playlist fetch failed"
                onStatus("Failed: $err")
                onLog("Playlist error: $err", SpikeLogEntry.LogType.ERROR)
                onRunning(false)
                return
            }
            playlistRes.getOrThrow()
        }

        onPlaylistResolved(playlist)
        onStageChange(PipelineStage.STAGE_2_PLAYLIST)
        onLog("Fetched playlist '${playlist.title}' (${playlist.tracks.size} tracks for testing)", SpikeLogEntry.LogType.SUCCESS)

        for ((index, track) in playlist.tracks.withIndex()) {
            onStatus("Processing track ${index + 1}/${playlist.tracks.size}: ${track.title}")
            onTrackResolved(track)

            val query = "${track.primaryArtist} - ${track.title}"
            val searchRes = searchProvider.search(query, limit = 3)
            if (searchRes.isSuccess) {
                val match = TrackMatchEngine.findBestMatch(track, searchRes.getOrThrow())
                if (match != null) {
                    onMatchResolved(match)

                    val downRes = spike.downloadAudioWithValidation(
                        canonicalYoutubeUrl = match.canonicalDownloadUrl,
                        taskId = "pl_${track.id}",
                        format = "m4a",
                        onProgress = onProgress
                    )
                    if (downRes.isSuccess) {
                        val audio = downRes.getOrThrow()
                        onAudioDownloaded(audio)

                        val ingestRes = ingestor.ingestTrack(audio, track)
                        if (ingestRes.isSuccess) {
                            onTrackIngested(ingestRes.getOrThrow())
                            onLog("Track ${index + 1} completed: ${track.title}", SpikeLogEntry.LogType.SUCCESS)
                        }
                    }
                }
            }
        }
        onStatus("Success: Playlist test finished! ${playlist.tracks.size} tracks processed.")
    } catch (e: Exception) {
        onStatus("Failed: ${e.message}")
        onLog("Playlist error: ${e.message}", SpikeLogEntry.LogType.ERROR)
    } finally {
        onRunning(false)
    }
}
