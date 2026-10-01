package com.notify.download.stream

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FollowedArtistsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("notify_followed_artists_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun firstLaunch_seedsFiveDefaultArtists() {
        val repo = FollowedArtistsRepository(context)
        val artists = repo.getFollowedArtists()

        assertEquals(5, artists.size)
        val names = artists.map { it.name }
        assertTrue("Contains Yo Yo Honey Singh", names.contains("Yo Yo Honey Singh"))
        assertTrue("Contains Diljit Dosanjh", names.contains("Diljit Dosanjh"))
        assertTrue("Contains Arijit Singh", names.contains("Arijit Singh"))
        assertTrue("Contains Karan Aujla", names.contains("Karan Aujla"))
        assertTrue("Contains AP Dhillon", names.contains("AP Dhillon"))
    }

    @Test
    fun followArtist_addsNewArtistAndPersistsAcrossInstances() {
        val repo = FollowedArtistsRepository(context)
        val badshah = FollowedArtist(id = "456863", name = "Badshah", imageUrl = "https://c.saavncdn.com/badshah.jpg")

        val success = repo.followArtist(badshah)
        assertTrue(success)
        assertEquals(6, repo.getFollowedArtists().size)

        // Reload fresh instance to verify persistence in SharedPreferences
        val repo2 = FollowedArtistsRepository(context)
        val loaded = repo2.getFollowedArtists()
        assertEquals(6, loaded.size)
        assertTrue(loaded.any { it.id == "456863" && it.name == "Badshah" })
    }

    @Test
    fun followArtist_cannotAddDuplicate() {
        val repo = FollowedArtistsRepository(context)
        val honeySingh = FollowedArtist(id = "485956", name = "Yo Yo Honey Singh")

        val result = repo.followArtist(honeySingh)
        assertFalse("Cannot add duplicate artist", result)
        assertEquals(5, repo.getFollowedArtists().size)
    }

    @Test
    fun followArtist_enforcesMaxTenLimit() {
        val repo = FollowedArtistsRepository(context)
        assertEquals(5, repo.getFollowedArtists().size)

        // Add 5 more to reach 10
        for (i in 6..10) {
            val added = repo.followArtist(FollowedArtist(id = "artist_$i", name = "Artist $i"))
            assertTrue("Artist $i should be added", added)
        }
        assertEquals(10, repo.getFollowedArtists().size)

        // Try to add 11th artist
        val eleventh = repo.followArtist(FollowedArtist(id = "artist_11", name = "Artist 11"))
        assertFalse("11th artist must be rejected due to max 10 limit", eleventh)
        assertEquals("List size must stay capped at 10", 10, repo.getFollowedArtists().size)
    }

    @Test
    fun unfollowArtist_removesArtistAndAllowsUndoRestore() {
        val repo = FollowedArtistsRepository(context)
        val initialList = repo.getFollowedArtists()
        val toRemove = initialList[1] // Diljit Dosanjh at index 1

        val removedPair = repo.unfollowArtist(toRemove.id)
        assertNotNull(removedPair)
        assertEquals(toRemove.id, removedPair!!.first.id)
        assertEquals(1, removedPair.second)
        assertEquals(4, repo.getFollowedArtists().size)
        assertFalse(repo.getFollowedArtists().any { it.id == toRemove.id })

        // Undo: restore artist at index 1
        val restored = repo.restoreArtist(removedPair.first, removedPair.second)
        assertTrue(restored)
        assertEquals(5, repo.getFollowedArtists().size)
        assertEquals(toRemove.id, repo.getFollowedArtists()[1].id)
    }
}
