package com.notify.telemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.notify.auth.SupabaseAuthRepository
import com.notify.download.stream.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks app usage duration, active sessions, music playback duration, and DAU.
 *
 * Heartbeat:
 * - Periodically flushes accumulated active seconds and music playback seconds to Supabase RPC `record_user_heartbeat`.
 * - Updates `profiles.last_seen_at`, `profiles.total_active_seconds`, and `user_daily_telemetry`.
 * - Offline-resilient: caches un-sent seconds locally in SharedPreferences until network connectivity is restored.
 */
class UserTelemetryManager private constructor(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "UserTelemetryManager"
        private const val PREFS_NAME = "notify_user_telemetry"
        private const val KEY_PENDING_ACTIVE_SEC = "pending_active_seconds"
        private const val KEY_PENDING_PLAY_SEC = "pending_play_seconds"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // Flush every 60 seconds

        @Volatile
        private var instance: UserTelemetryManager? = null

        fun getInstance(application: Application): UserTelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: UserTelemetryManager(application).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authRepo = SupabaseAuthRepository(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val startedActivityCount = AtomicInteger(0)
    private val isMusicPlaying = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private var lastTickElapsedRealtime: Long = 0L
    private var accumulatedActiveSec: Long = 0L
    private var accumulatedPlaySec: Long = 0L

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
            lastTickElapsedRealtime = SystemClock.elapsedRealtime()
            startHeartbeatLoop()
            // Send initial ping to mark last_seen_at and DAU on app launch
            scope.launch {
                flushHeartbeat(force = true)
            }
        }
    }

    fun setMusicPlaying(playing: Boolean) {
        val wasPlaying = isMusicPlaying.getAndSet(playing)
        if (wasPlaying != playing) {
            tickElapsed()
            if (playing) {
                // Started playing: ensure we flush soon
                lastTickElapsedRealtime = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun startHeartbeatLoop() {
        scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                tickElapsed()
                val isForeground = startedActivityCount.get() > 0
                val playing = isMusicPlaying.get()
                if (isForeground || playing || hasPendingTelemetry()) {
                    flushHeartbeat(force = false)
                }
            }
        }
    }

    @Synchronized
    private fun tickElapsed() {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = now - lastTickElapsedRealtime
        if (deltaMs <= 0) return
        lastTickElapsedRealtime = now

        val deltaSec = (deltaMs / 1000L).coerceAtLeast(0L)
        val isForeground = startedActivityCount.get() > 0
        val playing = isMusicPlaying.get()

        if (isForeground) {
            accumulatedActiveSec += deltaSec
        }
        if (playing) {
            accumulatedPlaySec += deltaSec
        }
    }

    private fun hasPendingTelemetry(): Boolean {
        val pendingActive = prefs.getLong(KEY_PENDING_ACTIVE_SEC, 0L)
        val pendingPlay = prefs.getLong(KEY_PENDING_PLAY_SEC, 0L)
        return pendingActive > 0L || pendingPlay > 0L || accumulatedActiveSec > 0L || accumulatedPlaySec > 0L
    }

    @Synchronized
    private fun takeAccumulated(): Pair<Long, Long> {
        val active = accumulatedActiveSec
        val play = accumulatedPlaySec
        accumulatedActiveSec = 0L
        accumulatedPlaySec = 0L
        return Pair(active, play)
    }

    suspend fun flushHeartbeat(force: Boolean = false) {
        tickElapsed()
        val (active, play) = takeAccumulated()

        val savedPendingActive = prefs.getLong(KEY_PENDING_ACTIVE_SEC, 0L)
        val savedPendingPlay = prefs.getLong(KEY_PENDING_PLAY_SEC, 0L)

        val totalActive = active + savedPendingActive
        val totalPlay = play + savedPendingPlay

        if (!force && totalActive <= 0L && totalPlay <= 0L) {
            return
        }

        val session = authRepo.getStoredSession()
        if (session == null || session.accessToken.isBlank()) {
            // Not authenticated yet: store in prefs to flush when user logs in
            prefs.edit()
                .putLong(KEY_PENDING_ACTIVE_SEC, totalActive)
                .putLong(KEY_PENDING_PLAY_SEC, totalPlay)
                .apply()
            return
        }

        val version = try {
            val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        val bodyJson = JSONObject().apply {
            put("p_active_seconds", totalActive)
            put("p_play_seconds", totalPlay)
            put("p_app_version", version)
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.REST_URL}/rpc/record_user_heartbeat")
            .header("apikey", SupabaseConfig.ANON_KEY)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Success! Clear pending
                    prefs.edit()
                        .remove(KEY_PENDING_ACTIVE_SEC)
                        .remove(KEY_PENDING_PLAY_SEC)
                        .apply()
                    Log.d(TAG, "Heartbeat recorded: active=${totalActive}s, play=${totalPlay}s")
                } else {
                    Log.w(TAG, "Heartbeat failed (${response.code}), saving for next retry")
                    prefs.edit()
                        .putLong(KEY_PENDING_ACTIVE_SEC, totalActive)
                        .putLong(KEY_PENDING_PLAY_SEC, totalPlay)
                        .apply()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat network error: ${e.message}")
            prefs.edit()
                .putLong(KEY_PENDING_ACTIVE_SEC, totalActive)
                .putLong(KEY_PENDING_PLAY_SEC, totalPlay)
                .apply()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val count = startedActivityCount.incrementAndGet()
        if (count == 1) {
            // Transition from background to foreground
            tickElapsed()
            lastTickElapsedRealtime = SystemClock.elapsedRealtime()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = startedActivityCount.decrementAndGet()
        if (count == 0) {
            // Transition from foreground to background
            tickElapsed()
            scope.launch {
                flushHeartbeat(force = true)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
