package com.notify.core.downloads.storage

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Result of comprehensive audio file integrity and playable duration validation.
 */
sealed class AudioValidationResult {
    data class Valid(val durationMs: Long, val fileSizeBytes: Long) : AudioValidationResult()
    data class Invalid(val reason: String) : AudioValidationResult()
}

/**
 * Validates downloaded audio files according to the Download Truth Rule:
 * 1. Final file exists on disk.
 * 2. File is readable (file stream can read bytes).
 * 3. File size > 0 bytes.
 * 4. MediaMetadataRetriever confirms valid audio duration (> 0 ms).
 * 5. Atomically promoted from .part storage.
 */
interface AudioValidator {
    fun validateAudioFile(file: File): AudioValidationResult
}

open class DefaultAudioFileValidator : AudioValidator {

    companion object {
        private const val TAG = "AudioFileValidator"
    }

    override fun validateAudioFile(file: File): AudioValidationResult {
        if (!file.exists()) {
            return AudioValidationResult.Invalid("File does not exist: ${file.name}")
        }

        val size = file.length()
        if (size <= 0L) {
            return AudioValidationResult.Invalid("File is empty (0 bytes): ${file.name}")
        }

        if (!file.canRead()) {
            return AudioValidationResult.Invalid("File cannot be read: ${file.name}")
        }

        // Verify physical byte readability
        try {
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(512)
                val read = stream.read(buffer)
                if (read <= 0) {
                    return AudioValidationResult.Invalid("Failed to read header bytes from ${file.name}")
                }
            }
        } catch (e: Exception) {
            return AudioValidationResult.Invalid("File stream read error: ${e.message}")
        }

        // Validate audio duration via MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                AudioValidationResult.Invalid("MediaMetadataRetriever confirmed invalid audio duration: $durationMs ms")
            } else {
                AudioValidationResult.Valid(durationMs = durationMs, fileSizeBytes = size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for ${file.name}: ${e.message}")
            AudioValidationResult.Invalid("MediaMetadataRetriever validation failed: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore release errors
            }
        }
    }
}
