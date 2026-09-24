package com.notify.download.engine

import androidx.core.content.FileProvider

/**
 * Narrowly scoped FileProvider for NotiFy offline downloads.
 * Separate authority ({applicationId}.offline) from the main FileProvider.
 * Only serves content:// URIs for files under notify_offline/pinned/ and notify_offline/smart/.
 */
class NotiFyOfflineProvider : FileProvider()
