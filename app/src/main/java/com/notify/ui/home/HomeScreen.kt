package com.notify.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.Track
import com.notify.download.stream.TrendingTrack
import com.notify.playback.PlaybackUiState
import com.notify.ui.LocalLibraryUiState
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.EmptyState
import com.notify.ui.components.EqualizerIndicator
import com.notify.ui.library.DownloadedTrackItem
import com.notify.ui.library.PlaylistCard
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.EmeraldLight
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    libraryState: LocalLibraryUiState,
    playbackState: PlaybackUiState,
    onNavigateToLibrary: () -> Unit,
    onNavigateToPlaylist: (String) -> Unit = {},
    onPlayTrack: (Track) -> Unit,
    onPlayStream: (Track, String, PlaybackOrigin) -> Unit = { _, _, _ -> },
    onPlayStreamWithContext: (Track, String, PlaybackOrigin, List<Track>) -> Unit = { track, streamUrl, origin, _ ->
        onPlayStream(track, streamUrl, origin)
    },
    onPlayQueue: (List<Track>, Int) -> Unit = { _, _ -> },
    onShuffleAll: () -> Unit,
    onOpenProfileDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Home ViewModel — manages filter chips, trending, artists, downloads, and playlists
    val homeViewModel: HomeViewModel = viewModel()
    val selectedChip by homeViewModel.selectedChip.collectAsStateWithLifecycle()
    val trendingState by homeViewModel.state.collectAsStateWithLifecycle()
    val artistsState by homeViewModel.artistsState.collectAsStateWithLifecycle()
    val resolvingTrackId by homeViewModel.resolvingTrackId.collectAsStateWithLifecycle()
    val downloadedTracks by homeViewModel.downloadedTracks.collectAsStateWithLifecycle()
    val playlists by homeViewModel.playlists.collectAsStateWithLifecycle()
    val allDbTracks by homeViewModel.allDbTracks.collectAsStateWithLifecycle()
    val followedArtists by homeViewModel.followedArtists.collectAsStateWithLifecycle()
    val artistSearchQuery by homeViewModel.artistSearchQuery.collectAsStateWithLifecycle()
    val artistSearchResults by homeViewModel.artistSearchResults.collectAsStateWithLifecycle()
    val isSearchingArtists by homeViewModel.isSearchingArtists.collectAsStateWithLifecycle()
    var showEditArtistsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val userProfilePrefs = remember { com.notify.core.preferences.UserProfilePreferences.getInstance(context) }
    val userDisplayName by userProfilePrefs.displayNameFlow.collectAsStateWithLifecycle()
    val nickname = remember(userDisplayName) {
        userDisplayName.ifBlank {
            com.notify.auth.SupabaseProfileAndKeyRepository(context).getCachedNickname() ?: ""
        }
    }

    val greeting = GreetingHelper.getGreeting()
    val allTracks = libraryState.allTracks

    // Aggregated tracks across scanned tracks, downloads, and Room playlists (deduplicated)
    val aggregatedTracks = remember(libraryState.allTracks, downloadedTracks, allDbTracks) {
        homeViewModel.getAggregatedTracks(libraryState.allTracks.map { it.track })
    }

    // Recently Added: sorted by dateModifiedEpochSeconds, up to 12 items
    val recentlyAdded = allTracks
        .filter { it.dateModifiedEpochSeconds > 0L }
        .sortedByDescending { it.dateModifiedEpochSeconds }
        .take(12)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // ── App Header & Greeting ──────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val avatarInitial = remember(nickname) {
                        nickname.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenProfileDrawer),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    brush = Brush.linearGradient(listOf(EmeraldAccent, EmeraldLight)),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = avatarInitial,
                                color = Color.Black,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NotiFy",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        val versionName = remember {
                            try {
                                context.packageManager
                                    .getPackageInfo(context.packageName, 0)
                                    .versionName ?: ""
                            } catch (_: Exception) { "" }
                        }
                        val subtitleText = remember(greeting, nickname, versionName) {
                            val greetingPart = if (nickname.isNotBlank()) "$greeting, $nickname" else greeting
                            if (versionName.isNotEmpty()) "$greetingPart · v$versionName" else greetingPart
                        }
                        Text(
                            text = subtitleText,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // ── Horizontal Filter Chips ────────────────────────────────────────
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                val chips = listOf(
                    HomeChip.ALL to "All",
                    HomeChip.DOWNLOADED to "Downloaded",
                    HomeChip.PLAYLISTS to "Playlists"
                )
                items(chips) { (chip, label) ->
                    FilterChip(
                        selected = selectedChip == chip,
                        onClick = { homeViewModel.selectChip(chip) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selectedChip == chip) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldAccent,
                            selectedLabelColor = Color.Black,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedChip == chip,
                            selectedBorderColor = Color.Transparent,
                            borderColor = DarkSurfaceBorder
                        )
                    )
                }
            }
        }

        when (selectedChip) {
            HomeChip.ALL -> {
                // ── Quick Action Cards (All Songs & Shuffle All) ───────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // All Songs Quick Card
                        Card(
                            onClick = onNavigateToLibrary,
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(DarkSurfaceVariant, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "All Songs",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${aggregatedTracks.size} tracks",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Shuffle All Quick Card
                        Card(
                            onClick = {
                                if (aggregatedTracks.isNotEmpty()) {
                                    onPlayQueue(aggregatedTracks.shuffled(), 0)
                                } else {
                                    onShuffleAll()
                                }
                            },
                            enabled = aggregatedTracks.isNotEmpty(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (aggregatedTracks.isNotEmpty()) EmeraldAccent else DarkSurfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = null,
                                        tint = if (aggregatedTracks.isNotEmpty()) Color.Black else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Shuffle All",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (aggregatedTracks.isNotEmpty()) "Play random" else "No songs",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Currently Playing Card (if active) ────────────────────────────
                playbackState.currentTrack?.let { currentTrack ->
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, EmeraldAccent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AlbumArtwork(
                                        track = currentTrack,
                                        modifier = Modifier.size(52.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        targetSizePx = 128
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = currentTrack.title,
                                            color = EmeraldAccent,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${currentTrack.artist} • ${currentTrack.album ?: "Unknown"}",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Trending Now Section ───────────────────────────────────────────
                when (val ts = trendingState) {
                    is TrendingUiState.Success -> {
                        item {
                            TrendingSectionHeader()
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(ts.tracks, key = { it.songId.ifBlank { it.query } }) { track ->
                                    val trackKey = track.songId.ifBlank { track.query }
                                    TrendingCard(
                                        track = track,
                                        isResolving = (resolvingTrackId == trackKey),
                                        onClick = {
                                            homeViewModel.playTrendingTrack(
                                                track = track,
                                                contextTracks = ts.tracks,
                                                onPlayStream = onPlayStream,
                                                onPlayStreamWithContext = onPlayStreamWithContext
                                            )
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    is TrendingUiState.Loading -> {
                        item {
                            TrendingSectionHeader()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = EmeraldAccent,
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                        }
                    }
                    is TrendingUiState.Hidden -> {
                        // Silently hide when intentionally empty
                    }
                    is TrendingUiState.Error -> {
                        item {
                            TrendingSectionHeader()
                            Card(
                                onClick = { homeViewModel.loadTrending(forceRefresh = true) },
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Tap to retry loading trending tracks",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Popular Curated Artists Sections ─────────────────────────────────
                item(key = "favorite_artists_header") {
                    FavoriteArtistsHeader(
                        onEditClick = { showEditArtistsSheet = true }
                    )
                }

                when (val asState = artistsState) {
                    is CuratedArtistsUiState.Success -> {
                        if (asState.sections.isEmpty()) {
                            item(key = "empty_artists_prompt") {
                                Card(
                                    onClick = { showEditArtistsSheet = true },
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 6.dp)
                                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .background(DarkSurfaceVariant, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = EmeraldAccent,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Add Your Favorite Artists",
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Follow artists to see their popular tracks on Home",
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            asState.sections.forEach { section ->
                                if (section.tracks.isNotEmpty()) {
                                    item(key = "artist_header_${section.artistId}") {
                                        ArtistSectionHeader(
                                            artistName = section.name,
                                            artistImageUrl = section.imageUrl
                                        )
                                    }
                                item(key = "artist_carousel_${section.artistId}") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(
                                            items = section.tracks,
                                            key = { "${section.artistId}_${it.songId.ifBlank { it.query }}" }
                                        ) { track ->
                                            val trackKey = track.songId.ifBlank { track.query }
                                            TrendingCard(
                                                track = track,
                                                isResolving = (resolvingTrackId == trackKey),
                                                onClick = {
                                                    homeViewModel.playTrendingTrack(
                                                        track = track,
                                                        contextTracks = section.tracks,
                                                        onPlayStream = onPlayStream,
                                                        onPlayStreamWithContext = onPlayStreamWithContext
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
                    is CuratedArtistsUiState.Loading -> {
                        item {
                            ShimmerArtistSection()
                            Spacer(modifier = Modifier.height(12.dp))
                            ShimmerArtistSection()
                        }
                    }
                    is CuratedArtistsUiState.Hidden -> {
                        // Silently hide when intentionally empty
                    }
                    is CuratedArtistsUiState.Error -> {
                        item(key = "artists_error_card") {
                            Card(
                                onClick = { homeViewModel.loadCuratedArtists(forceRefresh = true) },
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Tap to retry loading popular artists",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Recently Added Section (horizontal carousel) ───────────────────
                if (recentlyAdded.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Recently Added",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(recentlyAdded, key = { _, item -> item.track.id.rawId }) { index, item ->
                                    Card(
                                        onClick = {
                                            val domainTracks = recentlyAdded.map { it.track }
                                            onPlayQueue(domainTracks, index)
                                        },
                                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .width(140.dp)
                                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            AlbumArtwork(
                                                track = item.track,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = item.track.title,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.track.artist,
                                                color = TextSecondary,
                                                fontSize = 10.sp,
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
            }
            HomeChip.DOWNLOADED -> {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Downloaded Songs",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${downloadedTracks.size} offline tracks ready to play",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        if (downloadedTracks.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    onClick = {
                                        val domainTracks = downloadedTracks.map { homeViewModel.toDomainTrack(it) }
                                        onPlayQueue(domainTracks, 0)
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = EmeraldAccent,
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = Color.Black,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Play",
                                            color = Color.Black,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        val domainTracks = downloadedTracks.map { homeViewModel.toDomainTrack(it) }
                                        onPlayQueue(domainTracks.shuffled(), 0)
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = DarkSurfaceVariant,
                                    border = BorderStroke(1.dp, DarkSurfaceBorder),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = "Shuffle",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (downloadedTracks.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.FileDownload,
                            title = "No Offline Downloads",
                            description = "Songs you download or listen to will appear here for offline playback."
                        )
                    }
                } else {
                    itemsIndexed(downloadedTracks, key = { _, item -> item.downloadId }) { index, item ->
                        val isCurrentlyPlaying = playbackState.currentTrack?.id?.rawId == item.trackId
                        val isPlaying = isCurrentlyPlaying && playbackState.isPlaying

                        HomeDownloadedTrackRow(
                            position = index + 1,
                            item = item,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isPlaying = isPlaying,
                            onClick = {
                                val domainTracks = downloadedTracks.map { homeViewModel.toDomainTrack(it) }
                                onPlayQueue(domainTracks, index)
                            }
                        )
                    }
                }
            }
            HomeChip.PLAYLISTS -> {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Your Playlists",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${playlists.size} playlists available",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (playlists.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.LibraryMusic,
                            title = "No Playlists Yet",
                            description = "Create custom playlists or import your Spotify playlists in the Library tab."
                        )
                    }
                } else {
                    items(playlists.chunked(2), key = { chunk -> chunk.first().playlistId }) { chunk ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            chunk.forEach { playlist ->
                                Box(modifier = Modifier.weight(1f)) {
                                    PlaylistCard(
                                        playlist = playlist,
                                        onClick = { onNavigateToPlaylist(playlist.playlistId) },
                                        onRename = {},
                                        onDelete = {}
                                    )
                                }
                            }
                            if (chunk.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditArtistsSheet) {
        EditArtistsBottomSheet(
            onDismissRequest = {
                showEditArtistsSheet = false
                homeViewModel.clearArtistSearch()
            },
            followedArtists = followedArtists,
            searchQuery = artistSearchQuery,
            onSearchQueryChange = { homeViewModel.onArtistSearchQueryChanged(it) },
            searchResults = artistSearchResults,
            isSearching = isSearchingArtists,
            onFollowArtist = { artist ->
                homeViewModel.followArtist(artist)
            },
            onUnfollowArtist = { artist ->
                homeViewModel.unfollowArtist(artist)
            }
        )
    }
}

// ── Trending helper composables ───────────────────────────────────────────────

@Composable
private fun TrendingSectionHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = null,
            tint = EmeraldAccent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Trending Now",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TrendingCard(
    track: TrendingTrack,
    onClick: () -> Unit,
    isResolving: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = { if (!isResolving) onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .width(140.dp)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Album Art with resolving overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
            ) {
                if (track.albumArtUrl.isNotBlank()) {
                    val highResArt = remember(track.albumArtUrl) {
                        com.notify.core.playback.ArtworkResolution.highResArtwork(track.albumArtUrl, 512) ?: track.albumArtUrl
                    }
                    AsyncImage(
                        model = highResArt,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = EmeraldAccent.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
                // Per-card spinner overlay while stream is being resolved
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldAccent,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = track.title,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist.ifBlank { "Unknown Artist" },
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArtistSectionHeader(
    artistName: String,
    artistImageUrl: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
                .border(1.dp, EmeraldAccent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (artistImageUrl.isNotBlank()) {
                val highResArtist = remember(artistImageUrl) {
                    com.notify.core.playback.ArtworkResolution.highResArtwork(artistImageUrl, 256) ?: artistImageUrl
                }
                AsyncImage(
                    model = highResArtist,
                    contentDescription = artistName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = artistName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "Popular Songs",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ShimmerArtistSection() {
    val transition = rememberInfiniteTransition(label = "artist_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "artist_shimmer_alpha"
    )
    val shimmerBrush = DarkSurfaceVariant.copy(alpha = alpha)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(4) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .width(140.dp)
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDownloadedTrackRow(
    position: Int,
    item: DownloadedTrackItem,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .border(
                1.dp,
                if (isCurrentlyPlaying) EmeraldAccent.copy(alpha = 0.5f) else DarkSurfaceBorder,
                RoundedCornerShape(10.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position / Playing indicator
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isPlaying -> EqualizerIndicator(isPlaying = true, color = EmeraldAccent)
                    isCurrentlyPlaying -> Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = EmeraldAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    else -> Text(
                        text = position.toString(),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Artwork (uses local cache for offline + highRes fallback)
            AlbumArtwork(
                trackId = item.trackId,
                artworkUri = item.artworkUri,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(6.dp),
                targetSizePx = 256,
                contentDescription = item.title
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title & Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = if (isCurrentlyPlaying) EmeraldAccent else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = EmeraldAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${item.artist} • ${formatDuration(item.durationMs)}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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

// ── Favorite Artists Helper Composables ────────────────────────────────────────

@Composable
private fun FavoriteArtistsHeader(
    onEditClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Favorite Artists",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                text = "Top songs tailored for you",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Surface(
            onClick = onEditClick,
            shape = RoundedCornerShape(20.dp),
            color = DarkSurfaceVariant,
            border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f)),
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Edit Artists",
                    tint = EmeraldAccent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Edit Artists",
                    color = EmeraldAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditArtistsBottomSheet(
    onDismissRequest: () -> Unit,
    followedArtists: List<com.notify.download.stream.FollowedArtist>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchResults: List<com.notify.download.stream.FollowedArtist>,
    isSearching: Boolean,
    onFollowArtist: (com.notify.download.stream.FollowedArtist) -> Unit,
    onUnfollowArtist: (com.notify.download.stream.FollowedArtist) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Edit Favorite Artists",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Follow up to 10 artists for your Home feed",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        text = "Search artists (e.g. Arijit Singh, Diljit)...",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = if (searchQuery.isNotEmpty()) EmeraldAccent else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    focusedIndicatorColor = EmeraldAccent,
                    unfocusedIndicatorColor = DarkSurfaceBorder,
                    cursorColor = EmeraldAccent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Search Results Mode
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "Search Results",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldAccent,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No artists found for \"$searchQuery\"",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults, key = { it.id }) { artist ->
                            val isFollowed = followedArtists.any { it.id == artist.id }
                            ArtistSearchRow(
                                artist = artist,
                                isFollowed = isFollowed,
                                onFollow = { onFollowArtist(artist) }
                            )
                        }
                    }
                }
            } else {
                // Followed Artists List
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Followed Artists",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${followedArtists.size}/10",
                        color = if (followedArtists.size >= 10) EmeraldAccent else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (followedArtists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No artists followed yet. Search above to add your favorites.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(followedArtists, key = { it.id }) { artist ->
                            FollowedArtistRow(
                                artist = artist,
                                onRemove = { onUnfollowArtist(artist) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSearchRow(
    artist: com.notify.download.stream.FollowedArtist,
    isFollowed: Boolean,
    onFollow: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (artist.imageUrl.isNotBlank()) {
                        val highResArtist = remember(artist.imageUrl) {
                            com.notify.core.playback.ArtworkResolution.highResArtwork(artist.imageUrl, 256) ?: artist.imageUrl
                        }
                        AsyncImage(
                            model = highResArtist,
                            contentDescription = artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = artist.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isFollowed) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = EmeraldAccent.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.4f)),
                    modifier = Modifier.height(30.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Added",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Added",
                            color = EmeraldAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Surface(
                    onClick = onFollow,
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.6f)),
                    modifier = Modifier.height(30.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Follow",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Follow",
                            color = EmeraldAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedArtistRow(
    artist: com.notify.download.stream.FollowedArtist,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(1.dp, EmeraldAccent.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (artist.imageUrl.isNotBlank()) {
                        val highResArtist = remember(artist.imageUrl) {
                            com.notify.core.playback.ArtworkResolution.highResArtwork(artist.imageUrl, 256) ?: artist.imageUrl
                        }
                        AsyncImage(
                            model = highResArtist,
                            contentDescription = artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = artist.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${artist.name}",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

