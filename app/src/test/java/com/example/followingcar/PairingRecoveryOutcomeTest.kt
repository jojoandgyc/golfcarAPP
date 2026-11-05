package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class PairingRecoveryOutcomeTest {
    private val pending = PairingState(busy = true, stage = PairingStage.RECOVERING,
        confirmationRequired = true, error = "等待核对")

    @Test fun failedFreshProvisionRetainsOriginalReasonAfterConfirmedNetworkRestoration() {
        val reason = pairingErrorMessage("networkmanager_command_failed")
        val result = pending.copy(firstError = reason).withReleasedPairingOutcome(
            "network_restored", submittedThisOperation = true, firstFailureCode = "networkmanager_command_failed")
        assertEquals(PairingStage.ERROR, result.stage)
        assertTrue(result.error!!.contains(reason))
        assertTrue(result.error.orEmpty().contains("小车已恢复原网络"))
        assertTrue(result.status.contains("本次配网未成功"))
        assertEquals(reason, result.firstError)
        assertFalse(result.confirmationRequired)
        assertFalse(result.hotspotActive)
        assertNull(result.boardIp)
    }

    @Test fun lostInitialFailureStillShowsFailedAttemptWithoutInventingCause() {
        val result = pending.withReleasedPairingOutcome("network_restored", true, null)
        assertEquals(PairingStage.ERROR, result.stage)
        assertTrue(result.error!!.contains("本次未能接入手机热点"))
        assertTrue(result.error.orEmpty().contains("小车已恢复原网络"))
        assertNull(result.firstError)
    }

    @Test fun explicitRecoveryOfAnOlderFailureIsNotPresentedAsANewFailedAttempt() {
        val result = pending.withReleasedPairingOutcome("network_restored", false,
            "networkmanager_command_failed")
        assertEquals(PairingStage.IDLE, result.stage)
        assertNull(result.error)
        assertFalse(result.confirmationRequired)
        // The earlier failure remains history even though this read-only recovery succeeded.
        assertEquals(pairingErrorMessage("networkmanager_command_failed"), result.firstError)
        assertTrue(result.status.contains("恢复原网络"))
    }

    @Test fun confirmedCancellationDoesNotBecomeAFailureOrFirstException() {
        for (cause in listOf("cancelled", "join_cancelled")) {
            for (submitted in listOf(false, true)) {
                val result = pending.withReleasedPairingOutcome("network_restored", submitted, cause)
                assertEquals(PairingStage.IDLE, result.stage)
                assertNull(result.error)
                assertNull(result.firstError)
                assertFalse(result.confirmationRequired)
            }
        }
    }

    @Test fun otherTerminalReleaseCodesDoNotFalselyClaimOriginalNetworkWasRestored() {
        for (code in listOf("network_superseded", "hotspot_disconnected", "request_expired")) {
            val result = pending.withReleasedPairingOutcome(code, true, "bluetooth_eof")
            assertEquals(PairingStage.ERROR, result.stage)
            assertFalse(result.status.contains("恢复原网络"))
            assertFalse(result.error!!.contains("恢复原网络"))
            assertFalse(result.confirmationRequired)
        }
    }

    @Test fun ordinaryOldRequestReleaseRemainsNeutralAndClearsStaleEndpoint() {
        for (code in listOf("network_restored", "network_superseded", "hotspot_disconnected", "request_expired")) {
            val result = pending.copy(hotspotActive = true, boardIp = "192.168.1.3")
                .withReleasedPairingOutcome(code, false, null)
            assertEquals(PairingStage.IDLE, result.stage)
            assertNull(result.error)
            assertNull(result.boardIp)
            assertFalse(result.hotspotActive)
        }
    }

    @Test fun unresolvedResultCannotBeTurnedIntoAnUnlockedTerminalPresentation() {
        val failure = runCatching {
            pending.withReleasedPairingOutcome("network_address_unconfirmed", true, "bluetooth_eof")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(pending.confirmationRequired)
    }
}
