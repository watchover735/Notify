package com.notify.download.stream

import android.content.Context
import android.util.Log
import com.notify.core.model.ResolvedStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
 * Resolves temporary in-memory audio stream URLs via a remote yt-dlp microservice.
 * INVARIANT: Heavy yt-dlp / Python / ffmpeg execution is offloaded to the remote server to eliminate phone heating.
 * INVARIANT: Signed stream URLs are NEVER persisted to Room, TrackEntity, or SharedPreferences.
 * INVARIANT: Spotify URLs are NEVER passed to stream resolver.
 */
class OnlineStreamResolver @JvmOverloads constructor(
    private val context: Context? = null,
    private val endpointUrl: String = SupabaseConfig.RESOLVE_YTDLP_URL,
    private val httpClient: OkHttpClient = defaultHttpClient
) : AudioStreamResolver {

    companion object {
        private const val TAG = "OnlineStreamResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val activePrefetchJob = AtomicReference<Job?>(null)

        // Prioritized format spec (retained for backward compatibility and server parity)
        const val FORMAT_SPEC = "bestaudio[ext=m4a]/bestaudio/best"

        // Alternate client configurations
        val CLIENT_PROFILES = listOf(
            "youtube:player_client=tv_embedded,visionos",
            "youtube:player_client=android",
            "youtube:player_client=ios,tv,mweb",
            "youtube:player_client=all"
        )

        // Dedicated execution timeout starting only AFTER acquiring the dispatch slot.
        const val YTDLP_EXECUTION_TIMEOUT_MS = 20_000L

        /**
         * Cancels any active background prefetch resolution so foreground
         * user taps can obtain execution slots immediately.
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
            withContext(Dispatchers.IO) {
                // Enforce safety invariant: Spotify URLs must never be passed to yt-dlp
                if (canonicalYoutubeUrl.contains("spotify.com") || canonicalYoutubeUrl.contains("spotify.link")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Spotify URLs must never be passed directly to yt-dlp stream resolver: $canonicalYoutubeUrl")
                    )
                }

                withTimeout(YTDLP_EXECUTION_TIMEOUT_MS) {
                    val videoId = extractVideoId(canonicalYoutubeUrl) ?: ""
                    Log.d(TAG, "Resolving remote audio stream for: $canonicalYoutubeUrl (id=$videoId)")

                    val payload = JSONObject().apply {
                        put("videoId", videoId)
                        put("canonicalUrl", canonicalYoutubeUrl)
                    }

                    val request = Request.Builder()
                        .url(endpointUrl)
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val startTime = System.currentTimeMillis()
                    val response = try {
                        httpClient.newCall(request).execute()
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote yt-dlp backend connection failed: ${e.message}")
                        return@withTimeout Result.failure(
                            IOException("Remote yt-dlp backend unavailable: ${e.message}", e)
                        )
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful || responseBody.isBlank()) {
                        Log.w(TAG, "Remote yt-dlp backend HTTP ${response.code}: $responseBody")
                        return@withTimeout Result.failure(
                            IOException("Remote yt-dlp returned HTTP ${response.code}: $responseBody")
                        )
                    }

                    val json = try {
                        JSONObject(responseBody)
                    } catch (e: Exception) {
                        return@withTimeout Result.failure(
                            IOException("Invalid JSON response from remote yt-dlp: $responseBody", e)
                        )
                    }

                    val success = json.optBoolean("success", false)
                    if (!success) {
                        val rawErr = json.optString("error", "Unknown extraction error")
                        val isPermanent = isPermanentVideoError(rawErr)
                        val errorMsg = if (isPermanent) {
                            "Video is unavailable or unplayable in your region ($rawErr)"
                        } else {
                            rawErr.ifBlank { "Remote yt-dlp returned no stream URL" }
                        }
                        Log.e(TAG, "Remote yt-dlp stream resolution failed: $errorMsg")
                        return@withTimeout Result.failure(
                            IOException("yt-dlp stream resolution failed: $errorMsg")
                        )
                    }

                    val streamUrl = json.optString("streamUrl", "").trim()
                    if (streamUrl.isBlank()) {
                        return@withTimeout Result.failure(
                            IOException("Remote yt-dlp returned empty streamUrl")
                        )
                    }

                    val formatId = json.optString("formatId", "m4a/audio")
                    val mimeType = json.optString("mimeType", "audio/mp4")
                    val container = json.optString("container", "m4a")

                    // Parse expire epoch
                    val serverExpiresAt = json.optLong("expiresAtEpochMs", 0L)
                    val expiresAtEpochMs = if (serverExpiresAt > 0L) {
                        serverExpiresAt
                    } else {
                        val expireSec = try {
                            android.net.Uri.parse(streamUrl).getQueryParameter("expire")?.toLongOrNull()
                        } catch (_: Exception) {
                            null
                        }
                        if (expireSec != null) {
                            expireSec * 1000L
                        } else {
                            System.currentTimeMillis() + StreamUrlCache.DEFAULT_TTL_MS
                        }
                    }

                    Log.d(TAG, "Direct stream resolved remotely in ${elapsed}ms: format=$formatId")

                    val resolvedStream = ResolvedStream(
                        streamUrl = streamUrl,
                        headers = emptyMap(),
                        formatId = formatId,
                        mimeType = mimeType,
                        container = container,
                        videoId = videoId.ifBlank { null },
                        expiresAtEpochMs = expiresAtEpochMs
                    )

                    Result.success(resolvedStream)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val timeoutMsg = "yt-dlp execution timed out after ${YTDLP_EXECUTION_TIMEOUT_MS}ms of active execution"
            Log.w(TAG, timeoutMsg)
            Result.failure(TimeoutException(timeoutMsg))
        } catch (e: Exception) {
            Log.e(TAG, "Stream resolution error: ${e.message}", e)
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
