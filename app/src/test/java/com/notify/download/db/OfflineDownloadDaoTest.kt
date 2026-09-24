package com.notify.download.db

import android.app.Application
import androidx.room.Room
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
class OfflineDownloadDaoTest {

    private lateinit var app: Application
    private lateinit var db: NotiFyDatabase
    private lateinit var dao: OfflineDownloadDao

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.offlineDownloadDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createEntity(
        downloadId: String = "dl_1",
        trackId: String = "tr_1",
        provider: String = "YOUTUBE",
        providerSourceId: String = downloadId,
        bucket: String = "PINNED",
        qualityProfile: String = "HIGH",
        status: String = "PENDING",
        progressPercent: Int = 0,
        fileSizeBytes: Long? = null,
        relativeStorageKey: String? = null,
        lastAccessedEpochMs: Long = 1000L
    ): OfflineDownloadEntity {
        val now = System.currentTimeMillis()
        val key = relativeStorageKey ?: "$bucket/${provider.lowercase()}_$providerSourceId.opus"
        return OfflineDownloadEntity(
            downloadId = downloadId,
            trackId = trackId,
            provider = provider,
            providerSourceId = providerSourceId,
            canonicalUrl = "https://youtube.com/watch?v=$providerSourceId",
            relativeStorageKey = key,
            bucket = bucket,
            status = status,
            fileSizeBytes = fileSizeBytes,
            mimeType = "audio/ogg",
            bitrateKbps = 160,
            durationMs = 180000L,
            qualityProfile = qualityProfile,
            progressPercent = progressPercent,
            errorMessage = null,
            attemptCount = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastAccessedAtEpochMs = lastAccessedEpochMs
        )
    }

    @Test
    fun insertAndGetById_retrievesEntity() = runBlocking {
        val entity = createEntity(downloadId = "dl_test_1")
        dao.upsert(entity)

        val retrieved = dao.getById("dl_test_1")
        assertNotNull(retrieved)
        assertEquals("dl_test_1", retrieved?.downloadId)
        assertEquals("tr_1", retrieved?.trackId)
        assertEquals("PENDING", retrieved?.status)
    }

    @Test
    fun getCompletedForTrack_returnsOnlyCompleted() = runBlocking {
        val pending = createEntity(downloadId = "dl_p", trackId = "track_a", status = "DOWNLOADING")
        dao.upsert(pending)

        var completed = dao.getCompletedForTrack("track_a")
        assertNull("Downloading track should not be returned as completed", completed)

        val done = createEntity(
            downloadId = "dl_c",
            trackId = "track_a",
            status = "COMPLETED",
            fileSizeBytes = 3500000L,
            relativeStorageKey = "pinned/track_a.opus"
        )
        dao.upsert(done)

        completed = dao.getCompletedForTrack("track_a")
        assertNotNull(completed)
        assertEquals("dl_c", completed?.downloadId)
        assertEquals("COMPLETED", completed?.status)
    }

    @Test
    fun getCompletedForSource_returnsCompletedMatch() = runBlocking {
        val entity = createEntity(
            downloadId = "dl_src_1",
            trackId = "tr_x",
            providerSourceId = "video_999",
            status = "COMPLETED",
            fileSizeBytes = 5000000L
        )
        dao.upsert(entity)

        val found = dao.getCompletedForSource("video_999")
        assertNotNull(found)
        assertEquals("dl_src_1", found?.downloadId)
    }

    @Test
    fun markCompleted_updatesStatusAndFileMetadata() = runBlocking {
        val entity = createEntity(downloadId = "dl_prog", status = "DOWNLOADING", relativeStorageKey = "pinned/song.opus")
        dao.upsert(entity)

        val updatedTime = System.currentTimeMillis()
        dao.markCompleted(
            downloadId = "dl_prog",
            sizeBytes = 4200000L,
            mimeType = "audio/ogg",
            bitrateKbps = 160,
            durationMs = 215000L,
            updatedAt = updatedTime
        )

        val retrieved = dao.getById("dl_prog")
        assertNotNull(retrieved)
        assertEquals("COMPLETED", retrieved?.status)
        assertEquals(100, retrieved?.progressPercent)
        assertEquals(4200000L, retrieved?.fileSizeBytes)
        assertEquals("pinned/song.opus", retrieved?.relativeStorageKey)
        assertEquals(215000L, retrieved?.durationMs)
    }

