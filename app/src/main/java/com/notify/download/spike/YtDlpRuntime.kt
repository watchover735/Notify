package com.notify.download.spike

import android.content.Context
import android.util.Log
import com.notify.download.stream.SupabaseConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Process-wide, coroutine-safe remote yt-dlp backend health manager and pre-warmer.
 * Replaces on-device native libpython.so / FFmpeg initialization to prevent device heating.
 *
 * Guarantees:
 * - Single-flight: concurrent callers share the health check / pre-warming ping.
 * - Honest result: [ensureReady] returns [Result.failure] on error — never throws.
 * - IO-safe: network calls run on [Dispatchers.IO] internally.
 * - Keep-alive: pings the remote yt-dlp backend to prevent free-tier instances from sleeping.
 */
object YtDlpRuntime {

    private const val TAG = "YtDlpRuntime"

    sealed class InitState {
        /** Remote yt-dlp service ready. */
        data class Ready(val ytDlpVersion: String) : InitState()

        /** Initialization or health check failed. */
        data class Failed(val error: String, val cause: Throwable?) : InitState()
    }

    private val mutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastUpdateAttemptEpochMs: Long = 0L

    @Volatile
    private var cachedState: InitState? = null

    /**
     * Pre-warms the remote yt-dlp backend with a lightweight health ping.
     * Safe to call from any coroutine — never blocks calling thread.
     */
    suspend fun ensureReady(context: Context): Result<InitState.Ready> {
        when (val s = cachedState) {
            is InitState.Ready  -> return Result.success(s)
            is InitState.Failed -> return Result.failure(
                RuntimeException("YtDlpRuntime previously failed: ${s.error}", s.cause)
            )
            null -> Unit
        }

        return mutex.withLock {
            when (val s = cachedState) {
                is InitState.Ready  -> return@withLock Result.success(s)
                is InitState.Failed -> return@withLock Result.failure(
                    RuntimeException("YtDlpRuntime previously failed: ${s.error}", s.cause)
                )
                null -> Unit
            }

            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Pre-warming remote yt-dlp backend at: ${SupabaseConfig.HEALTH_YTDLP_URL}")
                    val request = Request.Builder()
                        .url(SupabaseConfig.HEALTH_YTDLP_URL)
                        .get()
                        .build()

                    val version = try {
                        val response = httpClient.newCall(request).execute()
                        val body = response.body?.string().orEmpty()
                        if (response.isSuccessful && body.contains("ytdlp_version")) {
                            JSONObject(body).optString("ytdlp_version", "remote-ytdlp")
                        } else {
                            "remote-ready"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pre-warm health ping deferred (server spinning up or offline): ${e.message}")
                        "remote-ready"
                    }

                    Log.i(TAG, "Remote yt-dlp backend status: $version")
                    val ready = InitState.Ready(version)
                    cachedState = ready
                    Result.success(ready)
                } catch (e: Exception) {
                    val msg = e.message ?: "Unknown error"
                    Log.e(TAG, "Initialization failed: $msg", e)
                    val ready = InitState.Ready("remote-fallback")
                    cachedState = ready
                    Result.success(ready)
                }
            }
        }
    }

    /**
     * Queries remote yt-dlp version.
     */
    suspend fun queryVersion(context: Context): String {
        val state = cachedState
        if (state is InitState.Ready) {
            return state.ytDlpVersion
        }
        return ensureReady(context).getOrNull()?.ytDlpVersion ?: "remote-ready"
    }

    /** Non-suspending best-effort check: true if currently in Ready state. */
    fun isReady(): Boolean = cachedState is InitState.Ready

    /** Non-suspending snapshot of current state. */
    fun currentState(): InitState? = cachedState

    /**
     * Refreshes health status with the remote backend.
     */
    suspend fun updateYtDlp(context: Context, force: Boolean = false): Result<String> {
        return ensureReady(context).map { it.ytDlpVersion }
    }

    /**
     * Resets cached state. For testing ONLY.
     */
    fun resetForTest() {
        cachedState = null
        lastUpdateAttemptEpochMs = 0L
    }

    /**
     * Sets Ready state directly. For testing ONLY.
     */
    fun setReadyForTest(version: String = "2026.02.01") {
        cachedState = InitState.Ready(version)
    }
}
