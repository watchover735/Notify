package com.notify.download.stream

import android.net.Uri
import android.util.Log
import com.notify.core.model.ResolvedStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Lightweight HTTP stream resolver using Deezer public search API.
 *
 * NOTE: Deezer public API provides 30-second preview clips (`preview`), NOT full tracks.
 * This resolver treats Deezer as a lightweight fast preview fallback. Full-track guarantee
 * is not provided by this endpoint.
 */
class DeezerStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 1500L
) {
    companion object {
        private const val TAG = "DeezerStreamResolver"
        private const val SEARCH_ENDPOINT = "https://api.deezer.com/search"
    }

    suspend fun resolveStream(
        query: String,
        videoId: String? = null
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty search query for Deezer"))
        }

        val start = System.currentTimeMillis()
        Log.i(TAG, "DEEZER_ATTEMPT_START query=\"$trimmedQuery\"")

        try {
            withTimeout(timeoutMs) {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val url = "$SEARCH_ENDPOINT?q=$encodedQuery&limit=1"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful || body.isBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("Deezer HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=no_tracks_found")
                    return@withTimeout Result.failure(NoSuchElementException("No tracks found on Deezer for query: $trimmedQuery"))
                }

                val firstTrack = data.getJSONObject(0)
                val previewUrl = firstTrack.optString("preview")
                if (previewUrl.isNullOrBlank() || !previewUrl.startsWith("http")) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=missing_preview_url")
                    return@withTimeout Result.failure(IllegalStateException("No valid preview URL on Deezer track"))
                }

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed")

                val resolvedStream = ResolvedStream(
                    streamUrl = previewUrl,
                    formatId = "deezer_preview_mp3_128",
                    mimeType = "audio/mpeg",
                    container = "mp3",
                    bitrate = 128_000L,
                    expiresAtEpochMs = System.currentTimeMillis() + 3600_000L, // 1 hour validity
                    videoId = videoId
                )
                Result.success(resolvedStream)
            }
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed error=${e.message}")
            Result.failure(e)
        }
    }
}
