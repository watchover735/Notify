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
        com.notify.core.playback.LocalArtworkStore.setSourceIdResolver { trackId ->
            try {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.notify.download.db.NotiFyDatabase.getInstance(this@NotiFyApplication)
                    db.trackDao().getSelectedSource(trackId)?.sourceId
                        ?: db.offlineDownloadDao().getCompletedForTrack(trackId)?.providerSourceId
                }
            } catch (e: Exception) {
                null
            }
        }
        com.notify.telemetry.UserTelemetryManager.getInstance(this).init()
        // B1.5: debug-only rolling file logger for stream resolution diagnostics
        com.notify.download.stream.StreamResolverDebugLog.init(this)

        // Asynchronous, low-priority background backfill for existing downloaded songs
        // Must be idempotent, batch-limited, non-blocking on main thread, and never delete/overwrite existing files
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(3000L) // Wait until UI startup completes
                val db = com.notify.download.db.NotiFyDatabase.getInstance(this@NotiFyApplication)
                val completed = db.offlineDownloadDao().getCompletedDownloads()

                // Pre-populate in-memory mapping in LocalArtworkStore (zero file I/O)
                for (dl in completed) {
                    if (dl.providerSourceId.isNotBlank()) {
                        com.notify.core.playback.LocalArtworkStore.mapTrackToSource(dl.trackId, dl.providerSourceId)
                    }
                }

                // Batch-limited download for missing artwork files (max 25 per app launch)
                var downloadCount = 0
                val maxBatch = 25
                for (dl in completed) {
                    if (downloadCount >= maxBatch) break

                    // Only download if artwork does not exist under trackId OR providerSourceId
                    val alreadyHasArt = com.notify.core.playback.LocalArtworkStore.hasArtwork(dl.trackId, this@NotiFyApplication)
                        || com.notify.core.playback.LocalArtworkStore.hasArtwork(dl.providerSourceId, this@NotiFyApplication)

                    if (!alreadyHasArt) {
                        val track = db.trackDao().getTrackById(dl.trackId)
                        val artUrl = track?.artworkUrl ?: track?.artworkUri
                        if (!artUrl.isNullOrBlank()) {
                            val saved = com.notify.core.playback.LocalArtworkStore.downloadAndCacheArtwork(
                                trackId = dl.providerSourceId.ifBlank { dl.trackId },
                                remoteUrl = artUrl,
                                context = this@NotiFyApplication
                            )
                            if (saved) {
                                downloadCount++
                            }
                            kotlinx.coroutines.delay(150L) // Yield to avoid frame drops
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
