package com.notify.ui.library

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistLibraryViewModelTest {

    private lateinit var app: Application
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun factory_createsViewModelWithoutReflectionCrash() {
        val factory = PlaylistLibraryViewModel.Factory(
            application = app,
            repository = repository
        )
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, factory)
        val vm = provider[PlaylistLibraryViewModel::class.java]

        assertNotNull("ViewModel must be successfully instantiated by Factory", vm)
        assertFalse("Initial loading should complete or be observed", vm.uiState.value.isImporting)
    }

    @Test
    fun createAndRenamePlaylist_updatesUiStateFlow() = runTest {
        val vm = PlaylistLibraryViewModel(app, repository, ioDispatcher = Dispatchers.Unconfined)

        // Create playlist
        vm.createPlaylist("Rock Classics")

        var foundCreated = false
        for (i in 0 until 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (vm.uiState.value.playlists.any { it.title == "Rock Classics" }) {
                foundCreated = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("Created playlist must appear in uiState", foundCreated)
        val plId = vm.uiState.value.playlists.first { it.title == "Rock Classics" }.playlistId

        // Rename playlist
        vm.renamePlaylist(plId, "Classic Rock 70s")

        var foundRenamed = false
        for (i in 0 until 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (vm.uiState.value.playlists.any { it.title == "Classic Rock 70s" }) {
                foundRenamed = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("Renamed playlist must appear in uiState", foundRenamed)

        // Delete playlist
        vm.deletePlaylist(plId)

        var foundDeleted = false
        for (i in 0 until 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (vm.uiState.value.playlists.none { it.playlistId == plId }) {
                foundDeleted = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("Deleted playlist must be removed from uiState", foundDeleted)
    }

    @Test
    fun importSpotifyPlaylist_validatesEmptyUrl() = runTest {
        val vm = PlaylistLibraryViewModel(app, repository, ioDispatcher = Dispatchers.Unconfined)
        var callbackResult: Boolean? = null

        vm.importSpotifyPlaylist("   ") { success ->
            callbackResult = success
        }

        for (i in 0 until 50) {
            if (callbackResult != null) break
            delay(20)
        }

        assertEquals(false, callbackResult)
        assertNotNull(vm.uiState.value.importError)
        assertEquals("Please enter a Spotify playlist URL", vm.uiState.value.importError)
    }
}
