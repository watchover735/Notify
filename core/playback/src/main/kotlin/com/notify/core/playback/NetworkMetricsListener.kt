package com.notify.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Listener attached specifically to the upstream network DataSource in Media3StreamCache.
 * Guarantees FIRST_HTTP_BYTE is only triggered by real network bytes, never from local disk cache.
 */
@OptIn(UnstableApi::class)
object NetworkMetricsListener : TransferListener {

    @Volatile
    var onFirstByteTransferred: ((bytesTransferred: Int) -> Unit)? = null

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        if (isNetwork && bytesTransferred > 0) {
            onFirstByteTransferred?.invoke(bytesTransferred)
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}
