package com.example.followingcar

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardVideoServiceTest {
    private val running = "loaded\tactive\t123\t0\tpopulated"
    private val stopped = "loaded\tinactive\t0\t0\tempty"

    private fun snapshot(
        video: String = running,
        media: String = running,
        health: String = "ready",
        sourceMode: String? = null,
    ) = CommandResult("inspect", "VIDEO_SERVICE_V1\t$video\t$media\t$health" +
        (sourceMode?.let { "\t$it" } ?: "") + "\n", "", 0)

    @Test
    fun manualCameraStartUsesFixedArgumentAndInspectsActualSource() {
        val start = videoServiceCommand(true, ManualVideoCamera.GEMINI335)
        assertTrue(start.contains("CAMERA = 'gemini335'"))
        assertTrue(start.contains("command += ['--camera', CAMERA]"))
        val stop = videoServiceCommand(false, ManualVideoCamera.GEMINI335)
        assertTrue(stop.contains("if ACTION == 'start':"))
        assertEquals(ManualVideoCamera.GEMINI335,
            requireVideoServiceInspection(snapshot(sourceMode = "gemini335"), true,
                ManualVideoCamera.GEMINI335).activeCamera)
        assertThrows(IOException::class.java) {
            requireVideoServiceInspection(snapshot(sourceMode = "astra"), true,
                ManualVideoCamera.GEMINI335)
        }
        assertThrows(IOException::class.java) {
            requireVideoServiceInspection(snapshot(), true, ManualVideoCamera.GEMINI335)
        }
    }

    @Test
    fun videoCameraSelectionRequiresKnownStoppedServiceWhenConnected() {
        val disconnected = BoardUiState()
        assertTrue(disconnected.canSelectManualVideoCamera)
        val stopped = disconnected.copy(connected = true, videoServiceState = VideoServiceState.STOPPED)
        assertTrue(stopped.canSelectManualVideoCamera)
        assertFalse(stopped.copy(videoServiceState = VideoServiceState.RUNNING).canSelectManualVideoCamera)
        assertFalse(stopped.copy(videoServiceState = VideoServiceState.UNKNOWN).canSelectManualVideoCamera)
        assertFalse(stopped.copy(driveEnabled = true).canSelectManualVideoCamera)
        assertFalse(stopped.copy(followActive = true).canSelectManualVideoCamera)
    }

    @Test
    fun processStartedWithoutLiveRgbAndDepthIsNotReady() {
        assertEquals(VideoServiceState.RUNNING, parseVideoServiceState(snapshot()))
        assertEquals(VideoServiceState.UNKNOWN, parseVideoServiceState(snapshot(health = "not-ready")))
        assertThrows(IOException::class.java) {
            requireVideoServiceState(snapshot(health = "not-ready"), enabled = true)
        }
    }

    @Test
    fun partialShutdownNeverClaimsCameraReleased() {
        assertEquals(VideoServiceState.UNKNOWN, parseVideoServiceState(snapshot(video = stopped)))
        assertEquals(VideoServiceState.UNKNOWN, parseVideoServiceState(snapshot(media = stopped)))
        assertThrows(IOException::class.java) {
            requireVideoServiceState(snapshot(video = stopped), enabled = false)
        }
    }

    @Test
    fun stoppedRequiresBothUnitsAndNoResidualProcesses() {
        assertEquals(VideoServiceState.STOPPED,
            requireVideoServiceState(snapshot(stopped, stopped, "not-ready"), enabled = false))
        assertEquals(VideoServiceState.STOPPED,
            requireVideoServiceState(snapshot("loaded\tfailed\t0\t0\tempty", stopped, "not-ready"), enabled = false))
        assertEquals(VideoServiceState.UNKNOWN,
            parseVideoServiceState(snapshot(stopped, stopped, "ready")))
        for (unreleased in listOf(
            "loaded\tinactive\t123\t0\tempty",
            "loaded\tinactive\t0\t123\tempty",
            "loaded\tinactive\t0\t0\tpopulated",
            "loaded\tinactive\t0\t0\tunknown",
            "not-found\tinactive\t0\t0\tempty",
            "loaded\tdeactivating\t0\t0\tempty",
        )) {
            assertEquals(VideoServiceState.UNKNOWN,
                parseVideoServiceState(snapshot(unreleased, stopped, "not-ready")))
            assertThrows(IOException::class.java) {
                requireVideoServiceState(snapshot(unreleased, stopped, "not-ready"), enabled = false)
            }
        }
    }

    @Test
    fun failedOrIncompleteCommandCannotClaimSuccess() {
        for (result in listOf(
            snapshot().copy(exitCode = 1, stderr = "sudo denied"),
            snapshot().copy(truncated = true),
            snapshot().copy(stdout = "active\n"),
            snapshot(video = "loaded\tactive\t-1\t0\tpopulated"),
            snapshot(health = "probably-ready"),
        )) {
            assertThrows(IOException::class.java) { parseVideoServiceState(result) }
        }
    }
}
