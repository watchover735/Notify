package com.notify.download.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.notify.download.db.NotiFyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Seeded Room migration test: v2 → v3.
 *
 * Verifies:
 *  1. All playlists, playlist_entries, tracks, track_sources survive the migration intact.
 *  2. `artworkOrigin` column added with default 'UNKNOWN'.
 *  3. search_history and search_candidate_cache tables are created.
 *  4. Legacy repair SQL: tracks whose artworkUrl equals their playlist's artworkUri
 *     are cleared (artworkOrigin = 'PLAYLIST_FALLBACK_LEGACY').
 *  5. Tracks with a genuine YouTube match in track_sources are promoted to YOUTUBE_MATCH
 *     and their YouTube artwork is preserved.
 *
 * This is an Android instrumented test — must run on device or emulator.
 */
@RunWith(JUnit4::class)
class RoomMigration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotiFyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val DB_NAME = "migration-test-2-3.db"

    // ── Schema for v2 (exact column names from NotiFyDatabase.MIGRATION_1_2) ───────────────
    private fun SupportSQLiteDatabase.createV2Schema() {
        execSQL("""
            CREATE TABLE IF NOT EXISTS `tracks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT,
                `durationMs` INTEGER NOT NULL,
                `artworkUri` TEXT,
                `artworkUrl` TEXT,
                `spotifyId` TEXT,
                `resolutionState` TEXT NOT NULL,
                `downloadState` TEXT NOT NULL,
                `localContentUri` TEXT,
                `dateAddedEpochMs` INTEGER NOT NULL
            )
        """.trimIndent())
        execSQL("""
            CREATE TABLE IF NOT EXISTS `playlists` (
                `playlistId` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `sourceUrl` TEXT,
                `artworkUri` TEXT,
                `dateCreatedEpochMs` INTEGER NOT NULL,
                `dateModifiedEpochMs` INTEGER NOT NULL
            )
        """.trimIndent())
        execSQL("""
            CREATE TABLE IF NOT EXISTS `playlist_entries` (
                `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `playlistId` TEXT NOT NULL,
                `trackId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`playlistId`) ON DELETE CASCADE,
                FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON DELETE CASCADE
            )
        """.trimIndent())
        execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_entries_playlistId_position` ON `playlist_entries` (`playlistId`, `position`)")
        execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_trackId` ON `playlist_entries` (`trackId`)")
        execSQL("""
            CREATE TABLE IF NOT EXISTS `track_sources` (
                `sourceKey` TEXT NOT NULL PRIMARY KEY,
                `trackId` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `canonicalUrl` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `durationDeltaMs` INTEGER NOT NULL,
                `artworkUrl` TEXT,
                `selected` INTEGER NOT NULL,
                FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON DELETE CASCADE
            )
        """.trimIndent())
        execSQL("CREATE INDEX IF NOT EXISTS `index_track_sources_trackId` ON `track_sources` (`trackId`)")
    }

    @Test
    fun migration2To3_preservesAllDataAndAppliesLegacyRepair() {
        val playlistArt = "https://i.scdn.co/image/ab67706f000000030000001_playlist"
        val spotifyTrackArt = "https://i.scdn.co/image/ab67616d0000b2732_track_art"
        val youtubeArt = "https://i.ytimg.com/vi/yt_abc/hqdefault.jpg"

        val db = helper.createDatabase(DB_NAME, 2)
        db.createV2Schema()

        // Seed playlist
        db.execSQL("""
            INSERT INTO playlists (playlistId, title, sourceUrl, artworkUri, dateCreatedEpochMs, dateModifiedEpochMs)
            VALUES ('pl_seed', 'My Playlist', 'https://open.spotify.com/playlist/seed', '$playlistArt', 1000, 1001)
        """.trimIndent())

        // Track 1: has Spotify per-track art (NOT playlist cover) → must be left as-is
        db.execSQL("""
            INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, spotifyId, resolutionState, downloadState, localContentUri, dateAddedEpochMs)
            VALUES ('track_1', 'Song A', 'Artist A', 'Album A', 214000, '$spotifyTrackArt', '$spotifyTrackArt', 'sp_1', 'METADATA_ONLY', 'NOT_DOWNLOADED', NULL, 2000)
        """.trimIndent())
        db.execSQL("INSERT INTO playlist_entries (playlistId, trackId, position) VALUES ('pl_seed', 'track_1', 1)")

        // Track 2: artworkUrl is playlist cover (legacy bug) and NO YouTube match source → must be CLEARED
        db.execSQL("""
            INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, spotifyId, resolutionState, downloadState, localContentUri, dateAddedEpochMs)
            VALUES ('track_2', 'Song B', 'Artist B', 'Album B', 180000, '$playlistArt', '$playlistArt', 'sp_2', 'METADATA_ONLY', 'NOT_DOWNLOADED', NULL, 3000)
        """.trimIndent())
        db.execSQL("INSERT INTO playlist_entries (playlistId, trackId, position) VALUES ('pl_seed', 'track_2', 2)")

        // Track 3: artworkUrl is playlist cover BUT has a genuine YouTube match source → must get YOUTUBE_MATCH, artwork = youtubeArt
        db.execSQL("""
            INSERT INTO tracks (id, title, artist, album, durationMs, artworkUri, artworkUrl, spotifyId, resolutionState, downloadState, localContentUri, dateAddedEpochMs)
            VALUES ('track_3', 'Song C', 'Artist C', 'Album C', 220000, '$playlistArt', '$playlistArt', 'sp_3', 'MATCHED', 'NOT_DOWNLOADED', NULL, 4000)
        """.trimIndent())
        db.execSQL("INSERT INTO playlist_entries (playlistId, trackId, position) VALUES ('pl_seed', 'track_3', 3)")
        db.execSQL("""
            INSERT INTO track_sources (sourceKey, trackId, provider, sourceId, canonicalUrl, confidence, durationDeltaMs, artworkUrl, selected)
            VALUES ('track_3:youtube', 'track_3', 'youtube_music_innertube', 'yt_c', 'https://youtube.com/watch?v=yt_c', 0.95, 200, '$youtubeArt', 1)
        """.trimIndent())

        db.close()

        // Run migration
        val migratedDb = helper.runMigrationsAndValidate(
            DB_NAME, 3, true,
            NotiFyDatabase.MIGRATION_2_3
        )

        // ── Verify search_history and search_candidate_cache created ────────────────────────
        val histCursor = migratedDb.query("SELECT * FROM search_history")
        assertNotNull("search_history table must exist", histCursor)
        histCursor.close()

        val cacheCursor = migratedDb.query("SELECT * FROM search_candidate_cache")
        assertNotNull("search_candidate_cache table must exist", cacheCursor)
        cacheCursor.close()

        // ── Verify playlist survived ────────────────────────────────────────────────────────
        val plCursor = migratedDb.query("SELECT playlistId, title, artworkUri FROM playlists")
        plCursor.moveToFirst()
        assertEquals("pl_seed", plCursor.getString(0))
        assertEquals("My Playlist", plCursor.getString(1))
        assertEquals(playlistArt, plCursor.getString(2))
        plCursor.close()

        // ── Verify playlist_entries survived ───────────────────────────────────────────────
        val entryCursor = migratedDb.query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = 'pl_seed'")
        entryCursor.moveToFirst()
        assertEquals(3, entryCursor.getInt(0))
        entryCursor.close()

        // ── Verify track 1 (Spotify art, NOT playlist cover) ──────────────────────────────
        val t1 = migratedDb.query("SELECT artworkUrl, artworkUri, artworkOrigin FROM tracks WHERE id = 'track_1'")
        t1.moveToFirst()
        assertEquals("Track 1 artworkUrl must be unchanged (Spotify art)", spotifyTrackArt, t1.getString(0))
        assertEquals("Track 1 artworkUri must be unchanged", spotifyTrackArt, t1.getString(1))
        // artworkOrigin should be UNKNOWN (not YOUTUBE_MATCH, no source; not legacy since != playlist art)
        assertEquals("Track 1 artworkOrigin must remain UNKNOWN", ArtworkOrigin.UNKNOWN, t1.getString(2))
        t1.close()

        // ── Verify track 2 (legacy playlist cover — must be cleared) ──────────────────────
        val t2 = migratedDb.query("SELECT artworkUrl, artworkUri, artworkOrigin FROM tracks WHERE id = 'track_2'")
        t2.moveToFirst()
        assertNull("Track 2 artworkUrl must be cleared (was playlist cover)", t2.getString(0))
        assertNull("Track 2 artworkUri must be cleared", t2.getString(1))
        assertEquals("Track 2 artworkOrigin must be PLAYLIST_FALLBACK_LEGACY", ArtworkOrigin.PLAYLIST_FALLBACK_LEGACY, t2.getString(2))
        t2.close()

        // ── Verify track 3 (legacy cover + YouTube source → YOUTUBE_MATCH) ───────────────
        val t3 = migratedDb.query("SELECT artworkUrl, artworkUri, artworkOrigin FROM tracks WHERE id = 'track_3'")
        t3.moveToFirst()
        assertEquals("Track 3 artworkUrl must be YouTube art", youtubeArt, t3.getString(0))
        assertEquals("Track 3 artworkUri must be YouTube art", youtubeArt, t3.getString(1))
        assertEquals("Track 3 artworkOrigin must be YOUTUBE_MATCH", ArtworkOrigin.YOUTUBE_MATCH, t3.getString(2))
        t3.close()

        // ── Verify track_sources are intact ───────────────────────────────────────────────
        val sourceCursor = migratedDb.query("SELECT COUNT(*) FROM track_sources WHERE trackId = 'track_3'")
        sourceCursor.moveToFirst()
        assertEquals(1, sourceCursor.getInt(0))
        sourceCursor.close()

        migratedDb.close()
    }
}
