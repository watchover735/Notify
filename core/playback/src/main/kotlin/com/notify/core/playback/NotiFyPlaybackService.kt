package com.notify.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Background MediaLibraryService hosting the sole ExoPlayer and MediaLibrarySession instance for NotiFy.
 * Supports Android Auto MediaBrowser browsing and playback without creating a second playback service.
 * Manages audio focus, becoming-noisy handling, wake lock, automatic MediaStyle notifications,
 * lock-screen controls, and service-owned queue coordination via PlaybackQueueDelegate.
 */
class NotiFyPlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var delegate: PlaybackQueueDelegate? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            delegate?.onMediaItemTransition(mediaItem, reason)
            SleepTimerManager.onMediaItemTransition(reason)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            delegate?.onPlaybackStateChanged(playbackState)
            SleepTimerManager.onPlaybackStateChanged(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            delegate?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            delegate?.onPlayerError(error)
        }
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootFuture = delegate?.onGetLibraryRoot(session, browser, params)
            if (rootFuture != null) return rootFuture

            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("NotiFy")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val childrenFuture = delegate?.onGetChildren(session, browser, parentId, page, pageSize, params)
            if (childrenFuture != null) return childrenFuture

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val itemFuture = delegate?.onGetItem(session, browser, mediaId)
            if (itemFuture != null) return itemFuture

            return Futures.immediateFuture(LibraryResult.ofError(androidx.media3.session.SessionResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val subFuture = delegate?.onSubscribe(session, browser, parentId, params)
            if (subFuture != null) return subFuture

            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val setFuture = delegate?.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            if (setFuture != null) return setFuture

            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT -> {
                    val handled = delegate?.handleNextAction() ?: false
                    if (handled) return androidx.media3.session.SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS -> {
                    val handled = delegate?.handlePreviousAction() ?: false
                    if (handled) return androidx.media3.session.SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SET_SHUFFLE_MODE -> {
                    val handled = delegate?.toggleShuffleMode() ?: false
                    if (handled) return androidx.media3.session.SessionResult.RESULT_INFO_SKIPPED
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = delegate?.onPlaybackResumption(mediaSession, controller)
            if (future != null) {
                return future
            }
            return super.onPlaybackResumption(mediaSession, controller)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val cachedHttpFactory = Media3StreamCache.getCacheDataSourceFactory(
            context = this,
            networkTransferListener = NetworkMetricsListener
        )
        val routingFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, cachedHttpFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(routingFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Handles audio focus automatically
            .setHandleAudioBecomingNoisy(true) // Pauses playback when headphones are disconnected
            .setWakeMode(C.WAKE_MODE_LOCAL) // Keeps CPU awake during local playback while screen is locked
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player = exoPlayer
        exoPlayer.addListener(playerListener)

        // Optional pending intent linking the notification to the app's launch intent
        val sessionActivityPendingIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val sessionBuilder = MediaLibrarySession.Builder(this, exoPlayer, librarySessionCallback)
        sessionActivityPendingIntent?.let {
            sessionBuilder.setSessionActivity(it)
        }

        val session = sessionBuilder.build()
        mediaLibrarySession = session

        // Attach queue delegate registered by application
        val queueDelegate = PlaybackQueueDelegateRegistry.factory?.create()
        delegate = queueDelegate
        queueDelegate?.attach(this, exoPlayer, session, serviceScope)

        // Attach SleepTimerManager
        SleepTimerManager.attach(this, exoPlayer, serviceScope)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    /**
     * Direct handler for MediaBrowser onGetRoot requests.
     */
    fun onGetRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return librarySessionCallback.onGetLibraryRoot(session, browser, params)
    }

    /**
     * Direct handler for MediaBrowser onLoadChildren requests.
     */
    fun onLoadChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return librarySessionCallback.onGetChildren(session, browser, parentId, page, pageSize, params)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val activePlayer = player
        // Keep service alive if player is playing or queue is actively running
        if (activePlayer == null || (!activePlayer.playWhenReady && activePlayer.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        SleepTimerManager.detach()
        delegate?.detach()
        delegate = null
        serviceScope.cancel()

        player?.removeListener(playerListener)
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        player = null
        super.onDestroy()
    }
}
