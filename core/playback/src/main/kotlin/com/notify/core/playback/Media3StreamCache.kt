package com.notify.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-singleton Media3 stream cache for progressive audio playback and radio lookahead preloading.
 *
 * Invariants:
 * 1. Process singleton: Exactly one SimpleCache instance per cacheDir directory.
 * 2. Bounded size: 100 MB LRU eviction.
 * 3. Separate from OfflineDownloadStore (filesDir/notify_offline).
 * 4. TransferListener for FIRST_HTTP_BYTE is attached to upstream HTTP DataSource only,
 *    preventing disk cache hits from falsely reporting as network bytes.
 */
@OptIn(UnstableApi::class)
object Media3StreamCache {

    private const val CACHE_DIR_NAME = "media3_stream_cache"
    private const val MAX_CACHE_BYTES = 100L * 1024L * 1024L // 100 MB

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null
    private val lock = Any()

    /**
     * Obtains the process-singleton SimpleCache instance.
     */
    fun getSimpleCache(context: Context): SimpleCache {
        return simpleCacheInstance ?: synchronized(lock) {
            simpleCacheInstance ?: run {
                val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
                val databaseProvider = StandaloneDatabaseProvider(context)
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                SimpleCache(cacheDir, evictor, databaseProvider).also {
                    simpleCacheInstance = it
                }
            }
        }
    }

    /**
     * Releases and resets the singleton SimpleCache instance for hermetic unit testing.
     */
    fun resetForTests() {
        synchronized(lock) {
            try {
                simpleCacheInstance?.release()
            } catch (_: Throwable) {}
            simpleCacheInstance = null
        }
    }

    /**
     * Builds a stable, deterministic cache key across stream resolutions.
     * Guaranteed identical between CacheWriter preload and ExoPlayer playback.
     */
    fun buildCacheKey(videoId: String, formatId: String? = null): String {
        return if (!formatId.isNullOrBlank()) {
            "youtube:$videoId:$formatId"
        } else {
            "youtube:$videoId"
        }
    }

    /**
     * Builds an upstream DefaultHttpDataSource.Factory with standard timeouts and User-Agent.
     * Optional [networkTransferListener] receives bytes only when transferred over the network.
     */
    fun createUpstreamHttpDataSourceFactory(
        networkTransferListener: TransferListener? = null
    ): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent("NotiFy/0.5.0")
            .apply {
                if (networkTransferListener != null) {
                    setTransferListener(networkTransferListener)
                }
            }
    }

    /**
     * Builds a CacheDataSource.Factory backed by the process-singleton SimpleCache.
     */
    fun getCacheDataSourceFactory(
        context: Context,
        networkTransferListener: TransferListener? = null,
        upstreamDataSourceFactory: DataSource.Factory? = null
    ): CacheDataSource.Factory {
        val cache = getSimpleCache(context)
        val upstreamFactory = upstreamDataSourceFactory ?: createUpstreamHttpDataSourceFactory(networkTransferListener)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { dataSpec ->
                dataSpec.key ?: dataSpec.uri.toString()
            }
    }

    /**
     * Builds the root playback DataSource.Factory with scheme routing via DefaultDataSource.Factory.
     *
     * Required routing:
     * - content:// -> ContentDataSource / ContentResolver
     * - file:// -> FileDataSource
     * - http/https -> existing CacheDataSource -> configured HTTP upstream
     *
     * Offline files bypass the temporary stream cache entirely.
     * Preserves existing network headers, cache keys, and HTTP configuration.
     */
    fun createPlaybackDataSourceFactory(
        context: Context,
        networkTransferListener: TransferListener? = null,
        upstreamDataSourceFactory: DataSource.Factory? = null
    ): DefaultDataSource.Factory {
        val cachedHttpFactory = getCacheDataSourceFactory(
            context = context,
            networkTransferListener = networkTransferListener,
            upstreamDataSourceFactory = upstreamDataSourceFactory
        )
        return DefaultDataSource.Factory(context, cachedHttpFactory)
    }
}
