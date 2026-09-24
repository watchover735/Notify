package com.notify.download.spike

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class YtDlpFeasibilitySpikeTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun scratchDirectory_isCreatedUnderFilesDir() {
        val spike = YtDlpFeasibilitySpike(context)
        val scratchDir = spike.scratchDir

        assertNotNull(scratchDir)
        assertTrue("Scratch dir must exist", scratchDir.exists())
        assertTrue("Scratch dir must be directory", scratchDir.isDirectory)
        assertEquals(File(context.filesDir, "download_scratch").absolutePath, scratchDir.absolutePath)
    }

    @Test
    fun spikeStatus_handlesSuccessAndFailure() {
        val success = YtDlpFeasibilitySpike.SpikeStatus.Success(
            message = "Init OK",
            details = mapOf("version" to "2024.08.06")
        )
        assertEquals("Init OK", success.message)
        assertEquals("2024.08.06", success.details["version"])

        val failure = YtDlpFeasibilitySpike.SpikeStatus.Failure("Init error", IllegalStateException("Missing bin"))
        assertEquals("Init error", failure.error)
        assertTrue(failure.throwable is IllegalStateException)
    }
}