    @Test
    fun updateBucket_switchesBetweenPinnedAndSmartOffline() = runBlocking {
        val entity = createEntity(downloadId = "dl_bucket", bucket = "SMART_OFFLINE", status = "COMPLETED")
        dao.upsert(entity)

        dao.updateBucket("dl_bucket", "PINNED", System.currentTimeMillis())
        var retrieved = dao.getById("dl_bucket")
        assertEquals("PINNED", retrieved?.bucket)

        dao.updateBucket("dl_bucket", "SMART_OFFLINE", System.currentTimeMillis())
        retrieved = dao.getById("dl_bucket")
        assertEquals("SMART_OFFLINE", retrieved?.bucket)
    }

    @Test
    fun getSmartOfflineEntriesLru_ordersByLastAccessedAscending() = runBlocking {
        val old = createEntity(downloadId = "dl_old", trackId = "t_old", bucket = "SMART_OFFLINE", status = "COMPLETED", lastAccessedEpochMs = 100L)
        val mid = createEntity(downloadId = "dl_mid", trackId = "t_mid", bucket = "SMART_OFFLINE", status = "COMPLETED", lastAccessedEpochMs = 200L)
        val recent = createEntity(downloadId = "dl_recent", trackId = "t_recent", bucket = "SMART_OFFLINE", status = "COMPLETED", lastAccessedEpochMs = 300L)
        val pinned = createEntity(downloadId = "dl_pin", trackId = "t_pin", bucket = "PINNED", status = "COMPLETED", lastAccessedEpochMs = 50L)

        dao.upsert(recent)
        dao.upsert(old)
        dao.upsert(pinned)
        dao.upsert(mid)

        val lruList = dao.getSmartOfflineEntriesLru()
        assertEquals(3, lruList.size)
        assertEquals("dl_old", lruList[0].downloadId)
        assertEquals("dl_mid", lruList[1].downloadId)
        assertEquals("dl_recent", lruList[2].downloadId)
    }

    @Test
    fun touchAccessTime_updatesTimestamp() = runBlocking {
        val entity = createEntity(downloadId = "dl_touch", lastAccessedEpochMs = 500L)
        dao.upsert(entity)

        dao.touchAccessTime("dl_touch", 99999L)
        val retrieved = dao.getById("dl_touch")
        assertEquals(99999L, retrieved?.lastAccessedAtEpochMs)
    }

    @Test
    fun getTotalDownloadedBytes_sumsCompletedDownloads() = runBlocking {
        val d1 = createEntity(downloadId = "d1", trackId = "t1", status = "COMPLETED", fileSizeBytes = 2000L)
        val d2 = createEntity(downloadId = "d2", trackId = "t2", status = "COMPLETED", fileSizeBytes = 3000L)
        val d3 = createEntity(downloadId = "d3", trackId = "t3", status = "FAILED", fileSizeBytes = 5000L)

        dao.upsert(d1)
        dao.upsert(d2)
        dao.upsert(d3)

        val totalBytes = dao.getTotalDownloadedBytes()
        assertEquals(5000L, totalBytes)
    }

    @Test
    fun observeCompletedDownloads_emitsFlow() = runBlocking {
        val d1 = createEntity(downloadId = "flow_1", trackId = "t1", status = "COMPLETED")
        dao.upsert(d1)

        val list = dao.observeCompletedDownloads().first()
        assertEquals(1, list.size)
        assertEquals("flow_1", list[0].downloadId)
    }
}
