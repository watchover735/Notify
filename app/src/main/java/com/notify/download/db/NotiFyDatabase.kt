package com.notify.download.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notify.core.downloads.db.DownloadQueueDao
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        TrackSourceEntity::class,
        SearchHistoryEntity::class,
        SearchCandidateCacheEntity::class,
        RecentSearchItemEntity::class,
        OfflineDownloadEntity::class,
        DownloadQueueEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class NotiFyDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun offlineDownloadDao(): OfflineDownloadDao
    abstract fun downloadQueueDao(): DownloadQueueDao

    companion object {
        @Volatile
        private var INSTANCE: NotiFyDatabase? = null

        /**
         * v1 → v2: Added `artworkUrl` to `tracks` and `track_sources`,
         * back-filled artworkUrl from artworkUri on existing rows.
         * Exact column names verified against Room @Entity definitions.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // tracks: id TEXT PK, title TEXT, artist TEXT, album TEXT, durationMs INTEGER,
                //         artworkUri TEXT, spotifyId TEXT, resolutionState TEXT, downloadState TEXT,
                //         localContentUri TEXT, dateAddedEpochMs INTEGER
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `artworkUrl` TEXT")
                // track_sources: sourceKey TEXT PK, trackId TEXT, provider TEXT, sourceId TEXT,
                //                canonicalUrl TEXT, confidence REAL, durationDeltaMs INTEGER, selected INTEGER
                db.execSQL("ALTER TABLE `track_sources` ADD COLUMN `artworkUrl` TEXT")
                db.execSQL("UPDATE `tracks` SET `artworkUrl` = `artworkUri` WHERE `artworkUrl` IS NULL AND `artworkUri` IS NOT NULL")
            }
        }

        /**
         * v2 → v3:
         *  1. Adds `artworkOrigin TEXT NOT NULL DEFAULT 'UNKNOWN'` to `tracks`.
         *  2. Creates `search_history` table.
         *  3. Creates `search_candidate_cache` table + index.
         *  4. Safe legacy-repair SQL: for any track where `artworkUrl = artworkUri` and that
         *     value equals its containing playlist's `artworkUri` (i.e. the playlist cover was
         *     incorrectly copied into the track row), sets `artworkOrigin = 'PLAYLIST_FALLBACK_LEGACY'`
         *     and clears artworkUrl / artworkUri ONLY if the track has no genuine selected
         *     track_source with its own artworkUrl. Tracks that already have a real YouTube
         *     match in track_sources are left untouched (artworkOrigin = 'YOUTUBE_MATCH').
         *
         * All column names and table names are verified against v2 schema above.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── Step 1: artworkOrigin column ─────────────────────────────────────────
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `artworkOrigin` TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )

                // ── Step 2: search_history table ─────────────────────────────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `normalizedQuery` TEXT NOT NULL PRIMARY KEY,
                        `displayQuery`    TEXT NOT NULL,
                        `lastSearchedAtEpochMs` INTEGER NOT NULL,
                        `useCount`        INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // ── Step 3: search_candidate_cache table + index ──────────────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_candidate_cache` (
                        `normalizedQuery`  TEXT NOT NULL,
                        `resultRank`       INTEGER NOT NULL,
                        `provider`         TEXT NOT NULL,
                        `providerSourceId` TEXT NOT NULL,
                        `title`            TEXT NOT NULL,
                        `artist`           TEXT NOT NULL,
                        `album`            TEXT,
                        `durationMs`       INTEGER NOT NULL,
                        `artworkUrl`       TEXT,
                        `canonicalWatchUrl` TEXT NOT NULL,
                        `cachedAtEpochMs`  INTEGER NOT NULL,
                        PRIMARY KEY(`normalizedQuery`, `resultRank`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_candidate_cache_normalizedQuery` ON `search_candidate_cache` (`normalizedQuery`)"
                )

                // ── Step 4: Safe legacy artwork repair ────────────────────────────────────
                //
                // Mark tracks that have a genuine YouTube match with YOUTUBE_MATCH.
                // These must NOT be cleared.
                db.execSQL(
                    """
                    UPDATE `tracks`
                    SET `artworkOrigin` = 'YOUTUBE_MATCH'
                    WHERE `id` IN (
                        SELECT DISTINCT `trackId` FROM `track_sources`
                        WHERE `selected` = 1
                          AND `artworkUrl` IS NOT NULL
                          AND `artworkUrl` != ''
                    )
                    """.trimIndent()
                )

                // For tracks with YOUTUBE_MATCH: update artworkUrl from track_source if the
                // track's artworkUrl is currently a stale playlist copy (= playlist artworkUri).
                // This restores genuine YouTube artwork in one go.
                db.execSQL(
                    """
                    UPDATE `tracks`
                    SET `artworkUrl`  = (
                            SELECT `ts`.`artworkUrl`
                            FROM `track_sources` `ts`
                            WHERE `ts`.`trackId` = `tracks`.`id`
                              AND `ts`.`selected` = 1
                              AND `ts`.`artworkUrl` IS NOT NULL
                              AND `ts`.`artworkUrl` != ''
                            LIMIT 1
                        ),
                        `artworkUri`  = (
                            SELECT `ts`.`artworkUrl`
                            FROM `track_sources` `ts`
                            WHERE `ts`.`trackId` = `tracks`.`id`
                              AND `ts`.`selected` = 1
                              AND `ts`.`artworkUrl` IS NOT NULL
                              AND `ts`.`artworkUrl` != ''
                            LIMIT 1
                        )
                    WHERE `artworkOrigin` = 'YOUTUBE_MATCH'
                    """.trimIndent()
                )

                // Mark & clear tracks where artworkUrl equals playlist artworkUri
                // (the stale playlist-cover fallback from old saveScrapedPlaylist).
                // Only applies to tracks that are NOT already YOUTUBE_MATCH.
                db.execSQL(
                    """
                    UPDATE `tracks`
                    SET `artworkOrigin` = 'PLAYLIST_FALLBACK_LEGACY',
                        `artworkUrl`    = NULL,
                        `artworkUri`    = NULL
                    WHERE `artworkOrigin` = 'UNKNOWN'
                      AND `artworkUrl` IS NOT NULL
                      AND `artworkUrl` IN (
                          SELECT DISTINCT `p`.`artworkUri`
                          FROM `playlists` `p`
                          INNER JOIN `playlist_entries` `pe` ON `pe`.`playlistId` = `p`.`playlistId`
                          WHERE `pe`.`trackId` = `tracks`.`id`
                            AND `p`.`artworkUri` IS NOT NULL
                      )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4:
         *  1. Adds `artworkEnrichmentStatus TEXT NOT NULL DEFAULT 'NOT_ATTEMPTED'` to `tracks`.
         *  2. Adds `lastArtworkAttemptEpochMs INTEGER NOT NULL DEFAULT 0` to `tracks`.
         *  3. Sets `artworkEnrichmentStatus = 'ENRICHED'` for tracks that already carry genuine artwork
         *     (artworkOrigin IN ('SPOTIFY_TRACK', 'YOUTUBE_MATCH') and artworkUrl/Uri is not null).
         *  4. Creates `recent_search_items` table + index on `lastInteractedAtEpochMs`.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `artworkEnrichmentStatus` TEXT NOT NULL DEFAULT 'NOT_ATTEMPTED'"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `lastArtworkAttemptEpochMs` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    UPDATE `tracks`
                    SET `artworkEnrichmentStatus` = 'ENRICHED'
                    WHERE `artworkOrigin` IN ('SPOTIFY_TRACK', 'YOUTUBE_MATCH')
                      AND (`artworkUrl` IS NOT NULL OR `artworkUri` IS NOT NULL)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_search_items` (
                        `id`                      TEXT NOT NULL PRIMARY KEY,
                        `provider`                TEXT NOT NULL,
                        `providerSourceId`        TEXT NOT NULL,
                        `catalogTrackId`          TEXT,
                        `title`                   TEXT NOT NULL,
                        `artist`                  TEXT NOT NULL,
                        `album`                   TEXT,
                        `durationMs`              INTEGER,
                        `artworkUrl`              TEXT,
                        `itemType`                TEXT NOT NULL,
                        `lastInteractedAtEpochMs` INTEGER NOT NULL,
                        `sourceContext`           TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_search_items_lastInteractedAtEpochMs` ON `recent_search_items` (`lastInteractedAtEpochMs`)"
                )
            }
        }

        /**
         * v4 → v5:
         *  1. Non-destructive deduplication of playlist_entries keeping MIN(entryId) per (playlistId, trackId).
         *  2. Unique index on playlist_entries(playlistId, trackId).
         *  3. Adds artworkAttemptCount INTEGER NOT NULL DEFAULT 0 to tracks.
         *  4. Migrates any remaining tracks with 'NOT_ATTEMPTED' status to canonical 'PENDING'.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM `playlist_entries`
                    WHERE `entryId` NOT IN (
                        SELECT MIN(`entryId`) FROM `playlist_entries` GROUP BY `playlistId`, `trackId`
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_entries_playlistId_trackId` ON `playlist_entries` (`playlistId`, `trackId`)"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `artworkAttemptCount` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE `tracks` SET `artworkEnrichmentStatus` = 'PENDING' WHERE `artworkEnrichmentStatus` = 'NOT_ATTEMPTED'"
                )
            }
        }

        /**
         * v5 → v6:
         *  1. Creates `offline_downloads` table for persistent NotiFy offline audio storage.
         *  2. Creates indices for efficient lookups by trackId, status, bucket+LRU, and unique relativeStorageKey.
         *  Non-destructive: no existing tables or columns are modified.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_downloads` (
                        `downloadId`           TEXT NOT NULL PRIMARY KEY,
                        `trackId`              TEXT NOT NULL,
                        `provider`             TEXT NOT NULL,
                        `providerSourceId`     TEXT NOT NULL,
                        `canonicalUrl`         TEXT NOT NULL,
                        `relativeStorageKey`   TEXT NOT NULL,
                        `bucket`               TEXT NOT NULL,
                        `status`               TEXT NOT NULL DEFAULT 'PENDING',
                        `fileSizeBytes`        INTEGER,
                        `mimeType`             TEXT,
                        `bitrateKbps`          INTEGER,
                        `durationMs`           INTEGER,
                        `qualityProfile`       TEXT NOT NULL DEFAULT 'NORMAL',
                        `progressPercent`      INTEGER NOT NULL DEFAULT 0,
                        `errorMessage`         TEXT,
                        `attemptCount`         INTEGER NOT NULL DEFAULT 0,
                        `createdAtEpochMs`     INTEGER NOT NULL,
                        `updatedAtEpochMs`     INTEGER NOT NULL,
                        `lastAccessedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_downloads_trackId` ON `offline_downloads` (`trackId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_downloads_relativeStorageKey` ON `offline_downloads` (`relativeStorageKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_downloads_status` ON `offline_downloads` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_downloads_bucket_lastAccessedAtEpochMs` ON `offline_downloads` (`bucket`, `lastAccessedAtEpochMs`)"
                )
            }
        }

        /**
         * v6 → v7:
         *  Creates `download_queue` table for persistent Room-backed serial download queue.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `download_queue`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_queue` (
                        `trackId`               TEXT NOT NULL,
                        `status`                TEXT NOT NULL,
                        `progressPercent`       INTEGER NOT NULL,
                        `trackTitle`            TEXT NOT NULL,
                        `artist`                TEXT NOT NULL,
                        `album`                 TEXT,
                        `durationMs`            INTEGER NOT NULL,
                        `artworkUrl`            TEXT,
                        `playlistId`            TEXT,
                        `playlistPosition`      INTEGER NOT NULL,
                        `queuePosition`         INTEGER NOT NULL,
                        `attemptCount`          INTEGER NOT NULL,
                        `errorMessage`          TEXT,
                        `claimedByWorkerId`     TEXT,
                        `lastProgressEpochMs`   INTEGER NOT NULL,
                        `createdAtEpochMs`      INTEGER NOT NULL,
                        `updatedAtEpochMs`      INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_status` ON `download_queue` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_queuePosition` ON `download_queue` (`queuePosition`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_playlistId` ON `download_queue` (`playlistId`)")
            }
        }

        fun getInstance(context: Context): NotiFyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotiFyDatabase::class.java,
                    "notify_database.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setTestInstance(testInstance: NotiFyDatabase?) {
            synchronized(this) {
                INSTANCE = testInstance
            }
        }
    }
}
