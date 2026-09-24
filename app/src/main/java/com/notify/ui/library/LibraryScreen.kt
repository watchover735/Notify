package com.notify.ui.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.download.db.PlaylistSummary
import com.notify.playback.PlaybackUiState
import com.notify.ui.components.EmptyState
import com.notify.ui.components.ErrorCard
import com.notify.ui.components.PlaylistArtwork
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
import com.notify.core.model.AudioSource
import com.notify.core.model.DownloadBucket
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.engine.StorageStats
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.EqualizerIndicator

enum class DownloadSortOrder(val displayName: String) {
    RECENT("Recent"),
    TITLE("Title"),
    SIZE("Size")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: PlaylistLibraryUiState,
    playbackState: PlaybackUiState,
    onPlaylistClick: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onImportSpotify: (String, (Boolean) -> Unit) -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    onPlayTrack: (Track) -> Unit = {},
    onTabSelected: (LibraryTab) -> Unit = {},
    onDeleteDownload: (String) -> Unit = {},
    onClearAllDownloads: () -> Unit = {},
    onPinDownload: (String) -> Unit = {},
    onUnpinDownload: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showImportSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<PlaylistSummary?>(null) }
    var playlistToDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var downloadSortOrder by remember { mutableStateOf(DownloadSortOrder.RECENT) }

    val filteredPlaylists = remember(uiState.playlists, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.playlists
        } else {
            uiState.playlists.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredDownloads = remember(uiState.downloadedTracks, searchQuery, downloadSortOrder) {
        val base = if (searchQuery.isBlank()) {
            uiState.downloadedTracks
        } else {
            uiState.downloadedTracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true) ||
                    (it.album != null && it.album.contains(searchQuery, ignoreCase = true))
            }
        }
        when (downloadSortOrder) {
            DownloadSortOrder.RECENT -> base.sortedByDescending { it.downloadedAtEpochMs }
            DownloadSortOrder.TITLE -> base.sortedBy { it.title.lowercase() }
            DownloadSortOrder.SIZE -> base.sortedByDescending { it.fileSizeBytes }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp)
            .testTag("library_screen_root")
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Header: Title & Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("library_title")
                )
                Text(
                    text = if (uiState.selectedTab == LibraryTab.PLAYLISTS) {
                        "${uiState.playlists.size} playlists"
                    } else {
                        "${uiState.downloadedTracks.size} downloaded tracks"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("library_playlist_count")
                )
            }

            if (uiState.selectedTab == LibraryTab.PLAYLISTS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // New Playlist Button
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                            .testTag("btn_create_playlist")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Playlist",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Import Spotify Playlist Button
                    Button(
                        onClick = { showImportSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_import_spotify")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Segmented Tabs: Playlists vs Downloads
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isPlaylists = uiState.selectedTab == LibraryTab.PLAYLISTS
            Surface(
                onClick = { onTabSelected(LibraryTab.PLAYLISTS) },
                color = if (isPlaylists) EmeraldAccent.copy(alpha = 0.2f) else DarkSurface,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isPlaylists) EmeraldAccent else DarkSurfaceBorder
                ),
                modifier = Modifier.testTag("library_tab_playlists")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = if (isPlaylists) EmeraldAccent else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Playlists (${uiState.playlists.size})",
                        color = if (isPlaylists) EmeraldAccent else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isPlaylists) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            val isDownloads = uiState.selectedTab == LibraryTab.DOWNLOADS
            Surface(
                onClick = { onTabSelected(LibraryTab.DOWNLOADS) },
                color = if (isDownloads) EmeraldAccent.copy(alpha = 0.2f) else DarkSurface,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDownloads) EmeraldAccent else DarkSurfaceBorder
                ),
                modifier = Modifier.testTag("library_tab_downloads")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = if (isDownloads) EmeraldAccent else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Downloads (${uiState.downloadedTracks.size})",
                        color = if (isDownloads) EmeraldAccent else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isDownloads) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Field (active for both tabs when items exist)
        val showSearch = if (uiState.selectedTab == LibraryTab.PLAYLISTS) {
            uiState.playlists.isNotEmpty()
        } else {
            uiState.downloadedTracks.isNotEmpty()
        }

        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (uiState.selectedTab == LibraryTab.PLAYLISTS) "Find in playlists..." else "Find in downloads...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedBorderColor = EmeraldAccent,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("library_filter_field")
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Active Error / Action Messages
        uiState.importError?.let { msg ->
            ErrorCard(message = msg, onDismiss = onDismissError)
            Spacer(modifier = Modifier.height(10.dp))
        }

        uiState.actionMessage?.let { msg ->
            Surface(
                color = DarkSurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissMessage() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        color = EmeraldAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissMessage, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Content: Loading / Playlists Grid / Downloads Section
        when (uiState.selectedTab) {
            LibraryTab.PLAYLISTS -> {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = EmeraldAccent, modifier = Modifier.size(36.dp))
                        }
                    }
                    uiState.playlists.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.LibraryMusic,
                            title = "Your Library is Empty",
                            description = "Import your favorite Spotify playlists or create custom playlists to start listening.",
                            actionLabel = "Import Spotify Playlist",
                            onActionClick = { showImportSheet = true }
                        )
                    }
                    filteredPlaylists.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "No Playlists Found",
                            description = "No playlists in your library match \"$searchQuery\"."
                        )
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_playlist_grid")
                        ) {
                            items(filteredPlaylists, key = { it.playlistId }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.playlistId) },
                                    onRename = { playlistToRename = playlist },
                                    onDelete = { playlistToDelete = playlist }
                                )
                            }
                        }
                    }
                }
            }
            LibraryTab.DOWNLOADS -> {
                DownloadsContent(
                    storageStats = uiState.storageStats,
                    downloadedTracks = filteredDownloads,
                    totalDownloadedCount = uiState.downloadedTracks.size,
                    searchQuery = searchQuery,
                    sortOrder = downloadSortOrder,
                    onSortOrderChange = { downloadSortOrder = it },
                    onClearAllClick = { showClearAllDialog = true },
                    onTrackClick = { item ->
                        val track = Track(
                            id = TrackId.spotify(item.trackId),
                            title = item.title,
                            artist = item.artist,
                            album = item.album,
                            durationMs = item.durationMs,
                            artworkUri = item.artworkUri,
                            source = AudioSource.Local(item.contentUriString)
                        )
                        onPlayTrack(track)
                    },
                    onPinDownload = onPinDownload,
                    onUnpinDownload = onUnpinDownload,
                    onDeleteDownload = onDeleteDownload,
                    playbackState = playbackState
                )
            }
        }
    }

    // Modal: Standalone Import Spotify Playlist BottomSheet
    if (showImportSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var inputUrl by remember { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { if (!uiState.isImporting) showImportSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            contentColor = TextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("import_playlist_bottom_sheet")
            ) {
                Text(
                    text = "Import Spotify Playlist",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Paste any public Spotify playlist URL or share link (e.g. open.spotify.com/playlist/...)",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    placeholder = { Text("https://open.spotify.com/playlist/...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !uiState.isImporting,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                inputUrl = clipText.trim()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = EmeraldAccent, modifier = Modifier.size(18.dp))
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
                        .testTag("import_spotify_url_field")
                )

                if (uiState.importError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.importError ?: "",
                        color = ErrorRed,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { showImportSheet = false },
                        enabled = !uiState.isImporting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onImportSpotify(inputUrl) { success ->
                                if (success) {
                                    showImportSheet = false
                                }
                            }
                        },
                        enabled = inputUrl.isNotBlank() && !uiState.isImporting,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_confirm_import")
                    ) {
                        if (uiState.isImporting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Import Playlist", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
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
                        if (newTitle.isNotBlank()) {
                            onCreatePlaylist(newTitle)
                            showCreateDialog = false
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_confirm_create_playlist")
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
            shape = RoundedCornerShape(14.dp)
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
                    placeholder = { Text("Playlist title", color = TextSecondary) },
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
                        .testTag("rename_playlist_title_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (updatedTitle.isNotBlank()) {
                            onRenamePlaylist(pl.playlistId, updatedTitle)
                            playlistToRename = null
                        }
                    },
                    enabled = updatedTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_confirm_rename_playlist")
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
            shape = RoundedCornerShape(14.dp)
        )
    }

    // Dialog: Delete Confirmation
    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${pl.title}\"? This action cannot be undone.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePlaylist(pl.playlistId)
                        playlistToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_confirm_delete_playlist")
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
            shape = RoundedCornerShape(14.dp)
        )
    }

    // Dialog: Clear All Downloads Confirmation
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Downloads", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete all ${uiState.downloadedTracks.size} offline tracks? This will free ${formatBytes(uiState.storageStats.totalSizeBytes)} of storage on this device.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllDownloads()
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_confirm_clear_all_downloads")
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun DownloadsContent(
    storageStats: StorageStats,
    downloadedTracks: List<DownloadedTrackItem>,
    totalDownloadedCount: Int,
    searchQuery: String,
    sortOrder: DownloadSortOrder,
    onSortOrderChange: (DownloadSortOrder) -> Unit,
    onClearAllClick: () -> Unit,
    onTrackClick: (DownloadedTrackItem) -> Unit,
    onPinDownload: (String) -> Unit,
    onUnpinDownload: (String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    playbackState: PlaybackUiState
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("downloads_list"),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Storage Breakdown Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                    .testTag("downloads_storage_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Offline Storage",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (totalDownloadedCount > 0) {
                            TextButton(
                                onClick = onClearAllClick,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("btn_clear_all_downloads")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = ErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Clear All",
                                    color = ErrorRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = formatBytes(storageStats.totalSizeBytes),
                                color = TextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$totalDownloadedCount tracks • ${formatBytes(storageStats.freeSpaceBytes)} free on device",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(EmeraldAccent, RoundedCornerShape(2.dp))
                                )
                                Text(
                                    text = "Pinned: ${formatBytes(storageStats.pinnedSizeBytes)}",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF00E5FF), RoundedCornerShape(2.dp))
                                )
                                Text(
                                    text = "Smart: ${formatBytes(storageStats.smartSizeBytes)}",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sort Row (only if we have downloads)
        if (totalDownloadedCount > 0) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sort:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    DownloadSortOrder.values().forEach { order ->
                        val isSelected = sortOrder == order
                        Surface(
                            onClick = { onSortOrderChange(order) },
                            color = if (isSelected) EmeraldAccent.copy(alpha = 0.2f) else DarkSurface,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) EmeraldAccent else DarkSurfaceBorder
                            ),
                            modifier = Modifier.testTag("sort_chip_${order.name.lowercase()}")
                        ) {
                            Text(
                                text = order.displayName,
                                color = if (isSelected) EmeraldAccent else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Empty States or Downloaded Track Rows
        when {
            totalDownloadedCount == 0 -> {
                item {
                    EmptyState(
                        icon = Icons.Default.FileDownload,
                        title = "No Offline Downloads",
                        description = "Songs you download or listen to for more than 30 seconds are saved here for seamless offline playback."
                    )
                }
            }
            downloadedTracks.isEmpty() -> {
                item {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No Downloads Found",
                        description = "No offline tracks match \"$searchQuery\"."
                    )
                }
            }
            else -> {
                itemsIndexed(downloadedTracks, key = { _, item -> item.downloadId }) { index, item ->
                    val isCurrentlyPlaying = playbackState.currentTrack?.id?.rawId == item.trackId
                    val isPlaying = isCurrentlyPlaying && playbackState.isPlaying

                    DownloadedTrackRow(
                        position = index + 1,
                        item = item,
                        isCurrentlyPlaying = isCurrentlyPlaying,
                        isPlaying = isPlaying,
                        onClick = { onTrackClick(item) },
                        onPin = { onPinDownload(item.downloadId) },
                        onUnpin = { onUnpinDownload(item.downloadId) },
                        onDelete = { onDeleteDownload(item.downloadId) }
                    )
                }
            }
        }
    }
}

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

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isCurrentlyPlaying) EmeraldAccent.copy(alpha = 0.5f) else DarkSurfaceBorder,
                RoundedCornerShape(10.dp)
            )
            .testTag("download_track_row_${item.trackId}")
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
                    isPlaying -> {
                        EqualizerIndicator(isPlaying = true, color = EmeraldAccent)
                    }
                    isCurrentlyPlaying -> {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Current Track",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = "$position",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Artwork
            AlbumArtwork(
                artworkUri = item.artworkUri,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                targetSizePx = 128
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title, Artist, Duration & Size
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = if (isCurrentlyPlaying) EmeraldAccent else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.artist,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${formatDuration(item.durationMs)} • ${formatBytes(item.fileSizeBytes)}",
                        color = TextTertiary,
                        fontSize = 10.sp
                    )

                    // Bucket Badge
                    Surface(
                        color = if (isPinned) EmeraldAccent.copy(alpha = 0.15f) else Color(0xFF00E5FF).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            if (isPinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(8.dp)
                                )
                            }
                            Text(
                                text = if (isPinned) "Pinned" else "Smart",
                                color = if (isPinned) EmeraldAccent else Color(0xFF00E5FF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Pin/Unpin action
            IconButton(
                onClick = if (isPinned) onUnpin else onPin,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_pin_${item.trackId}")
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = if (isPinned) "Unpin download" else "Pin permanently",
                    tint = if (isPinned) EmeraldAccent else TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Delete action
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("btn_delete_${item.trackId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete download",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

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
            // Artwork (Priority: artworkUri -> firstTrackArtworkUrl -> placeholder)
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

                // Overflow Menu icon
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

            // Title
            Text(
                text = playlist.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Subtitle: Track Count & Provider Badge
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
