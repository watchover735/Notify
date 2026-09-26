package com.notify.download.stream

import com.notify.core.model.ResolvedStream
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JioSaavnAndSoundCloudResolverTest {

    @org.junit.Before
    fun setUp() {
        CobaltStreamResolver.resetDemotionForTest()
    }

    @Test
    fun jioSaavnResolver_parsesSupabaseEdgeResponse_andPopulatesFallbacks() = runTest {
        val mockResponseBody = """
            {
                "success": true,
                "provider": "jiosaavn",
                "streamUrl": "https://aac.saavncdn.com/123/tum_hi_ho_320.mp4",
                "formatId": "jiosaavn_aac_320",
                "mimeType": "audio/mp4",
                "container": "m4a",
                "bitrate": 320000,
                "expiresAtEpochMs": 1790432264564,
                "durationMs": 268000,
                "fallbackUrls": [
                    "https://aac.saavncdn.com/123/tum_hi_ho_160.mp4",
                    "https://aac.saavncdn.com/123/tum_hi_ho_96.mp4"
                ],
                "videoId": "vid1"
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val resolver = JioSaavnStreamResolver(client = mockClient, timeoutMs = 2000L)
        val result = resolver.resolveStream("Tum Hi Ho Arijit Singh", "vid1")

        assertTrue("Resolution should succeed with Supabase Edge response", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_320.mp4", stream.streamUrl)
        assertEquals("jiosaavn_aac_320", stream.formatId)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals(320_000L, stream.bitrate)
        assertEquals(268_000L, stream.durationMs)
        assertEquals("vid1", stream.videoId)
        assertEquals(2, stream.fallbackUrls.size)
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_160.mp4", stream.fallbackUrls[0])
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_96.mp4", stream.fallbackUrls[1])
    }

    @Test
    fun soundCloudResolver_parsesSupabaseEdgeResponse() = runTest {
        val mockResponseBody = """
            {
                "success": true,
                "provider": "soundcloud",
                "streamUrl": "https://cf-media.sndcdn.com/stream/actual_audio.mp3",
                "formatId": "soundcloud_mp3_progressive",
                "mimeType": "audio/mpeg",
                "container": "mp3",
                "bitrate": 128000,
                "durationMs": 200000,
                "expiresAtEpochMs": 1790432264564,
                "videoId": "vid2"
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val resolver = SoundCloudStreamResolver(client = mockClient, timeoutMs = 2000L)
        val result = resolver.resolveStream("Blinding Lights The Weeknd", "vid2")

        assertTrue("SoundCloud resolution should succeed with Supabase Edge response", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://cf-media.sndcdn.com/stream/actual_audio.mp3", stream.streamUrl)
        assertEquals("soundcloud_mp3_progressive", stream.formatId)
        assertEquals("audio/mpeg", stream.mimeType)
        assertEquals(128_000L, stream.bitrate)
        assertEquals(200_000L, stream.durationMs)
        assertEquals("vid2", stream.videoId)
    }

    @Test
    fun deezerResolver_parsesSupabaseEdgeResponse() = runTest {
        val mockResponseBody = """
            {
                "success": true,
                "provider": "deezer",
                "streamUrl": "https://cdns-preview-d.dzcdn.net/stream/c-d.mp3",
                "formatId": "deezer_preview_mp3_128",
                "mimeType": "audio/mpeg",
                "container": "mp3",
                "bitrate": 128000,
                "durationMs": 30000,
                "expiresAtEpochMs": 1790432264564,
                "videoId": "vid3"
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val resolver = DeezerStreamResolver(client = mockClient, timeoutMs = 2000L)
        val result = resolver.resolveStream("Coldplay Viva La Vida", "vid3")

        assertTrue("Deezer resolution should succeed with Supabase Edge response", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://cdns-preview-d.dzcdn.net/stream/c-d.mp3", stream.streamUrl)
        assertEquals("deezer_preview_mp3_128", stream.formatId)
        assertEquals(30_000L, stream.durationMs)
    }

    @Test
    fun cobaltResolver_parsesSupabaseEdgeResponse() = runTest {
        val mockResponseBody = """
            {
                "success": true,
                "provider": "cobalt",
                "streamUrl": "https://stream.cobalt.tools/audio.m4a",
                "formatId": "cobalt_audio",
                "mimeType": "audio/mp4",
                "container": "m4a",
                "expiresAtEpochMs": 1790432264564,
                "videoId": "vid4"
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val resolver = CobaltStreamResolver(client = mockClient, timeoutMs = 2000L)
        val result = resolver.resolveStream("https://www.youtube.com/watch?v=vid4", "vid4")

        assertTrue("Cobalt resolution should succeed with Supabase Edge response", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://stream.cobalt.tools/audio.m4a", stream.streamUrl)
        assertEquals("cobalt_audio", stream.formatId)
    }
}
