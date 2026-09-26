package com.notify.download.stream

import android.content.Context
import android.net.Uri
import android.util.Log
import com.notify.core.model.ResolvedStream
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.matcher.InnerTubeConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * High-performance composite stream resolver implementing Parallel-Race architecture:
 *
 * Stage a: Valid NotiFy offline file (instant local playback, <5ms)
 * Stage b: Valid in-memory [StreamUrlCache] (instant <1ms hit)
 * Stage c: Parallel HTTP Provider Race (FastInnerTube, SoundCloud, Deezer, Cobalt, JioSaavn)
 *          Targeting sub-1s resolution with a 2.0s race cap.
 * Stage d: Existing yt-dlp resolver via [OnlineStreamResolver] as last-resort compatibility fallback.
 *
 * Invariants & Guarantees:
 * 1. Parallel Racing: Lightweight HTTP resolvers race concurrently; the first valid stream wins.
 * 2. Rapid Cancellation: When a competitor wins, all losing race jobs are immediately cancelled.
 * 3. yt-dlp Isolation: Heavy Python/yt-dlp is never in the race; it serves strictly as a safety net.
 * 4. In-flight Deduplication: Multiple callers for the same videoId share a single resolution job.
 * 5. Coroutine Boundary: Bound to an Application-level scope so rapid re-taps do not abort in-flight work.
 * 6. Hard Timeout: Prevents hung operations from blocking playback indefinitely.
 *
 * DATA-SAVER CONSIDERATIONS:
 * The parallel race initiates 2–4 lightweight HTTP metadata calls concurrently (~2-5 KB each).
 * For a future Data-Saver toggle, the race can be constrained to a single prioritized provider
 * or reverted to sequential order to conserve cellular data quota.
 */
