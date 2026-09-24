package com.notify.core.playback

import androidx.media3.common.PlaybackException
import com.notify.core.model.PlaybackError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorMapperTest {

    @Test
    fun testFileNotFoundMapsToMissingFile() {
        val exception = PlaybackException(
            "File vanished",
            null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )

        val error = PlaybackErrorMapper.fromPlaybackException(exception, "content://media/42")

        assertTrue(error is PlaybackError.MissingFile)
        val missing = error as PlaybackError.MissingFile
        assertEquals("content://media/42", missing.uriString)
        assertTrue(missing.isRecoverable)
    }

    @Test
    fun testNoPermissionMapsToPermissionRevoked() {
        val exception = PlaybackException(
            "Access denied",
            null,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        )

        val error = PlaybackErrorMapper.fromPlaybackException(exception)

        assertTrue(error is PlaybackError.PermissionRevoked)
        val perm = error as PlaybackError.PermissionRevoked
        assertEquals("STORAGE_READ_PERMISSION", perm.permission)
        assertTrue(perm.isRecoverable)
    }

    @Test
    fun testDecoderFailureMapsToDecoderFailure() {
        val exception = PlaybackException(
            "Codec initialization failed",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )

        val error = PlaybackErrorMapper.fromPlaybackException(exception)

        assertTrue(error is PlaybackError.DecoderFailure)
        assertFalse(error.isRecoverable)
    }

    @Test
    fun testUnknownCodeMapsToUnknownError() {
        val exception = PlaybackException(
            "Unspecified custom player error",
            null,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )

        val error = PlaybackErrorMapper.fromPlaybackException(exception)

        assertTrue(error is PlaybackError.Unknown)
        assertFalse(error.isRecoverable)
    }
}
