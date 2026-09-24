package com.notify.download.stream

import android.util.Base64
import com.notify.core.model.ResolvedStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JioSaavnAndSoundCloudResolverTest {

    private fun encryptDes(plaintext: String, key: String): String {
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    @Test
    fun jioSaavnResolver_decryptsWithVerifiedKey_andUpgradesQuality() = runTest {
        val originalUrl = "https://aac.saavncdn.com/123/tum_hi_ho_96.mp4"
        val verifiedKey = "38346591"
        val encryptedBase64 = encryptDes(originalUrl, verifiedKey)

        val mockResponseBody = """
            {
                "results": [
                    {
                        "id": "song123",
                        "title": "Tum Hi Ho",
                        "more_info": {
                            "encrypted_media_url": "$encryptedBase64"
                        }
                    }
                ]
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

        assertTrue("Resolution should succeed with verified DES key", result.isSuccess)
        val stream = result.getOrThrow()
        // Optimistic _320.mp4 served as primary stream (no HEAD probe delay)
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_320.mp4", stream.streamUrl)
        assertEquals("jiosaavn_aac_320", stream.formatId)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals(320_000L, stream.bitrate)
        // Fallback chain must be populated for transparent quality downgrade at playback time
        assertEquals(2, stream.fallbackUrls.size)
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_160.mp4", stream.fallbackUrls[0])
        assertEquals("https://aac.saavncdn.com/123/tum_hi_ho_96.mp4", stream.fallbackUrls[1])
    }

    @Test
    fun jioSaavnResolver_decryptsLiveApiCiphertextSuccessfully() = runTest {
        // Real ciphertext fetched live from JioSaavn API for Tu Hi Haqeeqat / Tum Mile
        val liveCiphertext = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyAvMrPEPyQyWwlVQjO4YDp/XUM/7KUEWWko3JLhCpqkEc8eg7xJ625Bw7tS9a8Gtq"

        val mockResponseBody = """
            {
                "results": [
                    {
                        "id": "WiLFvhYp",
                        "title": "Tu Hi Haqeeqat",
                        "more_info": {
                            "encrypted_media_url": "$liveCiphertext"
                        }
                    }
                ]
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
        val result = resolver.resolveStream("Tu Hi Haqeeqat Pritam", "vid_live")

        assertTrue("Live JioSaavn ciphertext should be decrypted successfully with key 38346591", result.isSuccess)
        val stream = result.getOrThrow()
        println("Decrypted stream URL from live JioSaavn: ${stream.streamUrl}")
        assertTrue("Stream URL must be an aac.saavncdn.com URL", stream.streamUrl.contains("saavncdn.com"))
        assertTrue("Stream URL must be .mp4", stream.streamUrl.endsWith(".mp4"))
    }

    @Test
    fun soundCloudResolver_autoRetriesOn401WithFreshClientId() = runTest {
        val callCount = AtomicInteger(0)

        val soundCloudHtml = """
            <html>
                <script src="https://a-v2.sndcdn.com/assets/0-abcdef.js"></script>
            </html>
        """.trimIndent()

        val soundCloudJs = """
            window.__sc_version="12345";
            client_id="fresh_test_client_id_0123456789";
        """.trimIndent()

        val searchSuccessJson = """
            {
                "collection": [
                    {
                        "id": 999,
                        "title": "Test Track",
                        "media": {
                            "transcodings": [
                                {
                                    "url": "https://api-v2.soundcloud.com/media/transcoding/123",
                                    "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val transcodingSuccessJson = """
            {
                "url": "https://cf-media.sndcdn.com/stream/actual_audio.mp3"
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val currentAttempt = callCount.incrementAndGet()

                when {
                    url.startsWith("https://soundcloud.com") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(soundCloudHtml.toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    url.contains("0-abcdef.js") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(soundCloudJs.toResponseBody("application/javascript".toMediaType()))
                            .build()
                    }
                    url.contains("/search/tracks") -> {
                        // First attempt returns 401 Unauthorized to trigger retry
                        if (currentAttempt <= 2) {
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(401)
                                .message("Unauthorized")
                                .body("{}".toResponseBody("application/json".toMediaType()))
                                .build()
                        } else {
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(searchSuccessJson.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                    }
                    url.contains("/media/transcoding") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(transcodingSuccessJson.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            })
            .build()

        val resolver = SoundCloudStreamResolver(client = mockClient, timeoutMs = 3000L)
        val result = resolver.resolveStream("Blinding Lights The Weeknd", "vid2")

        assertTrue("SoundCloud resolution should recover after 401 via auto-retry", result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://cf-media.sndcdn.com/stream/actual_audio.mp3", stream.streamUrl)
        assertEquals("soundcloud_mp3_progressive", stream.formatId)
    }
}
