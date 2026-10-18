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
import com.notify.auth.AuthRepository
import com.notify.auth.SupabaseAuthRepository

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
    private val authRepository: AuthRepository = SupabaseAuthRepository(context),
    private val deezerResolver: DeezerStreamResolver = DeezerStreamResolver(authRepository = authRepository),
    private val soundCloudResolver: SoundCloudStreamResolver = SoundCloudStreamResolver(authRepository = authRepository),
    private val jioSaavnResolver: JioSaavnStreamResolver = JioSaavnStreamResolver(authRepository = authRepository),
    private val cobaltResolver: CobaltStreamResolver = CobaltStreamResolver(authRepository = authRepository),
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
        const val RESOLUTION_TIMEOUT_MS = 35_000L
        const val RACE_TIMEOUT_MS = 2_800L

        /**
         * Version-modifier words/phrases that identify a non-original recording.
         * Matched with word-boundary semantics — NOT raw substring contains.
         * Edit this list here; do NOT add inline checks elsewhere.
         *
         * Exclusions (documented):
         *  - "live" handled separately via LIVE_PATTERNS (too many false rejects with plain contains)
         *  - "version" excluded — appears in "original version", "album version" which are legit
         *  - "edit" included (word-boundary prevents matching "edition", "edited")
         */
        val MODIFIER_WORDS: List<String> = listOf(
            "slow", "slowed",
            "reverb",
            "lofi", "lo-fi",
            "sped up", "speed up",
            "nightcore",
            "8d",
            "bass boosted",
            "mashup",
            "remix",
            "dj",
            "edit",
            "cover",
            "karaoke",
            "instrumental",
            "acoustic",
            "unplugged",
            "jhankar",
            "flip",
            "mix",
            "extended",
            "reprise",
            "female",
            "male",
            "sad",
            "remake",
            "tribute",
            "revisited",
            "rework",
            "redux",
            "unwind",
            "chill",
            "chillout"
        )

        /**
         * "live" is too common in legitimate song titles ("Live and Let Die", "Live Your Life",
         * "Oasis - Live Forever"). We only treat it as a modifier when it appears in these
         * context-specific patterns that unambiguously signal a live performance recording.
         * Match is case-insensitive substring on the lowercased title (not word-boundary,
         * because patterns already include surrounding punctuation/context).
         */
        val LIVE_PATTERNS: List<String> = listOf(
            "(live", "[live", "- live", "– live", "— live",
            "live at", "live from", "live version", "live performance", "live session"
        )

        /**
         * When the provider returns a stream with a null/blank title we cannot run the full
         * modifier filter.  We apply a tighter duration tolerance instead.
         * LOW_CONFIDENCE_TOLERANCE_PCT: fraction of expectedDuration (e.g. 0.08 = 8%).
         * LOW_CONFIDENCE_TOLERANCE_MIN_MS: minimum absolute tolerance floor.
         * Change these two constants to tune without touching logic.
         */
        const val LOW_CONFIDENCE_TOLERANCE_PCT = 0.08   // 8% of expected duration
        const val LOW_CONFIDENCE_TOLERANCE_MIN_MS = 8_000L  // 8 seconds minimum

        /**
         * Legit-phrase prefixes stripped from both titles before modifier comparison.
         * Prevents "original mix" from triggering the "mix" modifier check.
         */
        private val LEGIT_PHRASE_PATTERNS: List<Regex> = listOf(
            Regex("\\boriginal mix\\b"),
            Regex("\\boriginal version\\b"),
            Regex("\\balbum version\\b"),
            Regex("\\bofficial\\b"),
            Regex("\\bremastered\\b"),
            Regex("\\bfeat\\.?\\b"),
            // Strip "(From <movie>)" or "[From <movie>]" style suffixes
            Regex("[\\(\\[]from [^\\)\\]]+[\\)\\]]"),
            // Strip "(From the <movie> Soundtrack)" style
            Regex("from the .{0,60}?soundtrack"),
            // Strip "(Original Soundtrack)" style
            Regex("[\\(\\[]original soundtrack[\\)\\]]"),
            Regex("[\\(\\[]ost[\\)\\]]")
        )

        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlightResolutions = ConcurrentHashMap<String, Deferred<Result<ResolvedStream>>>()

        fun isInFlight(videoId: String): Boolean {
            val rawId = videoId.removePrefix("youtube:")
            return inFlightResolutions.containsKey(rawId)
        }

        fun clearInFlight() {
            inFlightResolutions.clear()
        }

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: Throwable) {}
        }
        private fun logI(msg: String) {
            try {
                Log.i(TAG, msg)
                StreamResolverDebugLog.i(TAG, msg)
            } catch (_: Throwable) {}
        }
        private fun logW(msg: String) {
            try {
                Log.w(TAG, msg)
                StreamResolverDebugLog.w(TAG, msg)
            } catch (_: Throwable) {}
        }

        /**
         * B1.1 Helper: extracts detected modifier words/phrases from title using word-boundary matching.
         */
        fun extractModifiers(title: String): Set<String> {
            if (title.isBlank()) return emptySet()
            val norm = normalizeForModifierCheck(title.lowercase())
            val matched = mutableSetOf<String>()
            for (word in MODIFIER_WORDS) {
                if (containsModifier(norm, word)) {
                    matched.add(word)
                }
            }
            if (hasLivePattern(norm)) {
                matched.add("live")
            }
            return matched
        }

        /**
         * B1.2 Candidate rejection logic.
         * Returns true if candidate is compatible with expected title; false if rejected.
         *
         * Rules:
         * 1. If expectedTitle has ANY modifier from MODIFIER_WORDS: candidate must contain at least one
         *    expected modifier and no unrequested modifiers.
         * 2. If expectedTitle has NO modifiers: candidate must NOT have ANY modifier from MODIFIER_WORDS.
         * 3. Live logic: if candidate has a live pattern and expected does not -> REJECT.
         *    Similarly, if expected has a live pattern and candidate does not -> REJECT.
         * Fail-open: if candidateTitle is null/blank, returns true (logged as warning).
         */
        fun isCandidateCompatible(
            provider: String,
            candidateTitle: String?,
            expectedTitle: String?,
            expectedArtist: String? = null
        ): Boolean {
            if (candidateTitle.isNullOrBlank()) {
                // Fail-open for missing metadata so audio is not silently dropped
                logW("CANDIDATE_CHECK_SKIPPED provider=$provider reason=missing_candidate_title")
                return true
            }
            if (expectedTitle.isNullOrBlank()) {
                return true
            }

            val normCandidate = normalizeForModifierCheck(candidateTitle.lowercase())
            val normExpected = normalizeForModifierCheck(expectedTitle.lowercase())
            val normExpectedWithArtist = if (!expectedArtist.isNullOrBlank()) {
                normExpected + " " + expectedArtist.lowercase()
            } else {
                normExpected
            }

            val expectedModifiers = extractModifiers(normExpectedWithArtist)
            val candidateModifiers = extractModifiers(normCandidate)

            // Rule 1: If expectedTitle has modifiers
            if (expectedModifiers.isNotEmpty()) {
                val hasMatch = expectedModifiers.any { it in candidateModifiers }
                if (!hasMatch) {
                    logW("CANDIDATE_REJECTED reason=MODIFIER_MISMATCH provider=$provider title=\"$candidateTitle\" expected=\"$expectedTitle\" detectedModifiers=$candidateModifiers")
                    return false
                }
                val hasUnrequested = candidateModifiers.any { it !in expectedModifiers }
                if (hasUnrequested) {
                    logW("CANDIDATE_REJECTED reason=MODIFIER_MISMATCH provider=$provider title=\"$candidateTitle\" expected=\"$expectedTitle\" detectedModifiers=$candidateModifiers")
                    return false
                }
            } else {
                // Rule 2: If expectedTitle has NO modifiers: candidate must NOT have ANY modifier
                if (candidateModifiers.isNotEmpty()) {
                    logW("CANDIDATE_REJECTED reason=MODIFIER_MISMATCH provider=$provider title=\"$candidateTitle\" expected=\"$expectedTitle\" detectedModifiers=$candidateModifiers")
                    return false
                }
            }

            // Rule 3: Live pattern check
            val candidateLive = hasLivePattern(normCandidate)
            val expectedLive = hasLivePattern(normExpectedWithArtist)
            if (candidateLive && !expectedLive) {
                logW("CANDIDATE_REJECTED reason=MODIFIER_MISMATCH provider=$provider title=\"$candidateTitle\" expected=\"$expectedTitle\" detectedModifiers=[live]")
                return false
            }
            if (expectedLive && !candidateLive) {
                logW("CANDIDATE_REJECTED reason=MODIFIER_MISMATCH provider=$provider title=\"$candidateTitle\" expected=\"$expectedTitle\" detectedModifiers=[]")
                return false
            }

            return true
        }

        /**
         * Strips legit phrases ("original mix", "remastered", "feat.", "From <movie>" etc.) from
         * a lowercased title before modifier-word comparison to prevent false positives.
         * The returned string is only used for modifier comparison, not displayed.
         */
        fun normalizeForModifierCheck(lower: String): String {
            if (lower.isBlank()) return lower
            return try {
                var s = lower
                for (pattern in LEGIT_PHRASE_PATTERNS) {
                    s = pattern.replace(s, " ")
                }
                s.trim()
            } catch (_: Throwable) { lower }
        }

        /**
         * Word-boundary modifier check.
         * Returns true if [phrase] appears in [normalizedText] as a whole word/phrase.
         * [normalizedText] must already be lowercased and legit-phrase-stripped.
         */
        fun containsModifier(normalizedText: String, phrase: String): Boolean {
            if (normalizedText.isBlank() || phrase.isBlank()) return false
            return try {
                // Boundary: preceded by non-alphanumeric or start-of-string,
                // followed by non-alphanumeric or end-of-string.
                val pattern = Regex("(?<![a-z0-9])${Regex.fromLiteral(phrase)}(?![a-z0-9])")
                pattern.containsMatchIn(normalizedText)
            } catch (_: Throwable) {
                normalizedText.contains(phrase)  // defensive fallback
            }
        }

        /**
         * Returns true if [normalizedText] contains any of the LIVE_PATTERNS.
         * Patterns are matched as plain substrings (they already include context).
         */
        fun hasLivePattern(normalizedText: String): Boolean {
            if (normalizedText.isBlank()) return false
            return LIVE_PATTERNS.any { pattern -> normalizedText.contains(pattern) }
        }

        /**
         * B.5 Artist Suffix Cleaner:
         * Strips noisy YouTube artist channel suffixes such as:
         *   - " - Topic", " Topic"
         *   - " - VEVO", " VEVO", "VEVO"
         *   - " - Official", " Official"
         * so downstream resolver queries (JioSaavn, SoundCloud) and expectedArtist
         * matching use clean real artist names instead of channel suffixes.
         */
        fun sanitizeArtist(artist: String?): String? {
            if (artist.isNullOrBlank()) return null
            val cleaned = artist.trim()
            val pattern = Regex("""(?i)(?:\s*[-–—]\s*|\s+)(?:topic|vevo|official)$|(?<=[a-z0-9])vevo$""", RegexOption.IGNORE_CASE)
            val match = pattern.find(cleaned)
            if (match != null && match.range.first > 0) {
                val stripped = cleaned.substring(0, match.range.first).trim()
                if (stripped.isNotEmpty()) {
                    return stripped
                }
            }
            return cleaned.takeIf { it.isNotEmpty() }
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
    ): Result<ResolvedStream> = resolveStream(canonicalYoutubeUrl, title, artist, isPrefetch, null)

    override suspend fun resolveStream(
        canonicalYoutubeUrl: String,
        title: String?,
        artist: String?,
        isPrefetch: Boolean,
        expectedDurationMs: Long?
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
                container = "m4a",
                durationMs = expectedDurationMs
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
                    executeChainResolution(videoId, canonicalYoutubeUrl, title, artist, isPrefetch, expectedDurationMs)
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
        isPrefetch: Boolean,
        expectedDurationMs: Long? = null
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
        var effectiveArtist = sanitizeArtist(artist?.trim()?.takeIf { it.isNotEmpty() && !isGenericArtist(it) })
        var effectiveExpectedDurationMs = expectedDurationMs
        var query = listOfNotNull(effectiveTitle, effectiveArtist).joinToString(" ").trim()

        // Failsafe: if query is blank or expectedDurationMs is null, fast-fetch title/author/duration (via oEmbed / InnerTube)
        // so videoId-only requests (radio autoplay, watch-next, 403 retries) can participate in Stage c race
        if ((query.isBlank() || effectiveExpectedDurationMs == null) && videoId.isNotBlank()) {
            val fetchedMeta = fetchVideoMetadataFast(videoId)
            if (fetchedMeta != null) {
                if (effectiveTitle.isNullOrBlank()) {
                    effectiveTitle = fetchedMeta.title.trim().takeIf { it.isNotEmpty() }
                }
                if (effectiveArtist.isNullOrBlank()) {
                    effectiveArtist = sanitizeArtist(fetchedMeta.author.trim().takeIf { it.isNotEmpty() && !isGenericArtist(it) })
                }
                if (effectiveExpectedDurationMs == null && fetchedMeta.durationMs != null) {
                    effectiveExpectedDurationMs = fetchedMeta.durationMs
                }
                query = listOfNotNull(effectiveTitle, effectiveArtist).joinToString(" ").trim()
                if (query.isNotBlank()) {
                    logI("QUERY_RECOVERED_FROM_VIDEOMETA videoId=$videoId recoveredQuery=\"$query\" expectedDurationMs=$effectiveExpectedDurationMs")
                }
            }
        }

        // ── Stage c: Parallel Lightweight Provider Race (Max 2.8s) ────────────
        val winnerDeferred = CompletableDeferred<Pair<String, ResolvedStream>>()
        val safeLateWinner = CompletableDeferred<Pair<String, ResolvedStream>>()
        var closestMismatchStream: Pair<String, ResolvedStream>? = null
        var closestDeltaMs = Long.MAX_VALUE

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
                            // STEP-A DIAGNOSTIC: log what the provider matched vs what we expected
                            val streamDurMs = stream.durationMs  // local val: cross-module Long? cannot be smart-cast
                            val matchedDurS = streamDurMs?.let { it / 1000L }
                            val expectedDurS = effectiveExpectedDurationMs?.let { it / 1000L }
                            val durDeltaS = if (streamDurMs != null && effectiveExpectedDurationMs != null)
                                Math.abs(streamDurMs - effectiveExpectedDurationMs) / 1000L else null
                            logI("PROVIDER_ATTEMPT_MATCHED provider=$name videoId=$videoId" +
                                " expectedTitle=\"$effectiveTitle\" matchedTitle=\"${stream.title}\"" +
                                " expectedArtist=\"$effectiveArtist\"" +
                                " expectedDurS=$expectedDurS matchedDurS=$matchedDurS durDeltaS=$durDeltaS")
                            val isValid = validateStream(name, stream, effectiveExpectedDurationMs, effectiveTitle, effectiveArtist)
                            if (isValid) {
                                // Atomic completion: first winner succeeds, others return false
                                if (winnerDeferred.complete(name to stream)) {
                                    safeLateWinner.complete(name to stream)
                                    val elapsed = System.currentTimeMillis() - raceStart
                                    logI("RACE_WINNER provider=$name videoId=$videoId elapsedMs=$elapsed format=${stream.formatId}" +
                                        " matchedTitle=\"${stream.title}\" expectedTitle=\"$effectiveTitle\"" +
                                        " matchedDurMs=${stream.durationMs} expectedDurMs=$effectiveExpectedDurationMs" +
                                        " deltaMs=$durDeltaS hasTitle=${!stream.title.isNullOrBlank()}")
                                }
                            } else {
                                val streamDuration = stream.durationMs
                                if (streamDuration != null && effectiveExpectedDurationMs != null && effectiveExpectedDurationMs > 0L) {
                                    val delta = Math.abs(streamDuration - effectiveExpectedDurationMs)
                                    synchronized(remainingJobs) {
                                        if (delta < closestDeltaMs) {
                                            closestDeltaMs = delta
                                            closestMismatchStream = name to stream
                                        }
                                    }
                                }
                            }
                        } else if (res != null && res.isFailure) {
                            logW("PROVIDER_ATTEMPT_FAILED provider=$name videoId=$videoId reason=${res.exceptionOrNull()?.message}")
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
                        resolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch, effectiveExpectedDurationMs)
                    }
                }
            } else {
                // 1. FastInnerTube (direct YouTube player endpoint)
                registerRacer("FastInnerTube") {
                    fastPrimaryResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch, effectiveExpectedDurationMs)
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
                        soundCloudResolver.resolveStream(
                            query = query,
                            videoId = videoId,
                            expectedDurationMs = effectiveExpectedDurationMs,
                            expectedTitle = effectiveTitle,
                            expectedArtist = effectiveArtist
                        )
                    }

                    // JioSaavn (conditional Indic / Indian artist routing)
                    val (includeJioSaavn, routingReason) = ProviderRoutingPolicy.evaluateJioSaavnRouting(query, effectiveTitle, effectiveArtist)
                    logI("ROUTING_DECISION jiosaavn=$includeJioSaavn reason=$routingReason query=\"$query\"")
                    if (includeJioSaavn) {
                        registerRacer("JioSaavn") {
                            jioSaavnResolver.resolveStream(
                                query = query,
                                videoId = videoId,
                                expectedDurationMs = effectiveExpectedDurationMs,
                                expectedTitle = effectiveTitle,
                                expectedArtist = effectiveArtist
                            )
                        }
                    }

                    // Deezer (preview fallback) — skip if Indic route to save slot and network bandwidth
                    // Deezer catalog has near-zero coverage for Indian / Bollywood / Punjabi tracks
                    if (!includeJioSaavn) {
                        registerRacer("Deezer") {
                            deezerResolver.resolveStream(query, videoId, effectiveExpectedDurationMs)
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

            logI("RACE_START competitors=$competitors videoId=$videoId query=\"$query\" expectedDurationMs=$effectiveExpectedDurationMs")

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
                val fallbackResult = fallbackResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch, effectiveExpectedDurationMs)
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
                val rescue = synchronized(remainingJobs) { closestMismatchStream }
                if (rescue != null) {
                    val (rescueProvider, rescueStream) = rescue
                    // B1.4: Only rescue if modifier filter also passes on this candidate.
                    // A modifier-mismatched rescue would play a wrong version silently.
                    val rescueModifierOk = !hasModifierMismatch(
                        rescueStream.title, effectiveTitle, effectiveArtist
                    )
                    if (rescueModifierOk) {
                        val enriched = if (rescueStream.videoId.isNullOrBlank()) rescueStream.copy(videoId = videoId) else rescueStream
                        logW("RESCUE_USED provider=$rescueProvider videoId=$videoId" +
                            " matchedTitle=\"${rescueStream.title}\" deltaMs=$closestDeltaMs" +
                            " reason=all_resolvers_failed_no_modifier_mismatch" +
                            " NOT_CACHED=true")
                        // Intentionally NOT cached: rescue streams failed validateStream and are
                        // low-confidence; caching would persist a wrong-version across sessions.
                        return Result.success(enriched)
                    } else {
                        logW("RESCUE_SKIPPED provider=$rescueProvider videoId=$videoId" +
                            " reason=modifier_mismatch_in_rescue matchedTitle=\"${rescueStream.title}\"")
                    }
                }
                logW("ALL_RESOLVERS_FAILED videoId=$videoId error=$fallbackErr")
                return Result.failure(fallbackResult.exceptionOrNull() ?: IllegalStateException("All stream resolvers failed: $fallbackErr"))
            }

            // Otherwise racers are still running! Start yt-dlp fallback concurrently,
            // allowing any late winner from lightweight providers to still win.
            logW("RACE_TIMEOUT_EXPIRED videoId=$videoId elapsedMs=$raceElapsed. Starting yt-dlp fallback while keeping late racers active.")
            val fallbackDeferred = raceScope.async {
                fallbackResolver.resolveStream(canonicalYoutubeUrl, effectiveTitle, effectiveArtist, isPrefetch, effectiveExpectedDurationMs)
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
                        val rescue = synchronized(remainingJobs) { closestMismatchStream }
                        if (rescue != null) {
                            val (rescueProvider, rescueStream) = rescue
                            // B1.4: Only rescue if modifier filter also passes.
                            val rescueModifierOk = !hasModifierMismatch(
                                rescueStream.title, effectiveTitle, effectiveArtist
                            )
                            if (rescueModifierOk) {
                                val enriched = if (rescueStream.videoId.isNullOrBlank()) rescueStream.copy(videoId = videoId) else rescueStream
                                logW("RESCUE_USED provider=$rescueProvider videoId=$videoId" +
                                    " matchedTitle=\"${rescueStream.title}\" deltaMs=$closestDeltaMs" +
                                    " reason=all_resolvers_failed_no_modifier_mismatch" +
                                    " NOT_CACHED=true")
                                return@onAwait Result.success(enriched)
                            } else {
                                logW("RESCUE_SKIPPED provider=$rescueProvider videoId=$videoId" +
                                    " reason=modifier_mismatch_in_rescue matchedTitle=\"${rescueStream.title}\"")
                            }
                        }
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

    /**
     * Entry-point for stream validation combining modifier filter + duration filter.
     * Never throws; a null/empty field is treated as "cannot verify" (passes that sub-check).
     *
     * @param expectedArtist Used to detect artist-name modifiers (e.g. "DJ Khaled") so a song
     *   by an artist whose name contains "dj" is not falsely rejected.
     */
    private fun validateStream(
        providerName: String,
        stream: ResolvedStream,
        expectedDurationMs: Long?,
        expectedTitle: String? = null,
        expectedArtist: String? = null
    ): Boolean {
        return try {
            validateStreamInternal(providerName, stream, expectedDurationMs, expectedTitle, expectedArtist)
        } catch (e: Throwable) {
            // Safety net: scorer/filter must never propagate exceptions into the race.
            logW("VALIDATE_STREAM_ERROR provider=$providerName error=${e.message} — treating as no-safe-match")
            false
        }
    }

    private fun validateStreamInternal(
        providerName: String,
        stream: ResolvedStream,
        expectedDurationMs: Long?,
        expectedTitle: String?,
        expectedArtist: String?
    ): Boolean {
        val streamTitle = stream.title
        val titleAvailable = !streamTitle.isNullOrBlank()

        // ── Modifier filter (B1.1 + B1.2) ───────────────────────────────
        // Only run full modifier filter when both stream.title and expectedTitle are present.
        // When stream title is blank, fall through to the LOW_CONFIDENCE tight duration path.
        if (!expectedTitle.isNullOrBlank() && titleAvailable) {
            val isCompatible = isCandidateCompatible(
                provider = providerName,
                candidateTitle = streamTitle,
                expectedTitle = expectedTitle,
                expectedArtist = expectedArtist
            )
            if (!isCompatible) {
                return false
            }
        }

        // ── Duration filter ───────────────────────────────────────────
        if (expectedDurationMs == null || expectedDurationMs <= 0L) {
            return true
        }
        val streamDuration = stream.durationMs
        if (streamDuration == null || streamDuration <= 0L) {
            // No stream duration: can't verify. Accept unless title was also missing
            // (double-unknown = accept, risk is lower than false-reject).
            return true
        }

        // B1.3: If stream title was blank, use LOW_CONFIDENCE tight tolerance instead of ±20%.
        val lowerToleranceMs: Long
        val upperToleranceMs: Long
        if (!titleAvailable) {
            val lowConf = maxOf(
                LOW_CONFIDENCE_TOLERANCE_MIN_MS,
                (expectedDurationMs * LOW_CONFIDENCE_TOLERANCE_PCT).toLong()
            )
            lowerToleranceMs = lowConf
            upperToleranceMs = lowConf
        } else {
            // Normal asymmetric tolerance: lower ±20%, upper ±20% (unchanged from FIX A).
            // DO NOT tighten the upper tolerance here — real data needed first.
            val norm = maxOf(15_000L, (expectedDurationMs * 0.20).toLong())
            lowerToleranceMs = norm
            upperToleranceMs = norm
        }

        val minAllowedMs = expectedDurationMs - lowerToleranceMs
        val maxAllowedMs = expectedDurationMs + upperToleranceMs
        val isSnippet = expectedDurationMs > 60_000L && streamDuration <= 35_000L

        if (streamDuration < minAllowedMs || streamDuration > maxAllowedMs || isSnippet) {
            val expSec   = expectedDurationMs / 1000L
            val gotSec   = streamDuration / 1000L
            val deltaS   = Math.abs(streamDuration - expectedDurationMs) / 1000L
            val confTag  = if (!titleAvailable) "LOW_CONFIDENCE" else "NORMAL"
            logW("DURATION_MISMATCH_REJECTED provider=$providerName" +
                " expected=${expSec}s got=${gotSec}s delta=${deltaS}s" +
                " lowerTol=${lowerToleranceMs / 1000}s upperTol=${upperToleranceMs / 1000}s" +
                " isSnippet=$isSnippet confidence=$confTag")
            return false
        }

        if (!titleAvailable) {
            logI("LOW_CONFIDENCE_ACCEPTED provider=$providerName videoId=unknown" +
                " streamDurS=${streamDuration / 1000L} expectedDurS=${expectedDurationMs / 1000L}" +
                " tolerance=${lowerToleranceMs / 1000L}s")
        }
        return true
    }

    /**
     * Returns true if [streamTitle] has a modifier mismatch against [expectedTitle]/[expectedArtist].
     * Used by rescue logic (B1.4) to avoid rescuing with a wrong-version stream.
     * Returns false (= no mismatch, rescue is safe) when streamTitle is null/blank.
     */
    private fun hasModifierMismatch(
        streamTitle: String?,
        expectedTitle: String?,
        expectedArtist: String?
    ): Boolean {
        if (streamTitle.isNullOrBlank() || expectedTitle.isNullOrBlank()) return false
        return !isCandidateCompatible("rescue_check", streamTitle, expectedTitle, expectedArtist)
    }

    internal fun normalizeForModifierCheck(lower: String): String = Companion.normalizeForModifierCheck(lower)
    internal fun containsModifier(normalizedText: String, phrase: String): Boolean = Companion.containsModifier(normalizedText, phrase)
    internal fun hasLivePattern(normalizedText: String): Boolean = Companion.hasLivePattern(normalizedText)
    internal fun extractModifiers(title: String): Set<String> = Companion.extractModifiers(title)
    internal fun isCandidateCompatible(
        provider: String,
        candidateTitle: String?,
        expectedTitle: String?,
        expectedArtist: String? = null
    ): Boolean = Companion.isCandidateCompatible(provider, candidateTitle, expectedTitle, expectedArtist)

    data class FastVideoMeta(
        val title: String,
        val author: String,
        val durationMs: Long? = null
    )

    private fun fetchVideoMetadataFast(videoId: String): FastVideoMeta? {
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
                        return FastVideoMeta(t, a, null)
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
                    val durMs = details?.optString("lengthSeconds")?.toLongOrNull()?.let { it * 1000L }
                    if (t.isNotBlank()) {
                        return FastVideoMeta(t, a, durMs)
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
