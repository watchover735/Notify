package com.notify.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun testTrackIdFormattingAndEquality() {
        val localId1 = TrackId.local("content://media/external/audio/media/100")
        val localId2 = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/100")
        val youtubeId = TrackId.youtube("dQw4w9WgXcQ")

        assertEquals(localId1, localId2)
        assertEquals("local:content://media/external/audio/media/100", localId1.toString())
        assertEquals("youtube:dQw4w9WgXcQ", youtubeId.toString())
        assertFalse(localId1 == youtubeId)
    }

    @Test
    fun testAudioSourceDistinction() {
        val localSource = AudioSource.Local("content://media/external/audio/media/1")
        val remoteSource = AudioSource.Remote(ProviderId.YOUTUBE, "videoId_123")

        val localTrack = Track(
            id = TrackId.local("content://media/external/audio/media/1"),
            title = "Acoustic Song",
            artist = "Local Artist",
            durationMs = 185_000L,
            source = localSource
        )

        val remoteTrack = Track(
            id = TrackId.youtube("videoId_123"),
            title = "Online Stream",
            artist = "Remote Artist",
            durationMs = 210_000L,
            source = remoteSource
        )

        assertTrue(localTrack.isLocal)
        assertFalse(localTrack.isRemote)

        assertTrue(remoteTrack.isRemote)
        assertFalse(remoteTrack.isLocal)
    }

    @Test
    fun testTrackDurationFormatting() {
        val zeroTrack = Track(
            id = TrackId.local("1"),
            title = "Zero",
            artist = "A",
            durationMs = 0L,
            source = AudioSource.Local("1")
        )
        val shortTrack = Track(
            id = TrackId.local("2"),
            title = "Short",
            artist = "A",
            durationMs = 65_000L, // 1 min 5 sec
            source = AudioSource.Local("2")
        )
        val longTrack = Track(
            id = TrackId.local("3"),
            title = "Long",
            artist = "A",
            durationMs = 3_725_000L, // 1 hr 2 min 5 sec
            source = AudioSource.Local("3")
        )

        assertEquals("--:--", zeroTrack.formattedDuration())
        assertEquals("1:05", shortTrack.formattedDuration())
        assertEquals("1:02:05", longTrack.formattedDuration())
    }

    @Test
    fun testQueueEntryAndOrigin() {
        val track = Track(
            id = TrackId.local("uri"),
            title = "Title",
            artist = "Artist",
            source = AudioSource.Local("uri")
        )

        val userEntry = QueueEntry(track = track, origin = QueueOrigin.USER)
        val radioEntry = QueueEntry(track = track, origin = QueueOrigin.RADIO)

        assertEquals(QueueOrigin.USER, userEntry.origin)
        assertEquals(QueueOrigin.RADIO, radioEntry.origin)
        assertNotNull(userEntry.queueId)
        assertNotNull(radioEntry.queueId)
    }

    @Test
    fun testPlaybackSnapshotInvariants() {
        val track1 = Track(
            id = TrackId.local("1"),
            title = "T1",
            artist = "A1",
            source = AudioSource.Local("1")
        )
        val track2 = Track(
            id = TrackId.local("2"),
            title = "T2",
            artist = "A2",
            source = AudioSource.Local("2")
        )

        val entry1 = QueueEntry("e1", track1, QueueOrigin.USER)
        val entry2 = QueueEntry("e2", track2, QueueOrigin.USER)

        val snapshot = PlaybackSnapshot(
            queue = listOf(entry1, entry2),
            currentIndex = 0,
            currentPositionMs = 5000L,
            repeatMode = RepeatMode.ALL,
            isShuffled = false
        )

        assertEquals(entry1, snapshot.currentEntry)
        assertEquals(track1, snapshot.currentTrack)
        assertTrue(snapshot.hasNext)
        assertFalse(snapshot.hasPrevious)

        val nextSnapshot = snapshot.copy(currentIndex = 1)
        assertEquals(entry2, nextSnapshot.currentEntry)
        assertFalse(nextSnapshot.hasNext)
        assertTrue(nextSnapshot.hasPrevious)
    }

    @Test
    fun testPlaybackErrorClassification() {
        val permissionError = PlaybackError.PermissionRevoked("READ_MEDIA_AUDIO")
        val missingFile = PlaybackError.MissingFile("content://audio/42")
        val offline = PlaybackError.Offline()
        val rateLimited = PlaybackError.RateLimited(retryAfterSeconds = 30)
        val resolveFailed = PlaybackError.ResolveFailed(TrackId.youtube("abc"), "Extractor 403")
        val expired = PlaybackError.ExpiredStream(TrackId.youtube("abc"))
        val decoder = PlaybackError.DecoderFailure("Codec init failed")
        val unknown = PlaybackError.Unknown("Unexpected exception", RuntimeException("Root cause"))

        assertTrue(permissionError.isRecoverable)
        assertTrue(missingFile.isRecoverable)
        assertTrue(offline.isRecoverable)
        assertTrue(rateLimited.isRecoverable)
        assertFalse(resolveFailed.isRecoverable)
        assertTrue(expired.isRecoverable)
        assertFalse(decoder.isRecoverable)
        assertFalse(unknown.isRecoverable)
        assertEquals("Unexpected exception", unknown.message)
    }

    @Test
    fun testLyricsBinarySearch() {
        val lines = listOf(
            LyricLine(1000L, "First line"),
            LyricLine(5000L, "Second line"),
            LyricLine(10000L, "Third line"),
            LyricLine(15000L, "Fourth line")
        )

        val synced = LyricsResult.Synced(lines)

        // Before first line (with 150ms tolerance, position 500 + 150 = 650 < 1000)
        assertNull(synced.getActiveLine(500L))

        // Right at line 1
        assertEquals("First line", synced.getActiveLine(1000L)?.text)

        // In between line 1 and 2
        assertEquals("First line", synced.getActiveLine(3000L)?.text)

        // Near line 2 (4900 + 150 = 5050 >= 5000) -> matches line 2 due to display tolerance
        assertEquals("Second line", synced.getActiveLine(4900L)?.text)

        // After last line
        assertEquals("Fourth line", synced.getActiveLine(20000L)?.text)
    }

    @Test
    fun testResolvedStreamExpiration() {
        val now = 1_000_000L
        val unexpired = ResolvedStream(
            streamUrl = "https://stream.example.com/audio.m4a",
            expiresAtEpochMs = now + 120_000L // 2 min in future
        )
        val expiringSoon = ResolvedStream(
            streamUrl = "https://stream.example.com/audio.m4a",
            expiresAtEpochMs = now + 30_000L // 30 sec in future, within 60s safety margin
        )
        val alreadyExpired = ResolvedStream(
            streamUrl = "https://stream.example.com/audio.m4a",
            expiresAtEpochMs = now - 1000L
        )

        assertFalse(unexpired.isExpired(currentEpochMs = now))
        assertTrue(expiringSoon.isExpired(currentEpochMs = now))
        assertTrue(alreadyExpired.isExpired(currentEpochMs = now))
    }
}
