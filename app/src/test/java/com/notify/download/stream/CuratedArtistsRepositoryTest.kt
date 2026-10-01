package com.notify.download.stream

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CuratedArtistsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clear prefs before each test
        context.getSharedPreferences("notify_curated_artists_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun curatedArtistsConfig_containsAllSpecifiedPopularArtists() {
        val names = CuratedArtistsConfig.ARTISTS.map { it.name }
        assertTrue("Contains Honey Singh", names.contains("Yo Yo Honey Singh"))
        assertTrue("Contains Diljit Dosanjh", names.contains("Diljit Dosanjh"))
        assertTrue("Contains Arijit Singh", names.contains("Arijit Singh"))
        assertTrue("Contains Karan Aujla", names.contains("Karan Aujla"))
        assertTrue("Contains AP Dhillon", names.contains("AP Dhillon"))
        assertTrue("Contains Badshah", names.contains("Badshah"))
        assertTrue("Contains Dhanda Nyoliwala", names.contains("Dhanda Nyoliwala"))
    }

    @Test
    fun cachedSections_whenCachePresent_returnsParsedSections() {
        val jsonPayload = """
            [
                {
                    "artistId": "485956",
                    "name": "Yo Yo Honey Singh",
                    "image": "https://c.saavncdn.com/artists/Yo_Yo_Honey_Singh_500x500.jpg",
                    "tracks": [
                        {
                            "title": "Casa Tupka Anthemo",
                            "artist": "Yo Yo Honey Singh",
                            "albumArt": "https://c.saavncdn.com/art_500x500.jpg",
                            "songId": "47_T2N3p",
                            "query": "Casa Tupka Anthemo Yo Yo Honey Singh",
                            "durationMs": 183000,
                            "streamHint": null
                        }
                    ]
                }
            ]
        """.trimIndent()

        context.getSharedPreferences("notify_curated_artists_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("curated_artists_json", jsonPayload)
            .putLong("curated_artists_fetched_at", System.currentTimeMillis())
            .commit()

        val repository = CuratedArtistsRepository(context)
        val cached = repository.getCachedSections()

        assertNotNull(cached)
        assertEquals(1, cached!!.size)
        val section = cached[0]
        assertEquals("485956", section.artistId)
        assertEquals("Yo Yo Honey Singh", section.name)
        assertEquals("https://c.saavncdn.com/artists/Yo_Yo_Honey_Singh_500x500.jpg", section.imageUrl)
        assertEquals(1, section.tracks.size)

        val track = section.tracks[0]
        assertEquals("Casa Tupka Anthemo", track.title)
        assertEquals("Yo Yo Honey Singh", track.artist)
        assertEquals("47_T2N3p", track.songId)
        assertEquals(183000L, track.durationMs)
    }

    @Test
    fun getCuratedArtists_whenCacheFresh_returnsCachedImmediatelyWithoutNetwork() = runTest {
        val jsonPayload = """
            [
                {
                    "artistId": "468245",
                    "name": "Diljit Dosanjh",
                    "image": "https://c.saavncdn.com/diljit_500x500.jpg",
                    "tracks": []
                }
            ]
        """.trimIndent()

        // Cached 10 minutes ago (< 8 hours TTL)
        val freshTimestamp = System.currentTimeMillis() - 10 * 60 * 1000L
        context.getSharedPreferences("notify_curated_artists_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("curated_artists_json", jsonPayload)
            .putLong("curated_artists_fetched_at", freshTimestamp)
            .commit()

        val repository = CuratedArtistsRepository(context)
        val result = repository.getCuratedArtists(forceRefresh = false)

        assertTrue(result.isSuccess)
        val sections = result.getOrThrow()
        assertEquals(1, sections.size)
        assertEquals("Diljit Dosanjh", sections[0].name)
    }
}
