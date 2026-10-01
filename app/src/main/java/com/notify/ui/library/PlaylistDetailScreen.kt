package com.notify.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.playback.PlaybackUiState
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.EqualizerIndicator
import com.notify.ui.components.ErrorCard
import com.notify.ui.components.PlaylistArtwork
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary

@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    playbackState: PlaybackUiState,
    onBack: () -> Unit,
    onTrackClick: (PlaylistEntryWithTrack) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onDismissError: () -> Unit,
    onRetryArtwork: () -> Unit = {},
    onDownloadPlaylist: () -> Unit = {},
    onDownloadTrack: (PlaylistEntryWithTrack) -> Unit = {},
    onRemoveDownload: (PlaylistEntryWithTrack) -> Unit = {},
    onPauseQueue: () -> Unit = {},
    onResumeQueue: () -> Unit = {},
    onCancelQueue: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onRetryTrackDownload: (PlaylistEntryWithTrack) -> Unit = {},
    onRemoveTrack: (PlaylistEntryWithTrack, (String) -> Unit) -> Unit = { _, _ -> },
    onUndoRemoveTrack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val playlist = uiState.playlist
    val tracks = uiState.tracks
    val firstTrackArtwork = tracks.firstOrNull()?.let { it.artworkUrl ?: it.artworkUri }
    val isSpotify = playlist?.playlistId?.startsWith("pl_spotify_") == true || !playlist?.sourceUrl.isNullOrBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("playlist_detail_screen_root")
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("btn_playlist_detail_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("btn_playlist_detail_menu")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = TextPrimary
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Retry Artwork", color = TextPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            showMenu = false
                            onRetryArtwork()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", color = TextPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            showMenu = false
                            showRenameDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Playlist", color = ErrorRed, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Hero Header Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistArtwork(
                        artworkUri = playlist?.artworkUri,
                        firstTrackArtworkUrl = firstTrackArtwork,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        targetSizePx = 256
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist?.title ?: "Playlist",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("playlist_detail_title")
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${tracks.size} songs",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.testTag("playlist_detail_track_count")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = if (isSpotify) EmeraldAccent.copy(alpha = 0.15f) else DarkSurfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isSpotify) "Spotify Playlist" else "Custom Playlist",
                                    color = if (isSpotify) EmeraldAccent else TextTertiary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            // Download Playlist Button
                            val allDownloaded = tracks.isNotEmpty() && tracks.all {
                                uiState.trackStates[it.trackId] is TrackDownloadDisplayState.Downloaded
                            }
                            val isDownloading = uiState.isQueueActive

                            Surface(
                                onClick = { if (!isDownloading && !allDownloaded) onDownloadPlaylist() },
                                color = if (allDownloaded) EmeraldAccent.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, if (allDownloaded) EmeraldAccent else DarkSurfaceBorder),
                                modifier = Modifier.testTag("btn_download_playlist")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    if (isDownloading) {
                                        CircularProgressIndicator(
                                            color = EmeraldAccent,
                                            strokeWidth = 1.5.dp,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Downloading...", color = EmeraldAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    } else if (allDownloaded) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Downloaded",
                                            tint = EmeraldAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Downloaded", color = EmeraldAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download Playlist",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Download", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            // Download Queue Status Card (Live Room updates: Downloading 3 / 93, Current, Queued, Failed, Controls)
            if (uiState.isQueueActive || uiState.queueTotalCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("playlist_download_queue_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (uiState.isQueuePaused) {
                                        "Queue Paused (${uiState.queueCurrentIndex} / ${uiState.queueTotalCount})"
                                    } else {
                                        "Downloading ${uiState.queueCurrentIndex} / ${uiState.queueTotalCount}"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isQueuePaused) TextSecondary else EmeraldAccent,
                                    modifier = Modifier.testTag("text_queue_progress_header")
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = if (uiState.isQueuePaused) onResumeQueue else onPauseQueue,
                                        modifier = Modifier.testTag("btn_queue_pause_resume")
                                    ) {
                                        Text(
                                            text = if (uiState.isQueuePaused) "Resume" else "Pause",
                                            color = EmeraldAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    TextButton(
                                        onClick = onCancelQueue,
                                        modifier = Modifier.testTag("btn_queue_cancel")
                                    ) {
                                        Text(
                                            text = "Cancel",
                                            color = ErrorRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            uiState.queueCurrentTitle?.let { title ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Current: $title",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("text_queue_current_title")
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Queued: ${uiState.queuePendingCount}   Failed: ${uiState.queueFailedCount}",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.testTag("text_queue_counts")
                                )

                                if (uiState.queueFailedCount > 0) {
                                    TextButton(
                                        onClick = onRetryFailed,
                                        modifier = Modifier.testTag("btn_queue_retry_failed")
                                    ) {
                                        Text(
                                            text = "Retry Failed",
                                            color = EmeraldAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Error Banner (if any)
            uiState.playbackError?.let { err ->
                item {
                    ErrorCard(message = err, onDismiss = onDismissError)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Tracks Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tracks",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    uiState.artworkProgress?.let { progressText ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                color = EmeraldAccent,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = progressText,
                                color = EmeraldAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Tracks List
            if (tracks.isEmpty() && !uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tracks in this playlist yet",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = tracks,
                    key = { _, entry -> "${entry.entryId}_${entry.trackId}" }
                ) { _, entry ->
                    val isCurrentlyPlaying = playbackState.currentTrack?.id?.rawId == entry.trackId
                    val isResolvingPlayback = uiState.resolvingTrackId == entry.trackId
                    val displayState = uiState.trackStates[entry.trackId] ?: TrackDownloadDisplayState.NotDownloaded

                    PlaylistTrackItem(
                        position = entry.position,
                        entry = entry,
                        displayState = displayState,
                        isCurrentlyPlaying = isCurrentlyPlaying,
                        isPlaying = isCurrentlyPlaying && playbackState.isPlaying,
                        isResolvingPlayback = isResolvingPlayback,
                        onClick = { onTrackClick(entry) },
                        onDownload = { onDownloadTrack(entry) },
                        onRemoveDownload = { onRemoveDownload(entry) },
                        onRetryDownload = { onRetryTrackDownload(entry) },
                        onRemove = {
                            onRemoveTrack(entry) { removedTitle ->
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val res = snackbarHostState.showSnackbar(
                                        message = "Removed \"$removedTitle\" from playlist",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (res == SnackbarResult.ActionPerformed) {
                                        onUndoRemoveTrack()
                                    }
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .testTag("playlist_detail_snackbar_host")
        )
    }

    // Dialog: Rename Playlist
    if (showRenameDialog && playlist != null) {
        var newTitle by remember { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
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
                        .testTag("dialog_rename_playlist_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onRenamePlaylist(newTitle)
                            showRenameDialog = false
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_dialog_rename_confirm")
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }

    // Dialog: Delete Confirmation
    if (showDeleteDialog && playlist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Playlist", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${playlist.title}\"? This action cannot be undone.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_dialog_delete_confirm")
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

/**
 * Renders an individual playlist track row with truthful download state.
 *
 * Rules:
 * - Exactly one state is always visible for every row.
 * - Playback indicators (equalizer / play arrow) represent playback ONLY, never download status.
 * - No misleading green dot for MATCHED.
 * - Downloaded checkmark requires passing strict download truth validation.
 */
@Composable
private fun PlaylistTrackItem(
    position: Int,
    entry: PlaylistEntryWithTrack,
    displayState: TrackDownloadDisplayState,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    isResolvingPlayback: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onRemove: () -> Unit = {}
) {
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
            .testTag("playlist_track_row_${entry.trackId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position or Playback Indicator (ONLY represents current playback)
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isResolvingPlayback -> {
                        CircularProgressIndicator(
                            color = EmeraldAccent,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
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
            val effectiveArtwork = entry.selectedSourceArtworkUrl
                ?: entry.artworkUrl?.takeIf { it.isNotBlank() }
                ?: entry.artworkUri?.takeIf { it.isNotBlank() }
            AlbumArtwork(
                trackId = entry.trackId,
                artworkUri = effectiveArtwork,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                targetSizePx = 256
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title & Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    color = if (isCurrentlyPlaying) EmeraldAccent else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.artist,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Truthful per-track download state indicator (ALWAYS non-blank and exactly one visible state)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .testTag("download_state_box_${entry.trackId}"),
                contentAlignment = Alignment.Center
            ) {
                when (displayState) {
                    is TrackDownloadDisplayState.NotDownloaded -> {
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("btn_download_track_${entry.trackId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download Track",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    is TrackDownloadDisplayState.Resolving -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.testTag("download_resolving_${entry.trackId}")
                        ) {
                            CircularProgressIndicator(
                                color = EmeraldAccent,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                    }

                    is TrackDownloadDisplayState.Queued -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.testTag("download_queued_${entry.trackId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Queued",
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            if (displayState.queuePosition > 0) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "#${displayState.queuePosition}",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is TrackDownloadDisplayState.Downloading -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.testTag("download_progress_${entry.trackId}")
                        ) {
                            CircularProgressIndicator(
                                progress = { displayState.progressPercent / 100f },
                                color = EmeraldAccent,
                                trackColor = DarkSurfaceBorder,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "${displayState.progressPercent}%",
                                color = EmeraldAccent,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    is TrackDownloadDisplayState.Validating -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.testTag("download_validating_${entry.trackId}")
                        ) {
                            CircularProgressIndicator(
                                color = EmeraldAccent,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    is TrackDownloadDisplayState.Downloaded -> {
                        IconButton(
                            onClick = onRemoveDownload,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("download_downloaded_${entry.trackId}")
                                .testTag("download_pinned_${entry.trackId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = EmeraldAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    is TrackDownloadDisplayState.Failed -> {
                        IconButton(
                            onClick = onRetryDownload,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("download_failed_${entry.trackId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Download Failed - Retry",
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    is TrackDownloadDisplayState.MissingCorrupt -> {
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("download_corrupt_${entry.trackId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "File Missing/Corrupt - Re-download",
                                tint = ErrorRed.copy(alpha = 0.9f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Formatted Duration
            Text(
                text = formatDuration(entry.durationMs),
                color = TextTertiary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Track Overflow Menu (Remove from this playlist)
            var showTrackMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showTrackMenu = true },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("btn_track_menu_${entry.trackId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Track options",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showTrackMenu,
                    onDismissRequest = { showTrackMenu = false },
                    modifier = Modifier.background(DarkSurfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Remove from playlist", color = ErrorRed, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            showTrackMenu = false
                            onRemove()
                        },
                        modifier = Modifier.testTag("menu_item_remove_${entry.trackId}")
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--:--"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
}
