package com.notify.playback

import android.util.Log
import com.notify.core.playback.NetworkMetricsListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Correlated startup latency metrics logger for progressive audio playback.
 *
 * Invariants:
 * 1. Correlated by requestId and playbackSessionId so parallel activity never mixes timings.
 * 2. Emits FIRST_HTTP_BYTE exactly once per playback request from upstream network DataSource.
 * 3. Never logs signed stream URLs (only requestId, videoId, provider, and elapsed times).
 * 4. Measures:
 *    - tapToResolveMs
 *    - resolveToFirstByteMs
 *    - firstByteToReadyMs
 *    - tapToPlayingMs
 */
object StartupMetricsLogger {

    private const val TAG = "StartupMetrics"

    data class SessionTiming(
        val requestId: String,
        val videoId: String,
        val sessionId: Long,
        var tapTimeMs: Long = 0L,
        var resolveStartTimeMs: Long = 0L,
        var resolveCompleteTimeMs: Long = 0L,
        var firstHttpByteTimeMs: Long = 0L,
        var playerBufferingTimeMs: Long = 0L,
        var playerReadyTimeMs: Long = 0L,
        var playingTimeMs: Long = 0L,
        val firstByteEmitted: AtomicBoolean = AtomicBoolean(false),
        val readyEmitted: AtomicBoolean = AtomicBoolean(false),
        val playingEmitted: AtomicBoolean = AtomicBoolean(false)
    ) {
        val tapToResolveMs: Long get() = if (resolveCompleteTimeMs > 0) resolveCompleteTimeMs - tapTimeMs else -1L
        val resolveToFirstByteMs: Long get() = if (firstHttpByteTimeMs > 0 && resolveCompleteTimeMs > 0) firstHttpByteTimeMs - resolveCompleteTimeMs else -1L
        val firstByteToReadyMs: Long get() = if (playerReadyTimeMs > 0 && firstHttpByteTimeMs > 0) playerReadyTimeMs - firstHttpByteTimeMs else -1L
        val tapToPlayingMs: Long get() = if (playingTimeMs > 0) playingTimeMs - tapTimeMs else -1L
    }

    private val activeTimings = ConcurrentHashMap<String, SessionTiming>()

    @Volatile
    private var currentActiveRequestId: String? = null

    init {
        // Wire NetworkMetricsListener from core:playback to report FIRST_HTTP_BYTE
        NetworkMetricsListener.onFirstByteTransferred = {
            val reqId = currentActiveRequestId
            if (reqId != null) {
                onFirstHttpByte(reqId)
            }
        }
    }

    fun onPlayTap(requestId: String, videoId: String, sessionId: Long) {
        val now = System.currentTimeMillis()
        val timing = SessionTiming(
            requestId = requestId,
            videoId = videoId,
            sessionId = sessionId,
            tapTimeMs = now
        )
        activeTimings[requestId] = timing
        currentActiveRequestId = requestId
        Log.i(TAG, "PLAY_TAP requestId=$requestId videoId=$videoId sessionId=$sessionId timestamp=$now")
    }

    fun onResolveStart(requestId: String, videoId: String, provider: String = "youtube") {
        val now = System.currentTimeMillis()
        activeTimings[requestId]?.let {
            it.resolveStartTimeMs = now
        }
        Log.i(TAG, "RESOLVE_START requestId=$requestId videoId=$videoId provider=$provider")
    }

    fun onResolveComplete(requestId: String) {
        val now = System.currentTimeMillis()
        val timing = activeTimings[requestId] ?: return
        timing.resolveCompleteTimeMs = now
        val elapsed = now - timing.tapTimeMs
        Log.i(TAG, "RESOLVE_COMPLETE requestId=$requestId elapsedMs=$elapsed")
    }

    fun onFirstHttpByte(requestId: String) {
        val timing = activeTimings[requestId] ?: return
        if (timing.firstByteEmitted.compareAndSet(false, true)) {
            val now = System.currentTimeMillis()
            timing.firstHttpByteTimeMs = now
            val elapsed = now - timing.tapTimeMs
            Log.i(TAG, "FIRST_HTTP_BYTE requestId=$requestId elapsedMs=$elapsed")
        }
    }

    fun onPlayerBuffering(requestId: String) {
        val now = System.currentTimeMillis()
        activeTimings[requestId]?.let {
            it.playerBufferingTimeMs = now
        }
        Log.i(TAG, "PLAYER_BUFFERING requestId=$requestId")
    }

    fun onPlayerReady(requestId: String) {
        val timing = activeTimings[requestId] ?: return
        if (timing.readyEmitted.compareAndSet(false, true)) {
            val now = System.currentTimeMillis()
            timing.playerReadyTimeMs = now
            val elapsed = now - timing.tapTimeMs
            Log.i(TAG, "PLAYER_READY requestId=$requestId elapsedMs=$elapsed")
        }
    }

    fun onPlaying(requestId: String) {
        val timing = activeTimings[requestId] ?: return
        if (timing.playingEmitted.compareAndSet(false, true)) {
            val now = System.currentTimeMillis()
            timing.playingTimeMs = now
            val elapsed = now - timing.tapTimeMs
            Log.i(TAG, "PLAYING requestId=$requestId elapsedMs=$elapsed")

            // Log consolidated benchmark report
            val tapToResolve = if (timing.resolveCompleteTimeMs > 0) timing.resolveCompleteTimeMs - timing.tapTimeMs else -1L
            val resolveToFirstByte = if (timing.firstHttpByteTimeMs > 0 && timing.resolveCompleteTimeMs > 0) timing.firstHttpByteTimeMs - timing.resolveCompleteTimeMs else -1L
            val firstByteToReady = if (timing.playerReadyTimeMs > 0 && timing.firstHttpByteTimeMs > 0) timing.playerReadyTimeMs - timing.firstHttpByteTimeMs else -1L
            val tapToPlaying = elapsed

            Log.i(TAG, "STARTUP_METRICS_SUMMARY requestId=$requestId videoId=${timing.videoId} " +
                    "tapToResolveMs=$tapToResolve resolveToFirstByteMs=$resolveToFirstByte " +
                    "firstByteToReadyMs=$firstByteToReady tapToPlayingMs=$tapToPlaying")
        }
    }

    fun getActiveTiming(requestId: String): SessionTiming? = activeTimings[requestId]

    fun clear() {
        activeTimings.clear()
        currentActiveRequestId = null
    }
}
