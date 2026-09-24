package com.notify.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notify.core.local.DefaultLocalAudioRepository
import com.notify.core.local.LocalAudioItem
import com.notify.core.local.LocalAudioRepository
import com.notify.core.local.LocalAudioResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for the Local Audio & SAF Storage screen.
 */
data class LocalLibraryUiState(
    val isLoading: Boolean = false,
    val hasMediaPermission: Boolean = false,
    val mediaStoreTracks: List<LocalAudioItem> = emptyList(),
    val safTracks: List<LocalAudioItem> = emptyList(),
    val errorMessage: String? = null
) {
    /**
     * Combined tracks with normalized duplicate prevention (distinct by raw URI).
     */
    val allTracks: List<LocalAudioItem>
        get() = (safTracks + mediaStoreTracks).distinctBy { it.track.id.rawId.trim().lowercase() }

    val isEmpty: Boolean
        get() = allTracks.isEmpty() && !isLoading
}

/**
 * Manages local audio state, MediaStore scans, and SAF document import.
 */
class LocalLibraryViewModel(
    private val repository: LocalAudioRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalLibraryUiState())
    val uiState: StateFlow<LocalLibraryUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    /**
     * On startup:
     * 1. Rebuilds imported Track entries from persisted read grants via SAF restart restoration.
     * 2. Checks broad media permission; if granted, triggers initial MediaStore scan.
     */
    fun loadInitialData() {
        val persistedSaf = repository.listPersistedSafAudio()
        val permissionGranted = repository.hasPermission()

        _uiState.update { current ->
            current.copy(
                safTracks = persistedSaf,
                hasMediaPermission = permissionGranted
            )
        }

        if (permissionGranted) {
            scanMediaStore()
        }
    }

    /**
     * Scans MediaStore.Audio.Media.
     */
    fun scanMediaStore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.scanMediaStore()) {
                is LocalAudioResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            hasMediaPermission = true,
                            mediaStoreTracks = result.data,
                            errorMessage = null
                        )
                    }
                }
                is LocalAudioResult.Failure -> {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            hasMediaPermission = repository.hasPermission(),
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Imports an audio file selected via SAF (OpenDocument).
     * Works independently of broad media permissions.
     */
    fun importSafUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.importSafUri(uri)) {
                is LocalAudioResult.Success -> {
                    val updatedSaf = (listOf(result.data) + _uiState.value.safTracks)
                        .distinctBy { it.track.id.rawId.trim().lowercase() }
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            safTracks = updatedSaf,
                            errorMessage = null
                        )
                    }
                }
                is LocalAudioResult.Failure -> {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Releases a persisted SAF URI grant and removes the track from library.
     */
    fun removeSafTrack(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            repository.removeImportedAudio(uri)
            _uiState.update { current ->
                current.copy(
                    safTracks = current.safTracks.filterNot { it.track.id.rawId == uriString }
                )
            }
        } catch (_: Exception) {}
    }

    fun onPermissionResult(isGranted: Boolean) {
        _uiState.update { it.copy(hasMediaPermission = isGranted) }
        if (isGranted) {
            scanMediaStore()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun requiredPermission(): String = repository.requiredPermission()

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val repo = DefaultLocalAudioRepository(appContext)
                    return LocalLibraryViewModel(repo) as T
                }
            }
    }
}