class ResolvedStreamProviderChain(
    private val context: Context,
    private val downloadManager: OfflineDownloadManager? = null,
    private val fastPrimaryResolver: AudioStreamResolver = FastInnerTubeStreamResolver(),
    private val fallbackResolver: AudioStreamResolver = OnlineStreamResolver(context),
    private val deezerResolver: DeezerStreamResolver = DeezerStreamResolver(),
    private val soundCloudResolver: SoundCloudStreamResolver = SoundCloudStreamResolver(),
    private val jioSaavnResolver: JioSaavnStreamResolver = JioSaavnStreamResolver(),
    private val cobaltResolver: CobaltStreamResolver = CobaltStreamResolver(),
    private val customRacers: List<Pair<String, AudioStreamResolver>>? = null,
    scope: CoroutineScope? = null
) : AudioStreamResolver {

    private val resolutionScope = scope ?: applicationScope

    private val effectiveDownloadManager by lazy {
        downloadManager ?: try {
            OfflineDownloadManager(context)
        } catch (_: Exception) {
            null
        }
    }

    private val metadataClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    companion object {
        private const val TAG = "StreamProviderChain"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        // Global safety timeout to prevent permanent coroutine leakage if external network calls hang indefinitely.
        // NOTE: yt-dlp per-attempt execution timeout is measured separately inside OnlineStreamResolver
        // strictly after acquiring the ytDlpDispatcher slot, so queue-wait time is excluded from yt-dlp timeout.
        const val RESOLUTION_TIMEOUT_MS = 35_000L
        const val RACE_TIMEOUT_MS = 2_800L

        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlightResolutions = ConcurrentHashMap<String, Deferred<Result<ResolvedStream>>>()

        /**
         * Checks if a resolution is currently in-flight for [videoId].
         */
        fun isInFlight(videoId: String): Boolean {
            val rawId = videoId.removePrefix("youtube:")
            return inFlightResolutions.containsKey(rawId)
        }

        /**
         * Clears in-flight resolutions (primarily for testing).
         */
        fun clearInFlight() {
            inFlightResolutions.clear()
        }

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: Throwable) {}
        }
        private fun logI(msg: String) {
            try { Log.i(TAG, msg) } catch (_: Throwable) {}
        }
        private fun logW(msg: String) {
            try { Log.w(TAG, msg) } catch (_: Throwable) {}
        }
    }

    override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> =
        resolveStream(canonicalYoutubeUrl, null, null, false)

    override suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?
    ): Result<ResolvedStream> = resolveStream(canonicalYoutubeUrl, title, artist, false)

    override suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?,
        isPrefetch: Boolean
    ): Result<ResolvedStream> {
        val videoId = extractVideoId(canonicalYoutubeUrl) ?: return Result.failure(
            IllegalArgumentException("Invalid canonical YouTube URL: $canonicalYoutubeUrl")
        )

        // ── Stage a: Check offline storage ─────────────────────────────────────
        val offlineUri = try {
            effectiveDownloadManager?.getOfflinePlaybackUriForSource(videoId)
        } catch (_: Exception) {
            null
        }
        if (offlineUri != null) {
            logI("RESOLVE_CHAIN_HIT stage=OFFLINE videoId=$videoId")
            val localStream = ResolvedStream(
                streamUrl = offlineUri.toString(),
                headers = emptyMap(),
                formatId = "offline",
                expiresAtEpochMs = Long.MAX_VALUE,
                videoId = videoId,
                mimeType = "audio/mp4",
                container = "m4a"
            )
            return Result.success(localStream)
        }

        // ── Stage b: Check in-memory stream cache ──────────────────────────────
        val cached = StreamUrlCache.get(videoId)
        if (cached != null) {
            logI("RESOLVE_CHAIN_HIT stage=CACHE videoId=$videoId format=${cached.formatId}")
            return Result.success(cached)
        }

        // ── In-Flight Deduplication & Application-Scoped Resolution ───────────
        val deferred = resolutionScope.async {
            try {
                withTimeout(RESOLUTION_TIMEOUT_MS) {
                    executeChainResolution(videoId, canonicalYoutubeUrl, title, artist, isPrefetch)
                }
            } catch (e: TimeoutCancellationException) {
                // Cache rescue: if a background racer or yt-dlp wrote to cache right before timeout cancelled
                val rescued = StreamUrlCache.get(videoId)
                if (rescued != null) {
                    logI("TIMEOUT_RESCUED stage=CACHE_LATE_HIT videoId=$videoId format=${rescued.formatId}")
                    Result.success(rescued)
                } else {
                    val timeoutMsg = "Stream resolution timed out after ${RESOLUTION_TIMEOUT_MS}ms for videoId=$videoId"
                    logW(timeoutMsg)
                    Result.failure(TimeoutException(timeoutMsg))
                }
            } catch (e: Throwable) {
                logW("Resolution error for videoId=$videoId: ${e.message}")
                Result.failure(e)
            } finally {
                inFlightResolutions.remove(videoId)
            }
        }

        val activeDeferred = inFlightResolutions.putIfAbsent(videoId, deferred) ?: deferred
        if (activeDeferred !== deferred) {
            deferred.cancel()
            logI("IN_FLIGHT_JOIN_RACED videoId=$videoId")
        }

        return activeDeferred.await()
    }

    private suspend fun executeChainResolution(
        videoId: String,
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?,
        isPrefetch: Boolean
    ): Result<ResolvedStream> {
        // Double check cache
        val doubleCheckCached = StreamUrlCache.get(videoId)
        if (doubleCheckCached != null) {
            return Result.success(doubleCheckCached)
        }

        val raceStart = System.currentTimeMillis()

        // Helper to filter out generic artist placeholders like "YouTube Music" or "Unknown Artist"
        fun isGenericArtist(a: String?): Boolean {
            if (a.isNullOrBlank()) return true
            val lower = a.trim().lowercase()
            return lower in listOf(
                "youtube music",
                "youtube",
                "unknown artist",
                "unknown",
                "various artists",
                "various",
                "auto-generated by youtube"
            )
        }

        var effectiveTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        var effectiveArtist = artist?.trim()?.takeIf { it.isNotEmpty() && !isGenericArtist(it) }
        var query = listOfNotNull(effectiveTitle, effectiveArtist).joinToString(" ").trim()

        // Failsafe: if query is blank but videoId is available, fast-fetch title/author (via oEmbed / InnerTube)
        // so videoId-only requests (radio autoplay, watch-next, 403 retries) can participate in Stage c race
        if (query.isBlank() && videoId.isNotBlank()) {
            val fetchedMeta = fetchVideoMetadataFast(videoId)
            if (fetchedMeta != null) {
                val (fTitle, fAuthor) = fetchedMeta
                if (effectiveTitle.isNullOrBlank()) {
                    effectiveTitle = fTitle.trim().takeIf { it.isNotEmpty() }
                }
                if (effectiveArtist.isNullOrBlank()) {
                    effectiveArtist = fAuthor.trim().takeIf { it.isNotEmpty() && !isGenericArtist(it) }
                }
                query = listOfNotNull(effectiveTitle, effectiveArtist).joinToString(" ").trim()
                if (query.isNotBlank()) {
                    logI("QUERY_RECOVERED_FROM_VIDEOMETA videoId=$videoId recoveredQuery=\"$query\"")
                }
            }
        }

        // ── Stage c: Parallel Lightweight Provider Race (Max 2.8s) ────────────
        val winnerDeferred = CompletableDeferred<Pair<String, ResolvedStream>>()
        val safeLateWinner = CompletableDeferred<Pair<String, ResolvedStream>>()

        val raceJob = SupervisorJob()
        try {
            val raceScope = CoroutineScope(coroutineContext + raceJob)
            val competitors = mutableListOf<String>()
            val remainingJobs = AtomicInteger(0)

            fun registerRacer(name: String, block: suspend () -> Result<ResolvedStream>?) {
                competitors.add(name)
                remainingJobs.incrementAndGet()
                raceScope.launch {
                    try {
                        val res = block()
                        if (res != null && res.isSuccess) {
                            val stream = res.getOrThrow()
                            // Atomic completion: first winner succeeds, others return false
                            if (winnerDeferred.complete(name to stream)) {
                                safeLateWinner.complete(name to stream)
                                val elapsed = System.currentTimeMillis() - raceStart
                                logI("RACE_WINNER provider=$name videoId=$videoId elapsedMs=$elapsed format=${stream.formatId}")
                            }
                        }
                    } catch (_: Throwable) {
                        // Suppress individual racer errors in parallel race
                    } finally {
                        if (remainingJobs.decrementAndGet() == 0 && !winnerDeferred.isCompleted) {
                            winnerDeferred.completeExceptionally(NoSuchElementException("All race competitors failed"))
                        }
                    }
                }
            }

            if (customRacers != null) {
                for ((name, resolver) in customRacers) {
                    registerRacer(name) {
                        resolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch)
                    }
                }
            } else {
                // 1. FastInnerTube (direct YouTube player endpoint)
                registerRacer("FastInnerTube") {
                    fastPrimaryResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch)
                }

                // 2. Cobalt (temporarily disabled due to Cobalt v10 API changes & Cloudflare Turnstile blocks;
                // saves 500-1200ms per attempt and removes circuit breaker overhead)
                val isCobaltEnabled = false
                if (isCobaltEnabled && !CobaltStreamResolver.isDemoted()) {
                    registerRacer("Cobalt") {
                        cobaltResolver.resolveStream(canonicalYoutubeUrl, videoId)
                    }
                }

                // 3. Query-based resolvers (SoundCloud, Deezer, JioSaavn)
                if (query.isNotBlank()) {
                    // SoundCloud
                    registerRacer("SoundCloud") {
                        soundCloudResolver.resolveStream(query, videoId)
                    }

                    // JioSaavn (conditional Indic / Indian artist routing)
                    val (includeJioSaavn, routingReason) = ProviderRoutingPolicy.evaluateJioSaavnRouting(query, effectiveTitle, effectiveArtist)
                    logI("ROUTING_DECISION jiosaavn=$includeJioSaavn reason=$routingReason query=\"$query\"")
                    if (includeJioSaavn) {
                        registerRacer("JioSaavn") {
                            jioSaavnResolver.resolveStream(query, videoId)
                        }
                    }

                    // Deezer (preview fallback) — skip if Indic route to save slot and network bandwidth
                    // Deezer catalog has near-zero coverage for Indian / Bollywood / Punjabi tracks
                    if (!includeJioSaavn) {
                        registerRacer("Deezer") {
                            deezerResolver.resolveStream(query, videoId)
                        }
                    } else {
                        logI("ROUTING_DECISION deezer=false reason=indic_route_optimized query=\"$query\"")
                    }
                } else {
                    logI("ROUTING_DECISION jiosaavn=false reason=no_match query=\"\"")
                }
            }

            if (competitors.isEmpty() && !winnerDeferred.isCompleted) {
                winnerDeferred.completeExceptionally(NoSuchElementException("No race competitors registered"))
            }

            logI("RACE_START competitors=$competitors videoId=$videoId query=\"$query\"")

            // Wait up to RACE_TIMEOUT_MS for early winner
            val earlyWinner = withTimeoutOrNull(RACE_TIMEOUT_MS) {
                try {
                    winnerDeferred.await()
                } catch (_: Exception) {
                    null
                }
            }

            if (earlyWinner != null) {
                val (providerName, stream) = earlyWinner
                val enriched = if (stream.videoId.isNullOrBlank()) stream.copy(videoId = videoId) else stream
                logI("RESOLVE_CHAIN_HIT stage=PARALLEL_RACE provider=$providerName videoId=$videoId format=${enriched.formatId}")
                StreamUrlCache.put(enriched)
                return Result.success(enriched)
            }

            val raceElapsed = System.currentTimeMillis() - raceStart

            // If all racers already failed before timeout, fallback to yt-dlp synchronously
            if (!winnerDeferred.isActive && remainingJobs.get() == 0) {
                logW("RACE_FAILED videoId=$videoId elapsedMs=$raceElapsed. Falling back to yt-dlp.")
                val fallbackStart = System.currentTimeMillis()
                val fallbackResult = fallbackResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch)
                val fallbackElapsed = System.currentTimeMillis() - fallbackStart

                if (fallbackResult.isSuccess) {
                    val resolved = fallbackResult.getOrThrow()
                    val enriched = if (resolved.videoId.isNullOrBlank()) {
                        resolved.copy(videoId = videoId)
                    } else resolved
                    logI("RESOLVE_CHAIN_HIT stage=YTDLP_FALLBACK videoId=$videoId elapsedMs=$fallbackElapsed format=${enriched.formatId}")
                    StreamUrlCache.put(enriched)
                    return Result.success(enriched)
                }

                val fallbackErr = fallbackResult.exceptionOrNull()?.message ?: "yt-dlp fallback error"
                logW("ALL_RESOLVERS_FAILED videoId=$videoId error=$fallbackErr")
                return Result.failure(fallbackResult.exceptionOrNull() ?: IllegalStateException("All stream resolvers failed: $fallbackErr"))
            }

            // Otherwise racers are still running! Start yt-dlp fallback concurrently,
            // allowing any late winner from lightweight providers to still win.
            logW("RACE_TIMEOUT_EXPIRED videoId=$videoId elapsedMs=$raceElapsed. Starting yt-dlp fallback while keeping late racers active.")
            val fallbackDeferred = raceScope.async {
                fallbackResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch)
            }

            return select<Result<ResolvedStream>> {
                safeLateWinner.onAwait { (providerName, stream) ->
                    val elapsed = System.currentTimeMillis() - raceStart
                    logI("RACE_LATE_WINNER_USED provider=$providerName videoId=$videoId elapsedMs=$elapsed format=${stream.formatId}")
                    fallbackDeferred.cancel()
                    val enriched = if (stream.videoId.isNullOrBlank()) stream.copy(videoId = videoId) else stream
                    logI("RESOLVE_CHAIN_HIT stage=LATE_WINNER provider=$providerName videoId=$videoId format=${enriched.formatId}")
                    StreamUrlCache.put(enriched)
                    Result.success(enriched)
                }
                fallbackDeferred.onAwait { fallbackResult ->
                    if (fallbackResult.isSuccess) {
                        val resolved = fallbackResult.getOrThrow()
                        val enriched = if (resolved.videoId.isNullOrBlank()) resolved.copy(videoId = videoId) else resolved
                        logI("RESOLVE_CHAIN_HIT stage=YTDLP_FALLBACK videoId=$videoId format=${enriched.formatId}")
                        StreamUrlCache.put(enriched)
                        Result.success(enriched)
                    } else {
                        // yt-dlp fallback failed; if racers are still running, wait for winnerDeferred
                        if (remainingJobs.get() > 0) {
                            logI("YTDLP_FALLBACK_FAILED videoId=$videoId, waiting for remaining lightweight racers...")
                            val lateWinner = try {
                                winnerDeferred.await()
                            } catch (_: Throwable) {
                                null
                            }
                            if (lateWinner != null) {
                                val (providerName, stream) = lateWinner
                                val elapsed = System.currentTimeMillis() - raceStart
                                logI("RACE_LATE_WINNER_USED provider=$providerName videoId=$videoId elapsedMs=$elapsed format=${stream.formatId}")
                                val enriched = if (stream.videoId.isNullOrBlank()) stream.copy(videoId = videoId) else stream
                                logI("RESOLVE_CHAIN_HIT stage=LATE_WINNER provider=$providerName videoId=$videoId format=${enriched.formatId}")
                                StreamUrlCache.put(enriched)
                                return@onAwait Result.success(enriched)
                            }
                        }
                        val fallbackErr = fallbackResult.exceptionOrNull()?.message ?: "yt-dlp fallback error"
                        logW("ALL_RESOLVERS_FAILED videoId=$videoId error=$fallbackErr")
                        Result.failure(fallbackResult.exceptionOrNull() ?: IllegalStateException("All stream resolvers failed: $fallbackErr"))
                    }
                }
            }
        } catch (e: Throwable) {
            logW("Parallel race execution exception: ${e.message}")
            return Result.failure(e)
        } finally {
            raceJob.cancel()
        }
    }

    private fun fetchVideoMetadataFast(videoId: String): Pair<String, String>? {
        // Fast Attempt 1: YouTube oEmbed endpoint (instant, unauthenticated GET)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = metadataClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val t = json.optString("title")
                    val a = json.optString("author_name")
                    if (t.isNotBlank()) {
                        return Pair(t, a)
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fast Attempt 2: InnerTube player endpoint videoDetails
        try {
            val playerUrl = "${InnerTubeConfig.BASE_URL}player"
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.27.52")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }
            val req = Request.Builder()
                .url(playerUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", InnerTubeConfig.PROFILE_ANDROID_MUSIC.userAgent)
                .build()
            val resp = metadataClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val root = JSONObject(body)
                    val details = root.optJSONObject("videoDetails")
                    val t = details?.optString("title").orEmpty()
                    val a = details?.optString("author").orEmpty()
                    if (t.isNotBlank()) {
                        return Pair(t, a)
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        return try {
            val uri = Uri.parse(trimmed)
            uri.getQueryParameter("v")
                ?: if (uri.host?.contains("youtu.be") == true) uri.pathSegments?.firstOrNull() else null
        } catch (_: Exception) {
            null
        }
    }
}
