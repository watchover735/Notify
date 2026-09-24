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
 * InnerTubeYouTubeMusicSearchProvider:
 * Fast, lightweight HTTP/JSON YouTube Music search using YouTube's internal InnerTube API.
 * Avoids spawning native Python/yt-dlp processes for search queries (~200-400ms vs 3-5s).
 */
class InnerTubeYouTubeMusicSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : OnlineCatalogSearchProvider {

    companion object {
        private const val TAG = "InnerTubeSearch"
        private const val INNERTUBE_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Filter param for "Songs" on YouTube Music
        private const val SONGS_FILTER_PARAM = "EgWKAQIIAWoECAEQDQ=="

        private fun logD(msg: String) {
            try {
                Log.d(TAG, msg)
            } catch (_: Throwable) {
                // JVM test environment safe
            }
        }

        private fun logW(msg: String, throwable: Throwable? = null) {
            try {
                Log.w(TAG, msg, throwable)
            } catch (_: Throwable) {
                // JVM test environment safe
            }
        }

        private fun logE(msg: String, throwable: Throwable? = null) {
            try {
                Log.e(TAG, msg, throwable)
            } catch (_: Throwable) {
                // JVM test environment safe
            }
        }
    }

    override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val targetLimit = limit.coerceIn(1, 20)
        val startTime = System.currentTimeMillis()
        logD("Executing fast InnerTube search for: \"$trimmed\" (limit: $targetLimit)")

