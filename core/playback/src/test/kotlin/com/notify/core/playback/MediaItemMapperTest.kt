package com.notify.core.playback

import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemMapperTest {

    @Test
    fun testNamespacedIdGenerationAndParsing() {
        val localTrackId = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/123")
        val namespacedId = MediaItemMapper.buildNamespacedMediaId(localTrackId)

        assertEquals("LOCAL:content://media/external/audio/media/123", namespacedId)

        val (provider, rawId) = MediaItemMapper.parseNamespacedMediaId(namespacedId)
        assertEquals(ProviderId.LOCAL, provider)
        assertEquals("content://media/external/audio/media/123", rawId)
    }

    @Test
    fun testNamespacedIdPreventsCollisionBetweenProvidersWithSameRawId() {
        val id1 = TrackId(ProviderId.LOCAL, "track_99")
        val id2 = TrackId(ProviderId.YOUTUBE, "track_99")

        val mediaId1 = MediaItemMapper.buildNamespacedMediaId(id1)
        val mediaId2 = MediaItemMapper.buildNamespacedMediaId(id2)

        assertEquals("LOCAL:track_99", mediaId1)
        assertEquals("YOUTUBE:track_99", mediaId2)
        assertFalse(mediaId1 == mediaId2)
    }

    @Test
    fun testLosslessRoundTripReconstruction() {
        val originalTrack = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/42"),
            title = "Symphony No. 9",
            artist = "Ludwig van Beethoven",
            album = "Classic Masterpieces",
            durationMs = 385_000L,
            artworkUri = null,
            source = AudioSource.Local("content://media/external/audio/media/42"),
            isExplicit = false
        )

        val mediaItem = MediaItemMapper.toMediaItem(originalTrack)
        val reconstructedTrack = MediaItemMapper.fromMediaItem(mediaItem)

        assertNotNull(reconstructedTrack)
        assertEquals(originalTrack.id, reconstructedTrack?.id)
        assertEquals(originalTrack.title, reconstructedTrack?.title)
        assertEquals(originalTrack.artist, reconstructedTrack?.artist)
        assertEquals(originalTrack.album, reconstructedTrack?.album)
        assertEquals(originalTrack.durationMs, reconstructedTrack?.durationMs)
        assertEquals(originalTrack.source, reconstructedTrack?.source)
        assertEquals(originalTrack.isExplicit, reconstructedTrack?.isExplicit)
        assertEquals(originalTrack, reconstructedTrack)
    }

    @Test
    fun testLocalContentUriPreservedWithoutRawFilesystemPath() {
        val contentUri = "content://com.android.providers.media/external/audio/media/77"
        val track = Track(
            id = TrackId(ProviderId.LOCAL, contentUri),
            title = "Local Audio",
            artist = "Artist",
            source = AudioSource.Local(contentUri)
        )

        val mediaItem = MediaItemMapper.toMediaItem(track)

        assertEquals("LOCAL:$contentUri", mediaItem.mediaId)
        assertEquals(contentUri, mediaItem.requestMetadata.mediaUri?.toString())
        assertFalse(mediaItem.mediaId.startsWith("/storage"))
        assertFalse(mediaItem.mediaId.startsWith("/data"))
    }

    @Test
    fun testNonLocalAudioSourceRejectedInPhase3() {
        val remoteTrack = Track(
            id = TrackId(ProviderId.YOUTUBE, "dQw4w9WgXcQ"),
            title = "Remote Video",
            artist = "Remote Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "dQw4w9WgXcQ")
        )

        assertThrows(IllegalArgumentException::class.java) {
            MediaItemMapper.toMediaItem(remoteTrack)
        }
    }

    @Test
    fun testMediaItemMapperUriRegressionId1000049464() {
        val expectedUri = "content://media/external/audio/media/1000049464"
        val forbiddenDoubleUri = "content://media/external/audio/media/1000049464/1000049464"

        val track = Track(
            id = TrackId(ProviderId.LOCAL, expectedUri),
            title = "Test Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            source = AudioSource.Local(contentUriString = expectedUri)
        )

        val mediaItem = MediaItemMapper.toMediaItem(track)

        // MediaItem localConfiguration.uri equals the Track source URI exactly
        assertEquals(expectedUri, mediaItem.localConfiguration?.uri?.toString())
        assertFalse(mediaItem.localConfiguration?.uri?.toString() == forbiddenDoubleUri)

        // MediaItemMapper does not modify or append TrackId.rawId
        assertEquals("LOCAL:$expectedUri", mediaItem.mediaId)
        assertFalse(mediaItem.mediaId.endsWith("1000049464/1000049464"))

        // URI path does not end with the same ID repeated twice
        val path = mediaItem.localConfiguration?.uri?.path ?: ""
        assertFalse(path.endsWith("1000049464/1000049464"))

        // Round-trip reconstruction preserves the exact URI without modification
        val reconstructed = MediaItemMapper.fromMediaItem(mediaItem)
        assertNotNull(reconstructed)
        val reconstructedSource = reconstructed?.source as AudioSource.Local
        assertEquals(expectedUri, reconstructedSource.contentUriString)
        assertFalse(reconstructedSource.contentUriString == forbiddenDoubleUri)
    }

    @Test
    fun testArtworkUriPreservedInMediaMetadataAndRoundTrip() {
        val artwork = "https://i.scdn.co/image/ab67616d0000b273b069d273..."
        val track = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/external/audio/media/42"),
            title = "Track with Artwork",
            artist = "Artist",
            artworkUri = artwork,
            source = AudioSource.Local("content://media/external/audio/media/42")
        )

        val mediaItem = MediaItemMapper.toMediaItem(track)
        assertEquals(artwork, mediaItem.mediaMetadata.artworkUri?.toString())

        val reconstructed = MediaItemMapper.fromMediaItem(mediaItem)
        assertNotNull(reconstructed)
        assertEquals(artwork, reconstructed?.artworkUri)
    }

    @Test
    fun testResolvedPlaybackItemFactorySetsArtworkUri() {
        val artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        val track = Track(
            id = TrackId(ProviderId.YOUTUBE, "dQw4w9WgXcQ"),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            artworkUri = artwork,
            source = AudioSource.Remote(ProviderId.YOUTUBE, "dQw4w9WgXcQ")
        )

        val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(track, "https://googlevideo.com/videoplayback?id=123")
        assertEquals(artwork, mediaItem.mediaMetadata.artworkUri?.toString())

        val reconstructed = MediaItemMapper.fromMediaItem(mediaItem)
        assertNotNull(reconstructed)
        assertEquals(artwork, reconstructed?.artworkUri)
        assertEquals(track.id, reconstructed?.id)
        assertTrue(reconstructed?.source is AudioSource.Remote)
    }
}
