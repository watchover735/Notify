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
 * Experimental stream resolver using Cobalt API (v10).
 *
 * DISCLAIMER & RELIABILITY:
 * The public `api.cobalt.tools` instance frequently enforces Cloudflare anti-bot verification
 * (Error 1010) on non-browser clients and may be intermittently blocked or down.
 * To use Cobalt reliably in production, point [instanceUrl] to a private self-hosted instance.
 *
 * AUTO-DEMOTION CIRCUIT BREAKER:
 * If Cobalt experiences [FAILURE_THRESHOLD] consecutive failures (e.g. Cloudflare blocks / timeouts),
 * it is automatically demoted and skipped in future parallel races for [DEMOTION_COOLDOWN_MS]
 * to avoid wasting race slots on known-dead requests.
 */
class CobaltStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 1500L,
    private val instanceUrl: String = DEFAULT_INSTANCE
) {
    companion object {
        private const val TAG = "CobaltResolver"
        const val DEFAULT_INSTANCE = "https://api.cobalt.tools/api/json"
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
                    put("downloadMode", "audio")
                    put("audioFormat", "best")
                }

                val request = Request.Builder()
                    .url(instanceUrl)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful || body.isBlank()) {
                    recordFailure()
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("Cobalt HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val status = json.optString("status")
                val streamUrl = json.optString("url")

                val isValidStatus = status.equals("stream", ignoreCase = true) ||
                        status.equals("redirect", ignoreCase = true) ||
                        status.equals("tunnel", ignoreCase = true) ||
                        status.equals("success", ignoreCase = true)

                if (!isValidStatus || streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
                    recordFailure()
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "COBALT_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=status_$status")
                    return@withTimeout Result.failure(IllegalStateException("Cobalt returned invalid stream: status=$status"))
                }

                // Success: reset circuit breaker
                consecutiveFailures.set(0)
                demotedUntilEpochMs.set(0L)

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "COBALT_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed")

                val resolvedStream = ResolvedStream(
                    streamUrl = streamUrl,
                    formatId = "cobalt_audio",
                    mimeType = "audio/mp4",
                    container = "m4a",
                    expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
                    videoId = videoId
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
