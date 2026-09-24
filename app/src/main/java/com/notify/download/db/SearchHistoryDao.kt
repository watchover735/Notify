package com.notify.download.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

object TimestampGenerator {
    private val lastTimestamp = java.util.concurrent.atomic.AtomicLong(0L)
    fun next(): Long {
        while (true) {
            val current = lastTimestamp.get()
            val now = System.currentTimeMillis()
            val next = if (now > current) now else current + 1L
            if (lastTimestamp.compareAndSet(current, next)) {
                return next
            }
        }
    }
}

/**
 * DAO for persistent search history and per-query candidate cache.
 *
 * Search history is bounded to the 15 most-recently-submitted unique queries.
 * Candidate cache has a 7-day TTL:
 *   - Fresh cache (< 7 days): display immediately, skip network call.
 *   - Stale cache (>= 7 days): display immediately, then refresh in background and replace rows.
 *
 * Invariants:
 *   - Keystroke-level query changes are NOT persisted; only explicit submit calls.
 *   - Signed stream URLs and auth cookies are NEVER stored.
 *   - Deleting a history entry also deletes its associated candidate cache rows.
 */
@Dao
interface SearchHistoryDao {

    // ── Recent Searches ────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM search_history
        ORDER BY lastSearchedAtEpochMs DESC
        LIMIT :limit
    """)
    fun observeRecentSearches(limit: Int = 15): Flow<List<SearchHistoryEntity>>

    @Query("""
        SELECT * FROM search_history
        ORDER BY lastSearchedAtEpochMs DESC
        LIMIT :limit
    """)
    suspend fun getRecentSearches(limit: Int = 15): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE normalizedQuery = :normalizedQuery")
    suspend fun getSearch(normalizedQuery: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearch(entity: SearchHistoryEntity)

    /**
     * Deletes entries outside the newest [limit] by recency.
     * Run inside a transaction after upsert to keep the table bounded.
     */
    @Query("""
        DELETE FROM search_history
        WHERE normalizedQuery NOT IN (
            SELECT normalizedQuery FROM search_history
            ORDER BY lastSearchedAtEpochMs DESC
            LIMIT :limit
        )
    """)
    suspend fun pruneOldSearches(limit: Int = 15)

    /**
     * Records a user-submitted search atomically:
     * 1. Normalizes the raw query (trim + collapse whitespace + lowercase for key).
     * 2. Upserts with updated timestamp and incremented useCount.
     * 3. Prunes entries beyond the newest 15.
     *
     * The display query preserves the user's original casing and spacing.
     */
    @Transaction
    suspend fun recordSearch(rawQuery: String) {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ")
        val now = TimestampGenerator.next()
        val existing = getSearch(normalized)
        val entity = if (existing != null) {
            existing.copy(
                displayQuery = trimmed,
                lastSearchedAtEpochMs = now,
                useCount = existing.useCount + 1
            )
        } else {
            SearchHistoryEntity(
                normalizedQuery = normalized,
                displayQuery = trimmed,
                lastSearchedAtEpochMs = now,
                useCount = 1
            )
        }
        upsertSearch(entity)
        pruneOldSearches(limit = 15)
    }

    /**
     * Deletes a single history entry AND its associated candidate cache rows.
     */
    @Transaction
    suspend fun deleteSearch(normalizedQuery: String) {
        deleteSearchEntry(normalizedQuery)
        deleteCandidatesForQuery(normalizedQuery)
    }

    @Query("DELETE FROM search_history WHERE normalizedQuery = :normalizedQuery")
    suspend fun deleteSearchEntry(normalizedQuery: String)

    /**
     * Clears all search history AND all candidate cache rows.
     */
    @Transaction
    suspend fun clearAllHistory() {
        clearSearchHistoryTable()
        clearAllCandidateCache()
    }

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistoryTable()

    // ── Candidate Cache ────────────────────────────────────────────────────────

    companion object {
        /** 7 days in milliseconds */
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L
    }

    @Query("""
        SELECT * FROM search_candidate_cache
        WHERE normalizedQuery = :normalizedQuery
        ORDER BY resultRank ASC
    """)
    suspend fun getCachedCandidates(normalizedQuery: String): List<SearchCandidateCacheEntity>

    /**
     * Returns cached candidates if any exist (regardless of freshness).
     * The caller is responsible for checking [SearchCandidateCacheEntity.cachedAtEpochMs]
     * against [CACHE_TTL_MS] to decide whether a background refresh is needed.
     */
    suspend fun getCachedCandidatesOrNull(normalizedQuery: String): List<SearchCandidateCacheEntity>? {
        val rows = getCachedCandidates(normalizedQuery)
        return rows.ifEmpty { null }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<SearchCandidateCacheEntity>)

    /**
     * Replaces all cached rows for a query with fresh results atomically.
     */
    @Transaction
    suspend fun replaceCandidates(normalizedQuery: String, candidates: List<SearchCandidateCacheEntity>) {
        deleteCandidatesForQuery(normalizedQuery)
        if (candidates.isNotEmpty()) {
            insertCandidates(candidates)
        }
    }

    @Query("DELETE FROM search_candidate_cache WHERE normalizedQuery = :normalizedQuery")
    suspend fun deleteCandidatesForQuery(normalizedQuery: String)

    @Query("DELETE FROM search_candidate_cache WHERE cachedAtEpochMs < :expiryEpochMs")
    suspend fun clearExpiredCandidates(expiryEpochMs: Long)

    @Query("DELETE FROM search_candidate_cache")
    suspend fun clearAllCandidateCache()

    // ── Recent Media Items (Spotify-Style Song Recents) ───────────────────────

    @Query("""
        SELECT * FROM recent_search_items
        ORDER BY lastInteractedAtEpochMs DESC
        LIMIT :limit
    """)
    fun observeRecentMediaItems(limit: Int = 15): Flow<List<RecentSearchItemEntity>>

    @Query("""
        SELECT * FROM recent_search_items
        ORDER BY lastInteractedAtEpochMs DESC
        LIMIT :limit
    """)
    suspend fun getRecentMediaItems(limit: Int = 15): List<RecentSearchItemEntity>

    @Query("SELECT * FROM recent_search_items WHERE id = :id")
    suspend fun getRecentMediaItem(id: String): RecentSearchItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentMediaItem(item: RecentSearchItemEntity)

    @Query("""
        DELETE FROM recent_search_items
        WHERE id NOT IN (
            SELECT id FROM recent_search_items
            ORDER BY lastInteractedAtEpochMs DESC
            LIMIT :limit
        )
    """)
    suspend fun pruneOldRecentMediaItems(limit: Int = 15)

    /**
     * Records a selected / tapped search result atomically:
     * 1. Re-selecting an existing item updates timestamp and moves it to top without duplication.
     * 2. Trims to max 15 items (the 16th removes the oldest).
     * 3. Never stores signed URLs.
     */
    @Transaction
    suspend fun recordRecentItem(item: RecentSearchItemEntity) {
        val updated = item.copy(lastInteractedAtEpochMs = TimestampGenerator.next())
        upsertRecentMediaItem(updated)
        pruneOldRecentMediaItems(limit = 15)
    }

    @Query("DELETE FROM recent_search_items WHERE id = :id")
    suspend fun deleteRecentMediaItem(id: String)

    @Query("DELETE FROM recent_search_items")
    suspend fun clearAllRecentMediaItems()
}
