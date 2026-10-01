package com.notify.download.stream

import android.content.Context
import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A curated artist section containing artist metadata and top tracks.
 *
 * @property artistId Unique JioSaavn artist identifier
 * @property name     Artist display name
 * @property imageUrl High-res artist profile artwork URL
 * @property tracks   List of popular tracks by this artist (normalized as [TrendingTrack]s)
 */
data class CuratedArtistSection(
    val artistId: String,
    val name: String,
    val imageUrl: String,
    val tracks: List<TrendingTrack>
)

/**
 * Repository responsible for fetching and caching curated artist sections for the Home screen.
 *
 * Caching Policy:
 * - Per-artist caching in SharedPreferences keyed by artistId.
 * - Cache freshness TTL is 8 hours (within the 6–12 hour window).
 * - Fresh cache is returned instantly with 0ms network latency.
 * - When a user adds an artist, only that artist is fetched incrementally.
 * - When a user removes an artist, the section is removed immediately from the UI with zero network calls.
 * - On network errors, stale cache is returned as a fallback if present.
 */
class CuratedArtistsRepository(private val context: Context) {

    companion object {
        private const val TAG = "CuratedArtistsRepo"
        private const val PREFS_NAME = "notify_curated_artists_cache"
        private const val KEY_JSON = "curated_artists_json"
        private const val KEY_FETCHED_AT = "curated_artists_fetched_at"
        private const val KEY_ARTIST_PREFIX = "artist_sec_"
        private const val KEY_ARTIST_TIME_PREFIX = "artist_time_"

        /** 8-hour client-side cache TTL. */
        const val CACHE_TTL_MS = 8L * 60 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Searches for artists on JioSaavn for the "Edit Artists" bottom sheet.
     *
     * @param query Search term (e.g. "Arijit", "Coldplay", "Diljit")
     * @return List of matching [FollowedArtist]s with clean names and 500x500 artwork URLs.
     */
    suspend fun searchArtists(query: String): List<FollowedArtist> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getArtistResults&q=$encoded&_format=json&_marker=0&ctx=web6dot0&api_version=4&n=10"
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "ARTIST_SEARCH_HTTP_FAIL code=${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()

            val list = mutableListOf<FollowedArtist>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val id = item.optString("id")
                val rawName = item.optString("name")
                val cleanName = cleanHtml(rawName)
                val rawImg = item.optString("image")
                val cleanImg = rawImg.replace("150x150", "500x500").replace("50x50", "500x500")

                if (id.isNotBlank() && cleanName.isNotBlank()) {
                    list.add(FollowedArtist(id = id, name = cleanName, imageUrl = cleanImg))
                }
            }
            list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "ARTIST_SEARCH_EXCEPTION: ${e.message}")
            emptyList()
        }
    }

    /**
     * Retrieves curated artist sections for the given list of [followedArtists]:
     * 1. Checks per-artist local cache. If all requested artists have fresh cached data (< 8h),
     *    returns them immediately with 0 network calls.
     * 2. If any artist is missing or expired, requests missing artists from Supabase `/curated-artists`.
     * 3. Saves newly fetched sections to per-artist cache.
     * 4. Assembles and returns all sections in the exact order of [followedArtists].
     */
    suspend fun getCuratedArtists(
        followedArtists: List<FollowedArtist>,
        forceRefresh: Boolean = false
    ): Result<List<CuratedArtistSection>> = withContext(Dispatchers.IO) {
        if (followedArtists.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val cachedMap = mutableMapOf<String, CuratedArtistSection>()
        val missingArtists = mutableListOf<FollowedArtist>()

        val legacyJson = prefs.getString(KEY_JSON, null)
        val legacyFetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        val legacyFresh = !forceRefresh && (System.currentTimeMillis() - legacyFetchedAt < CACHE_TTL_MS)
        val legacySections = if (legacyFresh && legacyJson != null) {
            try { parseJson(legacyJson).associateBy { it.artistId } } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        for (artist in followedArtists) {
            val section = getCachedArtistSection(artist.id) ?: legacySections[artist.id]
            val fetchedAt = prefs.getLong(KEY_ARTIST_TIME_PREFIX + artist.id, legacyFetchedAt)
            val isFresh = !forceRefresh && (System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS)

            if (section != null && isFresh) {
                cachedMap[artist.id] = section
            } else {
                missingArtists.add(artist)
            }
        }

        // All artists found in cache and fresh -> zero network calls!
        if (missingArtists.isEmpty()) {
            Log.d(TAG, "CURATED_ARTISTS_ALL_CACHE_HIT count=${followedArtists.size}")
            val assembled = followedArtists.mapNotNull { cachedMap[it.id] }
            return@withContext Result.success(assembled)
        }

        Log.i(TAG, "CURATED_ARTISTS_FETCH missing=${missingArtists.size}/${followedArtists.size}")

        try {
            // Build batched request body for missing artists
            val reqJson = JSONObject()
            val artistsArr = JSONArray()
            missingArtists.forEach { a ->
                val aObj = JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                }
                artistsArr.put(aObj)
            }
            reqJson.put("artists", artistsArr)

            val reqBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url(SupabaseConfig.CURATED_ARTISTS_URL)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(reqBody)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "CURATED_ARTISTS_HTTP_ERROR code=${resp.code}")
                // Fall back to any cached sections we have
                val fallback = followedArtists.mapNotNull { cachedMap[it.id] ?: getCachedArtistSection(it.id) }
                return@withContext if (fallback.isNotEmpty()) Result.success(fallback)
                else Result.failure(Exception("Curated Artists HTTP ${resp.code}"))
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) {
                val sectionsArr = json.optJSONArray("sections") ?: JSONArray()
                val newlyFetched = parseJson(sectionsArr.toString())

                // Store each newly fetched section in per-artist cache
                val editor = prefs.edit()
                val now = System.currentTimeMillis()
                for (s in newlyFetched) {
                    cachedMap[s.artistId] = s
                    editor.putString(KEY_ARTIST_PREFIX + s.artistId, serializeSection(s))
                    editor.putLong(KEY_ARTIST_TIME_PREFIX + s.artistId, now)
                }
                editor.apply()

                // Assemble sections in the exact order of requested followedArtists
                val assembled = followedArtists.mapNotNull { cachedMap[it.id] }
                Log.i(TAG, "CURATED_ARTISTS_OK assembled=${assembled.size}")
                Result.success(assembled)
            } else {
                val err = json.optString("error", "Unknown curated artists error")
                Log.w(TAG, "CURATED_ARTISTS_API_ERROR $err")
                val fallback = followedArtists.mapNotNull { cachedMap[it.id] ?: getCachedArtistSection(it.id) }
                if (fallback.isNotEmpty()) Result.success(fallback)
                else Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "CURATED_ARTISTS_EXCEPTION: ${e.message}")
            val fallback = followedArtists.mapNotNull { cachedMap[it.id] ?: getCachedArtistSection(it.id) }
            if (fallback.isNotEmpty()) Result.success(fallback)
            else Result.failure(e)
        }
    }

    /**
     * Backward-compatible overload for initial / default fetch.
     */
    suspend fun getCuratedArtists(forceRefresh: Boolean = false): Result<List<CuratedArtistSection>> {
        val cachedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        val isFresh = !forceRefresh && (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS)
        val cachedJson = prefs.getString(KEY_JSON, null)
        if (isFresh && cachedJson != null) {
            return Result.success(parseJson(cachedJson))
        }

        val repo = FollowedArtistsRepository(context)
        return getCuratedArtists(repo.getFollowedArtists(), forceRefresh)
    }

    /**
     * Returns cached artist sections synchronously for the given [followedArtists].
     */
    fun getCachedSections(followedArtists: List<FollowedArtist>? = null): List<CuratedArtistSection>? {
        val artists = followedArtists ?: FollowedArtistsRepository(context).getFollowedArtists()
        val sections = artists.mapNotNull { getCachedArtistSection(it.id) }
        if (sections.isNotEmpty()) return sections

        // Fallback to legacy single-blob cache if present
        val legacyJson = prefs.getString(KEY_JSON, null) ?: return null
        return try {
            parseJson(legacyJson).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads a single artist section from local cache.
     */
    private fun getCachedArtistSection(artistId: String): CuratedArtistSection? {
        val json = prefs.getString(KEY_ARTIST_PREFIX + artistId, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val tracksArr = obj.optJSONArray("tracks") ?: JSONArray()
            val tracks = mutableListOf<TrendingTrack>()
            for (j in 0 until tracksArr.length()) {
                val tObj = tracksArr.getJSONObject(j)
                tracks.add(
                    TrendingTrack(
                        title = tObj.optString("title"),
                        artist = tObj.optString("artist"),
                        albumArtUrl = tObj.optString("albumArt"),
                        songId = tObj.optString("songId"),
                        query = tObj.optString("query"),
                        durationMs = tObj.optLong("durationMs", -1L).takeIf { it > 0 },
                        streamHint = tObj.optString("streamHint").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
            CuratedArtistSection(
                artistId = obj.optString("artistId"),
                name = obj.optString("name"),
                imageUrl = obj.optString("image"),
                tracks = tracks
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun serializeSection(s: CuratedArtistSection): String {
        val obj = JSONObject().apply {
            put("artistId", s.artistId)
            put("name", s.name)
            put("image", s.imageUrl)
            val tArr = JSONArray()
            s.tracks.forEach { t ->
                val tObj = JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("albumArt", t.albumArtUrl)
                    put("songId", t.songId)
                    put("query", t.query)
                    t.durationMs?.let { put("durationMs", it) }
                    t.streamHint?.let { put("streamHint", it) }
                }
                tArr.put(tObj)
            }
            put("tracks", tArr)
        }
        return obj.toString()
    }

    private fun parseJson(jsonStr: String): List<CuratedArtistSection> {
        val arr = JSONArray(jsonStr)
        val sections = mutableListOf<CuratedArtistSection>()
        for (i in 0 until arr.length()) {
            val sObj = arr.getJSONObject(i)
            val tracksArr = sObj.optJSONArray("tracks") ?: JSONArray()
            val tracks = mutableListOf<TrendingTrack>()
            for (j in 0 until tracksArr.length()) {
                val tObj = tracksArr.getJSONObject(j)
                tracks.add(
                    TrendingTrack(
                        title = tObj.optString("title"),
                        artist = tObj.optString("artist"),
                        albumArtUrl = tObj.optString("albumArt"),
                        songId = tObj.optString("songId"),
                        query = tObj.optString("query"),
                        durationMs = tObj.optLong("durationMs", -1L).takeIf { it > 0 },
                        streamHint = tObj.optString("streamHint").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
            sections.add(
                CuratedArtistSection(
                    artistId = sObj.optString("artistId"),
                    name = sObj.optString("name"),
                    imageUrl = sObj.optString("image"),
                    tracks = tracks
                )
            )
        }
        return sections
    }

    private fun cleanHtml(text: String): String {
        return try {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (_: Exception) {
            text.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim()
        }
    }
}
