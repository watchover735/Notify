package com.notify.core.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Handles permission determination and checks across Android API versions.
 */
object LocalAudioPermissionHelper {

    /**
     * Pure testable function returning the appropriate runtime permission string for a given SDK integer.
     */
    fun requiredPermission(sdkInt: Int): String {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) { // API 33
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /**
     * Current device runtime permission required to query MediaStore audio.
     */
    val currentRequiredPermission: String
        get() = requiredPermission(Build.VERSION.SDK_INT)

    /**
     * Checks if the required MediaStore audio permission is currently granted.
     */
    fun hasPermission(context: Context): Boolean {
        val permission = currentRequiredPermission
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
