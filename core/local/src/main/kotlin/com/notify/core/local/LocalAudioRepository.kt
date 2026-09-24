package com.notify.core.local

import android.content.Context
import android.net.Uri
import com.notify.core.model.PlaybackError

/**
 * Public repository interface for local audio operations.
 */
interface LocalAudioRepository {
    suspend fun scanMediaStore(): LocalAudioResult<List<LocalAudioItem>>
    suspend fun importSafUri(uri: Uri): LocalAudioResult<LocalAudioItem>
    fun listPersistedSafAudio(): List<LocalAudioItem>
    fun removeImportedAudio(uri: Uri): Boolean
    fun checkAvailability(uri: Uri): LocalAudioResult<Boolean>
    fun hasPermission(): Boolean
    fun requiredPermission(): String
}

/**
 * Default implementation orchestrating scanner, SAF manager, and availability checker.
 */
class DefaultLocalAudioRepository(
    private val context: Context,
    private val scanner: MediaStoreAudioScanner = MediaStoreAudioScanner(context),
    private val availabilityChecker: LocalAudioAvailabilityChecker = LocalAudioAvailabilityChecker(context.contentResolver),
    private val safGrantManager: SafUriGrantManager = SafUriGrantManager(context, availabilityChecker)
) : LocalAudioRepository {

    override suspend fun scanMediaStore(): LocalAudioResult<List<LocalAudioItem>> {
        return scanner.scan().map { items ->
            deduplicateItems(items)
        }
    }

    override suspend fun importSafUri(uri: Uri): LocalAudioResult<LocalAudioItem> {
        // First verify availability
        when (val availResult = availabilityChecker.checkAvailability(uri)) {
            is LocalAudioResult.Failure -> return availResult
            is LocalAudioResult.Success -> { /* accessible */ }
        }

        // Take persistable permission
        when (val grantResult = safGrantManager.takePersistablePermission(uri)) {
            is LocalAudioResult.Failure -> return grantResult
            is LocalAudioResult.Success -> { /* grant secured */ }
        }

        val item = safGrantManager.extractItemFromUri(uri)
        return LocalAudioResult.Success(item)
    }

    override fun listPersistedSafAudio(): List<LocalAudioItem> {
        return deduplicateItems(safGrantManager.listPersistedAudio())
    }

    override fun removeImportedAudio(uri: Uri): Boolean {
        return safGrantManager.releasePersistedPermission(uri)
    }

    override fun checkAvailability(uri: Uri): LocalAudioResult<Boolean> {
        return availabilityChecker.checkAvailability(uri)
    }

    override fun hasPermission(): Boolean {
        return LocalAudioPermissionHelper.hasPermission(context)
    }

    override fun requiredPermission(): String {
        return LocalAudioPermissionHelper.currentRequiredPermission
    }

    companion object {
        /**
         * Guarantees exact and normalized URI duplicate prevention.
         */
        fun deduplicateItems(items: List<LocalAudioItem>): List<LocalAudioItem> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<LocalAudioItem>()
            for (item in items) {
                val normalizedUri = item.track.id.rawId.trim().lowercase()
                if (seen.add(normalizedUri)) {
                    result.add(item)
                }
            }
            return result
        }
    }
}
