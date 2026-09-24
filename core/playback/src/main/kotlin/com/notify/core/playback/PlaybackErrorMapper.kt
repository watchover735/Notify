package com.notify.core.playback

import androidx.media3.common.PlaybackException
import com.notify.core.model.PlaybackError

/**
 * Maps Media3 [PlaybackException] error codes to strongly typed domain [PlaybackError] entities.
 */
object PlaybackErrorMapper {

    fun fromPlaybackException(
        exception: PlaybackException,
        currentUriString: String? = null
    ): PlaybackError {
        return when (exception.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                PlaybackError.MissingFile(
                    uriString = currentUriString ?: "unknown",
                    message = "Audio file not found or has been moved/deleted: ${exception.message}"
                )
            }
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> {
                PlaybackError.PermissionRevoked(
                    permission = "STORAGE_READ_PERMISSION",
                    message = "Permission revoked to read audio file: ${exception.message}"
                )
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                PlaybackError.Offline(
                    message = "Network error during media playback: ${exception.message}"
                )
            }
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                PlaybackError.DecoderFailure(
                    reason = "Codec failure (error code ${exception.errorCode}): ${exception.message}"
                )
            }
            else -> {
                PlaybackError.Unknown(
                    message = "Playback error (${exception.errorCodeName}): ${exception.message}",
                    cause = exception
                )
            }
        }
    }
}
