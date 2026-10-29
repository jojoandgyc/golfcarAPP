package com.example.followingcar

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class PairingModelsTest {
    @Test fun responseLossAfterAnyPossibleCredentialWriteRequiresConfirmation() {
        assertTrue(pairingFailureRequiresConfirmation(true, false))
        assertFalse(pairingFailureRequiresConfirmation(false, false))
        assertFalse(pairingFailureRequiresConfirmation(true, true))
    }

    @Test fun unsuccessfulReplyOnlyClearsUnknownWhenBoardProvesNoNetworkChange() {
        assertTrue(pairingResultIsDefinitive(false, true, false))
        assertFalse(pairingResultIsDefinitive(false, null, false))
        assertFalse(pairingResultIsDefinitive(false, false, false))
        assertFalse(pairingResultIsDefinitive(false, true, true))
        assertTrue(pairingResultIsDefinitive(true, true, true))
    }

    @Test fun remoteErrorCannotEchoCredentialsEvenWhenItLooksLikeAnIdentifier() {
        assertEquals("networkmanager_command_failed", pairingBoardErrorCode("networkmanager_command_failed"))
        assertEquals("control_busy_or_unconfirmed", pairingBoardErrorCode("control_busy_or_unconfirmed"))
        for (untrusted in listOf("secretpassword123", "private_hotspot_name", "password=abc12345", "new_error", null, 123)) {
            assertEquals("board_failed", pairingBoardErrorCode(untrusted))
        }
        assertFalse(pairingErrorMessage(pairingBoardErrorCode("secretpassword123")).contains("secretpassword123"))
    }

    @Test fun unknownOutcomeAndExistingHotspotPreventRepeatedProvision() {
        val ready = PairingState(supported = true, permissionGranted = true, bluetoothEnabled = true)
        assertNull(pairingRequestAllowed(ready, true))
        assertNotNull(pairingRequestAllowed(ready.copy(confirmationRequired = true), true))
        assertNotNull(pairingRequestAllowed(ready.copy(hotspotActive = true), true))
        assertNotNull(pairingRequestAllowed(ready.copy(busy = true), true))
        assertNotNull(pairingRequestAllowed(ready, false))
    }

    @Test fun failedPermissionsAndDisabledBluetoothNeverProceed() {
        val ready = PairingState(supported = true, permissionGranted = true, bluetoothEnabled = true)
        assertNotNull(pairingRequestAllowed(ready.copy(permissionGranted = false), true))
        assertNotNull(pairingRequestAllowed(ready.copy(bluetoothEnabled = false), true))
        assertNotNull(pairingRequestAllowed(ready.copy(supported = false), true))
    }

    @Test fun credentialsUseByteLengthAndRejectControlCharacters() {
        assertTrue(validPairingCredentials("Android-Hotspot", "abcdefgh"))
        assertTrue(validPairingCredentials("中文热点", "1234567890"))
        assertFalse(validPairingCredentials("中".repeat(11), "abcdefgh"))
        assertFalse(validPairingCredentials("normal\nwrong", "abcdefgh"))
        assertFalse(validPairingCredentials("normal", "short"))
        assertFalse(validPairingCredentials("normal", "password\n"))
        assertTrue(validPairingCredentials("normal", "a".repeat(64)))
        assertFalse(validPairingCredentials("normal", "g".repeat(64)))
    }

    @Test fun frameReaderPreservesOneFrameWithoutConsumingNextReply() {
        val input = ByteArrayInputStream("{\"type\":\"progress\"}\n{\"type\":\"result\"}\n".toByteArray())
        assertEquals("{\"type\":\"progress\"}", readPairingFrame(input))
        assertEquals("{\"type\":\"result\"}", readPairingFrame(input))
    }

    @Test fun truncatedAndOversizedFramesAreRejectedWithDistinctFixedCodes() {
        assertFrameFailure("bluetooth_eof", byteArrayOf())
        assertFrameFailure("partial_reply", "unfinished".toByteArray())
        assertFrameFailure("empty_reply", "\n".toByteArray())
        assertFrameFailure("reply_too_large", ("x".repeat(4097) + "\n").toByteArray())
        assertEquals(4096, readPairingFrame(ByteArrayInputStream(("x".repeat(4096) + "\n").toByteArray())).length)
    }

    @Test fun malformedUtf8CannotBeSilentlyReplacedInProtocolReply() {
        assertFrameFailure("invalid_reply_encoding", byteArrayOf(0xc3.toByte(), 0x28, 10))
    }

    @Test fun boardAddressMustBelongToActualNewHotspotSubnet() {
        val before = listOf(PairingLocalAddress("172.16.16.10", 24), PairingLocalAddress("192.168.44.1", 24))
        val after = before + PairingLocalAddress("192.168.232.1", 28)
        val hotspot = newPairingLocalAddresses(before, after)
        assertEquals(listOf(PairingLocalAddress("192.168.232.1", 28)), hotspot)
        assertEquals("192.168.232.6", verifiedPairingBoardIp("192.168.232.6", "192.168.232.1", 28, hotspot))
        assertNull(verifiedPairingBoardIp("172.16.16.82", "172.16.16.10", 24, hotspot))
        assertNull(verifiedPairingBoardIp("192.168.44.3", "192.168.44.1", 24, hotspot))
    }

    @Test fun noDefaultSubnetAssumptionAndNoPhoneNetworkBroadcastEndpoints() {
        val local = listOf(PairingLocalAddress("192.168.232.1", 28))
        for (ip in listOf("192.168.232.1", "192.168.232.0", "192.168.232.15", "192.168.232.16"))
            assertNull(ip, verifiedPairingBoardIp(ip, "192.168.232.1", 28, local))
        assertNull(verifiedPairingBoardIp("192.168.232.6", "192.168.232.1", 24, local))
        assertNull(verifiedPairingBoardIp("192.168.232.6", "192.168.232.1", -1, local))
        assertNull(verifiedPairingBoardIp("car.local", "192.168.232.1", 28, local))
        assertNull(verifiedPairingBoardIp("127.0.0.1", "192.168.232.1", 28, local))
    }

    @Test fun uiModelToStringDoesNotExposeBluetoothIdentityOrNetworkAddress() {
        val device = PairingDevice("private-device-name", "12:34:56:78:90:AA", true)
        val state = PairingState(devices = listOf(device), selectedAddress = device.address, boardIp = "192.168.232.6")
        assertFalse(device.toString().contains("private-device-name"))
        assertFalse(device.toString().contains(device.address))
        assertFalse(state.toString().contains(device.address))
        assertFalse(state.toString().contains("192.168.232.6"))
    }

    private fun assertFrameFailure(code: String, bytes: ByteArray) {
        try {
            readPairingFrame(ByteArrayInputStream(bytes))
            fail("Expected $code")
        } catch (failure: PairingFailure) { assertEquals(code, failure.code) }
    }
}
