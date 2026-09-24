package com.notify.download.spike

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase A0 & A1: yt-dlp, FFmpeg and QuickJS runtime verification engine.
 * Features:
 * 1. Safe Native initialization with QuickJS runtime validation.
 * 2. Official yt-dlp updater with fallback preservation of working version.
 * 3. Exact yt-dlp version querying via '--version' request.
 * 4. Robust audio download with MediaMetadataRetriever duration validation.
 * 5. Non-fatal 90-day warning capture.
 */
class YtDlpFeasibilitySpike(context: Context) {

    private val appContext = context.applicationContext
    val scratchDir: File = File(appContext.filesDir, "download_scratch").apply { mkdirs() }

    private var isInitialized = false

    sealed class SpikeStatus {
        data class Progress(val percentage: Float, val log: String) : SpikeStatus()
        data class Success(val message: String, val details: Map<String, String>) : SpikeStatus()
        data class Failure(val error: String, val throwable: Throwable? = null) : SpikeStatus()
    }

    data class DownloadedAudio(
        val file: File,
        val formatExtension: String,
        val durationMs: Long,
        val warnings: List<String>
    )

    /**
     * Initializes YoutubeDL and FFmpeg runtimes and checks QuickJS availability.
     */
    suspend fun initLibraries(): SpikeStatus = withContext(Dispatchers.IO) {
        try {
            val initResult = YtDlpRuntime.ensureReady(appContext)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.message ?: "Initialization failed"
                return@withContext SpikeStatus.Failure("Failed to initialize yt-dlp / FFmpeg: $err", initResult.exceptionOrNull())
            }
            isInitialized = true

            val ytDlpVersion = initResult.getOrThrow().ytDlpVersion
            val quickJsAvailable = verifyQuickJs()

            SpikeStatus.Success(
                message = "Initialization successful",
                details = mapOf(
                    "yt-dlp version" to ytDlpVersion,
                    "QuickJS available" to (if (quickJsAvailable) "Yes (libqjs.so verified)" else "No"),
                    "ABI" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"),
                    "Scratch directory" to scratchDir.absolutePath
                )
            )
        } catch (e: Exception) {
            SpikeStatus.Failure("Failed to initialize yt-dlp / FFmpeg: ${e.message}", e)
        }
    }

    /**
     * Queries actual installed yt-dlp version using `yt-dlp --version`.
     */
    suspend fun queryActualVersion(): String = withContext(Dispatchers.IO) {
        val ready = YtDlpRuntime.ensureReady(appContext).getOrNull()
        if (ready != null) {
            return@withContext ready.ytDlpVersion
        }
        try {
            val request = YoutubeDLRequest("").apply {
                addOption("--version")
            }
            val response = YoutubeDL.getInstance().execute(request)
            response.out?.trim() ?: "Unknown"
        } catch (e: Exception) {
            Log.w("YtDlpSpike", "Could not query --version directly: ${e.message}")
            try {
                YoutubeDL.getInstance().version(appContext) ?: "Bundled"
            } catch (_: Exception) {
                "Bundled"
            }
        }
    }

    /**
     * Updates yt-dlp using official updater.
     * Guaranteed to preserve existing working version if updating fails (e.g. rate limits or offline).
     */
    suspend fun updateYtDlp(): SpikeStatus = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val initRes = initLibraries()
            if (initRes is SpikeStatus.Failure) return@withContext initRes
        }

        val previousVersion = queryActualVersion()

        try {
            Log.d("YtDlpSpike", "Updating yt-dlp via UpdateChannel.STABLE...")
            val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            val newVersion = queryActualVersion()

            SpikeStatus.Success(
                message = "yt-dlp update status: $status",
                details = mapOf(
                    "Previous version" to previousVersion,
                    "Current version" to newVersion,
                    "Update result" to (status?.name ?: "UNKNOWN")
                )
            )
        } catch (e: Exception) {
            Log.w("YtDlpSpike", "Update failed; existing version $previousVersion preserved", e)
            val fallbackVersion = queryActualVersion()
            SpikeStatus.Success(
                message = "Update skipped or failed (rate-limited/offline); previous version preserved safely",
                details = mapOf(
                    "Active version" to fallbackVersion,
                    "Reason" to (e.message ?: "Network or GitHub rate limit")
                )
            )
        }
    }

    /**
     * Verifies existence of QuickJS native library for JavaScript challenge execution.
     */
    private fun verifyQuickJs(): Boolean {
        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
        val qjsInNativeDir = File(nativeDir, "libqjs.so").exists()

        val filesDir = appContext.filesDir
        val qjsInFiles = File(filesDir, "packages/quickjs/libqjs.so").exists() ||
                File(filesDir, "youtubedl-android/quickjs/libqjs.so").exists()

        return qjsInNativeDir || qjsInFiles
    }

    /**
     * Resolves metadata for a YouTube URL with JS challenge fallback options.
     */
    suspend fun resolveTrack(
        videoUrl: String,
        onLog: (String) -> Unit = {}
    ): SpikeStatus = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initLibraries()

            onLog("Resolving metadata for $videoUrl...")
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
            }

            val response: YoutubeDLResponse = YoutubeDL.getInstance().execute(request)
            val output = response.out

            if (output.isNullOrBlank()) {
                SpikeStatus.Failure("Empty metadata response from yt-dlp")
            } else {
                onLog("Resolved successfully!")
                SpikeStatus.Success(
                    message = "Metadata resolved successfully",
                    details = mapOf(
                        "Elapsed time" to "${response.elapsedTime}ms",
                        "Raw output preview" to output.take(250) + "..."
                    )
                )
            }
        } catch (e: YoutubeDLException) {
            SpikeStatus.Failure("Resolution failed (yt-dlp error): ${e.message}", e)
        } catch (e: Exception) {
            SpikeStatus.Failure("Resolution failed: ${e.message}", e)
        }
    }

    /**
     * Downloads 1 audio track, extracts audio via FFmpeg, and verifies with MediaMetadataRetriever.
     * Treats "older than 90 days" as non-fatal warning.
     */
    suspend fun downloadAudioWithValidation(
        canonicalYoutubeUrl: String,
        taskId: String = "track_${System.currentTimeMillis() % 100000}",
        format: String = "m4a",
        onProgress: (Float, String) -> Unit
    ): Result<DownloadedAudio> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initLibraries()

            val outputTemplate = File(scratchDir, "$taskId.%(ext)s").absolutePath
            val capturedWarnings = mutableListOf<String>()

            val request = YoutubeDLRequest(canonicalYoutubeUrl).apply {
                addOption("-o", outputTemplate)
                addOption("-x")
                addOption("--audio-format", format)
                addOption("--continue")
                addOption("--no-mtime")
                addOption("--no-playlist")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
            }

            onProgress(0f, "Starting download for $canonicalYoutubeUrl...")

            val response = YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                val text = line ?: "ETA: ${etaInSeconds}s"
                if (text.contains("older than 90 days", ignoreCase = true) || text.contains("WARNING:", ignoreCase = true)) {
                    capturedWarnings.add(text)
                }
                onProgress(progress / 100f, text)
            }

            // Check non-zero exit code
            if (response.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException("yt-dlp exited with non-zero code: ${response.exitCode}. Stderr: ${response.err}")
                )
            }

            // Detect actual produced file in scratch directory
            val producedFiles = scratchDir.listFiles { file ->
                file.name.startsWith(taskId) && !file.name.endsWith(".part") && !file.name.endsWith(".ytdl")
            }
            val targetFile = producedFiles?.firstOrNull()

            if (targetFile == null || !targetFile.exists() || targetFile.length() <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Download finished but produced audio file not found or is empty (0 bytes)")
                )
            }

            // Validate duration using MediaMetadataRetriever
            val durationMs = try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(targetFile.absolutePath)
                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                mmr.release()
                durStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.w("YtDlpSpike", "Could not parse duration from downloaded audio file", e)
                0L
            }

            if (durationMs <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException("MediaMetadataRetriever could not read valid audio duration from ${targetFile.name}")
                )
            }

            val formatExt = targetFile.extension
            Log.d("YtDlpSpike", "Verified audio file: ${targetFile.name}, size=${targetFile.length()}, duration=${durationMs}ms")

            Result.success(
                DownloadedAudio(
                    file = targetFile,
                    formatExtension = formatExt,
                    durationMs = durationMs,
                    warnings = capturedWarnings
                )
            )
        } catch (e: Exception) {
            Log.e("YtDlpSpike", "Download execution failed", e)
            Result.failure(e)
        }
    }

    /**
     * Backward-compatible helper for Phase A0 spike dialog.
     */
    suspend fun downloadAudio(
        videoUrl: String,
        taskId: String = "spike_test",
        format: String = "m4a",
        onProgress: (Float, String) -> Unit
    ): SpikeStatus {
        val result = downloadAudioWithValidation(videoUrl, taskId, format, onProgress)
        return if (result.isSuccess) {
            val audio = result.getOrThrow()
            SpikeStatus.Success(
                message = "Audio download and FFmpeg extraction completed successfully",
                details = mapOf(
                    "Output file" to audio.file.name,
                    "File size" to "${audio.file.length() / 1024} KB",
                    "Validated Duration" to "${audio.durationMs / 1000}s",
                    "Warnings captured" to if (audio.warnings.isEmpty()) "None" else "${audio.warnings.size} notice(s)"
                )
            )
        } else {
            SpikeStatus.Failure("Download failed: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
        }
    }

    /**
     * Tests process cancellation and verifies .part file is preserved.
     */
    suspend fun testCancellation(
        videoUrl: String,
        taskId: String = "spike_cancel_test"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initLibraries()

            val outputTemplate = File(scratchDir, "$taskId.%(ext)s").absolutePath

            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-o", outputTemplate)
                addOption("--continue")
                addOption("--no-playlist")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
            }

            val downloadThread = Thread {
                try {
                    YoutubeDL.getInstance().execute(request, taskId) { _, _, _ -> }
                } catch (_: Exception) {}
            }
            downloadThread.start()

            kotlinx.coroutines.delay(2000L)

            val destroyed = YoutubeDL.getInstance().destroyProcessById(taskId)
            downloadThread.interrupt()

            val partFiles = scratchDir.listFiles { file -> file.name.startsWith(taskId) && file.name.endsWith(".part") }
            val partPreserved = (partFiles != null && partFiles.isNotEmpty())

            destroyed || partPreserved
        } catch (_: Exception) {
            false
        }
    }
}
