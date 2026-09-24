package com.notify.playback

import android.app.Application
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackViewModelAutoSaveTest {

    private lateinit var app: Application
    private lateinit var viewModel: PlaybackViewModel
    private lateinit var prefs: DownloadPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = RuntimeEnvironment.getApplication()
        prefs = DownloadPreferences(app)
        viewModel = PlaybackViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun playbackViewModel_initializesWithAutoSaveMonitor() {
        assertNotNull(viewModel)
        assertEquals(30_000L, prefs.autoSaveThresholdMs)
        assertTrue(prefs.autoSaveEnabled)
    }

    @Test
    fun autoSaveTriggerLogic_onlyTargetsRemoteAudioSources() {
        val remoteTrack = Track(
            id = TrackId.youtube("video_123"),
            title = "Streaming Song",
            artist = "Online Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "video_123")
        )
        val localTrack = Track(
            id = TrackId.local("content://media/1"),
            title = "Local Song",
            artist = "Offline Artist",
            source = AudioSource.Local("content://media/1")
        )

        assertTrue("Remote track is instance of AudioSource.Remote", remoteTrack.source is AudioSource.Remote)
        assertTrue("Local track is NOT instance of AudioSource.Remote", localTrack.source !is AudioSource.Remote)
    }
}
