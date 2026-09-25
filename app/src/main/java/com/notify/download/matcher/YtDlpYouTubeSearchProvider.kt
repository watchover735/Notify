package com.notify.download.matcher

import android.content.Context
import android.util.Log
import com.notify.download.stream.SupabaseConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * YtDlpYouTubeSearchProvider:
 * Executes YouTube queries using the remote yt-dlp microservice.
 * Offloads search execution from the mobile device to avoid battery drain and heating.
 */
class YtDlpYouTubeSearchProvider @JvmOverloads constructor(
    private val context: Context? = null,
    private val endpointUrl: String = SupabaseConfig.SEARCH_YTDLP_URL,
    private val httpClient: OkHttpClient = defaultClient
) : YouTubeSearchProvider {

    private companion object {
        private const val TAG = "YtDlpSearch"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> = withContext(Dispatchers.IO) {
        val sanitizedLimit = limit.coerceIn(1, 20)

        try {
            val payload = JSONObject().apply {
                put("query", query)
                put("limit", sanitizedLimit)
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Remote yt-dlp search call failed: ${e.message}")
                return@withContext Result.failure(e)
            }

            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(IllegalStateException("HTTP ${response.code}: $body"))
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                return@withContext Result.failure(IllegalStateException(json.optString("error", "Search failed")))
            }

            val results = json.optJSONArray("results") ?: return@withContext Result.success(emptyList())
            val candidates = mutableListOf<YouTubeCandidate>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                val uploader = item.optString("uploader", "")
                val durationSec = item.optDouble("duration", 0.0)
                val viewCount = item.optLong("viewCount", -1L).takeIf { it >= 0 }
                val artworkUrl = item.optString("thumbnail").takeIf { it.isNotBlank() }

                if (id.isNotBlank() && title.isNotBlank()) {
                    candidates.add(
                        YouTubeCandidate(
                            videoId = id,
                            title = title,
                            channelTitle = uploader,
                            durationMs = (durationSec * 1000.0).toLong(),
                            viewCount = viewCount,
                            artworkUrl = artworkUrl
                        )
                    )
                }
            }

            Log.d(TAG, "Remote search found ${candidates.size} candidates for '$query'")
            Result.success(candidates)
        } catch (e: Exception) {
            Log.e(TAG, "Remote search error for query: $query", e)
            Result.failure(e)
        }
    }
}
