package com.notify.download.stream

import android.util.Log
import com.notify.core.model.ResolvedStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lightweight HTTP stream resolver delegating Cobalt resolution to Supabase Edge Functions.
 */
class CobaltStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 2500L,
    private val instanceUrl: String = SupabaseConfig.RESOLVE_COBALT_URL,
    endpointUrl: String = instanceUrl
) {
    private val effectiveEndpoint = if (endpointUrl != SupabaseConfig.RESOLVE_COBALT_URL) endpointUrl else instanceUrl

    companion object {
        private const val TAG = "CobaltResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val FAILURE_THRESHOLD = 3
        private const val DEMOTION_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes

        private val consecutiveFailures = AtomicInteger(0)
        private val demotedUntilEpochMs = AtomicLong(0L)

        fun isDemoted(): Boolean {
            val demotedUntil = demotedUntilEpochMs.get()
            return System.currentTimeMillis() < demotedUntil
        }

        fun resetDemotionForTest() {
            consecutiveFailures.set(0)
            demotedUntilEpochMs.set(0L)
        }
    }

    suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        videoId: String? = null
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val demotedUntil = demotedUntilEpochMs.get()
        if (now < demotedUntil) {
            val remainingSec = (demotedUntil - now) / 1000
            Log.d(TAG, "COBALT_DEMOTED skipping race (demoted for another ${remainingSec}s)")
            return@withContext Result.failure(IllegalStateException("Cobalt demoted due to repeated failures"))
        }

        val start = System.currentTimeMillis()
        Log.i(TAG, "COBALT_ATTEMPT_START url=\"$canonicalYoutubeUrl\"")

        try {
            withTimeout(timeoutMs) {
                val payload = JSONObject().apply {
                    put("url", canonicalYoutubeUrl)
                    if (!videoId.isNullOrBlank()) {
                        put("videoId", videoId)
                    }
                }

                val request = Request.Builder()
                    .url(effectiveEndpoint)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful || body.isBlank()) {
                    recordFailure()
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("Cobalt edge function HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val success = json.optBoolean("success", false)
                if (!success) {
                    recordFailure()
                    val error = json.optString("error", "Cobalt resolution failed")
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=$error")
                    return@withTimeout Result.failure(NoSuchElementException(error))
                }

                val streamUrl = json.optString("streamUrl")
                if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
                    recordFailure()
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=invalid_stream_url")
                    return@withTimeout Result.failure(IllegalStateException("Invalid stream URL from Cobalt edge function"))
                }

                // Success
                consecutiveFailures.set(0)
                demotedUntilEpochMs.set(0L)

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "COBALT_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed")

                val returnedVideoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: videoId
                val resolvedStream = ResolvedStream(
                    streamUrl = streamUrl,
                    formatId = json.optString("formatId", "cobalt_audio"),
                    mimeType = json.optString("mimeType", "audio/mp4"),
                    container = json.optString("container", "m4a"),
                    expiresAtEpochMs = json.optLong("expiresAtEpochMs", System.currentTimeMillis() + 3600000L),
                    videoId = returnedVideoId
                )
                Result.success(resolvedStream)
            }
        } catch (e: Throwable) {
            recordFailure()
            val elapsed = System.currentTimeMillis() - start
            Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed error=${e.message}")
            Result.failure(e)
        }
    }

    private fun recordFailure() {
        val fails = consecutiveFailures.incrementAndGet()
        if (fails >= FAILURE_THRESHOLD) {
            val cooldownUntil = System.currentTimeMillis() + DEMOTION_COOLDOWN_MS
            demotedUntilEpochMs.set(cooldownUntil)
            Log.w(TAG, "COBALT_DEMOTED circuit breaker tripped after $fails consecutive failures. Demoted until epoch=$cooldownUntil")
        }
    }
}
