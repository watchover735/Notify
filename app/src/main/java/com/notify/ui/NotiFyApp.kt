package com.notify.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.playback.PlaybackViewModel
import com.notify.ui.navigation.NotiFyDestination
import com.notify.ui.navigation.NotiFyNavHost
import com.notify.ui.player.MiniPlayer
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

@Composable
fun NotiFyApp(
    libraryViewModel: LocalLibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    onOpenSafPicker: () -> Unit = {},
    onRequestPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val playlistRepository = remember(context) {
        PlaylistRepository(NotiFyDatabase.getInstance(context.applicationContext))
    }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isNowPlayingFullscreen = currentRoute == NotiFyDestination.NowPlaying.route

    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val isCurrentTrackLiked by playbackViewModel.isCurrentTrackLiked.collectAsStateWithLifecycle()
    val isCurrentTrackDownloaded by playbackViewModel.isCurrentTrackDownloaded.collectAsStateWithLifecycle()
    val userPlaylists by playbackViewModel.userPlaylists.collectAsStateWithLifecycle()
    val playlistsContainingCurrentTrack by playbackViewModel.playlistsContainingCurrentTrack.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            if (!isNowPlayingFullscreen) {
                Column {
                    // MiniPlayer anchored directly above NavigationBar
                    AnimatedVisibility(
                        visible = playbackState.hasTrack,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        MiniPlayer(
                            playbackState = playbackState,
                            onTogglePlayPause = { playbackViewModel.togglePlayPause() },
                            onNext = { playbackViewModel.skipToNext() },
                            onClick = {
                                navController.navigate(NotiFyDestination.NowPlaying.route) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    // Bottom Navigation Bar with launchSingleTop, saveState, restoreState
                    NavigationBar(
                        containerColor = DarkSurface,
                        contentColor = TextPrimary
                    ) {
                        NotiFyDestination.bottomNavTabs.forEach { destination ->
                            val isSelected = currentRoute == destination.route

                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_tab_${destination.route}"),
                                selected = isSelected,
                                onClick = {
                                    if (currentRoute != destination.route) {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = destination.title
                                    )
                                },
                                label = { Text(text = destination.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = EmeraldAccent,
                                    selectedTextColor = EmeraldAccent,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                    indicatorColor = DarkSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NotiFyNavHost(
                navController = navController,
                libraryState = libraryState,
                playbackState = playbackState,
                playlistRepository = playlistRepository,
                onScanAudio = {
                    if (libraryState.hasMediaPermission) {
                        libraryViewModel.scanMediaStore()
                    } else {
                        onRequestPermission()
                    }
                },
                onImportSaf = onOpenSafPicker,
                onRequestPermission = onRequestPermission,
                onPlayTrack = { track ->
                    playbackViewModel.playQueue(listOf(track), 0)
                },
                onPlayStream = { track, streamUrl, origin ->
                    playbackViewModel.playStream(track, streamUrl, origin)
                },
                onPlayQueue = { tracks, startIndex ->
                    playbackViewModel.playQueue(tracks, startIndex)
                },
                onPlayQueueEntries = { entries, startIndex ->
                    playbackViewModel.playQueueEntries(entries, startIndex)
                },
                onShuffleAll = {
                    val tracks = libraryState.allTracks.map { it.track }.shuffled()
                    if (tracks.isNotEmpty()) {
                        playbackViewModel.playQueue(tracks, 0)
                    }
                },
                onRemoveSafTrack = { uriString ->
                    libraryViewModel.removeSafTrack(uriString)
                },
                onDismissError = {
                    libraryViewModel.clearError()
                    playbackViewModel.clearError()
                },
                onTogglePlayPause = { playbackViewModel.togglePlayPause() },
                onPrevious = { playbackViewModel.skipToPrevious() },
                onNext = { playbackViewModel.skipToNext() },
                onSeek = { positionMs -> playbackViewModel.seekTo(positionMs) },
                onToggleShuffle = { playbackViewModel.toggleShuffle() },
                onCycleRepeat = { playbackViewModel.cycleRepeatMode() },
                onSeekToQueueIndex = { index -> playbackViewModel.seekToQueueIndex(index) },
                isCurrentTrackLiked = isCurrentTrackLiked,
                isCurrentTrackDownloaded = isCurrentTrackDownloaded,
                userPlaylists = userPlaylists,
                playlistsContainingCurrentTrack = playlistsContainingCurrentTrack,
                onToggleLikeCurrentTrack = { playbackViewModel.toggleLikeCurrentTrack() },
                onAddCurrentTrackToPlaylist = { playlistId -> playbackViewModel.addCurrentTrackToPlaylist(playlistId) },
                onToggleDownloadCurrentTrack = { playbackViewModel.toggleDownloadCurrentTrack() },
                onToggleABRepeat = { playbackViewModel.toggleABRepeat() },
                onClearABRepeat = { playbackViewModel.clearABRepeat() },
                onUpdateABStart = { startMs -> playbackViewModel.updateABStart(startMs) },
                onUpdateABEnd = { endMs -> playbackViewModel.updateABEnd(endMs) },
                onDismissABRepeatError = { playbackViewModel.dismissABRepeatError() }
            )
        }
    }
}
