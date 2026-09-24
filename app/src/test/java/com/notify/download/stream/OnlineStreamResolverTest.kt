package com.notify.download.stream

import android.content.Context
import com.notify.download.spike.YtDlpRuntime
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlineStreamResolverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        YtDlpRuntime.setReadyForTest("2026.02.01")
    }

    @After
    fun tearDown() {
        YtDlpRuntime.resetForTest()
    }

    @Test
    fun testFormatSpecificationIsPrioritized() = runTest {
        val executedRequests = mutableListOf<YoutubeDLRequest>()
        val resolver = OnlineStreamResolver(context) { request ->
            executedRequests.add(request)
            YoutubeDLResponse(emptyList(), 0, 150L, "https://googlevideo.com/videoplayback?expire=1735689600", "")
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")
        assertTrue("Expected success", result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)

        assertEquals(
            "Format spec must prioritize m4a, then any pure audio, then best",
            "bestaudio[ext=m4a]/bestaudio/best",
            OnlineStreamResolver.FORMAT_SPEC
        )

        assertEquals(1, executedRequests.size)
        val cmd = executedRequests[0].buildCommand()
        val formatIdx = cmd.indexOf("-f")
        assertTrue("Command must contain -f", formatIdx != -1 && formatIdx + 1 < cmd.size)
        assertEquals("bestaudio[ext=m4a]/bestaudio/best", cmd[formatIdx + 1])
    }

    @Test
    fun testPrimaryClientProfileUsesPoTokenFreeClients() = runTest {
        val executedRequests = mutableListOf<YoutubeDLRequest>()
        val resolver = OnlineStreamResolver(context) { request ->
            executedRequests.add(request)
            YoutubeDLResponse(emptyList(), 0, 150L, "https://googlevideo.com/videoplayback?expire=1735689600", "")
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")
        assertTrue(result.isSuccess)

        val cmd = executedRequests[0].buildCommand()
        val extractorArgsIdx = cmd.indexOf("--extractor-args")
        assertTrue("Command must contain --extractor-args", extractorArgsIdx != -1 && extractorArgsIdx + 1 < cmd.size)
        assertEquals("youtube:player_client=tv_embedded,visionos", cmd[extractorArgsIdx + 1])
    }

    @Test
    fun testFallbackToSecondaryClientWhenPrimaryThrowsFormatUnavailable() = runTest {
        val executedRequests = mutableListOf<YoutubeDLRequest>()
        var attemptCount = 0

        val resolver = OnlineStreamResolver(context) { request ->
            executedRequests.add(request)
            attemptCount++
            if (attemptCount == 1) {
                // Primary profile (tv_embedded,visionos) fails with YouTube's format unavailable error
                throw YoutubeDLException("ERROR: [youtube] uAoUXiCFtfw: Requested format is not available")
            } else {
                // Secondary profile succeeds
                YoutubeDLResponse(emptyList(), 0, 200L, "https://googlevideo.com/videoplayback?expire=1735689600&id=fallback", "")
            }
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")
        assertTrue("Resolution should succeed via fallback client", result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertEquals("https://googlevideo.com/videoplayback?expire=1735689600&id=fallback", stream?.streamUrl)

        // Verify that 2 attempts were executed
        assertEquals(2, executedRequests.size)

        // Verify primary was tv_embedded,visionos
        val cmd1 = executedRequests[0].buildCommand()
        val extractorArgsIdx1 = cmd1.indexOf("--extractor-args")
        assertEquals("youtube:player_client=tv_embedded,visionos", cmd1[extractorArgsIdx1 + 1])

        // Verify secondary was android
        val cmd2 = executedRequests[1].buildCommand()
        val extractorArgsIdx2 = cmd2.indexOf("--extractor-args")
        assertEquals("youtube:player_client=android", cmd2[extractorArgsIdx2 + 1])
    }

    @Test
    fun testSpotifyUrlsBlockedImmediately() = runTest {
        var executorCalled = false
        val resolver = OnlineStreamResolver(context) { _ ->
            executorCalled = true
            YoutubeDLResponse(emptyList(), 0, 50L, "https://googlevideo.com/videoplayback", "")
        }

        val result = resolver.resolveStream("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertTrue("Spotify URL should immediately fail", result.isFailure)
        assertTrue("Should not call yt-dlp", !executorCalled)
    }

    @Test
    fun testAllClientProfilesFailReturnsDescriptiveFailure() = runTest {
        val executedRequests = mutableListOf<YoutubeDLRequest>()
        val resolver = OnlineStreamResolver(context) { request ->
            executedRequests.add(request)
            throw YoutubeDLException("ERROR: [youtube] Requested format is not available")
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")
        assertTrue("Should fail when all client profiles fail", result.isFailure)
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue("Error message should mention failure details", err?.message?.contains("yt-dlp") == true)
        assertTrue("Should have tried all client profiles", executedRequests.size >= OnlineStreamResolver.CLIENT_PROFILES.size)
    }

    @Test
    fun testExpireQueryParameterParsed() = runTest {
        val resolver = OnlineStreamResolver(context) { _ ->
            YoutubeDLResponse(emptyList(), 0, 100L, "https://googlevideo.com/videoplayback?expire=1735689600&sparams=expire", "")
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")
        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertEquals(1735689600000L, stream?.expiresAtEpochMs)
    }

    @Test
    fun testPermanentVideoErrorAbortsRemainingClientProfilesImmediately() = runTest {
        val executedRequests = mutableListOf<YoutubeDLRequest>()
        val resolver = OnlineStreamResolver(context) { request ->
            executedRequests.add(request)
            throw YoutubeDLException("ERROR: [youtube] gykAMGrwTHs: Video unavailable. This video is not available on this country domain due to a legal complaint")
        }

        val result = resolver.resolveStream("https://www.youtube.com/watch?v=gykAMGrwTHs")
        assertTrue("Permanent video error must fail fast", result.isFailure)
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue("Must indicate video is unavailable in region", err?.message?.contains("unplayable in your region") == true)
        // Must have aborted immediately on the first attempt without wasting attempts on the other 3 profiles
        assertEquals("Must abort after 1 attempt on permanent error", 1, executedRequests.size)
    }
}
