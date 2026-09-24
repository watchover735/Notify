package com.notify.download.stream

import android.net.Uri
import android.util.Log
import com.notify.core.model.ResolvedStream
import com.notify.download.matcher.InnerTubeConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Fast primary audio stream resolver using the direct YouTube Music player endpoint (`v1/player`).
 *
 * Invariants:
 * 1. Strict 2.5–3.0 second timeout (2800ms).
 * 2. Parses adaptiveFormats for progressive audio (m4a/opus) with highest bitrate.
 * 3. Never logs signed stream URLs (only logs format metadata and timings).
 * 4. On failure or signature cipher presence, returns Result.failure immediately to allow
 *    yt-dlp compatibility fallback.
 */
open class FastInnerTubeStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 1500L
) : AudioStreamResolver {

    companion object {
        private const val TAG = "FastInnerTubeResolver"
        private const val PLAYER_PATH = "player"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: Throwable) {}
        }
        private fun logW(msg: String, t: Throwable? = null) {
            try { Log.w(TAG, msg, t) } catch (_: Throwable) {}
        }
    }

    override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(canonicalYoutubeUrl)
        if (videoId.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube URL: $canonicalYoutubeUrl"))
        }

        try {
            withTimeout(timeoutMs) {
                queryPlayerEndpoint(videoId)
            }
        } catch (e: Exception) {
            logW("FastInnerTube stream resolution failed or timed out for videoId=$videoId: ${e.message}")
            Result.failure(e)
        }
    }

    private fun queryPlayerEndpoint(videoId: String): Result<ResolvedStream> {
        val profile = InnerTubeConfig.PROFILE_ANDROID_MUSIC
        val url = "${InnerTubeConfig.BASE_URL}$PLAYER_PATH"

        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", profile.clientName)
                    put("clientVersion", profile.clientVersion)
                    put("hl", profile.hl)
                    put("gl", profile.gl)
                })
            })
            put("videoId", videoId)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("User-Agent", profile.userAgent)
            .addHeader("Referer", profile.referer)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return Result.failure(IllegalStateException("HTTP ${response.code} from player endpoint"))
        }

        val responseBody = response.body?.string() ?: return Result.failure(IllegalStateException("Empty player response"))
        val root = JSONObject(responseBody)

        val playabilityStatus = root.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status")
        if (status != "OK") {
            val reason = playabilityStatus?.optString("reason") ?: "Status: $status"
            return Result.failure(IllegalStateException("Playability not OK: $reason"))
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return Result.failure(IllegalStateException("No streamingData in player response"))

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            ?: return Result.failure(IllegalStateException("No adaptiveFormats in player response"))

        // Candidate audio format attributes
        data class AudioFormat(
            val url: String,
            val formatId: String,
            val mimeType: String,
            val bitrate: Long,
            val contentLength: Long,
            val container: String,
            val expiresAtEpochMs: Long
        )

        val audioCandidates = mutableListOf<AudioFormat>()

        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.optJSONObject(i) ?: continue
            val mime = fmt.optString("mimeType")
            if (!mime.startsWith("audio/")) continue

            val directUrl = fmt.optString("url")
            if (directUrl.isBlank()) {
                // Signature cipher present, cannot resolve directly without deciphering
                continue
            }

            val formatId = fmt.opt("itag")?.toString() ?: "unknown"
            val bitrate = fmt.optLong("bitrate", 128_000L)
            val contentLength = fmt.optString("contentLength").toLongOrNull() ?: 0L
            val container = when {
                mime.contains("mp4") -> "m4a"
                mime.contains("webm") -> "webm"
                else -> "audio"
            }

            val expireSec = try {
                Uri.parse(directUrl).getQueryParameter("expire")?.toLongOrNull()
            } catch (_: Exception) { null }

            val expiresAtEpochMs = if (expireSec != null) {
                expireSec * 1000L
            } else {
                System.currentTimeMillis() + 6 * 3600_000L
            }

            audioCandidates.add(
                AudioFormat(
                    url = directUrl,
                    formatId = formatId,
                    mimeType = mime,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    container = container,
                    expiresAtEpochMs = expiresAtEpochMs
                )
            )
        }

        if (audioCandidates.isEmpty()) {
            return Result.failure(IllegalStateException("No direct audio URLs in adaptiveFormats (cipher required)"))
        }

        // Prefer highest bitrate audio
        val best = audioCandidates.maxByOrNull { it.bitrate }!!
        logD("FastInnerTube resolved videoId=$videoId format=${best.formatId} bitrate=${best.bitrate}")

        return Result.success(
            ResolvedStream(
                streamUrl = best.url,
                headers = emptyMap(),
                formatId = best.formatId,
                expiresAtEpochMs = best.expiresAtEpochMs,
                videoId = videoId,
                mimeType = best.mimeType,
                container = best.container,
                bitrate = best.bitrate,
                contentLength = best.contentLength
            )
        )
    }

    private fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        return try {
            val uri = Uri.parse(trimmed)
            uri.getQueryParameter("v")
                ?: if (uri.host?.contains("youtu.be") == true) uri.pathSegments?.firstOrNull() else null
        } catch (_: Exception) {
            null
        }
    }
}
