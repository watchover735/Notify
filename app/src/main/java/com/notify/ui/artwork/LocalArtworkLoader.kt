package com.notify.ui.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.notify.core.model.AudioSource
import com.notify.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asynchronous, memory-safe album artwork loader for local MediaStore and SAF tracks.
 * - API 29+: First tries ContentResolver.loadThumbnail().
 * - Fallback (all APIs): Uses MediaMetadataRetriever.embeddedPicture with sampled downscaling.
 * - Dynamic memory cache sized to 1/8th of available runtime memory.
 * - Leaks zero Activity references (retains only applicationContext).
 */
class LocalArtworkLoader private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    // Calculate dynamic memory cache size (1/8th of available app memory in KB)
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(1024)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    suspend fun loadArtwork(track: Track?, targetSizePx: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        val uriString = (track?.source as? AudioSource.Local)?.contentUriString ?: return@withContext null
        val cacheKey = "$uriString-$targetSizePx"

        memoryCache.get(cacheKey)?.let { return@withContext it }

        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return@withContext null
        }

        // 1. On API 29+, try ContentResolver.loadThumbnail
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thumbnail = appContext.contentResolver.loadThumbnail(uri, Size(targetSizePx, targetSizePx), null)
                if (thumbnail != null) {
                    memoryCache.put(cacheKey, thumbnail)
                    return@withContext thumbnail
                }
            } catch (_: Exception) {
                // Fall through to embedded artwork fallback
            }
        }

        // 2. Embedded artwork fallback for all APIs
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(appContext, uri)
            val pictureBytes = retriever.embeddedPicture
            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, targetSizePx, targetSizePx)
                options.inJustDecodeBounds = false
                val decodedBitmap = BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, options)
                if (decodedBitmap != null) {
                    memoryCache.put(cacheKey, decodedBitmap)
                    return@withContext decodedBitmap
                }
            }
        } catch (_: Exception) {
            // Unreadable or corrupted artwork; safely ignore
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }

        null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    companion object {
        @Volatile
        private var instance: LocalArtworkLoader? = null

        fun getInstance(context: Context): LocalArtworkLoader {
            return instance ?: synchronized(this) {
                instance ?: LocalArtworkLoader(context.applicationContext).also { instance = it }
            }
        }
    }
}
