package com.notify.download.matcher

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * InnerTubeWatchNextProvider:
 * Fast, lightweight HTTP/JSON YouTube Music client for the `v1/next` endpoint.
 * Fetches related / watch-queue candidate recommendations based on a seed video ID.
 *
 * Invariants:
 * - Experimental provider; provider failure must never block current playback.
 * - Client configuration is replaceable in one place (InnerTubeConfig).
 * - Never logs or duplicates embedded API keys.
 */
open class InnerTubeWatchNextProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(InnerTubeConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(InnerTubeConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "InnerTubeWatchNext"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun logD(msg: String) {
            try {
                Log.d(TAG, msg)
            } catch (_: Throwable) {}
        }

        private fun logW(msg: String, throwable: Throwable? = null) {
            try {
                Log.w(TAG, msg, throwable)
            } catch (_: Throwable) {}
        }

        private fun logE(msg: String, throwable: Throwable? = null) {
            try {
                Log.e(TAG, msg, throwable)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Queries YouTube Music `v1/next` endpoint with the specified seed [videoId].
     * Returns up to [limit] related candidates, strictly excluding the seed video itself.
     */
    open suspend fun getWatchNext(videoId: String, limit: Int = 10): Result<List<YouTubeCandidate>> = withContext(Dispatchers.IO) {
        val trimmedId = videoId.trim()
        if (trimmedId.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val targetLimit = limit.coerceIn(1, 25)
        val startTime = System.currentTimeMillis()
        val profile = InnerTubeConfig.activeProfile
        logD("Fetching watch-next recommendations for seed: $trimmedId (limit: $targetLimit, profile: ${profile.profileName})")

        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", profile.clientName)
                        put("clientVersion", profile.clientVersion)
                        put("hl", profile.hl)
                        put("gl", profile.gl)
                    })
                })
                put("videoId", trimmedId)
                put("playlistId", "RDAMVM$trimmedId")
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
            }

            val requestBuilder = Request.Builder()
                .url(InnerTubeConfig.nextUrl())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", profile.userAgent)

            if (profile.referer.isNotBlank()) {
                requestBuilder.header("Referer", profile.referer)
            }
            profile.extraHeaders.forEach { (k, v) ->
                requestBuilder.header(k, v)
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                logW("InnerTube next failed with HTTP ${response.code}")
                return@withContext Result.failure(RuntimeException("InnerTube next failed with HTTP ${response.code}"))
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                return@withContext Result.success(emptyList())
            }

            val json = JSONObject(body)
            val candidates = mutableListOf<YouTubeCandidate>()
            extractRenderers(json, trimmedId, candidates, targetLimit)

            val elapsed = System.currentTimeMillis() - startTime
            logD("InnerTube watch-next completed in ${elapsed}ms: found ${candidates.size} related candidates for $trimmedId")
            Result.success(candidates)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            logE("InnerTube watch-next error after ${elapsed}ms for $trimmedId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Recursively traverses the response JSON tree to locate candidate renderers
     * (playlistPanelVideoRenderer, musicResponsiveListItemRenderer).
     */
    private fun extractRenderers(
        current: Any,
        seedVideoId: String,
        results: MutableList<YouTubeCandidate>,
        maxLimit: Int
    ) {
        if (results.size >= maxLimit) return

        when (current) {
            is JSONObject -> {
                if (current.has("playlistPanelVideoRenderer")) {
                    val renderer = current.optJSONObject("playlistPanelVideoRenderer")
                    if (renderer != null) {
                        parseCandidate(renderer, seedVideoId)?.let { candidate ->
                            if (results.none { it.videoId == candidate.videoId }) {
                                results.add(candidate)
                            }
                        }
                    }
                }
                val keys = current.keys()
                while (keys.hasNext() && results.size < maxLimit) {
                    val key = keys.next()
                    current.opt(key)?.let { extractRenderers(it, seedVideoId, results, maxLimit) }
                }
            }
            is JSONArray -> {
                for (i in 0 until current.length()) {
                    if (results.size >= maxLimit) break
                    current.opt(i)?.let { extractRenderers(it, seedVideoId, results, maxLimit) }
                }
            }
        }
    }

    private fun parseCandidate(renderer: JSONObject, seedVideoId: String): YouTubeCandidate? {
        val videoId = renderer.optString("videoId")
        if (videoId.isBlank() || videoId.equals(seedVideoId, ignoreCase = true)) return null

        // Extract title
        val titleRuns = renderer.optJSONObject("title")?.optJSONArray("runs")
        val title = titleRuns?.optJSONObject(0)?.optString("text")?.trim()
            ?: renderer.optJSONObject("title")?.optString("simpleText")?.trim()
            ?: renderer.optString("title").takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) return null

        // Extract artist / channel
        val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
            ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")
        val artist = bylineRuns?.optJSONObject(0)?.optString("text")?.trim() ?: "YouTube Music"

        // Extract duration
        val lengthText = renderer.optJSONObject("lengthText")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text")
            ?: renderer.optString("lengthText")
        val durationMs = parseDurationMs(lengthText)

        // Extract thumbnail
        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val artworkUrl = if (thumbnails != null && thumbnails.length() > 0) {
            thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
        } else null

        return YouTubeCandidate(
            videoId = videoId,
            title = title,
            channelTitle = artist,
            durationMs = durationMs,
            viewCount = 0L,
            artworkUrl = artworkUrl,
            album = null,
            provider = "youtube_music"
        )
    }

    private fun parseDurationMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(":")
        return try {
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toLong()
                    (m * 60 + s) * 1000L
                }
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    (h * 3600 + m * 60 + s) * 1000L
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
