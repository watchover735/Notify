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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.notify.ui.components.NotiFySnackbar
import com.notify.ui.navigation.NotiFyDestination
import com.notify.ui.navigation.NotiFyNavHost
import com.notify.ui.player.MiniPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import com.notify.auth.AuthGateViewModel
import com.notify.ui.components.ChangePasswordDialog
import com.notify.ui.components.ProfileDrawerSheet
import com.notify.ui.components.UpdateKeyDialog
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun NotiFyApp(
    libraryViewModel: LocalLibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    authViewModel: AuthGateViewModel? = null,
    onOpenSafPicker: () -> Unit = {},
    onRequestPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    var showUpdateKeyDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
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

    // ── Snackbar state ────────────────────────────────────────────────────────
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var snackbarAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // Collect app-wide snackbar events
    LaunchedEffect(Unit) {
        SnackbarManager.events.collect { event ->
            when (event) {
                is SnackbarEvent.AddedToLikedSongs -> {
                    snackbarMessage = "Added to Liked Songs"
                    snackbarAction = null
                }
                is SnackbarEvent.RemovedFromLikedSongs -> {
                    snackbarMessage = "Removed from Liked Songs"
                    snackbarAction = null
                }
                is SnackbarEvent.DownloadStarted -> {
                    snackbarMessage = "Download started"
                    snackbarAction = null
                }
                is SnackbarEvent.DownloadComplete -> {
                    snackbarMessage = "Downloaded ${event.songName}"
                    snackbarAction = null
                }
                is SnackbarEvent.DownloadFailed -> {
                    snackbarMessage = "Download failed"
                    snackbarAction = event.onRetry?.let { "Retry" to it }
                }
                is SnackbarEvent.AddedToPlaylist -> {
                    snackbarMessage = "Added to ${event.playlistName}"
                    snackbarAction = event.onUndo?.let { "Undo" to it }
                }
                is SnackbarEvent.AlreadyInPlaylist -> {
                    snackbarMessage = "Already in ${event.playlistName}"
                    snackbarAction = null
                }
                is SnackbarEvent.RemovedFromPlaylist -> {
                    snackbarMessage = "Removed from ${event.playlistName}"
                    snackbarAction = event.onUndo?.let { "Undo" to it }
                }
                is SnackbarEvent.PlaylistCreated -> {
                    snackbarMessage = "Playlist '${event.playlistName}' created"
                    snackbarAction = null
                }
                is SnackbarEvent.PlayFailed -> {
                    snackbarMessage = "Couldn't play this song"
                    snackbarAction = null
                }
                is SnackbarEvent.NoInternet -> {
                    snackbarMessage = "No internet connection"
                    snackbarAction = null
                }
                is SnackbarEvent.QueueExhausted -> {
                    snackbarMessage = "No more songs to play"
                    snackbarAction = null
                }
                is SnackbarEvent.ImportSucceeded -> {
                    snackbarMessage = "Imported '${event.playlistName}' · ${event.trackCount} tracks"
                    snackbarAction = null
                }
                is SnackbarEvent.ImportFailed -> {
                    snackbarMessage = "Import failed: ${event.reason}"
                    snackbarAction = null
                }
                is SnackbarEvent.ArtistFollowed -> {
                    snackbarMessage = "Added ${event.artistName} to your artists"
                    snackbarAction = null
                }
                is SnackbarEvent.ArtistUnfollowed -> {
                    snackbarMessage = "Removed ${event.artistName}"
                    snackbarAction = event.onUndo?.let { "Undo" to it }
                }
                is SnackbarEvent.ActionFailed -> {
                    snackbarMessage = event.message
                    snackbarAction = null
                }
                is SnackbarEvent.Message -> {
                    snackbarMessage = event.message
                    snackbarAction = if (event.actionLabel != null && event.onAction != null) {
                        event.actionLabel to event.onAction
                    } else null
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            val nickname = authViewModel?.getCachedNickname() ?: ""
            val email = authViewModel?.getStoredEmail() ?: ""
            val keyStatus = authViewModel?.getKeyStatusDescription() ?: "Key: Active"
            val isGoogleUser = authViewModel?.isGoogleUser() ?: false
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
            }

            ProfileDrawerSheet(
                nickname = nickname,
                email = email,
                keyStatusText = keyStatus,
                isGoogleUser = isGoogleUser,
                versionName = versionName,
                onUpdateKeyClick = {
                    coroutineScope.launch { drawerState.close() }
                    showUpdateKeyDialog = true
                },
                onChangePasswordClick = {
                    coroutineScope.launch { drawerState.close() }
                    showChangePasswordDialog = true
                },
                onLogoutClick = {
                    coroutineScope.launch { drawerState.close() }
                    showLogoutConfirmDialog = true
                }
            )
        }
    ) {
        Scaffold(
            containerColor = DarkBackground,
        snackbarHost = {
            snackbarMessage?.let { msg ->
                val (label, action) = snackbarAction ?: (null to null)
                NotiFySnackbar(
                    message = msg,
                    actionLabel = label,
                    onAction = action,
                    onDismiss = {
                        snackbarMessage = null
                        snackbarAction = null
                    }
                )
            }
        },
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
                onPlayStreamWithContext = { track, streamUrl, origin, contextTracks ->
                    playbackViewModel.playStream(track, streamUrl, origin, contextTracks = contextTracks)
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
                onDismissABRepeatError = { playbackViewModel.dismissABRepeatError() },
                onOpenProfileDrawer = {
                    coroutineScope.launch { drawerState.open() }
                }
            )
        }
    }
    }

    if (showUpdateKeyDialog && authViewModel != null) {
        UpdateKeyDialog(
            onDismiss = { showUpdateKeyDialog = false },
            onRedeem = { code -> authViewModel.updateKeyFromDrawer(code) }
        )
    }

    if (showChangePasswordDialog && authViewModel != null) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSubmit = { cur, new, conf -> authViewModel.changePassword(cur, new, conf) },
            onSuccess = {
                showChangePasswordDialog = false
                snackbarMessage = "Password badal gaya"
            }
        )
    }
}
