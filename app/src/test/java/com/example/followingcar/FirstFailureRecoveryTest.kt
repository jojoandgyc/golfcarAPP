package com.example.followingcar

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFailureRecoveryTest {
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
