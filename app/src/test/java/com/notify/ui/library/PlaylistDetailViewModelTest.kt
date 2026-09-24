package com.notify.ui.library

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.asExecutor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistDetailViewModelTest {

    private lateinit var app: Application
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Dispatchers.Unconfined.asExecutor())
            .setTransactionExecutor(Dispatchers.Unconfined.asExecutor())
            .build()
        NotiFyDatabase.setTestInstance(db)
        repository = PlaylistRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        NotiFyDatabase.setTestInstance(null)
    }

    @Test
    fun factory_createsDetailViewModelWithoutReflectionCrash() {
        val factory = PlaylistDetailViewModel.Factory(
            application = app,
            playlistId = "pl_test_123",
            repository = repository
        )
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, factory)
        val vm = provider[PlaylistDetailViewModel::class.java]

        assertNotNull("PlaylistDetailViewModel must be created by Factory", vm)
        store.clear()
    }

    @Test
    fun loadsPlaylistAndTracks_andSupportsRenameAndDelete() = runTest {
        val pl = PlaylistEntity(
            playlistId = "pl_detail_test",
            title = "Chill Vibes",
            sourceUrl = null,
            artworkUri = null
        )
        db.playlistDao().insertPlaylist(pl)

        val track = TrackEntity(id = "tr_chill_1", title = "Sunset", artist = "Chill Guy")
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = "pl_detail_test", trackId = "tr_chill_1", position = 1))
        )

        val vm = PlaylistDetailViewModel(
            application = app,
            playlistId = "pl_detail_test",
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined
        )

        var foundLoaded = false
        for (i in 0 until 50) {
            val state = vm.uiState.value
            if (state.playlist != null && state.tracks.isNotEmpty()) {
                foundLoaded = true
                break
            }
            Thread.sleep(20)
            delay(20)
        }
        assertTrue("Playlist and tracks must be loaded in uiState: ${vm.uiState.value}", foundLoaded)
        assertEquals("Chill Vibes", vm.uiState.value.playlist?.title)
        assertEquals(1, vm.uiState.value.tracks.size)
        assertEquals("Sunset", vm.uiState.value.tracks[0].title)

        // Rename
        vm.renamePlaylist("Deep Chill")
        var foundRenamed = false
        for (i in 0 until 50) {
            if (vm.uiState.value.playlist?.title == "Deep Chill") {
                foundRenamed = true
                break
            }
            Thread.sleep(20)
            delay(20)
        }
        assertTrue("Renamed title must appear in uiState", foundRenamed)

        // Delete
        var wasDeleted = false
        vm.deletePlaylist { wasDeleted = true }
        for (i in 0 until 50) {
            if (wasDeleted) break
            Thread.sleep(20)
            delay(20)
        }

        assertEquals(true, wasDeleted)
        assertNull(db.playlistDao().getPlaylistById("pl_detail_test"))
    }
}
