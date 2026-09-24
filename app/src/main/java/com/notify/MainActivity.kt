package com.notify

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.spike.YtDlpRuntime
import com.notify.playback.PlaybackViewModel
import com.notify.ui.LocalLibraryViewModel
import com.notify.ui.NotiFyApp
import com.notify.ui.theme.NotiFyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lean entry point Activity for NotiFy.
 * - Configures edge-to-edge system display.
 * - Initializes Activity-scoped shared ViewModels.
 * - Registers SAF and permission activity result launchers.
 * - Renders the root NotiFyApp within NotiFyTheme.
 */
class MainActivity : ComponentActivity() {

    private val libraryViewModel: LocalLibraryViewModel by viewModels {
        LocalLibraryViewModel.provideFactory(this)
    }

    private val playbackViewModel: PlaybackViewModel by viewModels()

    // SAF Document Picker launcher ("audio/*")
    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { libraryViewModel.importSafUri(it) }
    }

    // Storage / Media audio permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        libraryViewModel.onPermissionResult(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Reset abandoned SEARCHING states
        // 2. Reconcile persisted states (clear stale RESOLVE_FAILED badges with existing matches)
        // 3. Pre-warm YtDlpRuntime in background without blocking UI
        // 4. Run asynchronous startup recovery for incomplete artwork enrichment
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = PlaylistRepository(NotiFyDatabase.getInstance(applicationContext))
            repository.resetAbandonedSearchingStates()
            repository.reconcilePersistedStates()
            YtDlpRuntime.ensureReady(applicationContext)
            com.notify.download.worker.EnrichmentRecoveryCoordinator.recoverAllPlaylists(applicationContext)
            com.notify.download.engine.OfflineDownloadManager(applicationContext).recoverOnStartup()
        }

        setContent {
            NotiFyTheme {
                NotiFyApp(
                    libraryViewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel,
                    onOpenSafPicker = {
                        safPickerLauncher.launch(arrayOf("audio/*"))
                    },
                    onRequestPermission = {
                        permissionLauncher.launch(libraryViewModel.requiredPermission())
                    }
                )
            }
        }
    }
}
