package com.notify.download.stream

import android.content.Context
import android.util.Log
import com.notify.core.model.ResolvedStream
import com.notify.download.spike.YtDlpRuntime
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException

/**
 * Interface contract for resolving audio stream URLs from YouTube canonical URLs.
 */
interface AudioStreamResolver {
    suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream>

    suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String? = null,
        artist: String? = null
    ): Result<ResolvedStream> = resolveStream(canonicalYoutubeUrl)

    suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String? = null,
        artist: String? = null,
        isPrefetch: Boolean = false
    ): Result<ResolvedStream> = resolveStream(canonicalYoutubeUrl, title, artist)
}

/**
 * Fake stream resolver for testing and verifying remote MediaItem playback through MediaController
 * without calling yt-dlp or requiring an internet YouTube extraction.
 */
class FakeAudioStreamResolver(
    private val testStreamUrl: String = "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
) : AudioStreamResolver {

    var invocationCount = 0
        private set

    var lastResolvedUrl: String? = null
        private set

    override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
        invocationCount++
        lastResolvedUrl = canonicalYoutubeUrl
        if (canonicalYoutubeUrl.contains("spotify.com") || canonicalYoutubeUrl.contains("spotify.link")) {
            return Result.failure(
                IllegalArgumentException("Spotify URLs must never be passed directly to yt-dlp stream resolver: $canonicalYoutubeUrl")
            )
        }
        return Result.success(
            ResolvedStream(
                streamUrl = testStreamUrl,
                headers = emptyMap(),
                expiresAtEpochMs = System.currentTimeMillis() + 3600_000L
            )
        )
    }
}

/**
 * Resolves temporary in-memory audio stream URLs for online playback via yt-dlp.
 * INVARIANT: Signed stream URLs are NEVER persisted to Room, TrackEntity, or SharedPreferences.
 * INVARIANT: Spotify URLs are NEVER passed to YoutubeDLRequest.
 */
