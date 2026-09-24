package com.notify.core.model

/**
 * Declares how audio is located and accessed.
 * Transient signed URLs are strictly prohibited here to enforce offline & caching invariants.
 */
sealed interface AudioSource {
    /**
     * Local audio referenced by content URI (MediaStore or SAF persistable grant).
     */
    data class Local(
        val contentUriString: String
    ) : AudioSource

    /**
     * Remote audio stream that must be resolved dynamically by StreamResolver.
     */
    data class Remote(
        val provider: ProviderId,
        val sourceId: String
    ) : AudioSource

    /**
     * NotiFy offline download: audio stored in app-managed storage, referenced by relative key.
     * ContentProvider URI is resolved at playback time via OfflineStorage.
     * Never stores absolute paths.
     */
    data class Offline(
        val relativeStorageKey: String,
        val bucket: DownloadBucket
    ) : AudioSource
}

/**
 * The three storage buckets for NotiFy offline audio management.
 */
enum class DownloadBucket {
    /** User-pinned: never auto-evicted. */
    PINNED,
    /** Auto-saved after 30s playback: LRU eviction under budget (default 1 GB). */
    SMART_OFFLINE,
    /** Temporary stream cache managed by Media3 SimpleCache (default 250 MB). */
    TEMPORARY_CACHE
}
