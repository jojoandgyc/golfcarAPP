package com.example.followingcar

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class FollowReconnectPolicyTest {
    private val context = DiagnosticContext("operation", "old-connection", "a".repeat(32))

    @Test fun reconnectableStopIntentAndFirstFaultSurviveRestart() {
        val first = DiagnosticFailure("time", "follow.eof", "lost", context)
        for (stop in listOf(false, true)) {
            val snapshot = FollowRecoverySnapshot(context, first, reconnectable = true, stopIntent = stop)
            assertEquals(snapshot, FollowRecoverySnapshot.restore(snapshot.storedValues()))
            assertEquals(stop, snapshot.exportMetadata()["pendingFollowStopIntent"])
        }
        val missingStop = FollowRecoverySnapshot(context, reconnectable = true).storedValues() - "stopIntent"
        assertTrue(requireNotNull(FollowRecoverySnapshot.restore(missingStop)).stopIntent)
    }

    @Test fun detachedAllowsConnectionRecoveryButNeverNewMotorOrVideoControl() {
        val detached = BoardUiState(followUncertain = true, followDetached = true, followReconnectable = true)
        assertTrue(detached.canRecoverConnection)
        for (connected in listOf(false, true)) {
            val state = detached.copy(connected = connected)
            assertFalse(state.canStartFollow)
            assertFalse(state.canEnableDrive)
            assertFalse(state.canConfigureMotor)
            assertFalse(state.canToggleVideoService)
            assertTrue(followPresentation(state).subtitle.contains("可能"))
        }
        assertEquals("停止请求待确认", followPresentation(detached.copy(followStopPending = true)).title)
    }

    @Test fun onlyTransportErrorsQualifyAsCommunicationLoss() {
        assertTrue(IOException("EOF").isFollowCommunicationLoss())
        assertTrue(SocketTimeoutException("ack").isFollowCommunicationLoss())
        assertFalse(FollowProtocolException("wrong sequence").isFollowCommunicationLoss())
        assertFalse(FollowSessionException("worker fault", null).isFollowCommunicationLoss())
        assertFalse(IllegalStateException("programming error").isFollowCommunicationLoss())
    }

    @Test fun protocolFailureDoesNotClaimCommunicationLossOrAutonomousSuccess() {
        val state = BoardUiState(connected = true, followUncertain = true, followReconnectable = true,
            followDetached = false, followError = "invalid protocol")
        assertEquals("原会话待确认", followPresentation(state).title)
        assertFalse(followPresentation(state).fresh)
        assertFalse(state.canStartFollow)
    }
}
