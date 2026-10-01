package com.notify.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide snackbar event types.
 * Only one SnackbarManager instance should exist per app session.
 *
 * Rule: Only non-trivial user-impacting events should emit here.
 * DO NOT emit for: like/unlike, play/pause, shuffle toggle, seek.
 */
sealed class SnackbarEvent {

    /** Liked songs events. */
    data object AddedToLikedSongs : SnackbarEvent()
    data object RemovedFromLikedSongs : SnackbarEvent()

    /** Download lifecycle events. */
    data class DownloadStarted(val songName: String) : SnackbarEvent()
    data class DownloadComplete(val songName: String) : SnackbarEvent()
    data class DownloadFailed(val songName: String, val onRetry: (() -> Unit)? = null) : SnackbarEvent()

    /** Playlist membership events. */
    data class AddedToPlaylist(val playlistName: String, val onUndo: (() -> Unit)? = null) : SnackbarEvent()
    data class AlreadyInPlaylist(val playlistName: String) : SnackbarEvent()
    data class RemovedFromPlaylist(val playlistName: String, val onUndo: (() -> Unit)? = null) : SnackbarEvent()
    data class PlaylistCreated(val playlistName: String) : SnackbarEvent()

    /** Stream resolution completely failed across all providers + yt-dlp. */
    data object PlayFailed : SnackbarEvent()

    /** Network unavailable at play-attempt time. */
    data object NoInternet : SnackbarEvent()

    /** Radio/autoplay queue exhausted — no more tracks to auto-play. */
    data object QueueExhausted : SnackbarEvent()

    /** Spotify import succeeded. */
    data class ImportSucceeded(val playlistName: String, val trackCount: Int) : SnackbarEvent()

    /** Spotify import failed (includes retry intent). */
    data class ImportFailed(val reason: String) : SnackbarEvent()

    /** Followed artists events. */
    data class ArtistFollowed(val artistName: String) : SnackbarEvent()
    data class ArtistUnfollowed(val artistName: String, val onUndo: (() -> Unit)? = null) : SnackbarEvent()

    /** Explicit failure or action message. */
    data class ActionFailed(val message: String) : SnackbarEvent()
    data class Message(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null) : SnackbarEvent()
}

/**
 * Singleton channel for broadcasting [SnackbarEvent] events to the UI layer.
 *
 * Usage:
 * - Emit from any layer: `SnackbarManager.emit(SnackbarEvent.NoInternet)`
 * - Collect in UI:       `SnackbarManager.events.collect { … }`
 *
 * The replay=0 + extraBufferCapacity=4 policy ensures events are not silently lost
 * even if the collector is momentarily suspended, but old stale events are not re-shown.
 */
object SnackbarManager {

    private val _events = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )

    val events: SharedFlow<SnackbarEvent> = _events.asSharedFlow()

    /** Emit a snackbar event from any coroutine or suspend context. */
    suspend fun emit(event: SnackbarEvent) {
        _events.emit(event)
    }

    /** Emit a simple text message. */
    suspend fun emit(message: String) {
        _events.emit(SnackbarEvent.Message(message))
    }

    /** Emit from a non-suspending context (fire-and-forget, drops if buffer full). */
    fun tryEmit(event: SnackbarEvent) {
        _events.tryEmit(event)
    }

    /** Emit a simple text message from a non-suspending context. */
    fun tryEmit(message: String) {
        _events.tryEmit(SnackbarEvent.Message(message))
    }
}
