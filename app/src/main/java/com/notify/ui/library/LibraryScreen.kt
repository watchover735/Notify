package com.notify.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.notify.core.model.AudioSource
import com.notify.core.model.DownloadBucket
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.PlaylistSummary
import com.notify.download.stream.FollowedArtist
import com.notify.playback.PlaybackUiState
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.EmptyState
import com.notify.ui.components.EqualizerIndicator
import com.notify.ui.components.PlaylistArtwork
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextTertiary
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.notify.core.preferences.UserProfilePreferences
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary

enum class LibrarySortOrder(val displayName: String) {
    RECENTS("Recents"),
    ALPHABETICAL("Alphabetical"),
    CREATOR("Creator")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: PlaylistLibraryUiState,
    playbackState: PlaybackUiState,
    onPlaylistClick: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onImportSpotify: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onNavigateToImport: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onPlayTrack: (Track) -> Unit = {},
    onPlayQueue: (List<Track>, Int) -> Unit = { _, _ -> },
    onTabSelected: (LibraryTab) -> Unit = {},
    onFilterSelected: (LibraryFilter) -> Unit = {},
    onDeleteDownload: (String) -> Unit = {},
    onClearAllDownloads: () -> Unit = {},
    onPinDownload: (String) -> Unit = {},
    onUnpinDownload: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<PlaylistSummary?>(null) }
    var playlistToDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentSortOrder by remember { mutableStateOf(LibrarySortOrder.RECENTS) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreditSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val userProfilePrefs = remember { UserProfilePreferences.getInstance(context) }
    val userDisplayName by userProfilePrefs.displayNameFlow.collectAsState()
    var showProfileDialog by remember { mutableStateOf(false) }

    val profileInitial = remember(userDisplayName) {
        userDisplayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    // Separate Liked Songs from regular playlists
    val likedSongsPlaylist = remember(uiState.playlists) {
        uiState.playlists.find {
            it.playlistId == PlaylistRepository.LIKED_SONGS_PLAYLIST_ID ||
                it.title.equals("Liked Songs", ignoreCase = true)
        }
    }

    val regularPlaylists = remember(uiState.playlists) {
        uiState.playlists.filterNot {
            it.playlistId == PlaylistRepository.LIKED_SONGS_PLAYLIST_ID ||
                it.title.equals("Liked Songs", ignoreCase = true)
        }
    }

    val filteredPlaylists = remember(regularPlaylists, searchQuery, currentSortOrder) {
        val base = if (searchQuery.isBlank()) {
            regularPlaylists
        } else {
            regularPlaylists.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
        when (currentSortOrder) {
            LibrarySortOrder.RECENTS -> base.sortedWith(compareByDescending<PlaylistSummary> { it.dateModifiedEpochMs }.thenBy { it.playlistId })
            LibrarySortOrder.ALPHABETICAL -> base.sortedWith(compareBy<PlaylistSummary> { it.title.lowercase() }.thenBy { it.playlistId })
            LibrarySortOrder.CREATOR -> base.sortedWith(compareBy<PlaylistSummary> { it.sourceUrl.orEmpty() }.thenBy { it.playlistId })
        }
    }

    val filteredArtists = remember(uiState.followedArtists, searchQuery, currentSortOrder) {
        val base = if (searchQuery.isBlank()) {
            uiState.followedArtists
        } else {
            uiState.followedArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        when (currentSortOrder) {
            LibrarySortOrder.RECENTS -> base
            LibrarySortOrder.ALPHABETICAL -> base.sortedBy { it.name.lowercase() }
            LibrarySortOrder.CREATOR -> base
        }
    }

    val filteredDownloads = remember(uiState.downloadedTracks, searchQuery, currentSortOrder) {
        val base = if (searchQuery.isBlank()) {
            uiState.downloadedTracks
        } else {
            uiState.downloadedTracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true) ||
                    (it.album != null && it.album.contains(searchQuery, ignoreCase = true))
            }
        }
        when (currentSortOrder) {
            LibrarySortOrder.RECENTS -> base.sortedWith(compareByDescending<DownloadedTrackItem> { it.downloadedAtEpochMs }.thenBy { it.downloadId })
            LibrarySortOrder.ALPHABETICAL -> base.sortedWith(compareBy<DownloadedTrackItem> { it.title.lowercase() }.thenBy { it.downloadId })
            LibrarySortOrder.CREATOR -> base.sortedWith(compareByDescending<DownloadedTrackItem> { it.fileSizeBytes }.thenBy { it.downloadId })
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("library_screen_root")
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // ── 1. Top Header: User avatar, "Your Library" title, Search & Plus & Import ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Profile Avatar Initial
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE07A5F))
                        .clickable { showProfileDialog = true }
                        .testTag("btn_profile_avatar"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profileInitial,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Your Library",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("library_title")
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Search Toggle Icon
                IconButton(
                    onClick = {
                        isSearchVisible = !isSearchVisible
                        if (!isSearchVisible) searchQuery = ""
                    },
                    modifier = Modifier.size(38.dp).testTag("btn_library_search")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = if (isSearchVisible) EmeraldAccent else TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Import Playlist Icon (Navigates to ImportPlaylistScreen)
                IconButton(
                    onClick = onNavigateToImport,
                    modifier = Modifier.size(38.dp).testTag("btn_import_spotify")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Input,
                        contentDescription = "Import Playlist",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Create Playlist Button (Opens Create Dialog)
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.size(38.dp).testTag("btn_create_playlist")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Playlist",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Search Bar (Shown when search is toggled)
        AnimatedVisibility(
            visible = isSearchVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search your library...", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    focusedBorderColor = EmeraldAccent,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("library_search_field")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 2. Filter Chips: Playlists | Artists | Downloads ────────────────────────
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                LibraryChip(
                    label = "Playlists",
                    isSelected = uiState.selectedFilter == LibraryFilter.PLAYLISTS,
                    onClick = { onFilterSelected(LibraryFilter.PLAYLISTS) }
                )
            }
            item {
                LibraryChip(
                    label = "Artists",
                    isSelected = uiState.selectedFilter == LibraryFilter.ARTISTS,
                    onClick = { onFilterSelected(LibraryFilter.ARTISTS) }
                )
            }
            item {
                LibraryChip(
                    label = "Downloads",
                    isSelected = uiState.selectedFilter == LibraryFilter.DOWNLOADS,
                    onClick = { onFilterSelected(LibraryFilter.DOWNLOADS) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── 3. Sort Row: "⇅ Recents" ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .clickable { showSortMenu = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Sort",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentSortOrder.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(DarkSurfaceVariant)
                ) {
                    LibrarySortOrder.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sort.displayName,
                                    color = if (sort == currentSortOrder) EmeraldAccent else TextPrimary,
                                    fontWeight = if (sort == currentSortOrder) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                currentSortOrder = sort
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            if (uiState.selectedFilter == LibraryFilter.DOWNLOADS && uiState.downloadedTracks.isNotEmpty()) {
                val domainTracks = remember(filteredDownloads) {
                    filteredDownloads.map { dl ->
                        Track(
                            id = TrackId.spotify(dl.trackId),
                            title = dl.title,
                            artist = dl.artist,
                            album = dl.album,
                            durationMs = dl.durationMs,
                            artworkUri = dl.artworkUri,
                            source = AudioSource.Local(dl.contentUriString)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { onPlayQueue(domainTracks, 0) },
                        shape = RoundedCornerShape(16.dp),
                        color = EmeraldAccent,
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(14.dp))
                            Text("Play", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        onClick = { onPlayQueue(domainTracks.shuffled(), 0) },
                        shape = RoundedCornerShape(16.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkSurfaceBorder),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = TextPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                    TextButton(
                        onClick = { showClearAllDialog = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear all",
                            tint = ErrorRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Clear All", color = ErrorRed, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── 4. Main Body: Spotify-Style Vertical List ─────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Case A: Filter = DOWNLOADS
            if (uiState.selectedFilter == LibraryFilter.DOWNLOADS) {
                if (filteredDownloads.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.FileDownload,
                            title = if (searchQuery.isBlank()) "No Offline Downloads" else "No Downloads Found",
                            description = if (searchQuery.isBlank()) "Songs you download or listen to for more than 30 seconds are saved here." else "No offline tracks match \"$searchQuery\"."
                        )
                    }
                } else {
                    itemsIndexed(filteredDownloads, key = { _, item -> item.downloadId }) { index, item ->
                        val isCurrentlyPlaying = playbackState.currentTrack?.id?.rawId == item.trackId
                        val isPlaying = isCurrentlyPlaying && playbackState.isPlaying

                        DownloadedTrackRow(
                            position = index + 1,
                            item = item,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isPlaying = isPlaying,
                            onClick = {
                                val domainTracks = filteredDownloads.map { dl ->
                                    Track(
                                        id = TrackId.spotify(dl.trackId),
                                        title = dl.title,
                                        artist = dl.artist,
                                        album = dl.album,
                                        durationMs = dl.durationMs,
                                        artworkUri = dl.artworkUri,
                                        source = AudioSource.Local(dl.contentUriString)
                                    )
                                }
                                onPlayQueue(domainTracks, index)
                            },
                            onPin = { onPinDownload(item.downloadId) },
                            onUnpin = { onUnpinDownload(item.downloadId) },
                            onDelete = { onDeleteDownload(item.downloadId) }
                        )
                    }
                }
                item(key = "developer_credit_downloads") {
                    DeveloperCreditRow(onClick = { showCreditSheet = true })
                }
            }
            // Case B: Filter = ARTISTS
            else if (uiState.selectedFilter == LibraryFilter.ARTISTS) {
                if (filteredArtists.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Person,
                            title = if (searchQuery.isBlank()) "No artists yet" else "No Artists Found",
                            description = if (searchQuery.isBlank()) "Artists you follow on the Home screen will appear here." else "No followed artists match \"$searchQuery\"."
                        )
                    }
                } else {
                    items(filteredArtists, key = { it.id }) { artist ->
                        ArtistListRow(artist = artist)
                    }
                }
                item(key = "developer_credit_artists") {
                    DeveloperCreditRow(onClick = { showCreditSheet = true })
                }
            }
            // Case C: Filter = ALL or PLAYLISTS
            else {
                // 1. Pinned "Liked Songs" Row (always pinned at the top)
                item(key = "pinned_liked_songs") {
                    LikedSongsPinnedRow(
                        trackCount = likedSongsPlaylist?.trackCount ?: 0,
                        onClick = {
                            val targetId = likedSongsPlaylist?.playlistId ?: PlaylistRepository.LIKED_SONGS_PLAYLIST_ID
                            onPlaylistClick(targetId)
                        }
                    )
                }

                // 2. Playlists List
                if (filteredPlaylists.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "No Playlists Found",
                            description = "No playlists match \"$searchQuery\"."
                        )
                    }
                } else {
                    items(filteredPlaylists, key = { it.playlistId }) { playlist ->
                        PlaylistListRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.playlistId) },
                            onRename = { playlistToRename = playlist },
                            onDelete = { playlistToDelete = playlist }
                        )
                    }
                }

                // 3. Artists (when in ALL filter mode, display followed artists under playlists)
                if (uiState.selectedFilter == LibraryFilter.ALL && filteredArtists.isNotEmpty()) {
                    items(filteredArtists, key = { "artist_${it.id}" }) { artist ->
                        ArtistListRow(artist = artist)
                    }
                }

                item(key = "developer_credit_library") {
                    DeveloperCreditRow(onClick = { showCreditSheet = true })
                }
            }
        }
    }

    // Developer Contact Bottom Sheet
    if (showCreditSheet) {
        DeveloperCreditBottomSheet(onDismiss = { showCreditSheet = false })
    }

    // Dialog: Profile Settings
    if (showProfileDialog) {
        ProfileSettingsDialog(
            initialName = userDisplayName,
            onDismiss = { showProfileDialog = false },
            onSave = { newName ->
                userProfilePrefs.updateDisplayName(newName)
            }
        )
    }

    // Dialog: Create Playlist
    if (showCreateDialog) {
        var newTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("Playlist name", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = EmeraldAccent,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_playlist_title_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newTitle.trim()
                        if (trimmed.isNotBlank()) {
                            onCreatePlaylist(trimmed)
                            showCreateDialog = false
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    modifier = Modifier.testTag("create_playlist_confirm_button")
                ) {
                    Text("Create", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog: Rename Playlist
    playlistToRename?.let { pl ->
        var updatedTitle by remember { mutableStateOf(pl.title) }
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Rename Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = updatedTitle,
                    onValueChange = { updatedTitle = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = EmeraldAccent,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = updatedTitle.trim()
                        if (trimmed.isNotBlank()) {
                            onRenamePlaylist(pl.playlistId, trimmed)
                            playlistToRename = null
                        }
                    },
                    enabled = updatedTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog: Delete Playlist
    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete '${pl.title}'?",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePlaylist(pl.playlistId)
                        playlistToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog: Clear All Downloads
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Downloads", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete all offline tracks? This will free device storage.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllDownloads()
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Delete All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ── Components: Filter Chip ──────────────────────────────────────────────────
@Composable
private fun LibraryChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = EmeraldAccent,
            selectedLabelColor = Color.Black,
            containerColor = Color(0xFF282828),
            labelColor = TextPrimary
        ),
        border = null
    )
}

// ── Components: Pinned Liked Songs Row ───────────────────────────────────────
@Composable
private fun LikedSongsPinnedRow(
    trackCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag("playlist_card_liked_songs"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 64dp Purple Gradient Tile with White Heart
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF450AF5),
                            Color(0xFF8E8EE5),
                            Color(0xFFC4B5FD)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Liked Songs",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Liked Songs",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = EmeraldAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Playlist • $trackCount songs",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ── Components: Playlist Row (Spotify Style) ─────────────────────────────────
@Composable
private fun PlaylistListRow(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val isYouTube = playlist.playlistId.startsWith("pl_youtube_") ||
        playlist.sourceUrl?.contains("youtube", ignoreCase = true) == true
    val isSpotify = playlist.playlistId.startsWith("pl_spotify_") ||
        playlist.sourceUrl?.contains("spotify", ignoreCase = true) == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag("playlist_card_${playlist.playlistId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 64dp Cover Art
        PlaylistArtwork(
            artworkUri = playlist.artworkUri,
            firstTrackArtworkUrl = playlist.firstTrackArtworkUrl,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp)),
            targetSizePx = 256
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title & Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val subtitle = if (playlist.trackCount == 1) "Playlist • 1 song" else "Playlist • ${playlist.trackCount} songs"
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                if (isYouTube) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Color(0xFFFF0000).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "YouTube",
                            color = Color(0xFFFF4D4D),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                } else if (isSpotify) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = EmeraldAccent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Spotify",
                            color = EmeraldAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // More Options Dropdown
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .size(32.dp)
                    .testTag("playlist_menu_${playlist.playlistId}")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(DarkSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        showMenu = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = ErrorRed, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

// ── Components: Artist Row (Spotify Style) ───────────────────────────────────
@Composable
private fun ArtistListRow(
    artist: FollowedArtist,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 64dp Circular Avatar
        val highResArtistUrl = com.notify.core.playback.ArtworkResolution.highResArtwork(artist.imageUrl, 256)
        SubcomposeAsyncImage(
            model = highResArtistUrl ?: artist.imageUrl,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = EmeraldAccent,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2E2E2E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Artist",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

// ── Components: Downloaded Track Row ─────────────────────────────────────────
@Composable
private fun DownloadedTrackRow(
    position: Int,
    item: DownloadedTrackItem,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit
) {
    val isPinned = item.bucket == DownloadBucket.PINNED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 64dp Artwork
        Box(modifier = Modifier.size(64.dp)) {
            AlbumArtwork(
                trackId = item.trackId,
                artworkUri = item.artworkUri,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp)),
                targetSizePx = 256
            )

            if (isCurrentlyPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        EqualizerIndicator(
                            isPlaying = true,
                            color = EmeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Playing",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = if (isCurrentlyPlaying) EmeraldAccent else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Downloaded",
                    tint = EmeraldAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${item.artist} • ${formatBytes(item.fileSizeBytes)}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Actions: Pin / Delete
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (isPinned) onUnpin() else onPin() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin",
                    tint = if (isPinned) EmeraldAccent else TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Retained for HomeScreen and external callers.
 */
@Composable
fun PlaylistCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val isSpotify = playlist.playlistId.startsWith("pl_spotify_") || !playlist.sourceUrl.isNullOrBlank()

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
            .testTag("playlist_card_${playlist.playlistId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                PlaylistArtwork(
                    artworkUri = playlist.artworkUri,
                    firstTrackArtworkUrl = playlist.firstTrackArtworkUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    targetSizePx = 256
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .background(DarkSurface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                            .testTag("playlist_menu_${playlist.playlistId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = ErrorRed, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = playlist.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${playlist.trackCount} tracks",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Surface(
                    color = if (isSpotify) EmeraldAccent.copy(alpha = 0.15f) else DarkSurfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isSpotify) "Spotify" else "Custom",
                        color = if (isSpotify) EmeraldAccent else TextTertiary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Profile Settings",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "Set your display name to personalize your avatar initial.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text("Display Name (e.g. Raju, Simran)", color = TextTertiary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = EmeraldAccent,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        cursorColor = EmeraldAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_profile_name")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(nameInput.trim())
                    onDismiss()
                },
                modifier = Modifier.testTag("btn_save_profile")
            ) {
                Text("Save", color = EmeraldAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (nameInput.isNotBlank()) {
                    TextButton(
                        onClick = {
                            nameInput = ""
                            onSave("")
                            onDismiss()
                        },
                        modifier = Modifier.testTag("btn_clear_profile")
                    ) {
                        Text("Clear", color = TextSecondary)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        }
    )
}

