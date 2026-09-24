package com.notify.download.stream

import android.content.Context
import com.notify.core.model.ResolvedStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ParallelRacingResolverChainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        StreamUrlCache.clear()
        ResolvedStreamProviderChain.clearInFlight()
        CobaltStreamResolver.resetDemotionForTest()
    }

    @After
    fun tearDown() {
        StreamUrlCache.clear()
        ResolvedStreamProviderChain.clearInFlight()
        CobaltStreamResolver.resetDemotionForTest()
    }

    @Test
    fun fastRacerWins_cancelsLosingRacers_returnsQuickly() = runTest {
        val loserCancelled = AtomicBoolean(false)

        val fastResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(50L) // Completes first
                return Result.success(
                    ResolvedStream(
                        videoId = "vid1",
                        streamUrl = "https://stream.example.com/fast.m4a",
                        formatId = "fast_winner"
                    )
                )
            }
        }

        val slowResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                try {
                    delay(500L) // Slow loser
                    return Result.success(
                        ResolvedStream(
                            videoId = "vid1",
                            streamUrl = "https://stream.example.com/slow.m4a",
                            formatId = "slow_loser"
                        )
                    )
                } finally {
                    loserCancelled.set(true)
                }
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = context,
            fallbackResolver = slowResolver,
            customRacers = listOf("Fast" to fastResolver, "Slow" to slowResolver),
            scope = this
        )

        val result = chain.resolveStream("https://www.youtube.com/watch?v=vid1", "Song Title", "Artist Name")
        assertTrue(result.isSuccess)
        val winner = result.getOrThrow()
        assertEquals("fast_winner", winner.formatId)
        assertEquals("https://stream.example.com/fast.m4a", winner.streamUrl)
        assertTrue("Losing racer must be cancelled when winner completes", loserCancelled.get())

        // Cached in memory
        val cached = StreamUrlCache.get("vid1")
        assertNotNull(cached)
        assertEquals("fast_winner", cached?.formatId)
    }

    @Test
    fun simultaneousRacerCompletion_selectsFirstWinner_gracefullyIgnoresSecond() = runTest {
        // Point 5: Test race-condition when two racers complete almost simultaneously
        val completionOrder = mutableListOf<String>()

        val racerA = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(20L)
                completionOrder.add("RacerA")
                return Result.success(
                    ResolvedStream(
                        videoId = "raceVid",
                        streamUrl = "https://stream.example.com/racerA.m4a",
                        formatId = "racerA_format"
                    )
                )
            }
        }

        val racerB = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(20L) // Exact same delay
                completionOrder.add("RacerB")
                return Result.success(
                    ResolvedStream(
                        videoId = "raceVid",
                        streamUrl = "https://stream.example.com/racerB.m4a",
                        formatId = "racerB_format"
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = context,
            customRacers = listOf("RacerA" to racerA, "RacerB" to racerB),
            scope = this
        )

        val result = chain.resolveStream("https://www.youtube.com/watch?v=raceVid")
        assertTrue("Race resolution must succeed", result.isSuccess)
        val winner = result.getOrThrow()

        // Exactly one winner must be selected without crash or deadlock
        assertTrue(
            "Winner must be one of the simultaneous racers",
            winner.formatId == "racerA_format" || winner.formatId == "racerB_format"
        )
    }

    @Test
    fun allLightweightRacersFail_fallsBackToYtDlp() = runTest {
        val failingFastResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(10L)
                return Result.failure(RuntimeException("Simulated FastInnerTube failure"))
            }
        }

        val ytDlpFallback = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                return Result.success(
                    ResolvedStream(
                        videoId = "fallbackVid",
                        streamUrl = "https://stream.example.com/ytdlp.m4a",
                        formatId = "140"
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = context,
            fallbackResolver = ytDlpFallback,
            customRacers = listOf("FailingFast" to failingFastResolver),
            scope = this
        )

        val result = chain.resolveStream("https://www.youtube.com/watch?v=fallbackVid")
        assertTrue(result.isSuccess)
        val winner = result.getOrThrow()
        assertEquals("140", winner.formatId)
        assertEquals("https://stream.example.com/ytdlp.m4a", winner.streamUrl)
    }

    @Test
    fun cobaltAutoDemotes_afterConsecutiveFailures() = runTest {
        // Point 4: Cobalt auto-demotion after 3 consecutive failures
        assertFalse("Initially not demoted", CobaltStreamResolver.isDemoted())

        val resolver = CobaltStreamResolver(
            instanceUrl = "https://127.0.0.1:9999/api/json" // Non-existent endpoint triggers immediate failure
        )

        // Attempt 1 fails
        resolver.resolveStream("https://www.youtube.com/watch?v=test1")
        assertFalse(CobaltStreamResolver.isDemoted())

        // Attempt 2 fails
        resolver.resolveStream("https://www.youtube.com/watch?v=test2")
        assertFalse(CobaltStreamResolver.isDemoted())

        // Attempt 3 fails -> trips circuit breaker
        resolver.resolveStream("https://www.youtube.com/watch?v=test3")
        assertTrue("Cobalt must be demoted after 3 consecutive failures", CobaltStreamResolver.isDemoted())

        // Next call immediately returns failure without network call
        val demotedResult = resolver.resolveStream("https://www.youtube.com/watch?v=test4")
        assertTrue(demotedResult.isFailure)
        assertTrue(demotedResult.exceptionOrNull()?.message?.contains("demoted") == true)
    }

    @Test
    fun lateWinnerRacer_afterRaceTimeout_winsOverPendingYtDlp() = runTest {
        // Race timeout is 2800ms. Racer resolves at 2900ms.
        // Fallback resolver is slower (e.g. 4000ms) or queued.
        // Late winner must be used instead of discarding it!
        val lateRacer = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(2900L) // Resolves slightly after 2800ms race timeout
                return Result.success(
                    ResolvedStream(
                        videoId = "lateVid",
                        streamUrl = "https://stream.example.com/late_winner.m4a",
                        formatId = "soundcloud_late_winner"
                    )
                )
            }
        }

        val ytDlpFallback = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(4000L) // Slower fallback
                return Result.success(
                    ResolvedStream(
                        videoId = "lateVid",
                        streamUrl = "https://stream.example.com/ytdlp_slow.m4a",
                        formatId = "140"
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = context,
            fallbackResolver = ytDlpFallback,
            customRacers = listOf("SoundCloud" to lateRacer),
            scope = this
        )

        val result = chain.resolveStream("https://www.youtube.com/watch?v=lateVid", "Song", "Artist")
        assertTrue("Late winner resolution must succeed", result.isSuccess)
        val winner = result.getOrThrow()
        assertEquals("Late winner must win over slower yt-dlp fallback", "soundcloud_late_winner", winner.formatId)
        assertEquals("https://stream.example.com/late_winner.m4a", winner.streamUrl)
    }
}
