package com.notify.playback

import com.notify.core.model.ResolvedStream
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.ResolvedStreamProviderChain
import com.notify.download.stream.StreamUrlCache
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerformanceAndLibraryPatchTest {

    @Before
    fun setUp() {
        StreamUrlCache.clear()
    }

    @After
    fun tearDown() {
        StreamUrlCache.clear()
    }

    @Test
    fun streamUrlCache_storesAndRetrievesUnexpiredStream() {
        val stream = ResolvedStream(
            videoId = "vid123",
            streamUrl = "https://example.com/audio.m4a",
            headers = mapOf("User-Agent" to "TestAgent"),
            mimeType = "audio/mp4",
            container = "m4a",
            formatId = "140",
            bitrate = 128_000L,
            contentLength = 5_000_000L,
            expiresAtEpochMs = System.currentTimeMillis() + 3600_000L // 1 hour ahead
        )

        StreamUrlCache.put(stream)

        val retrieved = StreamUrlCache.get("vid123")
        assertNotNull(retrieved)
        assertEquals("vid123", retrieved?.videoId)
        assertEquals("140", retrieved?.formatId)
        assertEquals(128_000L, retrieved?.bitrate)
    }

    @Test
    fun streamUrlCache_enforcesConservativeExpirationMargin() {
        // Expiration only 30 seconds away (< 60s safety margin)
        val stream = ResolvedStream(
            videoId = "expiringSoon",
            streamUrl = "https://example.com/audio.m4a",
            expiresAtEpochMs = System.currentTimeMillis() + 30_000L
        )

        StreamUrlCache.put(stream)

        val retrieved = StreamUrlCache.get("expiringSoon")
        assertNull("Streams within 60s margin must be treated as expired", retrieved)
    }

    @Test
    fun streamUrlCache_canRetry403_allowsExactlyOneRetryPerVideoId() {
        val videoId = "forbiddenVideo"

        assertTrue("First 403 retry attempt must be allowed", StreamUrlCache.canRetry403(videoId))
        assertFalse("Second 403 retry attempt must be rejected to prevent infinite loop", StreamUrlCache.canRetry403(videoId))
        assertFalse("Third 403 retry attempt must be rejected", StreamUrlCache.canRetry403(videoId))

        // Resetting allows retry again
        StreamUrlCache.resetRetry403(videoId)
        assertTrue("Retry allowed after explicit reset", StreamUrlCache.canRetry403(videoId))
    }

    @Test
    fun resolvedStreamProviderChain_fallsBackToYtDlp_whenFastResolverFails() = runTest {
        val failingFastResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                return Result.failure(RuntimeException("Simulated FastInnerTube timeout"))
            }
        }

        val successfulYtDlpResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                return Result.success(
                    ResolvedStream(
                        videoId = "testVid",
                        streamUrl = "https://example.com/fallback.opus",
                        formatId = "251",
                        bitrate = 160_000L,
                        expiresAtEpochMs = System.currentTimeMillis() + 1800_000L
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            fastPrimaryResolver = failingFastResolver,
            fallbackResolver = successfulYtDlpResolver
        )

        val result = chain.resolveStream("https://www.youtube.com/watch?v=testVid")
        assertTrue("Expected success but failed: ${result.exceptionOrNull()}", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("testVid", stream.videoId)
        assertEquals("251", stream.formatId)

        // Verify that successful stream was placed in in-memory cache
        val cached = StreamUrlCache.get("testVid")
        assertNotNull(cached)
        assertEquals("251", cached?.formatId)
    }

    @Test
    fun startupMetricsLogger_calculatesElapsedTimesAndCorrelatesSession() {
        val sessionId = 1L
        val requestId = "req_1"
        val videoId = "vid_1"

        StartupMetricsLogger.onPlayTap(requestId = requestId, videoId = videoId, sessionId = sessionId)
        StartupMetricsLogger.onResolveStart(requestId = requestId, videoId = videoId, provider = "FastInnerTube")

        Thread.sleep(10) // simulate small elapsed time
        StartupMetricsLogger.onResolveComplete(requestId)

        StartupMetricsLogger.onFirstHttpByte(requestId)
        StartupMetricsLogger.onPlayerBuffering(requestId)
        StartupMetricsLogger.onPlayerReady(requestId)
        StartupMetricsLogger.onPlaying(requestId)

        val timings = StartupMetricsLogger.getActiveTiming(requestId)
        assertNotNull(timings)
        assertEquals(requestId, timings?.requestId)
        assertEquals(sessionId, timings?.sessionId)
        assertEquals(videoId, timings?.videoId)
        assertTrue((timings?.tapToResolveMs ?: 0L) >= 0L)
        assertTrue((timings?.tapToPlayingMs ?: 0L) >= 0L)
    }
}
