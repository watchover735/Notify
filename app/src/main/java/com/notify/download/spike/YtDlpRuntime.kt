package com.notify.download.spike

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide, Mutex-protected, coroutine-safe yt-dlp initializer.
 *
 * Guarantees:
 * - Single-flight: concurrent callers block until the first initialization completes.
 * - Honest result: [ensureReady] returns [Result.failure] on error — never throws.
 * - IO-safe: all blocking native calls run on [Dispatchers.IO] internally.
 * - No runBlocking or blocking synchronized{} on any thread.
 */
object YtDlpRuntime {

    private const val TAG = "YtDlpRuntime"

    sealed class InitState {
        /** yt-dlp and FFmpeg native libraries successfully initialized. */
        data class Ready(val ytDlpVersion: String) : InitState()

        /** Initialization failed. [error] contains the full cause-chain message. */
        data class Failed(val error: String, val cause: Throwable?) : InitState()
    }

    private val mutex = Mutex()
    private val updateMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastUpdateAttemptEpochMs: Long = 0L
    private const val MIN_UPDATE_INTERVAL_MS = 3600_000L // 1 hour cooldown between checks

    @Volatile
    private var cachedState: InitState? = null

    /**
     * Returns [InitState.Ready] wrapped in [Result.success] if yt-dlp is (or becomes)
     * initialized, or [Result.failure] if initialization fails.
     *
     * Safe to call from any coroutine — never blocks the calling thread.
     * Concurrent callers are serialized by [mutex]; only one native init runs.
     */
    suspend fun ensureReady(context: Context): Result<InitState.Ready> {
        // Fast path: already settled
        when (val s = cachedState) {
            is InitState.Ready  -> return Result.success(s)
            is InitState.Failed -> return Result.failure(
                RuntimeException("YtDlpRuntime previously failed: ${s.error}", s.cause)
            )
            null -> Unit // fall through to mutex path
        }

        return mutex.withLock {
            // Re-check inside lock to avoid double init
            when (val s = cachedState) {
                is InitState.Ready  -> return@withLock Result.success(s)
                is InitState.Failed -> return@withLock Result.failure(
                    RuntimeException("YtDlpRuntime previously failed: ${s.error}", s.cause)
                )
                null -> Unit
            }

            withContext(Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    Log.i(TAG, "Initializing YoutubeDL native runtime…")
                    YoutubeDL.getInstance().init(appContext)
                    Log.i(TAG, "YoutubeDL.init() OK")
                    FFmpeg.getInstance().init(appContext)
                    Log.i(TAG, "FFmpeg.init() OK")

                    val version = queryVersionInternal()
                    Log.i(TAG, "yt-dlp version: $version")

                    val ready = InitState.Ready(version)
                    cachedState = ready

                    // Asynchronously check for yt-dlp update in background without blocking immediate playback
                    runtimeScope.launch {
                        try {
                            updateYtDlp(appContext, force = false)
                        } catch (_: Throwable) {}
                    }

                    Result.success(ready)
                } catch (e: Exception) {
                    val msg = buildCauseChain(e)
                    Log.e(TAG, "Initialization FAILED: $msg", e)
                    val failed = InitState.Failed(msg, e)
                    cachedState = failed
                    Result.failure(RuntimeException("YtDlpRuntime init failed: $msg", e))
                }
            }
        }
    }

    /**
     * Queries installed yt-dlp version after ensuring readiness.
     * Returns "Uninitialized" if [ensureReady] was never called successfully.
     */
    suspend fun queryVersion(context: Context): String {
        val state = cachedState
        if (state is InitState.Ready) {
            return withContext(Dispatchers.IO) { queryVersionInternal() }
        }
        return ensureReady(context).getOrNull()?.ytDlpVersion ?: "Uninitialized"
    }

    /** Non-suspending best-effort check: true if currently in Ready state. */
    fun isReady(): Boolean = cachedState is InitState.Ready

    /** Non-suspending snapshot of current state (null = not yet attempted). */
    fun currentState(): InitState? = cachedState

    /**
     * Updates yt-dlp binary to the latest available release from GitHub via UpdateChannel.STABLE.
     * Safe to call from any coroutine.
     */
    suspend fun updateYtDlp(context: Context, force: Boolean = false): Result<String> {
        val now = System.currentTimeMillis()
        if (!force && (now - lastUpdateAttemptEpochMs < MIN_UPDATE_INTERVAL_MS)) {
            val currentVer = (cachedState as? InitState.Ready)?.ytDlpVersion ?: queryVersion(context)
            return Result.success(currentVer)
        }

        return updateMutex.withLock {
            if (!force && (System.currentTimeMillis() - lastUpdateAttemptEpochMs < MIN_UPDATE_INTERVAL_MS)) {
                val currentVer = (cachedState as? InitState.Ready)?.ytDlpVersion ?: queryVersion(context)
                return@withLock Result.success(currentVer)
            }
            lastUpdateAttemptEpochMs = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    Log.i(TAG, "Updating yt-dlp via UpdateChannel.STABLE...")
                    val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                    val newVersion = queryVersionInternal()
                    Log.i(TAG, "yt-dlp update status: $status, active version: $newVersion")
                    val ready = InitState.Ready(newVersion)
                    cachedState = ready
                    Result.success(newVersion)
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp update failed: ${e.message}", e)
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * Resets cached state. For testing ONLY — must never appear in production paths.
     */
    internal fun resetForTest() {
        cachedState = null
        lastUpdateAttemptEpochMs = 0L
    }

    /**
     * Sets Ready state directly. For testing ONLY — bypasses native init.
     */
    internal fun setReadyForTest(version: String = "2026.02.01") {
        cachedState = InitState.Ready(version)
    }

    // ---- private helpers ----

    private fun queryVersionInternal(): String {
        return try {
            val request = YoutubeDLRequest("").apply { addOption("--version") }
            YoutubeDL.getInstance().execute(request).out?.trim() ?: "Unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Could not query --version: ${e.message}")
            try {
                YoutubeDL.getInstance().version(null) ?: "Bundled"
            } catch (_: Exception) {
                "Bundled"
            }
        }
    }

    /** Builds a "A → caused by B → caused by C" chain for diagnostic logs. */
    private fun buildCauseChain(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        while (t != null) {
            if (sb.isNotEmpty()) sb.append(" → caused by: ")
            sb.append("${t::class.java.simpleName}: ${t.message}")
            t = t.cause
        }
        return sb.toString()
    }
}
