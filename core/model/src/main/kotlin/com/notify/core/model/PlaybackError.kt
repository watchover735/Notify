package com.notify.core.model

/**
 * Strongly typed error hierarchy for player, extractor, storage, and networking operations.
 * Enforces structured failure handling across the application.
 */
sealed class PlaybackError(
    open val message: String,
    open val isRecoverable: Boolean = true
) {
    /** MediaStore or SAF permission was revoked or denied by the system/user. */
    data class PermissionRevoked(
        val permission: String,
        override val message: String = "Storage permission revoked for audio access."
    ) : PlaybackError(message, isRecoverable = true)

    /** Local file was moved, renamed, deleted, or grant expired. */
    data class MissingFile(
        val uriString: String,
        override val message: String = "Local audio file cannot be found at: $uriString"
    ) : PlaybackError(message, isRecoverable = true)

    /** Device is offline / in airplane mode. */
    data class Offline(
        override val message: String = "Network connection is unavailable."
    ) : PlaybackError(message, isRecoverable = true)

    /** Remote service returned HTTP 429 Too Many Requests. */
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        override val message: String = "Rate limited by upstream server. Please try again later."
    ) : PlaybackError(message, isRecoverable = true)

    /** Stream resolution failed (extractor failure, cipher failure, format unavailable). */
    data class ResolveFailed(
        val trackId: TrackId,
        val reason: String,
        override val message: String = "Unable to resolve playable stream for track ${trackId}: $reason"
    ) : PlaybackError(message, isRecoverable = false)

    /** Stream URL expired (HTTP 403 or signature timeout). Should trigger single re-resolution. */
    data class ExpiredStream(
        val trackId: TrackId,
        override val message: String = "Streaming authorization expired for track $trackId."
    ) : PlaybackError(message, isRecoverable = true)

    /** Corrupted or inaccessible disk cache range. Safe to bypass. */
    data class CacheFailure(
        val reason: String,
        override val message: String = "Disk cache error: $reason"
    ) : PlaybackError(message, isRecoverable = true)

    /** Media3 / ExoPlayer hardware or software codec failure. */
    data class DecoderFailure(
        val reason: String,
        override val message: String = "Media decoder failure: $reason"
    ) : PlaybackError(message, isRecoverable = false)

    /** Generic unexpected error wrapper. */
    data class Unknown(
        override val message: String,
        val cause: Throwable? = null
    ) : PlaybackError(message, isRecoverable = false)
}
