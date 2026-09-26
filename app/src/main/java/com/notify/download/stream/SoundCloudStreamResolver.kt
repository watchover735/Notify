package com.notify.download.stream

import android.util.Log
import com.notify.core.model.ResolvedStream
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
 * Lightweight HTTP stream resolver delegating SoundCloud resolution to Supabase Edge Functions.
 * Eliminates on-device HTML parsing, regex asset scraping, and token tracking.
 */
class SoundCloudStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 3000L,
    private val endpointUrl: String = SupabaseConfig.RESOLVE_SOUNDCLOUD_URL
) {
    companion object {
        private const val TAG = "SoundCloudResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun resolveStream(
        query: String,
        videoId: String? = null,
        expectedDurationMs: Long? = null
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty query for SoundCloud"))
        }

        val start = System.currentTimeMillis()
        Log.i(TAG, "SOUNDCLOUD_ATTEMPT_START query=\"$trimmedQuery\"")

        try {
            withTimeout(timeoutMs) {
                val payload = JSONObject().apply {
                    put("query", trimmedQuery)
                    if (!videoId.isNullOrBlank()) {
                        put("videoId", videoId)
                    }
                    if (expectedDurationMs != null && expectedDurationMs > 0) {
                        put("expectedDurationMs", expectedDurationMs)
                    }
                }

                val request = Request.Builder()
                    .url(endpointUrl)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful || body.isBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("SoundCloud edge function HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val success = json.optBoolean("success", false)
                if (!success) {
                    val error = json.optString("error", "SoundCloud resolution failed")
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=$error")
                    return@withTimeout Result.failure(NoSuchElementException(error))
                }

                val streamUrl = json.optString("streamUrl")
                if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=empty_direct_url")
                    return@withTimeout Result.failure(IllegalStateException("Empty direct URL from SoundCloud edge function"))
                }

                val formatId = json.optString("formatId", "soundcloud_mp3_progressive")
                val mimeType = json.optString("mimeType", "audio/mpeg")
                val container = json.optString("container", "mp3")
                val bitrate = json.optLong("bitrate", 128000L)
                val durationMs = json.optLong("durationMs", 0L).takeIf { it > 0 }
                val expiresAtEpochMs = json.optLong("expiresAtEpochMs", System.currentTimeMillis() + 1800000L)
                val returnedVideoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: videoId

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed format=$formatId durationMs=$durationMs")

                val resolvedStream = ResolvedStream(
                    streamUrl = streamUrl,
                    formatId = formatId,
                    mimeType = mimeType,
                    container = container,
                    bitrate = bitrate,
                    durationMs = durationMs,
                    expiresAtEpochMs = expiresAtEpochMs,
                    videoId = returnedVideoId
                )
                Result.success(resolvedStream)
            }
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            Log.w(TAG, "SOUNDCLOUD_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed error=${e.message}")
            Result.failure(e)
        }
    }
}
