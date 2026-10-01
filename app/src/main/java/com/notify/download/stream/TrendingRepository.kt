package com.notify.download.stream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A single trending song item returned by the JioSaavn trending edge function.
 *
 * @property title       Cleaned song title
 * @property artist      Primary artist(s)
 * @property albumArtUrl High-res artwork URL (500×500)
 * @property songId      JioSaavn internal song ID (used for stable deduplication)
 * @property query       "Title Artist" search string — used by StreamProviderChain race
 * @property durationMs  Track duration in ms, or null if unavailable
 * @property streamHint  Optional decrypted 320kbps stream URL from JioSaavn CDN (may expire)
 */
data class TrendingTrack(
    val title: String,
    val artist: String,
    val albumArtUrl: String,
    val songId: String,
    val query: String,
    val durationMs: Long?,
    val streamHint: String?
)

/**
 * Fetches and caches trending tracks from the /trending-jiosaavn Supabase Edge Function.
 *
 * Cache policy:
 * - SharedPreferences stores the JSON payload + timestamp.
 * - Fresh threshold: 6 hours (configurable via [CACHE_TTL_MS]).
 * - On fetch failure: surfaces cached data even if stale; returns empty if no cache.
 */
class TrendingRepository(private val context: Context) {

    companion object {
        private const val TAG = "TrendingRepository"
        private const val PREFS_NAME = "notify_trending_cache"
        private const val KEY_JSON = "trending_json"
        private const val KEY_FETCHED_AT = "fetched_at_ms"

        /** 6-hour client-side cache; edge has its own 2-hour cache on top. */
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000L
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns trending tracks. Behaviour:
     * 1. If cache is fresher than [CACHE_TTL_MS] → return cache immediately (no network call).
     * 2. Otherwise → fetch from network; on success update cache; on failure return stale cache or empty.
     */
    suspend fun getTrending(forceRefresh: Boolean = false): Result<List<TrendingTrack>> =
        withContext(Dispatchers.IO) {
            val cachedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
            val isFresh = !forceRefresh && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
            val cachedJson = prefs.getString(KEY_JSON, null)

            if (isFresh && cachedJson != null) {
                Log.d(TAG, "TRENDING_CACHE_HIT age=${(System.currentTimeMillis() - cachedAt) / 1000}s")
                return@withContext Result.success(parseJson(cachedJson))
            }

            // Fetch from network
            return@withContext try {
                val url = SupabaseConfig.TRENDING_JIOSAAVN_URL
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "TRENDING_HTTP_ERROR code=${resp.code}")
                    // Return stale cache if available
                    if (cachedJson != null) Result.success(parseJson(cachedJson))
                    else Result.failure(Exception("Trending HTTP ${resp.code}"))
                } else {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val tracksJson = json.optJSONArray("tracks")?.toString() ?: "[]"
                        prefs.edit()
                            .putString(KEY_JSON, tracksJson)
                            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                            .apply()
                        val tracks = parseJson(tracksJson)
                        Log.i(TAG, "TRENDING_FETCH_OK count=${tracks.size} cached=${json.optBoolean("cached", false)}")
                        Result.success(tracks)
                    } else {
                        val err = json.optString("error", "Unknown trending error")
                        Log.w(TAG, "TRENDING_API_ERROR $err")
                        if (cachedJson != null) Result.success(parseJson(cachedJson))
                        else Result.failure(Exception(err))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "TRENDING_FETCH_EXCEPTION: ${e.message}")
                if (cachedJson != null) Result.success(parseJson(cachedJson))
                else Result.failure(e)
            }
        }

    /** Returns cached tracks synchronously without a network call. Null if cache is empty. */
    fun getCachedTrending(): List<TrendingTrack>? {
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return try { parseJson(json).takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }

    private fun parseJson(jsonArrayStr: String): List<TrendingTrack> {
        val arr = JSONArray(jsonArrayStr)
        val list = mutableListOf<TrendingTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                TrendingTrack(
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    albumArtUrl = obj.optString("albumArt"),
                    songId = obj.optString("songId"),
                    query = obj.optString("query"),
                    durationMs = obj.optLong("durationMs", -1L).takeIf { it > 0 },
                    streamHint = obj.optString("streamHint").takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
        return list
    }
}
