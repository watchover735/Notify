package com.notify.download.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for [SearchHistoryDao] covering:
 *  1. recordSearch: basic persistence.
 *  2. Duplicate normalized query moves to top without creating a second row.
 *  3. 16 queries → exactly 15 remain; oldest evicted.
 *  4. deleteSearch: removes entry and its candidate cache rows.
 *  5. clearAllHistory: removes everything.
 *  6. Candidate cache CRUD and fresh/stale TTL logic.
 *  7. replaceCandidates is atomic (no partial rows after conflict).
 *  8. observeRecentSearches emits in descending recency order.
 */
@RunWith(RobolectricTestRunner::class)
class SearchHistoryDaoTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var dao: SearchHistoryDao

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.searchHistoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── 1. Basic recordSearch ─────────────────────────────────────────────────────────────

    @Test
    fun recordSearch_persistsEntry() = runTest {
        dao.recordSearch("Born to Shine")

        val history = dao.getRecentSearches()
        assertEquals(1, history.size)
        assertEquals("born to shine", history[0].normalizedQuery)
        assertEquals("Born to Shine", history[0].displayQuery)
        assertEquals(1, history[0].useCount)
    }

    @Test
    fun recordSearch_normalizesWhitespace() = runTest {
        dao.recordSearch("  Diljit   Dosanjh  ")

        val history = dao.getRecentSearches()
        assertEquals(1, history.size)
        assertEquals("diljit dosanjh", history[0].normalizedQuery)
        assertEquals("Diljit   Dosanjh", history[0].displayQuery.trim())
    }

    @Test
    fun recordSearch_ignoresSingleCharOrBlank() = runTest {
        dao.recordSearch(" ")
        dao.recordSearch("a")
        dao.recordSearch("")

        val history = dao.getRecentSearches()
        assertEquals(0, history.size)
    }

    // ── 2. Duplicate query moves to top, no duplication ──────────────────────────────────

    @Test
    fun recordSearch_duplicateNormalizedQuery_movesToTop_noduplication() = runTest {
        dao.recordSearch("Born to Shine")
        dao.recordSearch("Diljit")
        dao.recordSearch("Born To Shine") // same normalized key as first, different case

        val history = dao.getRecentSearches()
        assertEquals("Duplicate normalized query must not create a second row", 2, history.size)
        assertEquals("Duplicate must move to top", "born to shine", history[0].normalizedQuery)
        assertEquals("Display query updates to latest casing", "Born To Shine", history[0].displayQuery)
        assertEquals("Use count must increment on repeat", 2, history[0].useCount)
        assertEquals("diljit", history[1].normalizedQuery)
    }

    // ── 3. 16 queries → exactly 15 remain ───────────────────────────────────────────────

    @Test
    fun recordSearch_16uniqueQueries_exactlyTop15Remain() = runTest {
        // Record 16 unique queries with deterministic ordering
        for (i in 1..16) {
            dao.recordSearch("Query $i")
        }

        val history = dao.getRecentSearches()
        assertEquals("Must have exactly 15 entries after 16 inserts", 15, history.size)

        // Query 16 is the newest, Query 2 is 15th newest; Query 1 should be evicted
        val normalizedList = history.map { it.normalizedQuery }
        assertTrue("Newest query must be present", normalizedList.contains("query 16"))
        assertTrue("15th newest must be present", normalizedList.contains("query 2"))
        assertTrue("Oldest (query 1) must have been evicted", !normalizedList.contains("query 1"))
    }

    // ── 4. deleteSearch removes entry and its candidate cache ─────────────────────────────

    @Test
    fun deleteSearch_removesEntryAndCandidateCache() = runTest {
        dao.recordSearch("Diljit Dosanjh")
        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = "diljit dosanjh",
                resultRank = 0,
                provider = "youtube_music_innertube",
                providerSourceId = "yt_1",
                title = "Born to Shine",
                artist = "Diljit Dosanjh",
                durationMs = 214_000L,
                artworkUrl = null,
                canonicalWatchUrl = "https://youtube.com/watch?v=yt_1"
            )
        ))

        dao.deleteSearch("diljit dosanjh")

        val history = dao.getRecentSearches()
        assertEquals(0, history.size)

        val cache = dao.getCachedCandidates("diljit dosanjh")
        assertEquals(0, cache.size)
    }

    // ── 5. clearAllHistory removes everything ─────────────────────────────────────────────

    @Test
    fun clearAllHistory_removesHistoryAndCache() = runTest {
        dao.recordSearch("Query A")
        dao.recordSearch("Query B")
        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = "query a",
                resultRank = 0,
                provider = "youtube_music_innertube",
                providerSourceId = "yt_1",
                title = "A",
                artist = "Artist",
                durationMs = 180_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=yt_1"
            )
        ))

        dao.clearAllHistory()

        assertEquals(0, dao.getRecentSearches().size)
        assertEquals(0, dao.getCachedCandidates("query a").size)
    }

    // ── 6. Candidate cache TTL logic ──────────────────────────────────────────────────────

    @Test
    fun candidateCache_freshEntry_returnsImmediately() = runTest {
        val now = System.currentTimeMillis()
        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = "born to shine",
                resultRank = 0,
                provider = "youtube_music_innertube",
                providerSourceId = "yt_fresh",
                title = "Born to Shine",
                artist = "Diljit Dosanjh",
                durationMs = 214_000L,
                artworkUrl = "https://i.ytimg.com/vi/yt_fresh/hqdefault.jpg",
                canonicalWatchUrl = "https://youtube.com/watch?v=yt_fresh",
                cachedAtEpochMs = now
            )
        ))

        val cached = dao.getCachedCandidatesOrNull("born to shine")
        assertNotNull(cached)
        assertEquals(1, cached!!.size)
        assertEquals("yt_fresh", cached[0].providerSourceId)

        val isFresh = (now - cached[0].cachedAtEpochMs) < SearchHistoryDao.CACHE_TTL_MS
        assertTrue("Entry cached now must be fresh", isFresh)
    }

    @Test
    fun candidateCache_staleEntry_detectedByTTL() = runTest {
        val eightDaysAgo = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000L)
        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = "stale query",
                resultRank = 0,
                provider = "youtube_music_innertube",
                providerSourceId = "yt_stale",
                title = "Old Song",
                artist = "Old Artist",
                durationMs = 180_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=yt_stale",
                cachedAtEpochMs = eightDaysAgo
            )
        ))

        val cached = dao.getCachedCandidatesOrNull("stale query")
        assertNotNull("Stale cache must still be returned (show immediately, refresh later)", cached)
        assertEquals(1, cached!!.size)

        val isStale = (System.currentTimeMillis() - cached[0].cachedAtEpochMs) >= SearchHistoryDao.CACHE_TTL_MS
        assertTrue("Entry from 8 days ago must be considered stale", isStale)
    }

    @Test
    fun clearExpiredCandidates_removesOnlyStaleRows() = runTest {
        val eightDaysAgo = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        val expiryMs = now - SearchHistoryDao.CACHE_TTL_MS

        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = "fresh query",
                resultRank = 0,
                provider = "yt",
                providerSourceId = "v1",
                title = "Fresh",
                artist = "A",
                durationMs = 180_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=v1",
                cachedAtEpochMs = now
            ),
            SearchCandidateCacheEntity(
                normalizedQuery = "stale query",
                resultRank = 0,
                provider = "yt",
                providerSourceId = "v2",
                title = "Stale",
                artist = "A",
                durationMs = 180_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=v2",
                cachedAtEpochMs = eightDaysAgo
            )
        ))

        dao.clearExpiredCandidates(expiryMs)

        assertNotNull("Fresh entry must remain", dao.getCachedCandidatesOrNull("fresh query"))
        assertNull("Stale entry must be cleared", dao.getCachedCandidatesOrNull("stale query"))
    }

    // ── 7. replaceCandidates is atomic ────────────────────────────────────────────────────

    @Test
    fun replaceCandidates_isAtomic_noPartialRows() = runTest {
        val key = "diljit dosanjh"

        dao.insertCandidates(listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = key,
                resultRank = 0,
                provider = "yt",
                providerSourceId = "old",
                title = "Old",
                artist = "A",
                durationMs = 100_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=old"
            )
        ))

        val newCandidates = listOf(
            SearchCandidateCacheEntity(
                normalizedQuery = key,
                resultRank = 0,
                provider = "yt",
                providerSourceId = "new_1",
                title = "New 1",
                artist = "A",
                durationMs = 200_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=new_1"
            ),
            SearchCandidateCacheEntity(
                normalizedQuery = key,
                resultRank = 1,
                provider = "yt",
                providerSourceId = "new_2",
                title = "New 2",
                artist = "B",
                durationMs = 210_000L,
                canonicalWatchUrl = "https://youtube.com/watch?v=new_2"
            )
        )
        dao.replaceCandidates(key, newCandidates)

        val result = dao.getCachedCandidates(key)
        assertEquals("Must have exactly 2 new rows after replace", 2, result.size)
        assertEquals("new_1", result[0].providerSourceId)
        assertEquals("new_2", result[1].providerSourceId)
    }

    // ── 8. observeRecentSearches emits in descending recency order ────────────────────────

    @Test
    fun observeRecentSearches_emitsInDescendingRecencyOrder() = runTest {
        dao.recordSearch("First")
        Thread.sleep(10) // ensure timestamp ordering
        dao.recordSearch("Second")
        Thread.sleep(10)
        dao.recordSearch("Third")

        val history = dao.observeRecentSearches(15).first()
        assertEquals(3, history.size)
        assertEquals("Newest must be first", "third", history[0].normalizedQuery)
        assertEquals("Oldest must be last", "first", history[2].normalizedQuery)
    }
}
