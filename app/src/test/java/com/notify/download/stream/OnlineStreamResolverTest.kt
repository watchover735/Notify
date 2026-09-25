package com.notify.download.stream

import android.content.Context
import com.notify.download.spike.YtDlpRuntime
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private fun createMockClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                handler(chain.request())
            })
            .build()
    }

    private fun jsonResponse(request: okhttp3.Request, json: String, code: Int = 200): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun testFormatSpecificationIsPrioritized() {
        assertEquals(
            "Format spec must prioritize m4a, then any pure audio, then best",
            "bestaudio[ext=m4a]/bestaudio/best",
            OnlineStreamResolver.FORMAT_SPEC
        )
    }

    @Test
    fun testPrimaryClientProfileUsesPoTokenFreeClients() {
        assertTrue(
            OnlineStreamResolver.CLIENT_PROFILES.contains("youtube:player_client=tv_embedded,visionos")
        )
    }

    @Test
    fun testSuccessfulRemoteResolution() = runTest {
        var callCount = 0
        val client = createMockClient { req ->
            callCount++
            jsonResponse(
                req,
                """
                {
                    "success": true,
                    "streamUrl": "https://googlevideo.com/videoplayback?expire=1735689600&id=test",
                    "formatId": "140",
                    "format": "ytdlp_m4a_140",
                    "container": "m4a",
                    "mimeType": "audio/mp4",
                    "bitrate": 128,
                    "expiresAtEpochMs": 1735689600000
                }
                """.trimIndent()
            )
        }

        val resolver = OnlineStreamResolver(context, endpointUrl = "http://mock-server/resolve-ytdlp", httpClient = client)
        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")

        assertTrue("Expected successful stream resolution", result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertEquals("https://googlevideo.com/videoplayback?expire=1735689600&id=test", stream?.streamUrl)
        assertEquals("140", stream?.formatId)
        assertEquals("audio/mp4", stream?.mimeType)
        assertEquals("m4a", stream?.container)
        assertEquals(1735689600000L, stream?.expiresAtEpochMs)
        assertEquals(1, callCount)
    }

    @Test
    fun testSpotifyUrlsBlockedImmediately() = runTest {
        var networkCalled = false
        val client = createMockClient { req ->
            networkCalled = true
            jsonResponse(req, """{"success": false}""")
        }

        val resolver = OnlineStreamResolver(context, endpointUrl = "http://mock-server/resolve-ytdlp", httpClient = client)
        val result = resolver.resolveStream("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")

        assertTrue("Spotify URL must immediately fail", result.isFailure)
        assertTrue("Network must not be called for Spotify URLs", !networkCalled)
    }

    @Test
    fun testFailedRemoteResolutionReturnsDescriptiveFailure() = runTest {
        val client = createMockClient { req ->
            jsonResponse(
                req,
                """{"success": false, "error": "Requested format is not available"}"""
            )
        }

        val resolver = OnlineStreamResolver(context, endpointUrl = "http://mock-server/resolve-ytdlp", httpClient = client)
        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")

        assertTrue("Should fail when remote resolver returns error", result.isFailure)
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            "Error message should mention stream resolution failure: ${err?.message}",
            err?.message?.contains("yt-dlp stream resolution failed") == true
        )
    }

    @Test
    fun testExpireQueryParameterParsed() = runTest {
        val client = createMockClient { req ->
            jsonResponse(
                req,
                """
                {
                    "success": true,
                    "streamUrl": "https://googlevideo.com/videoplayback?expire=1735689600&sparams=expire",
                    "formatId": "140",
                    "container": "m4a",
                    "mimeType": "audio/mp4"
                }
                """.trimIndent()
            )
        }

        val resolver = OnlineStreamResolver(context, endpointUrl = "http://mock-server/resolve-ytdlp", httpClient = client)
        val result = resolver.resolveStream("https://www.youtube.com/watch?v=uAoUXiCFtfw")

        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertEquals(1735689600000L, stream?.expiresAtEpochMs)
    }

    @Test
    fun testPermanentVideoErrorAbortsWithRegionMessage() = runTest {
        val client = createMockClient { req ->
            jsonResponse(
                req,
                """
                {
                    "success": false,
                    "error": "gykAMGrwTHs: Video unavailable. This video is not available on this country domain due to a legal complaint"
                }
                """.trimIndent()
            )
        }

        val resolver = OnlineStreamResolver(context, endpointUrl = "http://mock-server/resolve-ytdlp", httpClient = client)
        val result = resolver.resolveStream("https://www.youtube.com/watch?v=gykAMGrwTHs")

        assertTrue("Permanent video error must fail", result.isFailure)
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            "Must indicate video is unavailable in region: ${err?.message}",
            err?.message?.contains("unplayable in your region") == true
        )
    }
}
