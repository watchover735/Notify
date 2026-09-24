package com.notify.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.core.playback.PlaybackStateUtils
import com.notify.download.db.PlaylistSummary
import com.notify.playback.PlaybackUiState
import com.notify.ui.components.AlbumArtwork
import com.notify.ui.components.ErrorCard
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onDismissError: () -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    isLiked: Boolean = false,
    isDownloaded: Boolean = false,
    userPlaylists: List<PlaylistSummary> = emptyList(),
    containingPlaylists: Set<String> = emptySet(),
    onToggleLike: () -> Unit = {},
    onAddToPlaylist: (String) -> Unit = {},
    onToggleDownload: () -> Unit = {},
    onToggleABRepeat: () -> Unit = {},
    onClearABRepeat: () -> Unit = {},
    onUpdateABStart: (Long) -> Unit = {},
    onUpdateABEnd: (Long) -> Unit = {},
    onDismissABRepeatError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack
    var showQueueSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onDismissABRepeatError()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Collapse Button & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse Now Playing",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "NOW PLAYING",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Open Queue",
                        tint = TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Playback Error Banner (if present)
            playbackState.playbackError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                ErrorCard(
                    message = error.message,
                    onDismiss = onDismissError
                )
            }

            // Center: Album Artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtwork(
                    track = track,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    targetSizePx = 512
                )
            }

            // Track Information & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = track?.title ?: "No track playing",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track?.let { "${it.artist} • ${it.album ?: "Unknown"}" } ?: "Select a song to start",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Like button
                    IconButton(
                        onClick = onToggleLike,
                        enabled = track != null
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) EmeraldAccent else TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Add to playlist button
                    IconButton(
                        onClick = { showAddToPlaylistSheet = true },
                        enabled = track != null
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Add to playlist",
                            tint = TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Download button
                    IconButton(
                        onClick = onToggleDownload,
                        enabled = track != null
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = if (isDownloaded) "Downloaded" else "Download",
                            tint = if (isDownloaded) EmeraldAccent else TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // Seek Slider, Timestamps & A-B Repeat Info
            Column(modifier = Modifier.fillMaxWidth()) {
                ABRepeatSeekBar(
                    currentPositionMs = playbackState.currentPositionMs,
                    durationMs = playbackState.durationMs,
                    abRepeatState = playbackState.abRepeatState,
                    onSeek = onSeek,
                    onUpdateABStart = onUpdateABStart,
                    onUpdateABEnd = onUpdateABEnd,
                    enabled = playbackState.isSeekable && playbackState.durationMs > 0L,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = PlaybackStateUtils.formatDurationMs(playbackState.currentPositionMs),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (playbackState.durationMs > 0L) {
                            PlaybackStateUtils.formatDurationMs(playbackState.durationMs)
                        } else {
                            "--:--"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Inline A-B Repeat Status Chip & Error Feedback
                val abState = playbackState.abRepeatState
                if (abState.isBothMarked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = EmeraldAccent.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Loop: ${PlaybackStateUtils.formatDurationMs(abState.abStartMs ?: 0L)} – ${PlaybackStateUtils.formatDurationMs(abState.abEndMs ?: 0L)}",
                                color = EmeraldAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onClearABRepeat,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Section Loop",
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                } else if (abState.isStartMarked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f)),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Point A at ${PlaybackStateUtils.formatDurationMs(abState.abStartMs ?: 0L)} • Tap A-B to set Point B",
                                color = EmeraldAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = onClearABRepeat,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                abState.errorMessage?.let { errorMsg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { onDismissABRepeatError() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = errorMsg,
                                color = Color(0xFFFCA5A5),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // Controls Row (Shuffle, Previous, Play/Pause, Next, Repeat, A-B Repeat)
            FullPlaybackControls(
                isPlaying = playbackState.isPlaying,
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode,
                abRepeatState = playbackState.abRepeatState,
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleABRepeat = onToggleABRepeat,
                onClearABRepeat = onClearABRepeat,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            playbackState = playbackState,
            onTrackClick = { clickedIndex ->
                onSeekToQueueIndex(clickedIndex)
            },
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showAddToPlaylistSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddToPlaylistSheet = false },
            containerColor = DarkSurface,
            contentColor = TextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Add to Playlist",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (userPlaylists.isEmpty()) {
                    Text(
                        text = "No playlists found",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(userPlaylists) { playlist ->
                            val isAlreadyInPlaylist = containingPlaylists.contains(playlist.playlistId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isAlreadyInPlaylist) {
                                        onAddToPlaylist(playlist.playlistId)
                                        showAddToPlaylistSheet = false
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        color = if (isAlreadyInPlaylist) TextTertiary else TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${playlist.trackCount} tracks",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                if (isAlreadyInPlaylist) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Already Added",
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}