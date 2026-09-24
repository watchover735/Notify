package com.notify.core.local

import com.notify.core.model.PlaybackError

/**
 * Result wrapper for local audio scanning, import, and availability checks.
 */
sealed interface LocalAudioResult<out T> {
    data class Success<T>(val data: T) : LocalAudioResult<T>
    data class Failure(val error: PlaybackError) : LocalAudioResult<Nothing>
}

inline fun <T, R> LocalAudioResult<T>.map(transform: (T) -> R): LocalAudioResult<R> = when (this) {
    is LocalAudioResult.Success -> LocalAudioResult.Success(transform(data))
    is LocalAudioResult.Failure -> this
}
