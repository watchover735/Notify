package com.notify.download.stream

import android.content.Context
import com.notify.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B1.5 — Debug-only rolling file logger for stream resolution diagnostics.
 *
 * Appends log lines to `<filesDir>/stream_resolver_debug.log`.
 * - Active ONLY when BuildConfig.DEBUG is true. Release builds: completely silent.
 * - Rolling: file is truncated to the most recent half when it exceeds MAX_FILE_BYTES.
 * - No URLs, tokens, or secrets are written; callers are responsible for sanitizing messages.
 * - Thread-safe via `synchronized(lock)`.
 * - Never throws; all I/O errors are swallowed silently.
 */
internal object StreamResolverDebugLog {

    private const val FILE_NAME = "stream_resolver_debug.log"
    private const val MAX_FILE_BYTES = 500_000L          // 500 KB
    private const val ROLL_TARGET_BYTES = 250_000L       // keep last 250 KB after roll
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Must be called once from Application.onCreate() in DEBUG builds. */
    private var logFile: File? = null

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            try {
                logFile = File(context.filesDir, FILE_NAME)
            } catch (_: Throwable) {}
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        val file = logFile ?: return
        synchronized(lock) {
            try {
                rollIfNeeded(file)
                val timestamp = dateFormat.format(Date())
                FileWriter(file, /* append = */ true).use { fw ->
                    fw.write("$timestamp $level/$tag: $msg\n")
                }
            } catch (_: Throwable) {
                // Never throw from logger
            }
        }
    }

    /**
     * If the file exceeds [MAX_FILE_BYTES], read the last [ROLL_TARGET_BYTES] bytes and
     * overwrite the file with just those bytes.  This keeps the most recent entries.
     */
    private fun rollIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        try {
            val bytes = file.readBytes()
            val keepFrom = (bytes.size - ROLL_TARGET_BYTES).coerceAtLeast(0L).toInt()
            val kept = bytes.copyOfRange(keepFrom, bytes.size)
            file.writeBytes(kept)
        } catch (_: Throwable) {}
    }

    /** Returns the absolute path of the log file (for reporting; DEBUG only). */
    fun logFilePath(): String? = logFile?.absolutePath
}
