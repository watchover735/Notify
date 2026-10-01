package com.notify.download.stream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a musical artist followed by the user on the Home screen.
 *
 * @property id Unique JioSaavn artist ID (e.g. "485956")
 * @property name Display name of the artist
 * @property imageUrl High-res profile image URL
 */
data class FollowedArtist(
    val id: String,
    val name: String,
    val imageUrl: String = ""
)

/**
 * Persists and manages the user's followed/favorite artists for the Home screen.
 *
 * Requirements:
 * - Persisted locally across app sessions using SharedPreferences.
 * - Seeded on first launch with 5 default popular artists:
 *   Yo Yo Honey Singh, Diljit Dosanjh, Arijit Singh, Karan Aujla, AP Dhillon.
 * - Maximum limit: 10 followed artists.
 * - Exposes a reactive [StateFlow] of followed artists.
 */
class FollowedArtistsRepository(context: Context) {

    companion object {
        private const val TAG = "FollowedArtistsRepo"
        private const val PREFS_NAME = "notify_followed_artists_prefs"
        private const val KEY_ARTISTS_JSON = "followed_artists_json"
        private const val KEY_INITIALIZED = "followed_artists_initialized"

        /** Maximum number of artists a user can follow. */
        const val MAX_FOLLOWED_ARTISTS = 10

        /** Default 5 seeded artists on first launch. */
        val DEFAULT_SEEDED_ARTISTS = listOf(
            FollowedArtist(
                id = "485956",
                name = "Yo Yo Honey Singh",
                imageUrl = "https://c.saavncdn.com/artists/Yo_Yo_Honey_Singh_002_20221118070908_500x500.jpg"
            ),
            FollowedArtist(
                id = "468245",
                name = "Diljit Dosanjh",
                imageUrl = "https://c.saavncdn.com/artists/Diljit_Dosanjh_004_20221118070924_500x500.jpg"
            ),
            FollowedArtist(
                id = "459320",
                name = "Arijit Singh",
                imageUrl = "https://c.saavncdn.com/artists/Arijit_Singh_002_20230323062147_500x500.jpg"
            ),
            FollowedArtist(
                id = "697691",
                name = "Karan Aujla",
                imageUrl = "https://c.saavncdn.com/artists/Karan_Aujla_004_20230628084503_500x500.jpg"
            ),
            FollowedArtist(
                id = "681966",
                name = "AP Dhillon",
                imageUrl = "https://c.saavncdn.com/artists/AP_Dhillon_000_20221207074719_500x500.jpg"
            )
        )
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _followedArtists = MutableStateFlow<List<FollowedArtist>>(emptyList())
    val followedArtists: StateFlow<List<FollowedArtist>> = _followedArtists.asStateFlow()

    init {
        loadInitialArtists()
    }

    private fun loadInitialArtists() {
        val isInitialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if (!isInitialized) {
            // First launch seed
            saveArtistsInternal(DEFAULT_SEEDED_ARTISTS)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            _followedArtists.value = DEFAULT_SEEDED_ARTISTS
            Log.i(TAG, "Seeded default ${DEFAULT_SEEDED_ARTISTS.size} followed artists")
        } else {
            val json = prefs.getString(KEY_ARTISTS_JSON, null)
            val list = if (json.isNullOrBlank()) {
                emptyList()
            } else {
                parseJson(json)
            }
            _followedArtists.value = list
            Log.d(TAG, "Loaded ${list.size} followed artists from storage")
        }
    }

    /** Returns current list of followed artists. */
    fun getFollowedArtists(): List<FollowedArtist> = _followedArtists.value

    /**
     * Follows an artist.
     * @return true if added successfully, false if already followed or max limit reached.
     */
    @Synchronized
    fun followArtist(artist: FollowedArtist): Boolean {
        val current = _followedArtists.value.toMutableList()
        if (current.any { it.id == artist.id }) {
            return false // Already followed
        }
        if (current.size >= MAX_FOLLOWED_ARTISTS) {
            return false // Limit reached
        }
        current.add(artist)
        saveArtistsInternal(current)
        _followedArtists.value = current
        Log.i(TAG, "Followed artist: ${artist.name} (id=${artist.id}, total=${current.size})")
        return true
    }

    /**
     * Unfollows an artist by [artistId].
     * @return Pair of the removed [FollowedArtist] and its previous index, or null if not found.
     */
    @Synchronized
    fun unfollowArtist(artistId: String): Pair<FollowedArtist, Int>? {
        val current = _followedArtists.value.toMutableList()
        val index = current.indexOfFirst { it.id == artistId }
        if (index == -1) return null

        val removed = current.removeAt(index)
        saveArtistsInternal(current)
        _followedArtists.value = current
        Log.i(TAG, "Unfollowed artist: ${removed.name} (id=$artistId, remaining=${current.size})")
        return Pair(removed, index)
    }

    /**
     * Restores a previously unfollowed artist at [index] (for Undo).
     */
    @Synchronized
    fun restoreArtist(artist: FollowedArtist, index: Int): Boolean {
        val current = _followedArtists.value.toMutableList()
        if (current.any { it.id == artist.id }) return false
        if (current.size >= MAX_FOLLOWED_ARTISTS) return false

        val targetIndex = index.coerceIn(0, current.size)
        current.add(targetIndex, artist)
        saveArtistsInternal(current)
        _followedArtists.value = current
        Log.i(TAG, "Restored artist: ${artist.name} at index $targetIndex")
        return true
    }

    private fun saveArtistsInternal(artists: List<FollowedArtist>) {
        val arr = JSONArray()
        artists.forEach { a ->
            val obj = JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("imageUrl", a.imageUrl)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ARTISTS_JSON, arr.toString()).apply()
    }

    private fun parseJson(jsonStr: String): List<FollowedArtist> {
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<FollowedArtist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FollowedArtist(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        imageUrl = obj.optString("imageUrl")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing followed artists JSON: ${e.message}")
            emptyList()
        }
    }
}
