package com.notify.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.notify.ui.components.EqualizerIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notify.core.local.LocalAudioItem
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.RecentSearchItemEntity
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.stream.OnlineStreamResolver
import com.notify.playback.PlaybackUiState
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.EmptyState
import com.notify.ui.components.TrackRow
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary

/**
 * Search screen with two parallel search surfaces:
 *
 * 1. LOCAL: Filtered from scanned MediaStore / SAF tracks (instant, no network).
 * 2. ONLINE: Debounced YouTube Music search via [InnerTubeYouTubeMusicSearchProvider]
 *    with [YtDlpYouTubeSearchProvider] fallback.
 *    Tapping an online result calls [onPlayStream] through the same resolve-and-stream
 *    pipeline used by SpikeTestDialog.
 */
@Composable
fun SearchScreen(
    allTracks: List<LocalAudioItem>,
    playbackState: PlaybackUiState,
    onTrackClick: (List<Track>, Int) -> Unit,
    onPlayStream: (Track, String, PlaybackOrigin) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    onlineSearchViewModel: OnlineSearchViewModel = run {
        val application = LocalContext.current.applicationContext as Application
        val factory = remember(application) {
            OnlineSearchViewModel.Factory(
                application = application,
                innerTubeProvider = InnerTubeYouTubeMusicSearchProvider(),
                fallbackProvider = YtDlpYouTubeSearchProvider(application),
                streamResolver = com.notify.download.stream.ResolvedStreamProviderChain(application)
            )
        }
        viewModel(factory = factory)
    }
) {
    var searchQuery by remember { mutableStateOf("") }
    val onlineState by onlineSearchViewModel.uiState.collectAsState()
    val resolvingVideoId by onlineSearchViewModel.resolvingVideoId.collectAsState()
    val recentMediaItems by onlineSearchViewModel.recentMediaItems.collectAsState()
    val userPlaylists by onlineSearchViewModel.userPlaylists.collectAsState()
    val containingPlaylists by onlineSearchViewModel.playlistsContainingSelectedTrack.collectAsState()
    val downloadedTracks by onlineSearchViewModel.downloadedTracks.collectAsState()
    val playlistTracks by onlineSearchViewModel.playlistTracks.collectAsState()
    val context = LocalContext.current

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var itemToAddToPlaylist by remember { mutableStateOf<RecentSearchItemEntity?>(null) }
    var candidateToAddToPlaylist by remember { mutableStateOf<YouTubeCandidate?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistTitle by remember { mutableStateOf("") }

    val navBarInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniPlayerHeight = if (playbackState.hasTrack) 68.dp else 0.dp
    val dynamicBottomPadding = 100.dp + navBarInsets + miniPlayerHeight

    // Sync selected track ID to ViewModel for playlist membership check
    LaunchedEffect(itemToAddToPlaylist, candidateToAddToPlaylist) {
        val trackId = when {
            itemToAddToPlaylist != null -> {
                val item = itemToAddToPlaylist!!
                com.notify.download.db.PlaylistRepository.deterministicTrackId(item.provider, item.providerSourceId)
            }
            candidateToAddToPlaylist != null -> {
                val cand = candidateToAddToPlaylist!!
                com.notify.download.db.PlaylistRepository.deterministicTrackId(cand.provider ?: "youtube", cand.videoId)
            }
            else -> null
        }
        onlineSearchViewModel.setSelectedTrackForPlaylist(trackId)
    }

    // Sync query change to ViewModel (clears if blank, does not launch yt-dlp on keystroke)
    LaunchedEffect(searchQuery) {
        onlineSearchViewModel.onQueryChanged(searchQuery)
    }

    val localResults = remember(searchQuery, allTracks, downloadedTracks, playlistTracks) {
        onlineSearchViewModel.searchLocalLibrary(searchQuery, allTracks)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Search Input Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "What do you want to listen to?",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = EmeraldAccent,
                        modifier = Modifier.size(22.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = {
                                onlineSearchViewModel.searchOnline(searchQuery)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Submit Search",
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = {
                                searchQuery = ""
                                onlineSearchViewModel.clearSearch()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Search",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onlineSearchViewModel.searchOnline(searchQuery)
                    }
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedBorderColor = EmeraldAccent,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = EmeraldAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_query_field")
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                searchQuery.isBlank() -> {
                    if (recentMediaItems.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "Search for songs, artists or albums.",
                            description = "Songs you play from Search will appear here."
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(bottom = dynamicBottomPadding),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("recents_list")
                        ) {
                            // ── Recents header ─────────────────────────────────────
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Recents",
                                        color = TextPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(
                                        onClick = { showClearConfirmDialog = true },
                                        modifier = Modifier.testTag("btn_clear_all_recents")
                                    ) {
                                        Text(
                                            text = "Clear all",
                                            color = TextSecondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // ── Spotify-style Recent Song Rows ─────────────────────
                            itemsIndexed(
                                items = recentMediaItems,
                                key = { _, item -> item.id }
                            ) { _, item ->
                                RecentSongRow(
                                    item = item,
                                    isResolving = resolvingVideoId == item.providerSourceId,
                                    onPlay = {
                                        onlineSearchViewModel.playRecentMediaItem(item, onPlayStream)
                                    },
                                    onLike = {
                                        onlineSearchViewModel.toggleLikeRecentItem(item)
                                    },
                                    onDownload = {
                                        onlineSearchViewModel.downloadRecentItem(item)
                                    },
                                    onAddToPlaylist = {
                                        itemToAddToPlaylist = item
                                    },
                                    onRemove = {
                                        onlineSearchViewModel.deleteRecentMediaItem(item.id)
                                    }
                                )
                            }
                        }
                    }
                }
                else -> {
                    val resultTracks = remember(localResults) { localResults.map { it.track } }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = dynamicBottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                    // ── LOCAL RESULTS ──────────────────────────────────────────────
                    if (localResults.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "From your library (${localResults.size})",
                                icon = Icons.Default.LibraryMusic
                            )
                        }
                        itemsIndexed(
                            items = localResults,
                            key = { _, item -> "local:${item.track.id.provider.name}:${item.track.id.rawId}" }
                        ) { index, item ->
                            val isCurrentPlaying = playbackState.currentTrack?.id == item.track.id
                            LocalSearchResultRow(
                                item = item,
                                isCurrentTrack = isCurrentPlaying,
                                isPlaying = isCurrentPlaying && playbackState.isPlaying,
                                onClick = { onTrackClick(resultTracks, index) }
                            )
                        }
                    }

                    // ── DIVIDER ────────────────────────────────────────────────────
                    if (localResults.isNotEmpty()) {
                        item {
                            HorizontalDivider(
                                color = DarkSurfaceBorder,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    // ── ONLINE SEARCH ──────────────────────────────────────────────
                    item {
                        SectionHeader(
                            title = "Beyond your library",
                            icon = Icons.Default.Public
                        )
                    }

                    when (val state = onlineState) {
                        is OnlineSearchUiState.Idle -> {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isNotBlank()) {
                                                onlineSearchViewModel.searchOnline(searchQuery)
                                            }
                                        }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "Search everywhere for \"$searchQuery\"" else "Search everywhere",
                                        color = if (searchQuery.isNotBlank()) EmeraldAccent else TextTertiary,
                                        fontSize = 12.sp,
                                        fontWeight = if (searchQuery.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        is OnlineSearchUiState.Searching -> {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = EmeraldAccent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${state.progressText} for \"${state.query}\"…",
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = { onlineSearchViewModel.cancelSearch() }) {
                                            Text(
                                                text = "Cancel",
                                                color = Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is OnlineSearchUiState.TryingFallback -> {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color(0xFFFBBF24), // amber
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Searching \"${state.query}\"…",
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = state.reason,
                                                color = Color(0xFFFBBF24),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = { onlineSearchViewModel.cancelSearch() }) {
                                            Text(
                                                text = "Cancel",
                                                color = Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is OnlineSearchUiState.Loading -> {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = EmeraldAccent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Searching everywhere for \"${state.query}\"…",
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (state.isTakingLonger) {
                                                Text(
                                                    text = "Taking longer than usual…",
                                                    color = TextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = { onlineSearchViewModel.cancelSearch() }) {
                                            Text(
                                                text = "Cancel",
                                                color = Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is OnlineSearchUiState.Results -> {
                            itemsIndexed(
                                items = state.candidates,
                                key = { _, c -> "online:${c.videoId}" }
                            ) { _, candidate ->
                                OnlineCandidateRow(
                                    candidate = candidate,
                                    isResolving = resolvingVideoId == candidate.videoId,
                                    onPlay = {
                                        onlineSearchViewModel.playCandidate(candidate, onPlayStream)
                                    },
                                    onLike = {
                                        onlineSearchViewModel.toggleLikeCandidate(candidate)
                                    },
                                    onDownload = {
                                        onlineSearchViewModel.downloadCandidate(candidate)
                                    },
                                    onAddToPlaylist = {
                                        candidateToAddToPlaylist = candidate
                                    }
                                )
                            }
                        }

                        is OnlineSearchUiState.Empty -> {
                            item {
                                EmptyState(
                                    icon = Icons.Default.SearchOff,
                                    title = "No Online Results",
                                    description = "No tracks found for \"${state.query}\"."
                                )
                            }
                        }

                        is OnlineSearchUiState.Error -> {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (state.isNetworkError) "Network Error" else "Search Failed",
                                                color = Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = state.message,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                        if (state.retryableQuery != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = { onlineSearchViewModel.retry() },
                                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Retry", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is OnlineSearchUiState.Cancelled -> {
                            // Search was cancelled by user; no online items rendered
                        }
                    }

                    // Show empty state only if no local results AND online is not showing anything
                    if (localResults.isEmpty() && onlineState is OnlineSearchUiState.Idle) {
                        item {
                            EmptyState(
                                icon = Icons.Default.SearchOff,
                                title = "Not in your library yet",
                                description = "Ye gaana tumhari library me nahi mila. Poori duniya me dhoond ke dekhein?"
                            )
                        }
                    }
                }
            }
        }
    }

        // ── Clear Recents Confirmation Dialog ──────────────────────────────────
        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = {
                    Text(
                        text = "Clear recent searches?",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "This will clear your recent searches. Your playlists and downloads will not be affected.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onlineSearchViewModel.clearAllRecentMediaItems()
                            showClearConfirmDialog = false
                        },
                        modifier = Modifier.testTag("btn_confirm_clear_recents")
                    ) {
                        Text(
                            text = "Clear",
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearConfirmDialog = false },
                        modifier = Modifier.testTag("btn_cancel_clear_recents")
                    ) {
                        Text(
                            text = "Cancel",
                            color = TextSecondary
                        )
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // ── Add to Playlist Dialog ─────────────────────────────────────────────
        if (itemToAddToPlaylist != null || candidateToAddToPlaylist != null) {
            AlertDialog(
                onDismissRequest = {
                    itemToAddToPlaylist = null
                    candidateToAddToPlaylist = null
                    onlineSearchViewModel.setSelectedTrackForPlaylist(null)
                },
                title = {
                    Text(
                        text = "Add to playlist",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Create New playlist button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showNewPlaylistDialog = true }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "New playlist",
                                color = EmeraldAccent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        HorizontalDivider(
                            color = DarkSurfaceBorder,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        if (userPlaylists.isEmpty()) {
                            Text(
                                text = "No playlists created yet",
                                color = TextTertiary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.height(200.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(
                                    items = userPlaylists,
                                    key = { _, p -> p.playlistId }
                                ) { _, playlist ->
                                    val isAlreadyInPlaylist = containingPlaylists.contains(playlist.playlistId)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val item = itemToAddToPlaylist
                                                val cand = candidateToAddToPlaylist
                                                itemToAddToPlaylist = null
                                                candidateToAddToPlaylist = null
                                                onlineSearchViewModel.setSelectedTrackForPlaylist(null)

                                                if (item != null) {
                                                    onlineSearchViewModel.addRecentItemToPlaylist(playlist.playlistId, playlist.title, item)
                                                }
                                                if (cand != null) {
                                                    onlineSearchViewModel.addCandidateToPlaylist(playlist.playlistId, playlist.title, cand)
                                                }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.title,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (isAlreadyInPlaylist) "Already in playlist" else "${playlist.trackCount} tracks",
                                                color = if (isAlreadyInPlaylist) EmeraldAccent else TextSecondary,
                                                fontSize = 12.sp
                                            )
                                        }

                                        if (isAlreadyInPlaylist) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Already in playlist",
                                                tint = EmeraldAccent,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .testTag("membership_check_${playlist.playlistId}")
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add to playlist",
                                                tint = TextSecondary,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .testTag("membership_add_${playlist.playlistId}")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = {
                            itemToAddToPlaylist = null
                            candidateToAddToPlaylist = null
                            onlineSearchViewModel.setSelectedTrackForPlaylist(null)
                        }
                    ) {
                        Text(
                            text = "Cancel",
                            color = TextSecondary
                        )
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // ── New Playlist Dialog ────────────────────────────────────────────────
        if (showNewPlaylistDialog) {
            AlertDialog(
                onDismissRequest = {
                    showNewPlaylistDialog = false
                    newPlaylistTitle = ""
                },
                title = {
                    Text(
                        text = "New playlist",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    OutlinedTextField(
                        value = newPlaylistTitle,
                        onValueChange = { newPlaylistTitle = it },
                        placeholder = { Text("Playlist name", color = TextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedBorderColor = EmeraldAccent,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = EmeraldAccent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newPlaylistTitle.isNotBlank()) {
                                val title = newPlaylistTitle.trim()
                                val item = itemToAddToPlaylist
                                val cand = candidateToAddToPlaylist
                                showNewPlaylistDialog = false
                                newPlaylistTitle = ""
                                itemToAddToPlaylist = null
                                candidateToAddToPlaylist = null
                                onlineSearchViewModel.setSelectedTrackForPlaylist(null)

                                if (item != null) {
                                    onlineSearchViewModel.createPlaylistAndAddRecentItem(title, item)
                                } else if (cand != null) {
                                    onlineSearchViewModel.createPlaylistAndAddCandidate(title, cand)
                                } else {
                                    onlineSearchViewModel.createPlaylist(title)
                                }
                            }
                        },
                        enabled = newPlaylistTitle.isNotBlank()
                    ) {
                        Text(
                            text = "Create & Add",
                            color = if (newPlaylistTitle.isNotBlank()) EmeraldAccent else TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showNewPlaylistDialog = false
                            newPlaylistTitle = ""
                        }
                    ) {
                        Text(
                            text = "Cancel",
                            color = TextSecondary
                        )
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmeraldAccent,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RecentSongRow(
    item: RecentSearchItemEntity,
    isResolving: Boolean = false,
    onPlay: () -> Unit,
    onLike: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_song_row_${item.id}")
            .alpha(if (isResolving) 0.7f else 1.0f)
            .clickable(role = Role.Button, enabled = !isResolving) { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork with spinner overlay while resolving
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtwork(
                    trackId = item.id,
                    artworkUri = item.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(6.dp),
                    targetSizePx = 128
                )
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldAccent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Song • ${item.artist}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onLike,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_like_recent_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = "Like song",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_download_recent_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download song",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onAddToPlaylist,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_add_to_playlist_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add to playlist",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_remove_recent_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove from recents",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun OnlineCandidateRow(
    candidate: YouTubeCandidate,
    isResolving: Boolean = false,
    onPlay: () -> Unit,
    onLike: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {}
) {
    val durationSec = candidate.durationMs / 1000
    val mm = durationSec / 60
    val ss = durationSec % 60
    val durationLabel = "%d:%02d".format(mm, ss)

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("online_candidate_row_${candidate.videoId}")
            .alpha(if (isResolving) 0.7f else 1.0f)
            .clickable(role = Role.Button, enabled = !isResolving) { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtwork(
                    trackId = candidate.videoId,
                    artworkUri = candidate.artworkUrl,
                    contentDescription = candidate.title,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(6.dp),
                    targetSizePx = 128
                )
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldAccent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        candidate.channelTitle?.let { append(it) }
                        if (candidate.durationMs > 0) {
                            if (isNotEmpty()) append(" • ")
                            append(durationLabel)
                        }
                    },
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onLike,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_like_candidate_${candidate.videoId}")
            ) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_download_candidate_${candidate.videoId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onAddToPlaylist,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_add_candidate_${candidate.videoId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add to playlist",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .testTag("online_play_${candidate.videoId}"),
                contentAlignment = Alignment.Center
            ) {
                if (isResolving) {
                    CircularProgressIndicator(
                        color = EmeraldAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Maps a [YouTubeCandidate] to a [Track] domain model for the playback pipeline.
 * The canonical watch URL (www.youtube.com/watch?v=…) is stored as [AudioSource.Remote],
 * matching the invariant: Spotify URLs must never be passed to yt-dlp.
 */
private fun YouTubeCandidate.asDomainTrack(): Track = Track(
    id = TrackId(provider = ProviderId.YOUTUBE, rawId = videoId),
    title = title,
    artist = channelTitle ?: "",
    album = null,
    durationMs = durationMs,
    artworkUri = artworkUrl,
    source = AudioSource.Remote(ProviderId.YOUTUBE, videoId)
)

@Composable
fun LocalSearchResultRow(
    item: LocalSearchResult,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentTrack) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isCurrentTrack) EmeraldAccent.copy(alpha = 0.5f) else DarkSurfaceBorder,
                RoundedCornerShape(10.dp)
            )
            .testTag("local_result_row_${item.track.id.rawId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtwork(
                    track = item.track,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp)),
                    shape = RoundedCornerShape(6.dp),
                    targetSizePx = 128
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerIndicator(isPlaying = true, color = EmeraldAccent)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.track.title,
                    color = if (isCurrentTrack) EmeraldAccent else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.track.artist,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (item.isDownloaded) EmeraldAccent.copy(alpha = 0.15f) else DarkSurfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (item.isDownloaded) EmeraldAccent.copy(alpha = 0.4f) else DarkSurfaceBorder
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            if (item.isDownloaded) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = item.badgeText,
                                color = if (item.isDownloaded) EmeraldAccent else TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = if (item.isDownloaded) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

