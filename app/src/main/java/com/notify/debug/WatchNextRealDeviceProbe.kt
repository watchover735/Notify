package com.notify.debug

import android.util.Log
import com.notify.download.matcher.InnerTubeClientProfile
import com.notify.download.matcher.InnerTubeConfig
import com.notify.download.matcher.YouTubeCandidate
import java.io.IOException
import java.net.SocketTimeoutException
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
 * Isolated real-device probe for YouTube Music InnerTube WatchNext endpoint.
 *
 * Reports:
 * - Request profile (client name, client version, endpoint, safe headers)
 * - HTTP status or exact timeout stage
 * - Latency in ms
 * - Response byte count
 * - Parsed candidate count
 *
 * Invariant: Never logs or duplicates embedded API keys.
 */
data class ProbeResult(
    val videoId: String,
    val profileName: String,
    val stage: String,
    val httpStatusCode: Int?,
    val latencyMs: Long,
    val responseByteCount: Long,
    val candidateCount: Int,
    val candidates: List<YouTubeCandidate>,
    val errorMessage: String? = null
)

object WatchNextRealDeviceProbe {

    private const val TAG = "WatchNextProbe"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // 3 known video IDs:
    // 1. Jailer - Kaavaalaa / Hukum popular track
    // 2. International hit (Despacito)
    // 3. Global hit (Shape of You)
    val DEFAULT_PROBE_IDS = listOf(
        "f4Gtxm3f-l0", // Kaavaalaa (Jailer)
        "kJQP7kiw5Fk", // Despacito
        "JGwWNGJdvx8"  // Shape of You
    )

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun runProbeOnAllProfiles(
        videoIds: List<String> = DEFAULT_PROBE_IDS
    ): List<ProbeResult> = withContext(Dispatchers.IO) {
        val profiles = listOf(
            InnerTubeConfig.PROFILE_WEB_REMIX,
            InnerTubeConfig.PROFILE_ANDROID_MUSIC
        )
        val allResults = mutableListOf<ProbeResult>()
        for (profile in profiles) {
            for (id in videoIds) {
                val res = probeVideoId(id, profile)
                allResults.add(res)
            }
        }
        allResults
    }

