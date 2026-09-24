package com.notify.core.downloads.storage

import android.content.Context
import com.notify.core.model.DownloadBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OfflineStorageTest {

    private lateinit var context: Context
    private lateinit var storage: OfflineStorage

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = OfflineStorage(context)
        storage.ensureDirectoriesExist()
    }

    @Test
    fun buildRelativeKey_formatsDeterministicKeys() {
        val pinnedKey = OfflineStorage.buildRelativeKey(
            bucket = DownloadBucket.PINNED,
            provider = "YOUTUBE",
            sourceId = "dQw4w9WgXcQ",
            extension = "opus"
        )
        assertEquals("pinned/youtube_dQw4w9WgXcQ.opus", pinnedKey)

        val smartKey = OfflineStorage.buildRelativeKey(
            bucket = DownloadBucket.SMART_OFFLINE,
            provider = "YouTube",
            sourceId = "abc-123_xyz",
            extension = "m4a"
        )
        assertEquals("smart/youtube_abc-123_xyz.m4a", smartKey)
    }

    @Test
    fun buildRelativeKey_sanitizesSpecialCharacters() {
        val key = OfflineStorage.buildRelativeKey(
            bucket = DownloadBucket.PINNED,
            provider = "YOUTUBE!@#$",
            sourceId = "bad/source/id",
            extension = "m4a"
        )
        assertEquals("pinned/youtube_bad_source_id.m4a", key)
    }

    @Test
    fun getPartFile_returnsFileInTempParts() {
        val relKey = "pinned/youtube_123.opus"
        val partFile = storage.getPartFile(relKey)
        assertTrue(partFile.name.endsWith(".part"))
        assertEquals("pinned_youtube_123.opus.part", partFile.name)
        assertEquals("temp_parts", partFile.parentFile?.name)
    }

    @Test
    fun promotePartFile_failsOnMissingOrEmptyPartFile() {
        val relKey = "pinned/non_existent.opus"
        assertFalse("Missing part file promotion should fail", storage.promotePartFile(relKey))

        val partFile = storage.getPartFile(relKey)
        partFile.parentFile?.mkdirs()
        partFile.createNewFile() // 0-byte file
        assertFalse("0-byte part file promotion should fail", storage.promotePartFile(relKey))
        assertFalse("0-byte part file should be cleaned up", partFile.exists())
    }

    @Test
    fun promotePartFile_promotesNonEmptyFileSuccessfully() {
        val relKey = "pinned/youtube_song.opus"
        val partFile = storage.getPartFile(relKey)
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val success = storage.promotePartFile(relKey)
        assertTrue("Promotion must succeed", success)
        assertFalse("Part file must no longer exist", partFile.exists())

        val resolved = storage.resolveFile(relKey)
        assertTrue("Final promoted file must exist", resolved.exists())
        assertEquals(5L, resolved.length())
    }

    @Test
    fun isFileValid_validatesExistenceAndNonZeroSize() {
        val relKey = "smart/valid_test.opus"
        assertFalse(storage.isFileValid(relKey))

        val file = storage.resolveFile(relKey)
        file.parentFile?.mkdirs()
        file.createNewFile()
        assertFalse("0-byte file is not valid", storage.isFileValid(relKey))

        file.writeBytes(byteArrayOf(10, 20, 30))
        assertTrue("Non-empty file is valid", storage.isFileValid(relKey))
    }

    @Test
    fun deleteFile_deletesExistingFile() {
        val relKey = "smart/to_delete.opus"
        val file = storage.resolveFile(relKey)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(file.exists())

        val deleted = storage.deleteFile(relKey)
        assertTrue(deleted)
        assertFalse(file.exists())

        // Calling delete on non-existent file returns true (idempotent)
        assertTrue(storage.deleteFile(relKey))
    }

    @Test
    fun moveFileToBucket_movesBetweenSmartAndPinned() {
        val smartKey = "smart/youtube_move.opus"
        val file = storage.resolveFile(smartKey)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val newKey = storage.moveFileToBucket(smartKey, DownloadBucket.PINNED)
        assertNotNull(newKey)
        assertEquals("pinned/youtube_move.opus", newKey)

        assertFalse("Old file in smart must no longer exist", storage.resolveFile(smartKey).exists())
        val newFile = storage.resolveFile(newKey!!)
        assertTrue("New file in pinned must exist", newFile.exists())
        assertEquals(4L, newFile.length())
    }

    @Test
    fun cleanupStalePartFiles_deletesOldPartsKeepsFreshParts() {
        val oldKey = "pinned/old_track.opus"
        val freshKey = "pinned/fresh_track.opus"

        val oldPart = storage.getPartFile(oldKey)
        oldPart.parentFile?.mkdirs()
        oldPart.writeBytes(byteArrayOf(1, 2))
        // Set last modified to 48 hours ago
        oldPart.setLastModified(System.currentTimeMillis() - 48 * 3600 * 1000L)

        val freshPart = storage.getPartFile(freshKey)
        freshPart.writeBytes(byteArrayOf(3, 4))

        storage.cleanupStalePartFiles(maxAgeMs = 24 * 3600 * 1000L)

        assertFalse("Stale part file must be deleted", oldPart.exists())
        assertTrue("Fresh part file must be preserved", freshPart.exists())
    }
}
