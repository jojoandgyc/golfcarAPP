package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class FollowBackgroundControllerTest {
    @Test fun stopBeforeQueuedStartIsDeliveredCannotReviveService() {
        val gate = FollowBackgroundRequestGate()
        val start = gate.start()
        gate.stop()
        assertFalse(gate.isCurrent(start))
        assertFalse(gate.finish(start, "late failure"))
        assertNull(gate.failure)
    }

    @Test fun oldOwnerCompletionAndNotificationCannotAffectNewRun() {
        val gate = FollowBackgroundRequestGate()
        val old = gate.start()
        gate.stop()
        val current = gate.start()
        assertNotEquals(old, current)
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.finish(old))
        assertTrue(gate.hasPendingRequest())
        assertTrue(gate.isCurrent(current))
        assertTrue(gate.finish(current))
        assertFalse(gate.isCurrent(current))
        assertFalse(gate.hasPendingRequest())
    }

    @Test fun freshProcessGateCannotAcceptPreviousProcessNotification() {
        val previous = FollowBackgroundRequestGate()
        val next = FollowBackgroundRequestGate()
        val previousToken = previous.start()
        val nextToken = next.start()
        assertNotEquals(previousToken, nextToken)
        assertFalse(previous.isCurrent(nextToken))
        assertFalse(next.isCurrent(previousToken))
        assertFalse(next.finish(previousToken, "stale notification"))
        assertTrue(next.isCurrent(nextToken))
        assertNull(next.failure)
    }

    @Test fun currentFailureIsVisibleAndNewExplicitStartClearsIt() {
        val gate = FollowBackgroundRequestGate()
        val current = gate.start()
        assertTrue(gate.finish(current, "foreground unavailable"))
        assertEquals("foreground unavailable", gate.failure)
        assertFalse(gate.isCurrent(current))
        val retry = gate.start()
        assertTrue(gate.isCurrent(retry))
        assertNull(gate.failure)
        gate.stop()
        gate.stop()
        assertFalse(gate.isCurrent(retry))
    }
}