        try {
            // Attempt 1: Fast song-filtered query
            var candidates = executeInnerTubeQuery(trimmed, SONGS_FILTER_PARAM, targetLimit)

            // Attempt 2: If 0 candidates returned, try broad query without params
            if (candidates.isEmpty()) {
                logD("Song-filtered query returned 0 candidates, attempting broad InnerTube query...")
                candidates = executeInnerTubeQuery(trimmed, null, targetLimit)
            }

            val elapsed = System.currentTimeMillis() - startTime
            logD("InnerTube search completed in ${elapsed}ms: found ${candidates.size} candidates for \"$trimmed\"")
            Result.success(candidates)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            logE("InnerTube search failed after ${elapsed}ms for \"$trimmed\": ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun executeInnerTubeQuery(query: String, filterParams: String?, limit: Int): List<YouTubeCandidate> {
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20240101.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
            if (!filterParams.isNullOrBlank()) {
                put("params", filterParams)
            }
        }

        val request = Request.Builder()
            .url(INNERTUBE_SEARCH_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logW("InnerTube returned HTTP ${response.code}: ${response.message}")
                return emptyList()
            }

            val responseBody = response.body?.string() ?: return emptyList()
            return parseInnerTubeResponse(responseBody, limit)
        }
    }

    private fun parseInnerTubeResponse(jsonString: String, limit: Int): List<YouTubeCandidate> {
        val candidates = mutableListOf<YouTubeCandidate>()
        val seenVideoIds = mutableSetOf<String>()

        try {
            val root = JSONObject(jsonString)
            val contentsObj = root.optJSONObject("contents") ?: return emptyList()
            val tabbedSearch = contentsObj.optJSONObject("tabbedSearchResultsRenderer") ?: return emptyList()
            val tabs = tabbedSearch.optJSONArray("tabs") ?: return emptyList()
            if (tabs.length() == 0) return emptyList()

            val tab0 = tabs.optJSONObject(0)?.optJSONObject("tabRenderer") ?: return emptyList()
            val content = tab0.optJSONObject("content") ?: return emptyList()
            val sectionList = content.optJSONObject("sectionListRenderer") ?: return emptyList()
            val sectionContents = sectionList.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until sectionContents.length()) {
                if (candidates.size >= limit) break
                val section = sectionContents.optJSONObject(i) ?: continue

                // Check 1: Direct musicShelfRenderer
                val musicShelf = section.optJSONObject("musicShelfRenderer")
                if (musicShelf != null) {
                    val items = musicShelf.optJSONArray("contents")
                    if (items != null) {
                        parseItemsArray(items, candidates, seenVideoIds, limit)
                    }
                    continue
                }

                // Check 2: itemSectionRenderer wrapping musicShelfRenderer or items
                val itemSection = section.optJSONObject("itemSectionRenderer")
                if (itemSection != null) {
                    val subContents = itemSection.optJSONArray("contents")
                    if (subContents != null) {
                        for (j in 0 until subContents.length()) {
                            if (candidates.size >= limit) break
                            val subItem = subContents.optJSONObject(j) ?: continue
                            val subShelf = subItem.optJSONObject("musicShelfRenderer")
                            if (subShelf != null) {
                                val shelfItems = subShelf.optJSONArray("contents")
                                if (shelfItems != null) {
                                    parseItemsArray(shelfItems, candidates, seenVideoIds, limit)
                                }
                            } else {
                                val singleRenderer = subItem.optJSONObject("musicResponsiveListItemRenderer")
                                if (singleRenderer != null) {
                                    parseSingleListItem(singleRenderer)?.let {
                                        if (seenVideoIds.add(it.videoId)) candidates.add(it)
                                    }
                                }
                            }
                        }
                    }
                    continue
                }

                // Check 3: Top result musicCardShelfRenderer
                val musicCard = section.optJSONObject("musicCardShelfRenderer")
                if (musicCard != null) {
                    val cardCandidate = parseCardShelf(musicCard)
                    if (cardCandidate != null && seenVideoIds.add(cardCandidate.videoId)) {
                        candidates.add(cardCandidate)
                    }
                }
            }
        } catch (e: Exception) {
            logW("Error parsing InnerTube JSON response", e)
        }

        return candidates
    }

    private fun parseItemsArray(
        items: JSONArray,
        candidates: MutableList<YouTubeCandidate>,
        seenVideoIds: MutableSet<String>,
        limit: Int
    ) {
        for (i in 0 until items.length()) {
            if (candidates.size >= limit) break
            val item = items.optJSONObject(i) ?: continue
            val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            val candidate = parseSingleListItem(renderer) ?: continue
            if (seenVideoIds.add(candidate.videoId)) {
                candidates.add(candidate)
            }
        }
    }

    private fun parseSingleListItem(renderer: JSONObject): YouTubeCandidate? {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        // Column 0: Title & Watch Endpoint
        val col0 = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val title = col0?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
        if (title.isEmpty()) return null

        // Extract videoId from various possible endpoint locations
        var videoId: String? = null

        // 1. Check col0 run endpoint
        videoId = col0?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("videoId")

        // 2. Check overlay play button endpoint
        if (videoId.isNullOrBlank()) {
            videoId = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId")
        }

        // 3. Check item navigationEndpoint
        if (videoId.isNullOrBlank()) {
            videoId = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId")
        }

        if (videoId.isNullOrBlank()) {
            return null
        }

        // Column 1: Artist, Album, Duration runs
        var artist: String? = null
        var album: String? = null
        var durationMs: Long = 0L

        if (flexColumns.length() > 1) {
            val col1Runs = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")

            if (col1Runs != null && col1Runs.length() > 0) {
                val nonDelimiterRuns = mutableListOf<String>()
                for (k in 0 until col1Runs.length()) {
                    val runText = col1Runs.optJSONObject(k)?.optString("text")?.trim().orEmpty()
                    if (runText.isNotEmpty() && runText != "•") {
                        nonDelimiterRuns.add(runText)
                    }
                }

                if (nonDelimiterRuns.isNotEmpty()) {
                    // Check if last run is duration (e.g., "3:34")
                    val lastRun = nonDelimiterRuns.last()
                    val parsedDuration = parseDurationString(lastRun)
                    val infoRuns = if (parsedDuration > 0L) {
                        durationMs = parsedDuration
                        nonDelimiterRuns.dropLast(1)
                    } else {
                        nonDelimiterRuns
                    }

                    if (infoRuns.isNotEmpty()) {
                        artist = infoRuns[0]
                    }
                    if (infoRuns.size > 1) {
                        album = infoRuns[1]
                    }
                }
            }
        }

        // Thumbnail URL
        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val artworkUrl = extractBestThumbnail(thumbnails)

        return YouTubeCandidate(
            videoId = videoId,
            title = title,
            channelTitle = artist,
            durationMs = durationMs,
            viewCount = null,
            artworkUrl = artworkUrl,
            album = album,
            provider = "youtube_music_innertube"
        )
    }

    private fun parseCardShelf(card: JSONObject): YouTubeCandidate? {
        val title = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
        val videoId = card.optJSONObject("onTap")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: card.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: return null

        val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs")
        var artist: String? = null
        var durationMs: Long = 0L

        if (subtitleRuns != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until subtitleRuns.length()) {
                val text = subtitleRuns.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                if (text.isNotEmpty() && text != "•") {
                    parts.add(text)
                }
            }
            for (part in parts) {
                val d = parseDurationString(part)
                if (d > 0L) {
                    durationMs = d
                } else if (!part.contains("views", ignoreCase = true) && !part.equals("Song", ignoreCase = true) && !part.equals("Video", ignoreCase = true) && artist == null) {
                    artist = part
                }
            }
        }

        val thumbnails = card.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val artworkUrl = extractBestThumbnail(thumbnails)

        return YouTubeCandidate(
            videoId = videoId,
            title = title.ifEmpty { "Unknown Title" },
            channelTitle = artist,
            durationMs = durationMs,
            viewCount = null,
            artworkUrl = artworkUrl,
            album = null,
            provider = "youtube_music_innertube"
        )
    }

    private fun extractBestThumbnail(thumbnails: JSONArray?): String? {
        if (thumbnails == null || thumbnails.length() == 0) return null
        var bestUrl: String? = null
        var maxRes = -1

        for (i in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val width = item.optInt("width", 0)
            val height = item.optInt("height", 0)
            val res = width * height
            if (bestUrl == null || res > maxRes) {
                bestUrl = url
                maxRes = res
            }
        }

        return bestUrl
    }

    private fun parseDurationString(str: String): Long {
        val parts = str.split(":")
        if (parts.size == 2) {
            val mm = parts[0].toLongOrNull() ?: return 0L
            val ss = parts[1].toLongOrNull() ?: return 0L
            return (mm * 60 + ss) * 1000L
        } else if (parts.size == 3) {
            val hh = parts[0].toLongOrNull() ?: return 0L
            val mm = parts[1].toLongOrNull() ?: return 0L
            val ss = parts[2].toLongOrNull() ?: return 0L
            return (hh * 3600 + mm * 60 + ss) * 1000L
        }
        return 0L
    }
}
