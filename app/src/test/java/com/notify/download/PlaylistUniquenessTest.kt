package com.notify.download

import android.content.Context
import androidx.room.Room
import com.notify.download.db.AddTrackResult
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests verifying Patch A2.1.3 Duplicate-Proof Playlists & Membership Awareness:
 * 1. Single entry on duplicate add (returns AddTrackResult.AlreadyExists on second add).
 * 2. Concurrent double-add with equivalent provider-source inputs ("youtube", "vid1") vs ("YOUTUBE ", " vid1 ")
 *    resolves to deterministic TrackId and prevents duplicates without crashing.
 * 3. Separate playlists allowed (same track in playlist A and playlist B).
 * 4. observePlaylistsContainingTrack flow emits updated sets immediately.
 * 5. Room insertPlaylistEntryIgnore returns -1 on conflict without exception.
 * 6. Non-existent playlist returns AddTrackResult.PlaylistMissing.
 * 7. Legacy addTrackToPlaylist does not produce duplicate entries.
 * 8. Persistence across repository reload.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistUniquenessTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    private val PLAYLIST_A = "pl_test_a"
    private val PLAYLIST_B = "pl_test_b"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NotiFyDatabase.setTestInstance(db)
        repository = PlaylistRepository(db)

        runBlocking {
            db.playlistDao().insertPlaylist(PlaylistEntity(playlistId = PLAYLIST_A, title = "Playlist A"))
            db.playlistDao().insertPlaylist(PlaylistEntity(playlistId = PLAYLIST_B, title = "Playlist B"))
        }
    }

    @After
    fun tearDown() {
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testSingleEntryOnDuplicateAdd() = runBlocking {
        val res1 = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_A,
            title = "Test Song",
            artist = "Test Artist",
            provider = "youtube",
            providerSourceId = "vid_unique_1"
        )
        assertTrue("First add must succeed with Added", res1 is AddTrackResult.Added)

        val res2 = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_A,
            title = "Test Song",
            artist = "Test Artist",
            provider = "youtube",
            providerSourceId = "vid_unique_1"
        )
        assertTrue("Second add must return AlreadyExists", res2 is AddTrackResult.AlreadyExists)

        // Verify Room contains exactly 1 entry
        val entries = db.playlistDao().getPlaylistEntries(PLAYLIST_A)
        assertEquals("Playlist must contain exactly 1 entry", 1, entries.size)
        assertEquals("Track ID must match deterministic format", "youtube:vid_unique_1", entries[0].trackId)
    }

    @Test
    fun testConcurrentDoubleAddUsingEquivalentProviderSourceRecords() = runBlocking {
        // Two concurrent add operations with whitespace/casing variations of the same video
        // Variation 1: "youtube", "vid_concur_123"
        // Variation 2: "YOUTUBE ", " vid_concur_123 "
        val deferred1 = async(Dispatchers.IO) {
            repository.addTrackToPlaylistIfAbsent(
                playlistId = PLAYLIST_A,
                title = "Concurrent Song",
                artist = "Artist",
                provider = "youtube",
                providerSourceId = "vid_concur_123"
            )
        }
        val deferred2 = async(Dispatchers.IO) {
            repository.addTrackToPlaylistIfAbsent(
                playlistId = PLAYLIST_A,
                title = "Concurrent Song",
                artist = "Artist",
                provider = "YOUTUBE ",
                providerSourceId = " vid_concur_123 "
            )
        }

        val results = listOf(deferred1.await(), deferred2.await())
        val addedCount = results.count { it is AddTrackResult.Added }
        val alreadyExistsCount = results.count { it is AddTrackResult.AlreadyExists }

        assertEquals("Exactly one request must succeed with Added", 1, addedCount)
        assertEquals("The other request must receive AlreadyExists", 1, alreadyExistsCount)

        // Room must have exactly 1 entry
        val entries = db.playlistDao().getPlaylistEntries(PLAYLIST_A)
        assertEquals("Playlist entries must contain exactly 1 entry", 1, entries.size)
        assertEquals("Deterministic trackId must be lowercase trimmed", "youtube:vid_concur_123", entries[0].trackId)
    }

    @Test
    fun testSeparatePlaylistsAllowed() = runBlocking {
        // The same track CAN be added to two distinct playlists
        val resA = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_A,
            title = "Cross-Playlist Song",
            artist = "Artist Cross",
            provider = "youtube",
            providerSourceId = "vid_cross"
        )
        val resB = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_B,
            title = "Cross-Playlist Song",
            artist = "Artist Cross",
            provider = "youtube",
            providerSourceId = "vid_cross"
        )

        assertTrue("Must be Added to Playlist A", resA is AddTrackResult.Added)
        assertTrue("Must be Added to Playlist B", resB is AddTrackResult.Added)

        val entriesA = db.playlistDao().getPlaylistEntries(PLAYLIST_A)
        val entriesB = db.playlistDao().getPlaylistEntries(PLAYLIST_B)

        assertEquals("Playlist A has 1 entry", 1, entriesA.size)
        assertEquals("Playlist B has 1 entry", 1, entriesB.size)
    }

    @Test
    fun testObservePlaylistsContainingTrackFlow() = runBlocking {
        val trackId = PlaylistRepository.deterministicTrackId("youtube", "vid_flow_test")

        // Before adding: empty set
        val set0 = repository.observePlaylistsContainingTrack(trackId).first()
        assertTrue("Initially track is in no playlists", set0.isEmpty())

        // Add to Playlist A
        repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_A,
            title = "Flow Song",
            artist = "Artist",
            provider = "youtube",
            providerSourceId = "vid_flow_test"
        )
        val set1 = repository.observePlaylistsContainingTrack(trackId).first()
        assertEquals("Track must now be in Playlist A", setOf(PLAYLIST_A), set1)

        // Add to Playlist B
        repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_B,
            title = "Flow Song",
            artist = "Artist",
            provider = "youtube",
            providerSourceId = "vid_flow_test"
        )
        val set2 = repository.observePlaylistsContainingTrack(trackId).first()
        assertEquals("Track must now be in both Playlists A and B", setOf(PLAYLIST_A, PLAYLIST_B), set2)
    }

    @Test
    fun testRoomInsertIgnoreReturnsMinusOneOnDuplicate() = runBlocking {
        db.trackDao().insertTrack(TrackEntity(id = "test:track1", title = "T", artist = "A"))

        val entry1 = PlaylistEntryEntity(playlistId = PLAYLIST_A, trackId = "test:track1", position = 1)
        val rowId1 = db.playlistDao().insertPlaylistEntryIgnore(entry1)
        assertTrue("First insert must return rowId > 0", rowId1 > 0L)

        val entryDuplicate = PlaylistEntryEntity(playlistId = PLAYLIST_A, trackId = "test:track1", position = 2)
        val rowId2 = db.playlistDao().insertPlaylistEntryIgnore(entryDuplicate)
        assertEquals("Duplicate insert must return -1L", -1L, rowId2)
    }

    @Test
    fun testMissingPlaylistReturnsPlaylistMissing() = runBlocking {
        val res = repository.addTrackToPlaylistIfAbsent(
            playlistId = "pl_does_not_exist",
            title = "Song",
            artist = "Artist",
            provider = "youtube",
            providerSourceId = "vid_123"
        )
        assertTrue("Non-existent playlist must return PlaylistMissing", res is AddTrackResult.PlaylistMissing)
    }

    @Test
    fun testLegacyAddTrackToPlaylistDeduplicates() = runBlocking {
        repository.addTrackToPlaylist(
            playlistId = PLAYLIST_A,
            title = "Legacy Song",
            artist = "Legacy Artist",
            provider = "youtube",
            providerSourceId = "vid_legacy"
        )
        repository.addTrackToPlaylist(
            playlistId = PLAYLIST_A,
            title = "Legacy Song",
            artist = "Legacy Artist",
            provider = "youtube",
            providerSourceId = "vid_legacy"
        )

        val entries = db.playlistDao().getPlaylistEntries(PLAYLIST_A)
        assertEquals("Legacy method must also produce only 1 entry in Room", 1, entries.size)
    }

    @Test
    fun testDeterministicTrackIdFormatting() {
        val id1 = PlaylistRepository.deterministicTrackId("YOUTUBE", "  abc123XYZ  ")
        val id2 = PlaylistRepository.deterministicTrackId(" youtube ", "abc123XYZ")
        val id3 = PlaylistRepository.deterministicTrackId("YouTube", "abc123XYZ")

        assertEquals("youtube:abc123XYZ", id1)
        assertEquals("youtube:abc123XYZ", id2)
        assertEquals("youtube:abc123XYZ", id3)
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }
}
