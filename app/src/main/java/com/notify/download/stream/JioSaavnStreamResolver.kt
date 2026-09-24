package com.notify.download.stream

import android.util.Base64
import android.util.Log
import com.notify.core.model.ResolvedStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Lightweight HTTP stream resolver using JioSaavn catalog search.
 *
 * FRAGILITY WARNING:
 * JioSaavn encrypts direct stream URLs using a legacy DES algorithm with a static hardcoded key.
 * This key is marked as LEGACY / FRAGILE: JioSaavn may rotate or deprecate this cipher at any time.
 * No assumptions are made regarding its permanent availability. If decryption fails, this resolver
 * fails gracefully and immediately allows other racers or yt-dlp to fulfill the request.
 */
class JioSaavnStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
    private val timeoutMs: Long = 1500L
) {
    companion object {
        private const val TAG = "JioSaavnResolver"
        private const val SEARCH_ENDPOINT = "https://www.jiosaavn.com/api.php"

        // LEGACY / FRAGILE: Static DES key used by JioSaavn unofficial endpoints
        // Verified source: https://github.com/sumitkolhe/jiosaavn-api/blob/main/src/common/helpers/link.helper.ts
        // Also documented in cyberboysumanjay/JioSaavnAPI (helper.py) and sagarithm.in
        private const val LEGACY_DES_KEY = "38346591"
        private const val CIPHER_TRANSFORMATION = "DES/ECB/PKCS5Padding"

        // Ordered quality variants: highest first. Suffix swap confirmed via live HEAD probes
        // on aac.saavncdn.com CDN — all three variants exist and serve different file sizes.
        // Source: real decrypted URLs from JioSaavn search API (Sep 2026).
        private val QUALITY_VARIANTS = listOf(
            Triple("_320.mp4", 320_000L, "jiosaavn_aac_320"),
            Triple("_160.mp4", 160_000L, "jiosaavn_aac_160"),
            Triple("_96.mp4",  96_000L,  "jiosaavn_aac_96")
        )
    }

    suspend fun resolveStream(
        query: String,
        videoId: String? = null
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty query for JioSaavn"))
        }

        val start = System.currentTimeMillis()
        Log.i(TAG, "JIOSAAVN_ATTEMPT_START query=\"$trimmedQuery\"")

        try {
            withTimeout(timeoutMs) {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val url = "$SEARCH_ENDPOINT?__call=search.getResults&_format=json&n=3&p=1&q=$encodedQuery&_marker=0&api_version=4&ctx=web6dot0"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful || body.isBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "JIOSAAVN_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=http_${response.code}")
                    return@withTimeout Result.failure(IllegalStateException("JioSaavn HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results == null || results.length() == 0) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "JIOSAAVN_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=no_results")
                    return@withTimeout Result.failure(NoSuchElementException("No songs found on JioSaavn for: $trimmedQuery"))
                }

                var directStreamUrl: String? = null
                var fallbackStreamUrls: List<String> = emptyList()
                var selectedBitrate: Long = 320_000L
                var selectedFormatId: String = "jiosaavn_aac_320"

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val moreInfo = item.optJSONObject("more_info") ?: continue
                    val encUrl = moreInfo.optString("encrypted_media_url")
                    if (encUrl.isNotBlank()) {
                        val decrypted = decryptMediaUrl(encUrl)
                        if (!decrypted.isNullOrBlank() && decrypted.startsWith("http")) {
                            // Detect existing quality suffix in the decrypted URL.
                            // JioSaavn API always returns _96.mp4 as base quality,
                            // but CDN confirms _320.mp4 and _160.mp4 also exist for virtually all tracks.
                            // STRATEGY: Optimistic upgrade — return _320.mp4 immediately (no HEAD probe delay).
                            // ExoPlayer will transparently retry with fallbackUrls if _320 CDN returns 404.
                            val currentSuffix = QUALITY_VARIANTS.map { it.first }
                                .firstOrNull { decrypted.contains(it) }

                            if (currentSuffix != null) {
                                val best = QUALITY_VARIANTS[0] // _320.mp4
                                directStreamUrl = decrypted.replace(currentSuffix, best.first)
                                selectedBitrate = best.second
                                selectedFormatId = best.third
                                // Populate fallback chain (skip the optimistic one already selected)
                                fallbackStreamUrls = QUALITY_VARIANTS.drop(1)
                                    .map { (suffix, _, _) -> decrypted.replace(currentSuffix, suffix) }
                            } else {
                                // URL doesn't match known patterns — use as-is, no fallback
                                directStreamUrl = decrypted
                            }
                            break
                        }
                    }
                    // Fallback to media_preview_url if encrypted_media_url failed
                    val previewUrl = moreInfo.optString("media_preview_url")
                    if (directStreamUrl == null && previewUrl.isNotBlank() && previewUrl.startsWith("http")) {
                        directStreamUrl = previewUrl.replace("preview.saavncdn.com", "aac.saavncdn.com")
                            .replace("_96_p.mp4", "_320.mp4")
                    }
                }

                if (directStreamUrl.isNullOrBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    Log.w(TAG, "JIOSAAVN_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed reason=decryption_or_url_missing")
                    return@withTimeout Result.failure(IllegalStateException("Could not resolve/decrypt JioSaavn media URL"))
                }

                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "JIOSAAVN_ATTEMPT_COMPLETE outcome=SUCCESS elapsedMs=$elapsed format=$selectedFormatId fallbackCount=${fallbackStreamUrls.size}")

                val resolvedStream = ResolvedStream(
                    streamUrl = directStreamUrl,
                    formatId = selectedFormatId,
                    mimeType = "audio/mp4",
                    container = "m4a",
                    bitrate = selectedBitrate,
                    expiresAtEpochMs = System.currentTimeMillis() + 86400_000L, // 24 hour CDN link
                    videoId = videoId,
                    fallbackUrls = fallbackStreamUrls
                )
                Result.success(resolvedStream)
            }
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            Log.w(TAG, "JIOSAAVN_ATTEMPT_COMPLETE outcome=FAILED elapsedMs=$elapsed error=${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Decrypts the DES encrypted media URL with the legacy key.
     * Marked fragile: wraps all errors and returns null on any decryption discrepancy.
     */
    private fun decryptMediaUrl(encryptedBase64: String): String? {
        return try {
            val cleaned = encryptedBase64.trim().replace('.', '+')
            val keySpec = SecretKeySpec(LEGACY_DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(cleaned, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val decryptedUrl = String(decryptedBytes, Charsets.UTF_8).trim()
            if (decryptedUrl.startsWith("http://") || decryptedUrl.startsWith("https://")) {
                decryptedUrl
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.d(TAG, "JioSaavn legacy DES decryption failed: ${e.message}")
            null
        }
    }
}
