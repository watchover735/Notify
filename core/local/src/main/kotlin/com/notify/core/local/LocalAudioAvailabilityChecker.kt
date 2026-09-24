package com.notify.core.local

import android.content.ContentResolver
import android.net.Uri
import com.notify.core.model.PlaybackError
import java.io.FileNotFoundException

/**
 * Validates whether a local content URI (MediaStore or SAF) is currently accessible for reading.
 */
class LocalAudioAvailabilityChecker(
    private val contentResolver: ContentResolver
) {

    /**
     * Attempts to open a read-only file descriptor to verify accessibility without loading media bytes.
     * Safely closes the descriptor in all circumstances.
     */
    fun checkAvailability(uri: Uri): LocalAudioResult<Boolean> {
        return try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    pfd.close()
                } catch (_: Throwable) {}
                LocalAudioResult.Success(true)
            } else {
                LocalAudioResult.Failure(
                    PlaybackError.MissingFile(
                        uriString = uri.toString(),
                        message = "Content resolver returned null file descriptor for $uri"
                    )
                )
            }
        } catch (e: SecurityException) {
            LocalAudioResult.Failure(
                PlaybackError.PermissionRevoked(
                    permission = "URI_READ_PERMISSION",
                    message = "Permission revoked or read grant missing for URI: $uri"
                )
            )
        } catch (e: FileNotFoundException) {
            LocalAudioResult.Failure(
                PlaybackError.MissingFile(
                    uriString = uri.toString(),
                    message = "File does not exist or has been deleted/moved: $uri"
                )
            )
        } catch (e: Exception) {
            LocalAudioResult.Failure(
                PlaybackError.MissingFile(
                    uriString = uri.toString(),
                    message = "Failed to access local file: ${e.message}"
                )
            )
        }
    }
}
