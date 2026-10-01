package com.notify.core.model

/**
 * Explicit origin of a playback request.
 * Dictates whether a track is eligible for Search Recents persistence.
 */
enum class PlaybackOrigin {
    UNKNOWN,
    USER_SEARCH_SELECTION,
    SEARCH_HISTORY_SELECTION,
    PLAYLIST,
    MANUAL_QUEUE,
    RADIO_AUTOPLAY,
    OFFLINE_DOWNLOAD,
    SMART_SHUFFLE
}
