package com.notify.download.matcher

import android.content.Context
import android.util.Log
import com.notify.download.spike.YtDlpRuntime
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * YtDlpYouTubeSearchProvider:
 * Executes YouTube queries using yt-dlp's built-in ytsearch extractor.
 * Passes each argument through YoutubeDLRequest options to avoid raw shell command construction.
 */
class YtDlpYouTubeSearchProvider(
    private val context: Context? = null
) : YouTubeSearchProvider {

    private companion object {
        private const val TAG = "YtDlpSearch"
    }

    override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> = withContext(Dispatchers.IO) {
        val sanitizedLimit = limit.coerceIn(1, 20)
        val searchTarget = "ytsearch$sanitizedLimit:$query"

        try {
            if (context != null) {
                val initResult = YtDlpRuntime.ensureReady(context)
                if (initResult.isFailure) {
                    val err = initResult.exceptionOrNull()?.message ?: "YtDlpRuntime init failed"
                    Log.e(TAG, "Cannot search: yt-dlp not ready: $err")
                    return@withContext Result.failure(IllegalStateException(err))
                }
                Log.d(TAG, "YtDlpRuntime.Ready, yt-dlp version: ${(initResult.getOrNull())?.ytDlpVersion}")
            }
            Log.d(TAG, "Executing safe ytsearch: $searchTarget")
            val request = YoutubeDLRequest(searchTarget).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--ignore-errors")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val output = response.out ?: ""

            val candidates = mutableListOf<YouTubeCandidate>()
            // yt-dlp prints one JSON object per line for multiple results
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        val json = JSONObject(trimmed)
                        val id = json.optString("id")
                        val title = json.optString("title")
                        val uploader = json.optString("uploader", json.optString("channel"))
                        val durationSec = json.optDouble("duration", 0.0)
                        val viewCount = json.optLong("view_count", -1L).takeIf { it >= 0 }

                        val artworkUrl = run {
                            val thumbnails = json.optJSONArray("thumbnails")
                            if (thumbnails != null && thumbnails.length() > 0) {
                                var bestUrl: String? = null
                                var maxResolution = -1
                                for (i in 0 until thumbnails.length()) {
                                    val item = thumbnails.optJSONObject(i) ?: continue
                                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                                    val width = item.optInt("width", 0)
                                    val height = item.optInt("height", 0)
                                    val resolution = width * height
                                    if (bestUrl == null || resolution > maxResolution) {
                                        bestUrl = url
                                        maxResolution = resolution
                                    }
                                }
                                bestUrl ?: json.optString("thumbnail").takeIf { it.isNotBlank() }
                            } else {
                                json.optString("thumbnail").takeIf { it.isNotBlank() }
                            }
                        }

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
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unparseable JSON line in search output", e)
                    }
                }
            }

            Log.d(TAG, "Found ${candidates.size} YouTube search candidates for '$query'")
            Result.success(candidates)
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search failed for query: $query", e)
            Result.failure(e)
        }
    }
}
