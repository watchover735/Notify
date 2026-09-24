package com.notify

import android.app.Application
import com.notify.playback.PlaybackQueueCoordinator

/**
 * Application class for NotiFy.
 * Installs PlaybackQueueCoordinator factory into PlaybackQueueDelegateRegistry
 * so NotiFyPlaybackService receives the coordinator instance on service onCreate.
 */
class NotiFyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PlaybackQueueCoordinator.install(this)
    }
}
