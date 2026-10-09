package com.notify

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.notify.playback.PlaybackQueueCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Application class for NotiFy.
 * Installs PlaybackQueueCoordinator factory into PlaybackQueueDelegateRegistry
 * so NotiFyPlaybackService receives the coordinator instance on service onCreate.
 *
 * Configures Coil ImageLoader with 100MB disk cache, 25% memory cache, and crossfade.
 */
class NotiFyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        PlaybackQueueCoordinator.install(this)
        com.notify.core.playback.LocalArtworkStore.init(this)
        com.notify.telemetry.UserTelemetryManager.getInstance(this).init()

        // Asynchronous, low-priority background backfill for existing downloaded songs
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(2000L) // Wait until UI startup completes
                val db = com.notify.download.db.NotiFyDatabase.getInstance(this@NotiFyApplication)
                val completed = db.offlineDownloadDao().getCompletedDownloads()
                for (dl in completed) {
                    if (!com.notify.core.playback.LocalArtworkStore.hasArtwork(dl.trackId, this@NotiFyApplication)) {
                        val track = db.trackDao().getTrackById(dl.trackId)
                        val artUrl = track?.artworkUrl ?: track?.artworkUri
                        if (!artUrl.isNullOrBlank()) {
                            com.notify.core.playback.LocalArtworkStore.downloadAndCacheArtwork(
                                trackId = dl.trackId,
                                remoteUrl = artUrl,
                                context = this@NotiFyApplication
                            )
                            kotlinx.coroutines.delay(100L) // Yield to avoid frame drops
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NotiFyApplication", "Artwork backfill error: ${e.message}")
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .build()
    }
}
