package com.notify.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope

/**
 * Interface contract between NotiFyPlaybackService and application-level Queue Coordinator.
 * Ensures NotiFyPlaybackService owns the coordinator lifecycle without core:playback depending on app module.
 */
interface PlaybackQueueDelegate {
    /**
     * Called when NotiFyPlaybackService is created. Attaches player, session, and service scope.
     */
    fun attach(service: NotiFyPlaybackService, player: Player, session: MediaSession, serviceScope: CoroutineScope)

    /**
     * Called when NotiFyPlaybackService is destroyed.
     */
    fun detach()

    /**
     * Handles media item transitions from ExoPlayer.
     */
    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)

    /**
     * Handles playback state changes from ExoPlayer.
     */
    fun onPlaybackStateChanged(playbackState: Int)

    /**
     * Handles isPlaying changes from ExoPlayer.
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {}

    /**
     * Handles player errors from ExoPlayer.
     */
    fun onPlayerError(error: PlaybackException)

    /**
     * Intercepts Next navigation (from notification, Bluetooth, or UI).
     * Returns true if handled by the delegate (e.g. immediate resolve), false to let default Player behavior run.
     */
    fun handleNextAction(): Boolean = false

    /**
     * Intercepts Previous navigation.
     * Returns true if handled by the delegate, false to let default Player behavior run.
     */
    fun handlePreviousAction(): Boolean = false

    /**
     * Handles MediaSession playback resumption requests.
     * Returns a ListenableFuture with MediaItemsWithStartPosition, or null if unhandled.
     */
    fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition>? = null

    /**
     * Updates shuffle mode on the queue coordinator.
     */
    fun setShuffleMode(mode: com.notify.core.model.ShuffleMode) {}

    /**
     * Toggles/cycles shuffle mode on the queue coordinator.
     */
    fun toggleShuffleMode(): Boolean = false

    /**
     * Handles MediaBrowser root request (Android Auto, etc.).
     */
    fun onGetLibraryRoot(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>>? = null

    /**
     * Handles MediaBrowser get children request for browsing content tree.
     */
    fun onGetChildren(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? = null

    /**
     * Handles MediaBrowser get item request.
     */
    fun onGetItem(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>>? = null

    /**
     * Handles MediaBrowser subscribe request.
     */
    fun onSubscribe(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>>? = null

    /**
     * Handles setMediaItems from media browser / external controllers.
     */
    fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition>? = null
}

fun interface PlaybackQueueDelegateFactory {
    fun create(): PlaybackQueueDelegate
}

object PlaybackQueueDelegateRegistry {
    @Volatile
    var factory: PlaybackQueueDelegateFactory? = null
}