    suspend fun probeVideoId(
        videoId: String,
        profile: InnerTubeClientProfile = InnerTubeConfig.activeProfile
    ): ProbeResult = withContext(Dispatchers.IO) {
        val trimmedId = videoId.trim()
        val startTime = System.currentTimeMillis()

        Log.i(TAG, "PROBE_START: id=$trimmedId, profile=${profile.profileName}, client=${profile.clientName}/${profile.clientVersion}")

        var stage = "INIT"
        var httpStatus: Int? = null
        var byteCount = 0L
        val candidates = mutableListOf<YouTubeCandidate>()

        try {
            stage = "BUILD_PAYLOAD"
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

            stage = "EXECUTE_CALL"
            val response = probeClient.newCall(request).execute()
            httpStatus = response.code

            stage = "READ_RESPONSE"
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)
            byteCount = bodyBytes.size.toLong()

            if (!response.isSuccessful) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "PROBE_RESULT: id=$trimmedId, profile=${profile.profileName}, stage=HTTP_ERROR, status=$httpStatus, latencyMs=$elapsed, bytes=$byteCount, candidateCount=0")
                return@withContext ProbeResult(
                    videoId = trimmedId,
                    profileName = profile.profileName,
                    stage = "HTTP_ERROR",
                    httpStatusCode = httpStatus,
                    latencyMs = elapsed,
                    responseByteCount = byteCount,
                    candidateCount = 0,
                    candidates = emptyList(),
                    errorMessage = "HTTP $httpStatus"
                )
            }

            stage = "PARSE_JSON"
            val bodyString = String(bodyBytes, Charsets.UTF_8)
            if (bodyString.isNotBlank()) {
                val json = JSONObject(bodyString)
                extractCandidates(json, trimmedId, candidates, maxLimit = 10)
            }

            stage = "SUCCESS"
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "PROBE_RESULT: id=$trimmedId, profile=${profile.profileName}, stage=SUCCESS, status=$httpStatus, latencyMs=$elapsed, bytes=$byteCount, candidateCount=${candidates.size}")

            ProbeResult(
                videoId = trimmedId,
                profileName = profile.profileName,
                stage = "SUCCESS",
                httpStatusCode = httpStatus,
                latencyMs = elapsed,
                responseByteCount = byteCount,
                candidateCount = candidates.size,
                candidates = candidates
            )
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - startTime
            val timeoutStage = if (stage == "EXECUTE_CALL") "CONNECT_TIMEOUT" else "READ_TIMEOUT"
            Log.e(TAG, "PROBE_RESULT: id=$trimmedId, profile=${profile.profileName}, stage=$timeoutStage, status=$httpStatus, latencyMs=$elapsed, bytes=$byteCount, candidateCount=0, err=${e.message}")
            ProbeResult(
                videoId = trimmedId,
                profileName = profile.profileName,
                stage = timeoutStage,
                httpStatusCode = httpStatus,
                latencyMs = elapsed,
                responseByteCount = byteCount,
                candidateCount = 0,
                candidates = emptyList(),
                errorMessage = e.message
            )
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "PROBE_RESULT: id=$trimmedId, profile=${profile.profileName}, stage=IO_ERROR, status=$httpStatus, latencyMs=$elapsed, bytes=$byteCount, candidateCount=0, err=${e.message}")
            ProbeResult(
                videoId = trimmedId,
                profileName = profile.profileName,
                stage = "IO_ERROR",
                httpStatusCode = httpStatus,
                latencyMs = elapsed,
                responseByteCount = byteCount,
                candidateCount = 0,
                candidates = emptyList(),
                errorMessage = e.message
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "PROBE_RESULT: id=$trimmedId, profile=${profile.profileName}, stage=PARSE_ERROR, status=$httpStatus, latencyMs=$elapsed, bytes=$byteCount, candidateCount=0, err=${e.message}")
            ProbeResult(
                videoId = trimmedId,
                profileName = profile.profileName,
                stage = "PARSE_ERROR",
                httpStatusCode = httpStatus,
                latencyMs = elapsed,
                responseByteCount = byteCount,
                candidateCount = 0,
                candidates = emptyList(),
                errorMessage = e.message
            )
        }
    }

    private fun extractCandidates(
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
                        parseRenderer(renderer, seedVideoId)?.let {
                            if (results.none { existing -> existing.videoId == it.videoId }) {
                                results.add(it)
                            }
                        }
                    }
                }
                val keys = current.keys()
                while (keys.hasNext() && results.size < maxLimit) {
                    val key = keys.next()
                    val child = current.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        extractCandidates(child, seedVideoId, results, maxLimit)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until current.length()) {
                    if (results.size >= maxLimit) break
                    val child = current.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        extractCandidates(child, seedVideoId, results, maxLimit)
                    }
                }
            }
        }
    }

    private fun parseRenderer(renderer: JSONObject, seedVideoId: String): YouTubeCandidate? {
        val videoId = renderer.optString("videoId").trim()
        if (videoId.isEmpty() || videoId.equals(seedVideoId, ignoreCase = true)) {
            return null
        }

        val title = extractText(renderer.optJSONObject("title"))
            ?: renderer.optString("title").takeIf { it.isNotBlank() }
            ?: "Unknown Title"

        val artist = extractText(renderer.optJSONObject("shortBylineText"))
            ?: extractText(renderer.optJSONObject("longBylineText"))
            ?: "YouTube Music"

        val artworkUrl = extractThumbnail(renderer.optJSONObject("thumbnail"))

        return YouTubeCandidate(
            videoId = videoId,
            title = title,
            channelTitle = artist,
            durationMs = 0L,
            artworkUrl = artworkUrl,
            provider = "youtube_music"
        )
    }

    private fun extractText(json: JSONObject?): String? {
        if (json == null) return null
        val simple = json.optString("simpleText")
        if (simple.isNotBlank()) return simple

        val runs = json.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val text = run.optString("text")
            if (text.isNotBlank()) sb.append(text)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun extractThumbnail(thumbnailObj: JSONObject?): String? {
        if (thumbnailObj == null) return null
        val thumbnails = thumbnailObj.optJSONArray("thumbnails") ?: return null
        var bestUrl: String? = null
        var maxDim = 0
        for (i in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(i) ?: continue
            val url = item.optString("url")
            val width = item.optInt("width", 0)
            if (url.isNotBlank() && width >= maxDim) {
                maxDim = width
                bestUrl = url
            }
        }
        return bestUrl
    }
}
