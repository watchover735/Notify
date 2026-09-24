package com.notify.core.downloads.storage

import android.content.Context
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SmartStorageManagerTest {

    private lateinit var context: Context
    private lateinit var storage: OfflineStorage
    private lateinit var prefs: DownloadPreferences
    private lateinit var fakeDao: FakeOfflineDownloadDao
    private lateinit var manager: SmartStorageManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = OfflineStorage(context)
        storage.ensureDirectoriesExist()
        prefs = DownloadPreferences(context)
        fakeDao = FakeOfflineDownloadDao()
        manager = SmartStorageManager(storage, prefs, fakeDao)
    }

    @Test
    fun canDownload_returnsTrueWhenSufficientSpace() {
        // Device in test environment has free space
        val can = manager.canDownload(10_000_000L) // 10 MB
        assertTrue("Should allow download when within available space", can)
    }

    @Test
    fun evictIfOverBudget_evictsLruEntriesUntilWithinBudget() = runBlocking {
        // Set budget to 10 MB
        prefs.smartBudgetBytes = 10_000_000L

        // Create two 6 MB files in smart offline storage (total 12 MB -> 2 MB over budget)
        val file1 = storage.resolveFile("smart/song1.opus")
        file1.parentFile?.mkdirs()
        file1.writeBytes(ByteArray(6_000_000))

        val file2 = storage.resolveFile("smart/song2.opus")
        file2.writeBytes(ByteArray(6_000_000))

        val entity1 = OfflineDownloadEntity(
            downloadId = "dl_1",
            trackId = "t1",
            provider = "YOUTUBE",
            providerSourceId = "s1",
            canonicalUrl = "url1",
            relativeStorageKey = "smart/song1.opus",
            bucket = "SMART_OFFLINE",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = 6_000_000L,
            lastAccessedAtEpochMs = 100L // older
        )
        val entity2 = OfflineDownloadEntity(
            downloadId = "dl_2",
            trackId = "t2",
            provider = "YOUTUBE",
            providerSourceId = "s2",
            canonicalUrl = "url2",
            relativeStorageKey = "smart/song2.opus",
            bucket = "SMART_OFFLINE",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = 6_000_000L,
            lastAccessedAtEpochMs = 200L // newer
        )
        fakeDao.smartEntries.add(entity1)
        fakeDao.smartEntries.add(entity2)

        val evictedCount = manager.evictIfOverBudget()
        assertEquals("Should evict exactly 1 entry to clear the 2 MB overage", 1, evictedCount)
        assertEquals(OfflineDownloadStatus.EVICTED, fakeDao.updatedStatuses["dl_1"])
        assertFalse("Evicted file on disk should be deleted", file1.exists())
        assertTrue("Second file should remain untouched", file2.exists())
    }

    @Test
    fun evictIfOverBudget_doesNothingWhenUnderBudget() = runBlocking {
        prefs.smartBudgetBytes = 100_000_000L

        val file = storage.resolveFile("smart/small.opus")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1_000_000))

        fakeDao.smartEntries.add(
            OfflineDownloadEntity(
                downloadId = "dl_small",
                trackId = "t_small",
                provider = "YOUTUBE",
                providerSourceId = "s_small",
                canonicalUrl = "url",
                relativeStorageKey = "smart/small.opus",
                bucket = "SMART_OFFLINE",
                status = OfflineDownloadStatus.COMPLETED,
                fileSizeBytes = 1_000_000L
            )
        )

        val evicted = manager.evictIfOverBudget()
        assertEquals(0, evicted)
        assertTrue(file.exists())
    }

    @Test
    fun storageSummary_formatsCorrectly() {
        val summary = StorageSummary(
            pinnedBytes = 100 * 1024 * 1024L,
            smartOfflineBytes = 50 * 1024 * 1024L,
            totalBytes = 150 * 1024 * 1024L,
            smartBudgetBytes = 1024 * 1024 * 1024L,
            freeSpaceBytes = 5 * 1024 * 1024 * 1024L,
            freeSpaceReserveBytes = 1024 * 1024 * 1024L
        )

        assertFalse(summary.isOverBudget)
        assertFalse(summary.isLowStorage)
        assertTrue(summary.formatBytes(summary.totalBytes).contains("MB"))
    }

    class FakeOfflineDownloadDao : OfflineDownloadDao {
        val smartEntries = mutableListOf<OfflineDownloadEntity>()
        val updatedStatuses = mutableMapOf<String, String>()

        override suspend fun insertIgnore(entity: OfflineDownloadEntity): Long = 1L
        override suspend fun upsert(entity: OfflineDownloadEntity) {}
        override suspend fun getById(downloadId: String): OfflineDownloadEntity? = null
        override suspend fun getCompletedForTrack(trackId: String): OfflineDownloadEntity? = null
        override suspend fun getLatestForTrack(trackId: String): OfflineDownloadEntity? = null
        override suspend fun getActiveForTrack(trackId: String): OfflineDownloadEntity? = null
        override suspend fun isTrackDownloaded(trackId: String): Boolean = false
        override suspend fun getCompletedForSource(sourceId: String): OfflineDownloadEntity? = null
        override suspend fun getCompletedForYouTubeSource(sourceId: String): OfflineDownloadEntity? = null
        override suspend fun getCompletedForProviderAndSource(provider: String, sourceId: String): OfflineDownloadEntity? = null
        override suspend fun isRecordingDownloaded(trackId: String, sourceId: String): Boolean = false
        override fun observeIsRecordingDownloaded(trackId: String, sourceId: String): Flow<Boolean> = emptyFlow()
        override suspend fun updateStatus(downloadId: String, status: String, updatedAt: Long) {
            updatedStatuses[downloadId] = status
        }
        override suspend fun updateProgress(downloadId: String, status: String, progress: Int, updatedAt: Long) {}
        override suspend fun markCompleted(downloadId: String, sizeBytes: Long, mimeType: String?, bitrateKbps: Int?, durationMs: Long?, updatedAt: Long) {}
        override suspend fun markFailed(downloadId: String, errorMessage: String?, updatedAt: Long) {}
        override suspend fun touchAccessTime(downloadId: String, accessedAt: Long) {}
        override suspend fun updateBucket(downloadId: String, bucket: String, updatedAt: Long) {}
        override fun observeCompletedDownloads(): Flow<List<OfflineDownloadEntity>> = emptyFlow()
        override suspend fun getCompletedDownloads(): List<OfflineDownloadEntity> = emptyList()
        override suspend fun getActiveDownloads(): List<OfflineDownloadEntity> = emptyList()
        override fun observeActiveDownloads(): Flow<List<OfflineDownloadEntity>> = emptyFlow()
        override suspend fun getNextPending(): OfflineDownloadEntity? = null
        override suspend fun getSmartOfflineEntriesLru(): List<OfflineDownloadEntity> = smartEntries
        override fun observeCompletedCount(): Flow<Int> = emptyFlow()
        override suspend fun getTotalDownloadedBytes(): Long = 0L
        override suspend fun delete(downloadId: String) {}
        override suspend fun cleanupTerminalEntries(): Int = 0
        override suspend fun resetStaleActiveDownloads(now: Long, staleCutoffMs: Long): Int = 0
        override fun observeNonTerminalDownloads(): Flow<List<OfflineDownloadEntity>> = emptyFlow()
        override fun observeFailedDownloads(): Flow<List<OfflineDownloadEntity>> = emptyFlow()
    }
}