class OnlineStreamResolver(
    private val context: Context,
    private val ytDlpExecutor: (YoutubeDLRequest) -> YoutubeDLResponse = { req ->
        YoutubeDL.getInstance().execute(req)
    }
) : AudioStreamResolver {

    companion object {
        private const val TAG = "OnlineStreamResolver"
        private val ytDlpDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val activePrefetchJob = AtomicReference<Job?>(null)

        // Prioritized format spec: pure m4a audio preferred, any pure audio second, best muxed as safety net
        const val FORMAT_SPEC = "bestaudio[ext=m4a]/bestaudio/best"

        // Alternate client configurations to avoid PO Token requirements and SABR streaming
        val CLIENT_PROFILES = listOf(
            "youtube:player_client=tv_embedded,visionos",
            "youtube:player_client=android",
            "youtube:player_client=ios,tv,mweb",
            "youtube:player_client=all"
        )

        // Dedicated execution timeout starting only AFTER acquiring the single-slot dispatcher.
        // Queue wait time is excluded so stacked or prefetch tracks do not expire prematurely.
        const val YTDLP_EXECUTION_TIMEOUT_MS = 20_000L

        /**
         * Cancels any active background prefetch resolution on ytDlpDispatcher so foreground
         * user taps can obtain the execution slot immediately without queuing delay.
         */
        fun cancelActivePrefetch() {
            activePrefetchJob.getAndSet(null)?.let {
                try {
                    it.cancel()
                    Log.i(TAG, "PREFETCH_PREEMPTED active background prefetch cancelled for user tap")
                } catch (_: Exception) {}
            }
        }

        /**
         * Checks if an error message represents a permanent, non-retryable video failure
         * (e.g. geo-blocking, legal complaint, private/deleted video).
         */
        fun isPermanentVideoError(errorMessage: String?): Boolean {
            if (errorMessage.isNullOrBlank()) return false
            val lower = errorMessage.lowercase()
            return (lower.contains("not available") && (lower.contains("country") || lower.contains("domain"))) ||
                    lower.contains("legal complaint") ||
                    lower.contains("this video is unavailable") ||
                    lower.contains("video unavailable") ||
                    lower.contains("blocked in your country") ||
                    lower.contains("private video") ||
                    lower.contains("this video has been removed") ||
                    lower.contains("has been deleted") ||
                    lower.contains("copyright claim")
        }
    }

    override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> =
        resolveStream(canonicalYoutubeUrl, null, null, false)

    override suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?
    ): Result<ResolvedStream> = resolveStream(canonicalYoutubeUrl, title, artist, false)

    override suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?,
        isPrefetch: Boolean
    ): Result<ResolvedStream> {
        if (!isPrefetch) {
            cancelActivePrefetch()
        }
        val callingJob = coroutineContext[Job]
        if (isPrefetch && callingJob != null) {
            activePrefetchJob.set(callingJob)
        }

        return try {
            withContext(ytDlpDispatcher) {
                // Enforce safety invariant: Spotify URLs must never be passed to yt-dlp
                if (canonicalYoutubeUrl.contains("spotify.com") || canonicalYoutubeUrl.contains("spotify.link")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Spotify URLs must never be passed directly to yt-dlp stream resolver: $canonicalYoutubeUrl")
                    )
                }

                // Dedicated execution timeout: starts counting ONLY when ytDlpDispatcher slot is acquired
                withTimeout(YTDLP_EXECUTION_TIMEOUT_MS) {
                    val initResult = YtDlpRuntime.ensureReady(context)
                    if (initResult.isFailure) {
                        val err = initResult.exceptionOrNull()?.message ?: "YtDlpRuntime init failed"
                        Log.e(TAG, "Cannot resolve stream: yt-dlp not ready: $err")
                        return@withTimeout Result.failure(IllegalStateException(err))
                    }
                    Log.d(TAG, "Resolving direct audio stream for: $canonicalYoutubeUrl")

                var lastException: Throwable? = null
                var streamUrl: String? = null
                var successfulClient: String? = null
                var elapsedTimeMs: Long = 0L

                for ((index, clientArg) in CLIENT_PROFILES.withIndex()) {
                    val attemptStart = System.currentTimeMillis()
                    Log.i(TAG, "CLIENT_ATTEMPT_START client=$clientArg attempt=${index + 1}/${CLIENT_PROFILES.size}")
                    try {
                        val request = YoutubeDLRequest(canonicalYoutubeUrl).apply {
                            addOption("-g") // Print direct stream URL
                            addOption("-f", FORMAT_SPEC)
                            addOption("--no-playlist")
                            addOption("--no-check-certificates")
                            addOption("--extractor-args", clientArg)
                        }

                        val response = ytDlpExecutor(request)
                        val elapsed = System.currentTimeMillis() - attemptStart
                        val extractedUrl = response.out?.lines()?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.trim()

                        if (!extractedUrl.isNullOrBlank()) {
                            Log.i(TAG, "CLIENT_ATTEMPT_COMPLETE client=$clientArg outcome=SUCCESS elapsedMs=$elapsed")
                            streamUrl = extractedUrl
                            successfulClient = clientArg
                            elapsedTimeMs = response.elapsedTime
                            break
                        } else {
                            Log.w(TAG, "CLIENT_ATTEMPT_COMPLETE client=$clientArg outcome=FAILED elapsedMs=$elapsed reason=no_stream_url; trying fallback")
                        }
                    } catch (e: YoutubeDLException) {
                        val elapsed = System.currentTimeMillis() - attemptStart
                        lastException = e
                        Log.w(TAG, "CLIENT_ATTEMPT_COMPLETE client=$clientArg outcome=FAILED elapsedMs=$elapsed error=${e.message}; trying fallback")
                        if (isPermanentVideoError(e.message)) {
                            Log.w(TAG, "PERMANENT_VIDEO_ERROR detected: \"${e.message}\". Aborting remaining client attempts immediately.")
                            break
                        }
                    } catch (e: Exception) {
                        val elapsed = System.currentTimeMillis() - attemptStart
                        lastException = e
                        Log.w(TAG, "CLIENT_ATTEMPT_COMPLETE client=$clientArg outcome=FAILED elapsedMs=$elapsed unexpected=${e.message}; trying fallback")
                        if (isPermanentVideoError(e.message)) {
                            Log.w(TAG, "PERMANENT_VIDEO_ERROR detected: \"${e.message}\". Aborting remaining client attempts immediately.")
                            break
                        }
                    }
                }

                val isPermanent = isPermanentVideoError(lastException?.message)

                // Fallback recovery: if all client profiles failed, try updating yt-dlp binary if possible (skip if permanent error)
                if (streamUrl.isNullOrBlank() && !isPermanent) {
                    Log.i(TAG, "All client profiles failed for $canonicalYoutubeUrl. Attempting yt-dlp update recovery...")
                    val updateResult = YtDlpRuntime.updateYtDlp(context, force = true)
                    if (updateResult.isSuccess) {
                        try {
                            val recoveryProfile = CLIENT_PROFILES.first()
                            val request = YoutubeDLRequest(canonicalYoutubeUrl).apply {
                                addOption("-g")
                                addOption("-f", FORMAT_SPEC)
                                addOption("--no-playlist")
                                addOption("--no-check-certificates")
                                addOption("--extractor-args", recoveryProfile)
                            }
                            val response = ytDlpExecutor(request)
                            val extractedUrl = response.out?.lines()?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.trim()
                            if (!extractedUrl.isNullOrBlank()) {
                                streamUrl = extractedUrl
                                successfulClient = "$recoveryProfile (post-update)"
                                elapsedTimeMs = response.elapsedTime
                            }
                        } catch (e: Exception) {
                            lastException = e
                        }
                    }
                } else if (isPermanent) {
                    Log.i(TAG, "Permanent video error encountered. Skipping yt-dlp update recovery.")
                }

                if (streamUrl.isNullOrBlank()) {
                    val rawErr = lastException?.message.orEmpty()
                    val errorMsg = if (isPermanent) {
                        "Video is unavailable or unplayable in your region ($rawErr)"
                    } else {
                        rawErr.ifBlank { "yt-dlp returned no stream URL after trying ${CLIENT_PROFILES.size} client profiles" }
                    }
                    Log.e(TAG, "yt-dlp stream resolution failed: $errorMsg", lastException)
                    return@withTimeout Result.failure(
                        IOException("yt-dlp returned no stream URL for $canonicalYoutubeUrl: $errorMsg", lastException)
                    )
                }

                Log.d(TAG, "Direct stream resolved successfully via $successfulClient (${elapsedTimeMs}ms)")

                val expireSec = try {
                    android.net.Uri.parse(streamUrl).getQueryParameter("expire")?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }

                val expiresAtEpochMs = if (expireSec != null) {
                expireSec * 1000L
            } else {
                System.currentTimeMillis() + StreamUrlCache.DEFAULT_TTL_MS
            }

            val videoId = extractVideoId(canonicalYoutubeUrl)

            // Construct in-memory ephemeral ResolvedStream
            val resolvedStream = ResolvedStream(
                streamUrl = streamUrl,
                headers = emptyMap(),
                formatId = "m4a/audio",
                mimeType = "audio/mp4",
                container = "m4a",
                videoId = videoId,
                expiresAtEpochMs = expiresAtEpochMs
            )

            Result.success(resolvedStream)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val timeoutMsg = "yt-dlp execution timed out after ${YTDLP_EXECUTION_TIMEOUT_MS}ms of active execution (queue-wait excluded)"
            Log.w(TAG, timeoutMsg)
            Result.failure(TimeoutException(timeoutMsg))
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "yt-dlp stream resolution failed: ${e.message}", e)
            Result.failure(IOException("Failed to resolve audio stream: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Stream resolution error", e)
            Result.failure(e)
        }
    }

    private fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        return try {
            val uri = android.net.Uri.parse(trimmed)
            uri.getQueryParameter("v")
                ?: if (uri.host?.contains("youtu.be") == true) uri.pathSegments?.firstOrNull() else null
        } catch (_: Exception) {
            null
        }
    }
}
