package com.notify.ui.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.Track
import com.notify.download.matcher.InnerTubeYouTubeMusicSearchProvider
import com.notify.download.matcher.YtDlpYouTubeSearchProvider
import com.notify.download.stream.OnlineStreamResolver
import com.notify.playback.PlaybackUiState
import com.notify.ui.LocalLibraryUiState
import com.notify.ui.home.HomeScreen
import com.notify.ui.library.LibraryScreen
import com.notify.ui.player.NowPlayingScreen
import com.notify.ui.search.OnlineSearchViewModel
import com.notify.ui.search.SearchScreen

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.ui.library.PlaylistDetailScreen
import com.notify.ui.library.PlaylistDetailViewModel
import com.notify.ui.library.PlaylistLibraryViewModel

@Composable
fun NotiFyNavHost(
    navController: NavHostController,
    libraryState: LocalLibraryUiState,
    playbackState: PlaybackUiState,
    playlistRepository: PlaylistRepository,
    onScanAudio: () -> Unit = {},
    onImportSaf: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onPlayTrack: (Track) -> Unit,
    onPlayStream: (Track, String, PlaybackOrigin) -> Unit = { _, _, _ -> },
    onPlayQueue: (List<Track>, Int) -> Unit,
    onPlayQueueEntries: ((List<com.notify.core.model.QueueEntry>, Int) -> Unit)? = null,
    onShuffleAll: () -> Unit,
    onRemoveSafTrack: (String) -> Unit = {},
    onDismissError: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    isCurrentTrackLiked: Boolean = false,
    isCurrentTrackDownloaded: Boolean = false,
    userPlaylists: List<com.notify.download.db.PlaylistSummary> = emptyList(),
    playlistsContainingCurrentTrack: Set<String> = emptySet(),
    onToggleLikeCurrentTrack: () -> Unit = {},
    onAddCurrentTrackToPlaylist: (String) -> Unit = {},
    onToggleDownloadCurrentTrack: () -> Unit = {},
    onToggleABRepeat: () -> Unit = {},
    onClearABRepeat: () -> Unit = {},
    onUpdateABStart: (Long) -> Unit = {},
    onUpdateABEnd: (Long) -> Unit = {},
    onDismissABRepeatError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NotiFyDestination.Home.route,
        modifier = modifier
    ) {
        composable(NotiFyDestination.Home.route) {
            HomeScreen(
                libraryState = libraryState,
                playbackState = playbackState,
                onNavigateToLibrary = {
                    navController.navigate(NotiFyDestination.Library.route) {
                        popUpTo(NotiFyDestination.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onPlayTrack = onPlayTrack,
                onPlayStream = onPlayStream,
                onShuffleAll = onShuffleAll
            )
        }

        composable(NotiFyDestination.Search.route) {
            val context = LocalContext.current
            val application = context.applicationContext as Application
            val factory = remember(application, playlistRepository) {
                val db = NotiFyDatabase.getInstance(application)
                OnlineSearchViewModel.Factory(
                    application = application,
                    innerTubeProvider = InnerTubeYouTubeMusicSearchProvider(),
                    fallbackProvider = YtDlpYouTubeSearchProvider(application),
                    streamResolver = com.notify.download.stream.ResolvedStreamProviderChain(application),
                    searchHistoryDao = db.searchHistoryDao(),
                    playlistRepository = playlistRepository
                )
            }
            val searchViewModel: OnlineSearchViewModel = viewModel(factory = factory)

            SearchScreen(
                allTracks = libraryState.allTracks,
                playbackState = playbackState,
                onTrackClick = onPlayQueue,
                onPlayStream = onPlayStream,
                onlineSearchViewModel = searchViewModel
            )
        }

        composable(NotiFyDestination.Library.route) {
            val context = LocalContext.current
            val application = context.applicationContext as Application
            val libraryFactory = remember(application, playlistRepository) {
                PlaylistLibraryViewModel.Factory(application, playlistRepository)
            }
            val libraryViewModel: PlaylistLibraryViewModel = viewModel(factory = libraryFactory)
            val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

            LibraryScreen(
                uiState = uiState,
                playbackState = playbackState,
                onPlaylistClick = { playlistId ->
                    navController.navigate(NotiFyDestination.PlaylistDetail.createRoute(playlistId))
                },
                onCreatePlaylist = { title -> libraryViewModel.createPlaylist(title) },
                onRenamePlaylist = { id, title -> libraryViewModel.renamePlaylist(id, title) },
                onDeletePlaylist = { id -> libraryViewModel.deletePlaylist(id) },
                onImportSpotify = { url, callback -> libraryViewModel.importSpotifyPlaylist(url, callback) },
                onDismissError = { libraryViewModel.clearImportError() },
                onDismissMessage = { libraryViewModel.clearActionMessage() },
                onPlayTrack = onPlayTrack,
                onTabSelected = libraryViewModel::setLibraryTab,
                onDeleteDownload = libraryViewModel::deleteDownload,
                onClearAllDownloads = libraryViewModel::clearAllDownloads,
                onPinDownload = libraryViewModel::pinDownload,
                onUnpinDownload = libraryViewModel::unpinDownload
            )
        }

        composable(
            route = NotiFyDestination.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
            val context = LocalContext.current
            val application = context.applicationContext as Application
            val detailFactory = remember(application, playlistId, playlistRepository) {
                PlaylistDetailViewModel.Factory(
                    application = application,
                    playlistId = playlistId,
                    repository = playlistRepository
                )
            }
            val detailViewModel: PlaylistDetailViewModel = viewModel(factory = detailFactory)
            val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

            PlaylistDetailScreen(
                uiState = detailUiState,
                playbackState = playbackState,
                onBack = { navController.popBackStack() },
                onTrackClick = { entry ->
                    if (onPlayQueueEntries != null) {
                        detailViewModel.playPlaylistFromEntry(
                            tappedEntry = entry,
                            onPlayQueueEntries = onPlayQueueEntries
                        )
                    } else {
                        detailViewModel.playSingleTrack(
                            entry = entry,
                            onPlayStream = onPlayStream,
                            onPlayTrack = onPlayTrack
                        )
                    }
                },
                onRenamePlaylist = { newTitle -> detailViewModel.renamePlaylist(newTitle) },
                onDeletePlaylist = {
                    detailViewModel.deletePlaylist(
                        onDeleted = {
                            navController.popBackStack()
                        }
                    )
                },
                onDismissError = { detailViewModel.clearError() },
                onRetryArtwork = { detailViewModel.retryMissingArtwork() },
                onDownloadPlaylist = { detailViewModel.downloadPlaylist() },
                onDownloadTrack = { entry -> detailViewModel.downloadTrack(entry) },
                onRemoveDownload = { entry -> detailViewModel.removeDownload(entry) },
                onPauseQueue = { detailViewModel.pauseQueue() },
                onResumeQueue = { detailViewModel.resumeQueue() },
                onCancelQueue = { detailViewModel.cancelQueue() },
                onRetryFailed = { detailViewModel.retryFailed() },
                onRetryTrackDownload = { entry -> detailViewModel.retrySingleTrack(entry) },
                onRemoveTrack = { entry, onUndo -> detailViewModel.removeTrackFromPlaylist(entry, onUndo) },
                onUndoRemoveTrack = { detailViewModel.undoRemoveTrack() }
            )
        }

        composable(NotiFyDestination.NowPlaying.route) {
            NowPlayingScreen(
                playbackState = playbackState,
                onCollapse = {
                    navController.popBackStack()
                },
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onDismissError = onDismissError,
                onSeekToQueueIndex = onSeekToQueueIndex,
                isLiked = isCurrentTrackLiked,
                isDownloaded = isCurrentTrackDownloaded,
                userPlaylists = userPlaylists,
                containingPlaylists = playlistsContainingCurrentTrack,
                onToggleLike = onToggleLikeCurrentTrack,
                onAddToPlaylist = onAddCurrentTrackToPlaylist,
                onToggleDownload = onToggleDownloadCurrentTrack,
                onToggleABRepeat = onToggleABRepeat,
                onClearABRepeat = onClearABRepeat,
                onUpdateABStart = onUpdateABStart,
                onUpdateABEnd = onUpdateABEnd,
                onDismissABRepeatError = onDismissABRepeatError
            )
        }
    }
}
