package com.notify.playback

import android.app.Application
import com.notify.core.downloads.storage.DownloadPreferences
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.NotiFyDatabase
import com.notify.download.engine.OfflineDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SurprisePlaybackTest {

    private lateinit var app: Application
    private lateinit var database: NotiFyDatabase
    private lateinit var downloadManager: OfflineDownloadManager
    private lateinit var prefs: DownloadPreferences
    private lateinit var manager: SurprisePlaybackManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = NotiFyDatabase.getInstance(app)
        downloadManager = OfflineDownloadManager(app)
        prefs = DownloadPreferences(app)
        manager = SurprisePlaybackManager(app, database, downloadManager, prefs)
    }

    private fun createEntry(id: String, isSurprise: Boolean = false): QueueEntry {
        val queueId = if (isSurprise) "surprise_${id}" else "entry_${id}"
        val track = Track(
            id = TrackId.youtube(id),
            title = "Track $id",
            artist = "Artist $id",
            source = AudioSource.Local("file:///path/$id")
        )
        return QueueEntry(
            queueId = queueId,
            track = track,
            origin = QueueOrigin.PLAYLIST,
            playbackOrigin = PlaybackOrigin.UNKNOWN
        )
    }

    @Test
    fun featureToggle_defaultsToTrue() {
        assertTrue(prefs.surpriseCachedPlaybackEnabled)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenFeatureDisabled() {
        prefs.surpriseCachedPlaybackEnabled = false
        manager.resetSession(isPlaylist = true)
        val entry1 = createEntry("1")
        val entry2 = createEntry("2")

        val trigger = manager.shouldTriggerSurprise(
            isNetworkConnected = false,
            originalPlaylistSize = 10,
            isRepeatOne = false,
            currentEntry = entry1,
            nextEntry = entry2,
            hasUpcomingSurprise = false
        )
        assertFalse(trigger)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenNotPlaylistPlayback() {
        manager.resetSession(isPlaylist = false) // e.g. search, radio, single track
        val entry1 = createEntry("1")
        val entry2 = createEntry("2")

        val trigger = manager.shouldTriggerSurprise(
            isNetworkConnected = false,
            originalPlaylistSize = 10,
            isRepeatOne = false,
            currentEntry = entry1,
            nextEntry = entry2,
            hasUpcomingSurprise = false
        )
        assertFalse(trigger)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenOnlineAndPlaylistNotEntirelyOffline() {
        manager.resetSession(isPlaylist = true)
        manager.isEntirelyOfflinePlaylist = false
        val entry1 = createEntry("1")
        val entry2 = createEntry("2")

        val trigger = manager.shouldTriggerSurprise(
            isNetworkConnected = true, // Device online
            originalPlaylistSize = 10,
            isRepeatOne = false,
            currentEntry = entry1,
            nextEntry = entry2,
            hasUpcomingSurprise = false
        )
        assertFalse(trigger)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenPlaylistHasFewerThan4Songs() {
        manager.resetSession(isPlaylist = true)
        val entry1 = createEntry("1")
        val entry2 = createEntry("2")

        val trigger = manager.shouldTriggerSurprise(
            isNetworkConnected = false,
            originalPlaylistSize = 3, // Fewer than 4
            isRepeatOne = false,
            currentEntry = entry1,
            nextEntry = entry2,
            hasUpcomingSurprise = false
        )
        assertFalse(trigger)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenRepeatOneActive() {
        manager.resetSession(isPlaylist = true)
        val entry1 = createEntry("1")
        val entry2 = createEntry("2")

        val trigger = manager.shouldTriggerSurprise(
            isNetworkConnected = false,
            originalPlaylistSize = 10,
            isRepeatOne = true, // Single-track repeat
            currentEntry = entry1,
            nextEntry = entry2,
            hasUpcomingSurprise = false
        )
        assertFalse(trigger)
    }

    @Test
    fun shouldTriggerSurprise_returnsFalse_whenCurrentOrNextIsSurprise() {
        manager.resetSession(isPlaylist = true)
        val realEntry = createEntry("1")
        val surpriseEntry = createEntry("surprise_1", isSurprise = true)

        // Current track is surprise
        assertFalse(
            manager.shouldTriggerSurprise(
                isNetworkConnected = false,
                originalPlaylistSize = 10,
                isRepeatOne = false,
                currentEntry = surpriseEntry,
                nextEntry = realEntry,
                hasUpcomingSurprise = false
            )
        )

        // Next track is surprise
        assertFalse(
            manager.shouldTriggerSurprise(
                isNetworkConnected = false,
                originalPlaylistSize = 10,
                isRepeatOne = false,
                currentEntry = realEntry,
                nextEntry = surpriseEntry,
                hasUpcomingSurprise = false
            )
        )

        // Upcoming surprise already in timeline
        assertFalse(
            manager.shouldTriggerSurprise(
                isNetworkConnected = false,
                originalPlaylistSize = 10,
                isRepeatOne = false,
                currentEntry = realEntry,
                nextEntry = realEntry,
                hasUpcomingSurprise = true
            )
        )
    }

    @Test
    fun songsPlayedCounter_incrementsOnRealSongs_andTriggersAtGap() {
        manager.resetSession(isPlaylist = true)
        val gap = manager.currentGap
        assertTrue("Random gap must be 3 or 4", gap in 3..4)

        val entryA = createEntry("A")
        val entryB = createEntry("B")

        // First songs completed
        for (i in 1 until gap - 1) {
            manager.onRealSongCompleted(createEntry("song_$i"))
            assertFalse(
                manager.shouldTriggerSurprise(
                    isNetworkConnected = false,
                    originalPlaylistSize = 10,
                    isRepeatOne = false,
                    currentEntry = entryA,
                    nextEntry = entryB,
                    hasUpcomingSurprise = false
                )
            )
        }

        // When counter + 1 reaches gap, it triggers!
        manager.onRealSongCompleted(createEntry("song_trigger"))
        val shouldTrigger = manager.shouldTriggerSurprise(
            isNetworkConnected = false,
            originalPlaylistSize = 10,
            isRepeatOne = false,
            currentEntry = entryA,
            nextEntry = entryB,
            hasUpcomingSurprise = false
        )
        assertTrue(shouldTrigger)
    }

    @Test
    fun markSurprisePlayed_resetsCounter_andRecomputesGap() {
        manager.resetSession(isPlaylist = true)
        manager.onRealSongCompleted(createEntry("1"))
        manager.onRealSongCompleted(createEntry("2"))
        manager.onRealSongCompleted(createEntry("3"))

        val surprise = createEntry("s1", isSurprise = true)
        manager.markSurprisePlayed(surprise)

        assertEquals(0, manager.songsPlayedSinceLastSurprise)
        assertTrue(manager.currentGap in 3..4)
        assertEquals("s1", manager.lastSurpriseTrackKey)
    }
}
