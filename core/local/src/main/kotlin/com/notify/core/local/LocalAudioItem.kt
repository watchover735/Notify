package com.notify.core.local

import com.notify.core.model.Track

/**
 * Storage-specific envelope pairing a domain [Track] with its device-level metadata.
 * Keeps [Track] completely decoupled from filesystem/database storage attributes.
 */
data class LocalAudioItem(
    val track: Track,
    val sizeBytes: Long,
    val mimeType: String?,
    /**
     * Date modified represented explicitly in Unix epoch seconds (as returned by MediaStore).
     */
    val dateModifiedEpochSeconds: Long
) {
    /**
     * Helper returning date modified converted to epoch milliseconds.
     */
    val dateModifiedEpochMs: Long
        get() = dateModifiedEpochSeconds * 1000L
}
