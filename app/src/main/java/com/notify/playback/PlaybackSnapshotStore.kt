package com.notify.playback

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackSnapshot
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.RepeatMode
import com.notify.core.model.ShuffleMode
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Persists durable PlaybackSnapshot using Android's AtomicFile for atomic crash-resilient storage.
 * Enforces strict invariant: Never persists temporary signed streaming URLs.
 * Handles corrupted or malformed snapshots safely without crashing application startup.
 */
class PlaybackSnapshotStore(
    context: Context
) {
    companion object {
        private const val TAG = "PlaybackSnapshotStore"
        private const val FILE_NAME = "playback_snapshot.json"
    }

    private val snapshotFile = File(context.applicationContext.filesDir, FILE_NAME)
    private val atomicFile = AtomicFile(snapshotFile)

    @Synchronized
    fun saveSnapshot(snapshot: PlaybackSnapshot) {
        var fos: FileOutputStream? = null
        try {
            val jsonObject = JSONObject().apply {
                put("currentIndex", snapshot.currentIndex)
                put("currentPositionMs", snapshot.currentPositionMs)
                put("repeatMode", snapshot.repeatMode.name)
                put("isShuffled", snapshot.isShuffled)
                put("shuffleMode", snapshot.shuffleMode.name)
                put("isAutoplayEnabled", snapshot.isAutoplayEnabled)
                put("radioSeedSourceId", snapshot.radioSeedSourceId ?: JSONObject.NULL)

                val queueArray = JSONArray()
                snapshot.queue.forEach { entry ->
                    val entryObj = JSONObject().apply {
                        put("queueId", entry.queueId)
                        put("origin", entry.origin.name)

                        val track = entry.track
                        val trackObj = JSONObject().apply {
                            put("provider", track.id.provider.name)
                            put("rawId", track.id.rawId)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album ?: JSONObject.NULL)
                            put("durationMs", track.durationMs)
                            put("artworkUri", track.artworkUri ?: JSONObject.NULL)
                            put("isExplicit", track.isExplicit)

                            // Durable source identifier only; NEVER persist signed streaming URLs
                            val sourceObj = JSONObject()
                            when (val source = track.source) {
                                is AudioSource.Local -> {
                                    sourceObj.put("type", "LOCAL")
                                    sourceObj.put("contentUri", source.contentUriString)
                                }
                                is AudioSource.Remote -> {
                                    sourceObj.put("type", "REMOTE")
                                    sourceObj.put("provider", source.provider.name)
                                    sourceObj.put("sourceId", source.sourceId)
                                }
                                is AudioSource.Offline -> {
                                    sourceObj.put("type", "OFFLINE")
                                    sourceObj.put("relativeStorageKey", source.relativeStorageKey)
                                    sourceObj.put("bucket", source.bucket.name)
                                }
                            }
                            put("source", sourceObj)
                        }
                        put("track", trackObj)
                    }
                    queueArray.put(entryObj)
                }
                put("queue", queueArray)
            }

            val bytes = jsonObject.toString(2).toByteArray(StandardCharsets.UTF_8)
            fos = atomicFile.startWrite()
            fos.write(bytes)
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playback snapshot: ${e.message}", e)
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
        }
    }

    @Synchronized
    fun loadSnapshot(): PlaybackSnapshot? {
        if (!snapshotFile.exists()) {
            return null
        }

        return try {
            val bytes = atomicFile.readFully()
            val jsonString = String(bytes, StandardCharsets.UTF_8)
            val root = JSONObject(jsonString)

            val currentIndex = root.optInt("currentIndex", -1)
            val currentPositionMs = root.optLong("currentPositionMs", 0L)
            val repeatModeStr = root.optString("repeatMode", RepeatMode.OFF.name)
            val repeatMode = try { RepeatMode.valueOf(repeatModeStr) } catch (_: Exception) { RepeatMode.OFF }
            val isShuffled = root.optBoolean("isShuffled", false)
            val shuffleModeStr = root.optString("shuffleMode", if (isShuffled) ShuffleMode.SHUFFLE.name else ShuffleMode.OFF.name)
            val shuffleMode = try { ShuffleMode.valueOf(shuffleModeStr) } catch (_: Exception) { if (isShuffled) ShuffleMode.SHUFFLE else ShuffleMode.OFF }
            val isAutoplayEnabled = root.optBoolean("isAutoplayEnabled", true)
            val radioSeed = if (root.isNull("radioSeedSourceId")) null else root.optString("radioSeedSourceId")

            val queueList = mutableListOf<QueueEntry>()
            val queueArray = root.optJSONArray("queue") ?: JSONArray()
            for (i in 0 until queueArray.length()) {
                val entryObj = queueArray.optJSONObject(i) ?: continue
                val queueId = entryObj.optString("queueId", java.util.UUID.randomUUID().toString())
                val originStr = entryObj.optString("origin", QueueOrigin.USER.name)
                val origin = try { QueueOrigin.valueOf(originStr) } catch (_: Exception) { QueueOrigin.USER }

                val trackObj = entryObj.optJSONObject("track") ?: continue
                val providerStr = trackObj.optString("provider", ProviderId.LOCAL.name)
                val provider = try { ProviderId.valueOf(providerStr) } catch (_: Exception) { ProviderId.LOCAL }
                val rawId = trackObj.optString("rawId", "")
                val title = trackObj.optString("title", "Unknown Title")
                val artist = trackObj.optString("artist", "Unknown Artist")
                val album = if (trackObj.isNull("album")) null else trackObj.optString("album")
                val durationMs = trackObj.optLong("durationMs", 0L)
                val artworkUri = if (trackObj.isNull("artworkUri")) null else trackObj.optString("artworkUri")
                val isExplicit = trackObj.optBoolean("isExplicit", false)

                val sourceObj = trackObj.optJSONObject("source")
                val sourceType = sourceObj?.optString("type", "LOCAL") ?: "LOCAL"
                val source: AudioSource = when (sourceType) {
                    "REMOTE" -> {
                        val pStr = sourceObj?.optString("provider", provider.name) ?: provider.name
                        val p = try { ProviderId.valueOf(pStr) } catch (_: Exception) { provider }
                        val sId = sourceObj?.optString("sourceId", rawId) ?: rawId
                        AudioSource.Remote(p, sId)
                    }
                    "OFFLINE" -> {
                        val relKey = sourceObj?.optString("relativeStorageKey", "") ?: ""
                        val bucketStr = sourceObj?.optString("bucket", com.notify.core.model.DownloadBucket.PINNED.name)
                        val b = try { com.notify.core.model.DownloadBucket.valueOf(bucketStr ?: "") } catch (_: Exception) { com.notify.core.model.DownloadBucket.PINNED }
                        AudioSource.Offline(relKey, b)
                    }
                    else -> {
                        val uriStr = sourceObj?.optString("contentUri", rawId) ?: rawId
                        AudioSource.Local(uriStr)
                    }
                }

                val track = Track(
                    id = TrackId(provider, rawId),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    artworkUri = artworkUri,
                    source = source,
                    isExplicit = isExplicit
                )

                queueList.add(QueueEntry(queueId = queueId, track = track, origin = origin))
            }

            PlaybackSnapshot(
                queue = queueList,
                currentIndex = currentIndex,
                currentPositionMs = currentPositionMs,
                repeatMode = repeatMode,
                isShuffled = isShuffled,
                isAutoplayEnabled = isAutoplayEnabled,
                radioSeedSourceId = radioSeed,
                shuffleMode = shuffleMode
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed or malformed playback snapshot ignored safely: ${e.message}")
            null
        }
    }

    @Synchronized
    fun clearSnapshot() {
        try {
            atomicFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete snapshot file: ${e.message}")
        }
    }
}
