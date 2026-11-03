package com.example.followingcar

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DriveTransactionGateTest {
    @Test fun stopBeforeAdmissionRefusesMotionButAllowsTerminalRequest() {
        val gate = DriveTransactionGate()
        assertFalse(gate.requestStop("APP_PAUSED"))
        assertNull(gate.interruptedStopSource())
        assertThrows(DriveStopRequestedException::class.java) { gate.begin(terminal = false) }
        assertFalse(gate.isPending())
        gate.begin(terminal = true)
        assertTrue(gate.isPending())
        assertNull("An idle normal close must still report genuine transport failures", gate.interruptedStopSource())
        gate.finish()
        assertFalse(gate.isPending())
    }

    @Test fun stopDuringRequestRequiresInterruptionAndKeepsFirstSource() {
        val gate = DriveTransactionGate()
        gate.begin(terminal = false)
        assertTrue(gate.requestStop("EMERGENCY_STOP"))
        assertTrue(gate.requestStop("APP_PAUSED"))
        assertEquals("EMERGENCY_STOP", gate.requestedStopSource())
        assertEquals("EMERGENCY_STOP", gate.interruptedStopSource())
        gate.finish()
        assertThrows(DriveStopRequestedException::class.java) { gate.begin(terminal = false) }
    }

    @Test fun concurrentStopCannotMissARequestThatHasNotStartedWriting() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val gate = DriveTransactionGate()
                val start = CyclicBarrier(2)
                val admission = pool.submit(Callable {
                    start.await(1, TimeUnit.SECONDS)
                    try { gate.begin(terminal = false); true }
                    catch (_: DriveStopRequestedException) { false }
                })
                val stop = pool.submit(Callable {
                    start.await(1, TimeUnit.SECONDS)
                    gate.requestStop("WINDOW_FOCUS_LOST")
                })
                assertEquals("An admitted request must be visible to the stopper",
                    admission.get(2, TimeUnit.SECONDS), stop.get(2, TimeUnit.SECONDS))
                gate.finish()
                assertThrows(DriveStopRequestedException::class.java) { gate.begin(terminal = false) }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun noRequestCanQueueBehindAnotherTransaction() {
        val gate = DriveTransactionGate()
        gate.begin(terminal = false)
        assertThrows(IllegalStateException::class.java) { gate.begin(terminal = true) }
        gate.finish()
        gate.begin(terminal = true)
    }

    @Test fun deliberateStopsInterruptWhileTransportLossKeepsFailureClassification() {
        for (source in ControlStopSource.entries) {
            assertEquals(source != ControlStopSource.TRANSPORT_LOST,
                shouldInterruptDriveForStop(false, source))
            assertTrue(shouldInterruptDriveForStop(true, source))
        }
    }
}
