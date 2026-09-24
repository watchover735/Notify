package com.notify.core.downloads.storage

import android.util.Log
import com.notify.core.downloads.db.OfflineDownloadDao
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.model.DownloadBucket

/**
 * Smart storage manager: enforces storage budgets, performs LRU eviction,
 * and checks safety constraints before allowing new downloads.
 *
 * Rules:
 * - PINNED: never auto-evicted
 * - SMART_OFFLINE: LRU eviction when over budget (default 1 GB)
 * - Free space reserve: 1 GB minimum maintained
 * - Downloads pause when free space is critically low
 */
class SmartStorageManager(
    private val offlineStorage: OfflineStorage,
    private val preferences: DownloadPreferences,
    private val downloadDao: OfflineDownloadDao
) {

    companion object {
        private const val TAG = "SmartStorageManager"
    }

    /**
     * Checks if a new download of the given estimated size can proceed safely.
     */
    fun canDownload(estimatedSizeBytes: Long): Boolean {
        val freeSpace = offlineStorage.availableFreeSpaceBytes()
        val reserve = preferences.freeSpaceReserveBytes
        val available = freeSpace - reserve

        if (available < estimatedSizeBytes) {
            Log.w(TAG, "Insufficient storage: free=$freeSpace, reserve=$reserve, need=$estimatedSizeBytes")
            return false
        }
        return true
    }

    /**
     * Enforces the smart offline storage budget by evicting least-recently-used entries.
     * Only evicts SMART_OFFLINE downloads (never PINNED).
     *
     * @param requiredFreeBytes additional space needed for a pending download
     * @return number of entries evicted
     */
    suspend fun evictIfOverBudget(requiredFreeBytes: Long = 0L): Int {
        val budget = preferences.smartBudgetBytes
        val currentSmartSize = offlineStorage.smartSizeBytes()
        val overage = (currentSmartSize + requiredFreeBytes) - budget

        if (overage <= 0) {
            return 0 // Within budget
        }

        Log.d(TAG, "Smart storage over budget by $overage bytes, evicting LRU entries")

        // Get smart offline downloads sorted by LRU (oldest accessed first)
        val smartEntries = downloadDao.getSmartOfflineEntriesLru()
        var freedBytes = 0L
        var evictedCount = 0

        for (entry in smartEntries) {
            if (freedBytes >= overage) break

            val fileSize = entry.fileSizeBytes ?: 0L
            offlineStorage.deleteFile(entry.relativeStorageKey)
            downloadDao.updateStatus(
                downloadId = entry.downloadId,
                status = OfflineDownloadStatus.EVICTED,
                updatedAt = System.currentTimeMillis()
            )
            freedBytes += fileSize
            evictedCount++
            Log.d(TAG, "Evicted: ${entry.relativeStorageKey} (${fileSize} bytes)")
        }

        Log.d(TAG, "Evicted $evictedCount entries, freed $freedBytes bytes")
        return evictedCount
    }

    /**
     * Returns a storage accounting summary for UI display.
     */
    fun getStorageSummary(): StorageSummary {
        return StorageSummary(
            pinnedBytes = offlineStorage.pinnedSizeBytes(),
            smartOfflineBytes = offlineStorage.smartSizeBytes(),
            totalBytes = offlineStorage.totalSizeBytes(),
            smartBudgetBytes = preferences.smartBudgetBytes,
            freeSpaceBytes = offlineStorage.availableFreeSpaceBytes(),
            freeSpaceReserveBytes = preferences.freeSpaceReserveBytes
        )
    }
}

/**
 * Storage accounting summary for display in the Library UI.
 */
data class StorageSummary(
    val pinnedBytes: Long,
    val smartOfflineBytes: Long,
    val totalBytes: Long,
    val smartBudgetBytes: Long,
    val freeSpaceBytes: Long,
    val freeSpaceReserveBytes: Long
) {
    val isOverBudget: Boolean
        get() = smartOfflineBytes > smartBudgetBytes

    val isLowStorage: Boolean
        get() = freeSpaceBytes < freeSpaceReserveBytes

    /**
     * Format bytes to human-readable string (e.g. "1.2 GB", "256 MB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }
}
