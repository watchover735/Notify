package com.notify.core.downloads.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.notify.core.model.DownloadBucket
import java.io.File

/**
 * Manages the on-disk storage layout for NotiFy offline audio downloads.
 *
 * Directory structure under `context.filesDir`:
 * ```
 * notify_offline/
 *   pinned/     — User-pinned downloads, never auto-evicted
 *   smart/      — Auto-saved downloads, LRU eviction under budget
 *   temp_parts/ — Incomplete .part files during download
 * ```
 *
 * INVARIANT: Only relative keys are persisted to Room.
 * INVARIANT: No absolute paths escape this class; callers get content:// URIs.
 */
class OfflineStorage(private val context: Context) {

    companion object {
        private const val TAG = "OfflineStorage"
        private const val ROOT_DIR = "notify_offline"
        private const val PINNED_DIR = "pinned"
        private const val SMART_DIR = "smart"
        private const val TEMP_PARTS_DIR = "temp_parts"
        const val PART_EXTENSION = ".part"

        /**
         * Generates a stable, deterministic relative storage key from provider + sourceId.
         * Format: `{bucket_prefix}/{provider}_{sourceId}.{ext}`
         */
        fun buildRelativeKey(
            bucket: DownloadBucket,
            provider: String,
            sourceId: String,
            extension: String = "m4a"
        ): String {
            val bucketPrefix = when (bucket) {
                DownloadBucket.PINNED -> PINNED_DIR
                DownloadBucket.SMART_OFFLINE -> SMART_DIR
                DownloadBucket.TEMPORARY_CACHE -> SMART_DIR // temp cache uses Media3 SimpleCache, not this
            }
            val sanitizedProvider = provider.lowercase().replace(Regex("[^a-z0-9]"), "")
            val sanitizedSourceId = sourceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return "$bucketPrefix/${sanitizedProvider}_$sanitizedSourceId.$extension"
        }
    }

    private val rootDir: File
        get() = File(context.filesDir, ROOT_DIR)

    private val pinnedDir: File
        get() = File(rootDir, PINNED_DIR)

    private val smartDir: File
        get() = File(rootDir, SMART_DIR)

    private val tempPartsDir: File
        get() = File(rootDir, TEMP_PARTS_DIR)

    /**
     * Ensures all storage directories exist. Call once at app startup.
     */
    fun ensureDirectoriesExist() {
        pinnedDir.mkdirs()
        smartDir.mkdirs()
        tempPartsDir.mkdirs()
    }

    /**
     * Resolves a relative storage key to its absolute File.
     * For internal use only — external callers should use [getContentUri].
     */
    fun resolveFile(relativeKey: String): File {
        return File(rootDir, relativeKey)
    }

    /**
     * Returns the .part temporary file for a download in progress.
     */
    fun getPartFile(relativeKey: String): File {
        val name = relativeKey.replace("/", "_") + PART_EXTENSION
        return File(tempPartsDir, name)
    }

    /**
     * Atomically promotes a .part file to its final location.
     * Creates parent directories if needed.
     *
     * @return true if promotion succeeded
     */
    fun promotePartFile(relativeKey: String): Boolean {
        val partFile = getPartFile(relativeKey)
        val finalFile = resolveFile(relativeKey)

        if (!partFile.exists()) {
            Log.e(TAG, "Part file does not exist: ${partFile.name}")
            return false
        }

        if (partFile.length() == 0L) {
            Log.e(TAG, "Part file is empty: ${partFile.name}")
            partFile.delete()
            return false
        }

        finalFile.parentFile?.mkdirs()

        return try {
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val success = partFile.renameTo(finalFile)
            if (!success) {
                // Fallback: copy + delete (cross-filesystem rename)
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            Log.d(TAG, "Promoted ${partFile.name} -> ${finalFile.name} (${finalFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote part file: ${e.message}", e)
            false
        }
    }

    /**
     * Returns a content:// URI for the given relative key via NotiFyOfflineProvider.
     * Returns null if the file does not exist.
     */
    fun getContentUri(relativeKey: String): Uri? {
        val file = resolveFile(relativeKey)
        if (!file.exists()) {
            Log.w(TAG, "File does not exist for key: $relativeKey")
            return null
        }
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.offline",
                file
            )
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider failed, falling back to file URI: ${e.message}")
            try {
                Uri.fromFile(file)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Checks if the audio file for a given key exists and is non-empty.
     */
    fun isFileValid(relativeKey: String): Boolean {
        val file = resolveFile(relativeKey)
        return file.exists() && file.length() > 0
    }

    /**
     * Deletes the audio file for a given key.
     * @return true if the file was deleted or already absent
     */
    fun deleteFile(relativeKey: String): Boolean {
        val file = resolveFile(relativeKey)
        return if (file.exists()) {
            file.delete().also { success ->
                if (success) Log.d(TAG, "Deleted: $relativeKey")
                else Log.w(TAG, "Failed to delete: $relativeKey")
            }
        } else {
            true
        }
    }

    /**
     * Returns total bytes used in pinned storage.
     */
    fun pinnedSizeBytes(): Long = dirSizeBytes(pinnedDir)

    /**
     * Returns total bytes used in smart offline storage.
     */
    fun smartSizeBytes(): Long = dirSizeBytes(smartDir)

    /**
     * Returns total offline storage usage.
     */
    fun totalSizeBytes(): Long = pinnedSizeBytes() + smartSizeBytes()

    /**
     * Returns available free space on the filesystem.
     */
    fun availableFreeSpaceBytes(): Long {
        return rootDir.usableSpace
    }

    /**
     * Cleans up orphan .part files older than the given cutoff, never deleting active/paused files.
     */
    fun cleanupStalePartFiles(
        maxAgeMs: Long = 15 * 60 * 1000L,
        excludeFileNames: Set<String> = emptySet()
    ) {
        val now = System.currentTimeMillis()
        tempPartsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(PART_EXTENSION)) {
                if (!excludeFileNames.contains(file.name) && (now - file.lastModified() > maxAgeMs)) {
                    file.delete()
                    Log.d(TAG, "Cleaned up stale part: ${file.name}")
                }
            }
        }
    }

    /**
     * Moves a file between buckets (e.g. smart -> pinned).
     * @return the new relative key, or null on failure.
     */
    fun moveFileToBucket(currentKey: String, targetBucket: DownloadBucket): String? {
        val currentFile = resolveFile(currentKey)
        if (!currentFile.exists()) return null

        // Parse the filename from the current key
        val filename = currentFile.name
        val targetPrefix = when (targetBucket) {
            DownloadBucket.PINNED -> PINNED_DIR
            DownloadBucket.SMART_OFFLINE -> SMART_DIR
            DownloadBucket.TEMPORARY_CACHE -> return null // Not applicable
        }
        val newKey = "$targetPrefix/$filename"

        if (newKey == currentKey) return currentKey // already in target bucket

        val newFile = resolveFile(newKey)
        newFile.parentFile?.mkdirs()
        return try {
            currentFile.renameTo(newFile).let { success ->
                if (!success) {
                    currentFile.copyTo(newFile, overwrite = true)
                    currentFile.delete()
                }
            }
            Log.d(TAG, "Moved $currentKey -> $newKey")
            newKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move file: ${e.message}", e)
            null
        }
    }

    private fun dirSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
