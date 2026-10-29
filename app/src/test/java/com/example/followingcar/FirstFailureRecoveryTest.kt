package com.example.followingcar

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFailureRecoveryTest {
    @Test
    fun disconnectedTransportInvalidatesObservationsWithoutClaimingStopped() {
        val configuration = FollowConfiguration(FollowCamera.CONFIG, 1.5, 50.0, 50.0, 0.0,
            "fake", "fake", emptyList(), emptyMap(), "{}")
        val first = DiagnosticFailure("time", "follow.eof", "first", DiagnosticContext("op", "conn", "run"))
        val before = BoardUiState(connected = true, status = "已连接", videoServiceState = VideoServiceState.RUNNING,
            followConfiguration = configuration, followUncertain = true, followFirstFailure = first,
            driveEnabled = true, driveConfirmedStopped = true,
            followStatus = FollowStatus("RUNNING", false, FollowCamera.CONFIG, statusAgeMs = 10))
        val after = before.withTransportState(false)
        assertFalse(after.connected)
        assertEquals("连接已断开", after.status)
        assertEquals(VideoServiceState.UNKNOWN, after.videoServiceState)
        assertNull(after.followConfiguration)
        assertNull(after.followStatus!!.statusAgeMs)
        assertFalse(after.driveConfirmedStopped)
        assertTrue(after.followUncertain)
        assertEquals(first, after.followFirstFailure)
        assertFalse(after.canStartFollow)
        assertFalse(after.canEnableDrive)
    }

    @Test
    fun pendingRecoveryRestoresCorrelationAndFirstFaultIndependentlyOfRotatedHistory() {
        val context = DiagnosticContext("original-op", "original-ssh", "original-run")
        val first = DiagnosticFailure("time", "follow.ack_timeout", "[REDACTED] timeout", context)
        val snapshot = FollowRecoverySnapshot(context, first)
        val stored = snapshot.storedValues()
        assertEquals(snapshot, FollowRecoverySnapshot.restore(stored))
        assertEquals("original-run", snapshot.exportMetadata()["pendingFollowRunId"])
        assertEquals(first.message, snapshot.exportMetadata()["pendingFollowFirstFailureMessage"])
        assertEquals(6, snapshot.exportMetadata().size)
        assertEquals(setOf("pending", "operationId", "connectionId", "runId", "failureTime", "failureEvent", "failureMessage"), stored.keys)
        assertNull(FollowRecoverySnapshot.restore(emptyMap<String, String>()))
        assertNull(FollowRecoverySnapshot.restore(mapOf("pending" to "true")))
        assertEquals(FollowRecoverySnapshot(context), FollowRecoverySnapshot.restore(FollowRecoverySnapshot(context).storedValues()))
    }

    @Test
    fun laterSameOperationErrorCannotReplacePendingFirstFaultAfterHistoryRotationAndRestart() {
        val context = DiagnosticContext("pending-op", "old-ssh", "run")
        val first = DiagnosticFailure("first-time", "follow.ack_timeout", "original timeout", context)
        val persisted = FollowRecoverySnapshot(context, first).storedValues()
        val rotatedHistory = (1..10).map {
            DiagnosticFailure("later-$it", "follow.availability_failed", "check failed", DiagnosticContext("inspection-$it"))
        }
        val restored = FollowRecoverySnapshot.restore(persisted)
        assertEquals(first, selectFollowFirstFailure(context, restored, null, rotatedHistory))
        val newer = DiagnosticFailure("new-time", "follow.cleanup_failed", "later same-op failure", context)
        assertEquals(first, selectFollowFirstFailure(context, restored, null, rotatedHistory.drop(1) + newer))
        assertEquals(first, selectFollowFirstFailure(context, restored, newer, listOf(newer)))
        assertNull(selectFollowFirstFailure(DiagnosticContext("different-session"), restored, first, listOf(newer)))
    }

    @Test
    fun failedRecoveryPreservesOriginalAckFailureAndCorrectsDisconnectedLabel() {
        val first = DiagnosticFailure("2026-10-29T07:04:06Z", "follow.ack_timeout",
            SocketTimeoutException("跟随回执超时").message!!,
            DiagnosticContext("follow-operation", "ssh-connection", "board-run"))
        val failedFollow = BoardUiState(connected = true, status = "已连接",
            followUncertain = true, followFirstFailure = first, followError = first.message)

        val failedInspection = failedFollow.afterAvailabilityFailure("channel is not opened.", false)
        assertEquals(first, failedInspection.followFirstFailure)
        assertEquals("channel is not opened.", failedInspection.followError)
        assertEquals("连接已断开", failedInspection.status)
        assertFalse(failedInspection.connected)
        assertTrue(failedInspection.followUncertain)
        assertFalse(failedInspection.canStartFollow)
        assertFalse(failedInspection.canEnableDrive)

        val reconnected = failedInspection.copy(connected = true, status = "已连接")
        val secondFailure = reconnected.afterAvailabilityFailure("control still owned", true)
        assertEquals(first, secondFailure.followFirstFailure)
        assertEquals("已连接", secondFailure.status)
        assertTrue(secondFailure.followUncertain)
    }
}
