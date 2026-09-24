package com.notify.download.stream

import android.util.Log
import com.notify.core.model.ResolvedStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Lightweight HTTP stream resolver using SoundCloud API v2.
 *
 * Scrapes and caches a public `client_id` with an embedded fallback.
 * Resolves progressive or HLS audio streams directly without Python / yt-dlp overhead.
 */
class SoundCloudStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 1500L
) {
    companion object {
        private const val TAG = "SoundCloudResolver"
        private const val SEARCH_ENDPOINT = "https://api-v2.soundcloud.com/search/tracks"

        // EMERGENCY FALLBACK ONLY: Used only if dynamic scraping fails AND no runtime client_id was ever scraped.
        // WARNING: THIS WILL EXPIRE — verify before relying on it!
        private const val EMERGENCY_FALLBACK_CLIENT_ID = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"
        private const val CLIENT_ID_CACHE_TTL_MS = 6 * 3600 * 1000L // 6 hours

        private val cachedClientId = AtomicReference<Pair<String, Long>?>(null)
        private val lastKnownGoodClientId = AtomicReference<String?>(null)

        private val SCRIPT_PATTERN = Pattern.compile("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"")
        private val CLIENT_ID_PATTERN = Pattern.compile("client_id[:=]\"([a-zA-Z0-9]{32})\"")
    }

    suspend fun resolveStream(
        query: String,
        videoId: String? = null
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty query for SoundCloud"))
        }

        val start = System.currentTimeMillis()
        Log.i(TAG, "SOUNDCLOUD_ATTEMPT_START query=\"$trimmedQuery\"")

        try {
            withTimeout(timeoutMs) {
                var effectiveClientId = getOrScrapeClientId()
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val searchUrl = "$SEARCH_ENDPOINT?q=$encodedQuery&client_id=$effectiveClientId&limit=1"

                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                var response = client.newCall(request).execute()
                var body = response.body?.string().orEmpty()

                // HTTP 401 Auto-Retry: stale client_id expired, re-scrape live and retry once
                if (response.code == 401) {
                    Log.w(TAG, "SOUNDCLOUD_AUTH_EXPIRED HTTP 401 with client_id=$effectiveClientId. Invalidate cache and retry once.")
                    cachedClientId.set(null)
                    val freshClientId = getOrScrapeClientId(forceRefresh = true)
                    effectiveClientId = freshClientId
                    val retryUrl = "$SEARCH_ENDPOINT?q=$encodedQuery&client_id=$freshClientId&limit=1"
                    val retryReq = Request.Builder()
                        .url(retryUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    response = client.newCall(retryReq).execute()
                    body = response.body?.string().orEmpty()
                }

                if (!response.isSuccessful || body.isBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("SoundCloud HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val collection = json.optJSONArray("collection")
                if (collection == null || collection.length() == 0) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=no_tracks_found")
                    return@withTimeout Result.failure(NoSuchElementException("No tracks found on SoundCloud for: $trimmedQuery"))
                }

                val track = collection.getJSONObject(0)
                val media = track.optJSONObject("media")
                val transcodings = media?.optJSONArray("transcodings")
                if (transcodings == null || transcodings.length() == 0) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=no_transcodings")
                    return@withTimeout Result.failure(IllegalStateException("No transcodings on SoundCloud track"))
                }

                // Prioritize progressive MP3; fallback to HLS
                var chosenTranscodingUrl: String? = null
                var isProgressive = false

                for (i in 0 until transcodings.length()) {
                    val tc = transcodings.getJSONObject(i)
                    val format = tc.optJSONObject("format")
                    val protocol = format?.optString("protocol").orEmpty()
                    val url = tc.optString("url")
                    if (url.isNotBlank()) {
                        if (protocol.equals("progressive", ignoreCase = true)) {
                            chosenTranscodingUrl = url
                            isProgressive = true
                            break
                        } else if (chosenTranscodingUrl == null) {
                            chosenTranscodingUrl = url
                            isProgressive = false
                        }
                    }
                }

                if (chosenTranscodingUrl.isNullOrBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=no_valid_stream_url")
                    return@withTimeout Result.failure(IllegalStateException("No valid transcoding URL found"))
                }

                // Query transcoding stream URL
                val separator = if (chosenTranscodingUrl.contains("?")) "&" else "?"
                val streamResolveUrl = "$chosenTranscodingUrl${separator}client_id=$effectiveClientId"
                val streamReq = Request.Builder()
                    .url(streamResolveUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                var streamResp = client.newCall(streamReq).execute()
                var streamBody = streamResp.body?.string().orEmpty()

                if (streamResp.code == 401) {
                    Log.w(TAG, "SOUNDCLOUD_TRANSCODING_AUTH_EXPIRED HTTP 401. Re-scraping client_id and retrying transcoding.")
                    cachedClientId.set(null)
                    val freshClientId = getOrScrapeClientId(forceRefresh = true)
                    effectiveClientId = freshClientId
                    val retryStreamResolveUrl = "$chosenTranscodingUrl${separator}client_id=$freshClientId"
                    val retryStreamReq = Request.Builder()
                        .url(retryStreamResolveUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    streamResp = client.newCall(retryStreamReq).execute()
                    streamBody = streamResp.body?.string().orEmpty()
                }

                if (!streamResp.isSuccessful || streamBody.isBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=transcoding_http_${streamResp.code}")
                    return@withTimeout Result.failure(IllegalStateException("Transcoding resolution failed: ${streamResp.code}"))
                }

                val streamJson = JSONObject(streamBody)
                val directUrl = streamJson.optString("url")
                if (directUrl.isNullOrBlank() || !directUrl.startsWith("http")) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=empty_direct_url")
                    return@withTimeout Result.failure(IllegalStateException("Empty direct URL from SoundCloud"))
                }

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed")

                val resolvedStream = ResolvedStream(
                    streamUrl = directUrl,
                    formatId = if (isProgressive) "soundcloud_mp3_progressive" else "soundcloud_hls",
                    mimeType = if (isProgressive) "audio/mpeg" else "application/x-mpegURL",
                    container = if (isProgressive) "mp3" else "m3u8",
                    bitrate = 128_000L,
                    expiresAtEpochMs = System.currentTimeMillis() + 1800_000L, // 30 min expiration
                    videoId = videoId
                )
                Result.success(resolvedStream)
            }
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed error=${e.message}")
            Result.failure(e)
        }
    }

    private fun getOrScrapeClientId(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cachedClientId.get()
            if (cached != null && (now - cached.second) < CLIENT_ID_CACHE_TTL_MS) {
                return cached.first
            }
        }

        // Primary: Dynamic runtime scraping directly from soundcloud.com asset scripts
        val scraped = tryScrapeClientId()
        if (!scraped.isNullOrBlank()) {
            lastKnownGoodClientId.set(scraped)
            cachedClientId.set(Pair(scraped, now))
            return scraped
        }

        // Secondary: In-memory last known good scraped client_id
        val lastKnown = lastKnownGoodClientId.get()
        if (!lastKnown.isNullOrBlank()) {
            Log.w(TAG, "Dynamic scraping failed; using last-known-good runtime client_id")
            cachedClientId.set(Pair(lastKnown, now))
            return lastKnown
        }

        // Last resort: Emergency static fallback
        Log.w(TAG, "Using emergency static fallback client_id (WARNING: THIS WILL EXPIRE)")
        return EMERGENCY_FALLBACK_CLIENT_ID
    }

    private fun tryScrapeClientId(): String? {
        return try {
            val req = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = client.newCall(req).execute()
            val html = resp.body?.string().orEmpty()
            val matcher = SCRIPT_PATTERN.matcher(html)
            val scriptUrls = mutableListOf<String>()
            while (matcher.find()) {
                matcher.group(1)?.let { scriptUrls.add(it) }
            }

            // Check the last few scripts (SoundCloud assets bundle typically defines client_id near the end)
            for (scriptUrl in scriptUrls.takeLast(4).reversed()) {
                val scriptReq = Request.Builder().url(scriptUrl).build()
                val scriptResp = client.newCall(scriptReq).execute()
                val js = scriptResp.body?.string().orEmpty()
                val idMatcher = CLIENT_ID_PATTERN.matcher(js)
                if (idMatcher.find()) {
                    val id = idMatcher.group(1)
                    if (!id.isNullOrBlank()) {
                        Log.d(TAG, "Scraped fresh SoundCloud client_id")
                        return id
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Dynamic SoundCloud client_id scraping skipped: ${e.message}")
            null
        }
    }
}
