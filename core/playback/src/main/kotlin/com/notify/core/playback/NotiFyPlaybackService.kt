package com.notify.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Background MediaSessionService hosting the sole ExoPlayer and MediaSession instance for NotiFy.
 * Manages audio focus, becoming-noisy handling, wake lock, automatic MediaStyle notifications,
 * and service-owned queue coordination via PlaybackQueueDelegate.
 */
class NotiFyPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var delegate: PlaybackQueueDelegate? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            delegate?.onMediaItemTransition(mediaItem, reason)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            delegate?.onPlaybackStateChanged(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            delegate?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            delegate?.onPlayerError(error)
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

        val sessionBuilder = MediaSession.Builder(this, exoPlayer)
        sessionActivityPendingIntent?.let {
            sessionBuilder.setSessionActivity(it)
        }

        val sessionCallback = object : MediaSession.Callback {
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
        sessionBuilder.setCallback(sessionCallback)

        val session = sessionBuilder.build()
        mediaSession = session

        // Attach queue delegate registered by application
        val queueDelegate = PlaybackQueueDelegateRegistry.factory?.create()
        delegate = queueDelegate
        queueDelegate?.attach(this, exoPlayer, session, serviceScope)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val activePlayer = player
        // Keep service alive if player is playing or queue is actively running
        if (activePlayer == null || (!activePlayer.playWhenReady && activePlayer.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        delegate?.detach()
        delegate = null
        serviceScope.cancel()

        player?.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
