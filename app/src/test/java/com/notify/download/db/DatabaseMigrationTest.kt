package com.notify.download.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Migration test for [NotiFyDatabase.MIGRATION_3_4].
 * Verifies non-destructive schema migration from version 3 to version 4:
 *  - Adds columns `artworkEnrichmentStatus` and `lastArtworkAttemptEpochMs` to `tracks`.
 *  - Backfills genuine existing artwork to `ENRICHED`.
 *  - Leaves null, empty, UNKNOWN, and PLAYLIST_FALLBACK_LEGACY tracks as `NOT_ATTEMPTED`.
 *  - Creates `recent_search_items` table and its timestamp index.
 *  - Preserves all pre-existing playlists, tracks, playlist_entries, search_history, and cache.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotiFyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration_3_to_4_preservesData_andAppliesSchemaChanges() {
        val context: Context = RuntimeEnvironment.getApplication()
        val dbName = "test_migration_3_4.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create v3 schema
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS playlists (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            trackCount INTEGER NOT NULL,
                            coverArtworkUri TEXT,
                            lastModifiedEpochMs INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS tracks (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            artist TEXT NOT NULL,
                            album TEXT,
                            durationMs INTEGER NOT NULL,
                            artworkUri TEXT,
                            artworkUrl TEXT,
                            artworkOrigin TEXT,
                            sourceId TEXT,
                            resolutionState TEXT NOT NULL,
                            matchConfidence REAL NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS playlist_entries (
                            playlistId TEXT NOT NULL,
                            trackId TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            addedAtEpochMs INTEGER NOT NULL,
                            PRIMARY KEY(playlistId, trackId)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS search_history (
                            normalizedQuery TEXT NOT NULL PRIMARY KEY,
                            displayQuery TEXT NOT NULL,
                            lastSearchedEpochMs INTEGER NOT NULL,
                            useCount INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS search_candidate_cache (
                            normalizedQuery TEXT NOT NULL,
                            resultRank INTEGER NOT NULL,
                            provider TEXT NOT NULL,
                            providerSourceId TEXT NOT NULL,
                            title TEXT NOT NULL,
                            artist TEXT NOT NULL,
                            durationMs INTEGER NOT NULL,
                            artworkUrl TEXT,
                            canonicalWatchUrl TEXT NOT NULL,
                            cachedAtEpochMs INTEGER NOT NULL,
                            PRIMARY KEY(normalizedQuery, resultRank)
                        )
                        """.trimIndent()
                    )

                    // Seed v3 test data
                    db.execSQL("INSERT INTO playlists VALUES ('p1', 'Existing 93-Track Playlist', 93, 'https://art/p1.jpg', 1700000000000)")
                    db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, artworkOrigin, sourceId, resolutionState, matchConfidence) VALUES ('t_genuine', 'Song A', 'Artist A', 'Album A', 200000, 'https://i.scdn.co/image/abc', 'https://i.scdn.co/image/abc', 'SPOTIFY_TRACK', 's1', 'METADATA_ONLY', 0.0)")
                    db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, artworkOrigin, sourceId, resolutionState, matchConfidence) VALUES ('t_null_art', 'Song B', 'Artist B', 'Album B', 180000, NULL, NULL, NULL, 's2', 'METADATA_ONLY', 0.0)")
                    db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, artworkOrigin, sourceId, resolutionState, matchConfidence) VALUES ('t_unknown', 'Song C', 'Artist C', 'Album C', 190000, 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 's3', 'METADATA_ONLY', 0.0)")
                    db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, artworkOrigin, sourceId, resolutionState, matchConfidence) VALUES ('t_legacy', 'Song D', 'Artist D', 'Album D', 210000, 'PLAYLIST_FALLBACK_LEGACY', 'PLAYLIST_FALLBACK_LEGACY', 'PLAYLIST_FALLBACK_LEGACY', 's4', 'METADATA_ONLY', 0.0)")
                    db.execSQL("INSERT INTO playlist_entries VALUES ('p1', 't_genuine', 0, 1700000000000)")
                    db.execSQL("INSERT INTO playlist_entries VALUES ('p1', 't_null_art', 1, 1700000000000)")
                    db.execSQL("INSERT INTO search_history VALUES ('diljit dosanjh', 'Diljit Dosanjh', 1700000000000, 1)")
                    db.execSQL("INSERT INTO search_candidate_cache VALUES ('diljit dosanjh', 0, 'yt', 'vid_1', 'Title', 'Artist', 200000, 'url', 'watch', 1700000000000)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Execute MIGRATION_3_4
        NotiFyDatabase.MIGRATION_3_4.migrate(db)

        // 1. Verify tracks schema & backfill
        val cursor = db.query("SELECT id, artworkEnrichmentStatus, lastArtworkAttemptEpochMs FROM tracks ORDER BY id ASC")
        val statusMap = mutableMapOf<String, String>()
        val attemptMap = mutableMapOf<String, Long>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val status = cursor.getString(1)
            val lastAttempt = cursor.getLong(2)
            statusMap[id] = status
            attemptMap[id] = lastAttempt
        }
        cursor.close()

        assertEquals("Genuine artwork must be backfilled to ENRICHED", "ENRICHED", statusMap["t_genuine"])
        assertEquals("Null artwork track must remain NOT_ATTEMPTED", "NOT_ATTEMPTED", statusMap["t_null_art"])
        assertEquals("UNKNOWN artwork track must remain NOT_ATTEMPTED", "NOT_ATTEMPTED", statusMap["t_unknown"])
        assertEquals("PLAYLIST_FALLBACK_LEGACY artwork track must remain NOT_ATTEMPTED", "NOT_ATTEMPTED", statusMap["t_legacy"])

        assertEquals("lastArtworkAttemptEpochMs defaults to 0", 0L, attemptMap["t_null_art"])
        assertEquals("lastArtworkAttemptEpochMs defaults to 0", 0L, attemptMap["t_genuine"])

        // 2. Verify recent_search_items table is created and functional
        db.execSQL(
            """
            INSERT INTO recent_search_items (id, provider, providerSourceId, catalogTrackId, title, artist, album, durationMs, artworkUrl, itemType, lastInteractedAtEpochMs, sourceContext)
            VALUES ('yt:test_1', 'yt', 'test_1', NULL, 'Test Song', 'Test Artist', NULL, 214000, 'https://art/test.jpg', 'TRACK', 1700001000000, 'SEARCH')
            """.trimIndent()
        )
        val recentsCursor = db.query("SELECT id, title, artist FROM recent_search_items WHERE id = 'yt:test_1'")
        assertTrue("recent_search_items must contain inserted row", recentsCursor.moveToFirst())
        assertEquals("Test Song", recentsCursor.getString(1))
        recentsCursor.close()

        // 3. Verify existing data preserved
        val playlistCursor = db.query("SELECT id, title, trackCount FROM playlists WHERE id = 'p1'")
        assertTrue(playlistCursor.moveToFirst())
        assertEquals("Existing 93-Track Playlist", playlistCursor.getString(1))
        assertEquals(93, playlistCursor.getInt(2))
        playlistCursor.close()

        val entriesCursor = db.query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = 'p1'")
        assertTrue(entriesCursor.moveToFirst())
        assertEquals(2, entriesCursor.getInt(0))
        entriesCursor.close()

        val historyCursor = db.query("SELECT displayQuery FROM search_history WHERE normalizedQuery = 'diljit dosanjh'")
        assertTrue(historyCursor.moveToFirst())
        assertEquals("Diljit Dosanjh", historyCursor.getString(0))
        historyCursor.close()

        val cacheCursor = db.query("SELECT COUNT(*) FROM search_candidate_cache WHERE normalizedQuery = 'diljit dosanjh'")
        assertTrue(cacheCursor.moveToFirst())
        assertEquals(1, cacheCursor.getInt(0))
        cacheCursor.close()

        db.close()
    }

    @Test
    fun migration_4_to_5_deduplicatesPlaylistEntries_createsUniqueIndex_andAddsArtworkAttemptCount() {
        val context: Context = RuntimeEnvironment.getApplication()
        val dbName = "test_migration_4_5.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS playlists (
                            playlistId TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            sourceUrl TEXT,
                            artworkUri TEXT,
                            dateCreatedEpochMs INTEGER NOT NULL,
                            dateModifiedEpochMs INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS tracks (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            artist TEXT NOT NULL,
                            album TEXT,
                            durationMs INTEGER NOT NULL,
                            artworkUri TEXT,
                            artworkUrl TEXT,
                            spotifyId TEXT,
                            resolutionState TEXT NOT NULL,
                            downloadState TEXT NOT NULL,
                            localContentUri TEXT,
                            dateAddedEpochMs INTEGER NOT NULL,
                            artworkOrigin TEXT NOT NULL,
                            artworkEnrichmentStatus TEXT NOT NULL,
                            lastArtworkAttemptEpochMs INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS playlist_entries (
                            entryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            playlistId TEXT NOT NULL,
                            trackId TEXT NOT NULL,
                            position INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS recent_search_items (
                            id TEXT NOT NULL PRIMARY KEY,
                            provider TEXT NOT NULL,
                            providerSourceId TEXT NOT NULL,
                            catalogTrackId TEXT,
                            title TEXT NOT NULL,
                            artist TEXT NOT NULL,
                            album TEXT,
                            durationMs INTEGER,
                            artworkUrl TEXT,
                            itemType TEXT NOT NULL,
                            lastInteractedAtEpochMs INTEGER NOT NULL,
                            sourceContext TEXT NOT NULL
                        )
                        """.trimIndent()
                    )

                    // Seed data with duplicate playlist entries
                    db.execSQL("INSERT INTO playlists VALUES ('p1', 'My Playlist', NULL, NULL, 1700000000000, 1700000000000)")
                    db.execSQL("INSERT INTO tracks VALUES ('t1', 'Song 1', 'Artist 1', 'Album 1', 200000, NULL, NULL, NULL, 'METADATA_ONLY', 'NOT_DOWNLOADED', NULL, 1700000000000, 'UNKNOWN', 'NOT_ATTEMPTED', 0)")
                    db.execSQL("INSERT INTO tracks VALUES ('t2', 'Song 2', 'Artist 2', 'Album 2', 220000, 'art2', 'art2', NULL, 'MATCHED', 'NOT_DOWNLOADED', NULL, 1700000000000, 'YOUTUBE_MATCH', 'ENRICHED', 1700000000000)")

                    // Duplicate entries for (p1, t1) with different entryId
                    db.execSQL("INSERT INTO playlist_entries (entryId, playlistId, trackId, position) VALUES (1, 'p1', 't1', 1)")
                    db.execSQL("INSERT INTO playlist_entries (entryId, playlistId, trackId, position) VALUES (2, 'p1', 't1', 2)")
                    db.execSQL("INSERT INTO playlist_entries (entryId, playlistId, trackId, position) VALUES (3, 'p1', 't2', 3)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Execute MIGRATION_4_5
        NotiFyDatabase.MIGRATION_4_5.migrate(db)

        // 1. Verify duplicates removed keeping MIN(entryId)
        val entriesCursor = db.query("SELECT entryId, playlistId, trackId, position FROM playlist_entries ORDER BY entryId ASC")
        val entries = mutableListOf<Triple<Long, String, String>>()
        while (entriesCursor.moveToNext()) {
            entries.add(Triple(entriesCursor.getLong(0), entriesCursor.getString(1), entriesCursor.getString(2)))
        }
        entriesCursor.close()

        assertEquals("Duplicates must be removed, leaving exactly 2 unique entries", 2, entries.size)
        assertEquals("MIN(entryId) must be kept for t1", 1L, entries[0].first)
        assertEquals("t1", entries[0].third)
        assertEquals("t2 entry preserved", 3L, entries[1].first)
        assertEquals("t2", entries[1].third)

        // 2. Verify UNIQUE index prevents duplicate insertion
        var constraintViolated = false
        try {
            db.execSQL("INSERT INTO playlist_entries (playlistId, trackId, position) VALUES ('p1', 't1', 4)")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            constraintViolated = true
        }
        assertTrue("Inserting duplicate (playlistId, trackId) must violate UNIQUE index", constraintViolated)

        // 3. Verify tracks columns & NOT_ATTEMPTED -> PENDING migration
        val tracksCursor = db.query("SELECT id, artworkEnrichmentStatus, artworkAttemptCount FROM tracks ORDER BY id ASC")
        val trackMap = mutableMapOf<String, Pair<String, Int>>()
        while (tracksCursor.moveToNext()) {
            trackMap[tracksCursor.getString(0)] = Pair(tracksCursor.getString(1), tracksCursor.getInt(2))
        }
        tracksCursor.close()

        assertEquals("t1 status migrated to PENDING", "PENDING", trackMap["t1"]?.first)
        assertEquals("t1 artworkAttemptCount initialized to 0", 0, trackMap["t1"]?.second)
        assertEquals("t2 ENRICHED status preserved", "ENRICHED", trackMap["t2"]?.first)
        assertEquals("t2 artworkAttemptCount initialized to 0", 0, trackMap["t2"]?.second)

        db.close()
    }

    @Test
    fun migration_5_to_6_createsOfflineDownloadsTable_andIndices() {
        val context: Context = RuntimeEnvironment.getApplication()
        val dbName = "test_migration_5_6.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v5 schema
                    db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, trackCount INTEGER NOT NULL, coverArtworkUri TEXT, lastModifiedEpochMs INTEGER NOT NULL, dateCreatedEpochMs INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS tracks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Execute MIGRATION_5_6
        NotiFyDatabase.MIGRATION_5_6.migrate(db)

        // Verify offline_downloads table exists and can be inserted into
        db.execSQL(
            """
            INSERT INTO offline_downloads (
                downloadId, trackId, provider, providerSourceId, canonicalUrl, relativeStorageKey, bucket, status,
                fileSizeBytes, mimeType, bitrateKbps, durationMs, qualityProfile, progressPercent, errorMessage,
                attemptCount, createdAtEpochMs, updatedAtEpochMs, lastAccessedAtEpochMs
            ) VALUES (
                'dl_1', 'track_1', 'YOUTUBE', 'src_1', 'https://youtube.com/watch?v=src_1', 'pinned/track_1.opus', 'PINNED', 'COMPLETED',
                4500000, 'audio/ogg', 160, 210000, 'HIGH', 100, NULL,
                0, 1700000000000, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )

        val cursor = db.query("SELECT downloadId, trackId, bucket, status, fileSizeBytes FROM offline_downloads WHERE downloadId = 'dl_1'")
        assertTrue("Row must be queryable in offline_downloads", cursor.moveToFirst())
        assertEquals("dl_1", cursor.getString(0))
        assertEquals("track_1", cursor.getString(1))
        assertEquals("PINNED", cursor.getString(2))
        assertEquals("COMPLETED", cursor.getString(3))
        assertEquals(4500000L, cursor.getLong(4))
        cursor.close()

        // Verify index exists
        val indexCursor = db.query("PRAGMA index_list('offline_downloads')")
        val indices = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indices.add(indexCursor.getString(1))
        }
        indexCursor.close()

        assertTrue("index_offline_downloads_trackId must exist", indices.contains("index_offline_downloads_trackId"))
        assertTrue("index_offline_downloads_relativeStorageKey must exist", indices.contains("index_offline_downloads_relativeStorageKey"))
        assertTrue("index_offline_downloads_status must exist", indices.contains("index_offline_downloads_status"))
        assertTrue("index_offline_downloads_bucket_lastAccessedAtEpochMs must exist", indices.contains("index_offline_downloads_bucket_lastAccessedAtEpochMs"))

        db.close()
    }

    @Test
    fun migration_6_to_7_usingExactRoomSchema_preservesData_andValidatesSchema() = runTest {
        val testDbName = "migration_test_6_7.db"
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(testDbName)

        // 1. Create database at version 6 using the EXACT Room exported version-6 schema
        val dbV6 = helper.createDatabase(testDbName, 6)

        // 2. Insert sample playlists, tracks, and offline-download data
        dbV6.execSQL(
            """
            INSERT INTO playlists (playlistId, title, sourceUrl, artworkUri, dateCreatedEpochMs, dateModifiedEpochMs)
            VALUES ('pl_v6_saved', 'My 90s Favorites', NULL, 'https://art/p1.jpg', 1700000000000, 1700000000000)
            """.trimIndent()
        )
        dbV6.execSQL(
            """
            INSERT INTO tracks (
                id, title, artist, album, durationMs, artworkUri, artworkUrl, spotifyId,
                resolutionState, downloadState, localContentUri, dateAddedEpochMs,
                artworkOrigin, artworkEnrichmentStatus, lastArtworkAttemptEpochMs, artworkAttemptCount
            ) VALUES (
                'tr_v6_offline', 'Born to Shine', 'Diljit Dosanjh', 'G.O.A.T.', 214000,
                'https://art/t1.jpg', 'https://art/t1.jpg', 'sp_1',
                'MATCHED', 'DOWNLOADED', 'content://offline/audio_1.opus', 1700000000000,
                'YOUTUBE_MATCH', 'ENRICHED', 1700000000000, 0
            )
            """.trimIndent()
        )
        dbV6.execSQL(
            """
            INSERT INTO offline_downloads (
                downloadId, trackId, provider, providerSourceId, canonicalUrl, relativeStorageKey, bucket, status,
                fileSizeBytes, mimeType, bitrateKbps, durationMs, qualityProfile, progressPercent, errorMessage,
                attemptCount, createdAtEpochMs, updatedAtEpochMs, lastAccessedAtEpochMs
            ) VALUES (
                'dl_v6_offline', 'tr_v6_offline', 'YOUTUBE', 'src_yt_1', 'https://youtube.com/watch?v=src_yt_1',
                'pinned/tr_v6_offline.opus', 'PINNED', 'COMPLETED',
                4500000, 'audio/ogg', 160, 214000, 'NORMAL', 100, NULL,
                0, 1700000000000, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )
        dbV6.close()

        // 3. Run MIGRATION_6_7 and perform rigorous Room schema validation against compiled Room v7 schema
        val dbV7 = helper.runMigrationsAndValidate(testDbName, 7, true, NotiFyDatabase.MIGRATION_6_7)
        dbV7.close()

        // 4. Open with real Room database instance
        val roomDb = Room.databaseBuilder(context, NotiFyDatabase::class.java, testDbName)
            .addMigrations(NotiFyDatabase.MIGRATION_6_7)
            .build()

        // 5. Confirm pre-existing user data remains intact (zero data loss)
        val pl = roomDb.playlistDao().getPlaylistById("pl_v6_saved")
        assertNotNull("Pre-existing playlist must remain intact after migration", pl)
        assertEquals("My 90s Favorites", pl?.title)

        val tr = roomDb.trackDao().getTrackById("tr_v6_offline")
        assertNotNull("Pre-existing track must remain intact after migration", tr)
        assertEquals("Born to Shine", tr?.title)
        assertEquals("content://offline/audio_1.opus", tr?.localContentUri)

        val dl = roomDb.offlineDownloadDao().getById("dl_v6_offline")
        assertNotNull("Pre-existing offline download must remain intact after migration", dl)
        assertEquals("tr_v6_offline", dl?.trackId)
        assertEquals("COMPLETED", dl?.status)
        assertEquals(4500000L, dl?.fileSizeBytes)

        // 6. Confirm download_queue table is created and operational via Room DAO
        val queueDao = roomDb.downloadQueueDao()
        queueDao.upsert(
            DownloadQueueEntity(
                trackId = "tr_new_queue_1",
                status = DownloadQueueStatus.QUEUED,
                trackTitle = "New Queued Track",
                artist = "New Artist",
                playlistId = "pl_v6_saved",
                queuePosition = 1
            )
        )

        val queuedItem = queueDao.getByTrackId("tr_new_queue_1")
        assertNotNull("download_queue must open and store rows successfully", queuedItem)
        assertEquals("New Queued Track", queuedItem?.trackTitle)
        assertEquals(DownloadQueueStatus.QUEUED, queuedItem?.status)
        assertEquals(1, queuedItem?.queuePosition)

        roomDb.close()
    }

    @Test
    fun database_freshV7_opensAndValidatesSuccessfully() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val dbName = "test_fresh_v7.db"
        context.deleteDatabase(dbName)

        val roomDb = Room.databaseBuilder(context, NotiFyDatabase::class.java, dbName).build()
        // Opening writable database triggers onCreate and full Room validation
        val sqliteDb = roomDb.openHelper.writableDatabase
        assertNotNull("Fresh version-7 database opens successfully", sqliteDb)

        // Verify playlist CRUD
        roomDb.playlistDao().insertPlaylist(PlaylistEntity(playlistId = "pl_fresh", title = "Fresh Playlist"))
        val pl = roomDb.playlistDao().getPlaylistById("pl_fresh")
        assertNotNull(pl)
        assertEquals("Fresh Playlist", pl?.title)

        // Verify download_queue CRUD
        val queueDao = roomDb.downloadQueueDao()
        queueDao.upsert(
            DownloadQueueEntity(
                trackId = "tr_fresh_test",
                status = DownloadQueueStatus.QUEUED,
                trackTitle = "Fresh Queue Song",
                artist = "Fresh Artist"
            )
        )
        val item = queueDao.getByTrackId("tr_fresh_test")
        assertNotNull(item)
        assertEquals("Fresh Queue Song", item?.trackTitle)
        assertEquals(DownloadQueueStatus.QUEUED, item?.status)

        roomDb.close()
    }
}

