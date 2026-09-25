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
 * Lightweight HTTP stream resolver delegating Deezer preview resolution to Supabase Edge Functions.
 */
class DeezerStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 2500L,
    private val endpointUrl: String = SupabaseConfig.RESOLVE_DEEZER_URL
) {
    companion object {
        private const val TAG = "DeezerStreamResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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
                val payload = JSONObject().apply {
                    put("query", trimmedQuery)
                    if (!videoId.isNullOrBlank()) {
                        put("videoId", videoId)
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
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("Deezer edge function HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val success = json.optBoolean("success", false)
                if (!success) {
                    val error = json.optString("error", "Deezer resolution failed")
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=$error")
                    return@withTimeout Result.failure(NoSuchElementException(error))
                }

                val streamUrl = json.optString("streamUrl")
                if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=missing_preview_url")
                    return@withTimeout Result.failure(IllegalStateException("No valid preview URL on Deezer edge function"))
                }

                val formatId = json.optString("formatId", "deezer_preview_mp3_128")
                val mimeType = json.optString("mimeType", "audio/mpeg")
                val container = json.optString("container", "mp3")
                val bitrate = json.optLong("bitrate", 128000L)
                val expiresAtEpochMs = json.optLong("expiresAtEpochMs", System.currentTimeMillis() + 3600000L)
                val returnedVideoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: videoId

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "DEEZER_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed format=$formatId")

                val resolvedStream = ResolvedStream(
                    streamUrl = streamUrl,
                    formatId = formatId,
                    mimeType = mimeType,
                    container = container,
                    bitrate = bitrate,
                    expiresAtEpochMs = expiresAtEpochMs,
                    videoId = returnedVideoId
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
