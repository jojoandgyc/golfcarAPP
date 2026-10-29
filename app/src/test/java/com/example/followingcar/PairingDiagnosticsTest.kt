package com.example.followingcar

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PairingDiagnosticsTest {
    @Test fun transportFailureRetainsCauseButLogsNoExceptionTextOrStack() {
        val privateFrame = "{\"ssid\":\"private-hotspot\",\"psk\":\"private-password\"}"
        val raw = IOException("failed to send $privateFrame")
        val failure = PairingFailure("bluetooth_failed", raw)
        assertSame(raw, failure.cause)

        val fields = pairingFailureFields(failure, PairingStage.SENDING_CREDENTIALS,
            PairingIoPhase.WRITE, 1250, 42, PairingBondState.BONDED)
        assertEquals("SENDING_CREDENTIALS", fields["stage"])
        assertEquals("write", fields["ioPhase"])
        assertEquals(PairingFailure::class.java.name, fields["exceptionType"])
        assertEquals(IOException::class.java.name, fields["causeTypes"])
        assertEquals(1250L, fields["elapsedMs"])
        assertEquals(42L, fields["ioElapsedMs"])
        assertEquals("BONDED", fields["bondState"])
        assertEquals(setOf("stage", "ioPhase", "exceptionType", "causeTypes", "elapsedMs", "ioElapsedMs", "bondState"), fields.keys)
        assertFalse(fields.toString().contains("private-"))
        assertFalse(fields.toString().contains(privateFrame))
        assertFalse(fields.toString().contains("failed to send"))
        assertFalse(fields.toString().contains("PairingDiagnosticsTest"))
    }

    @Test fun failureDetailsKeepOriginalStageAfterUiHasEnteredError() {
        val fields = mapOf<String, Any?>("stage" to PairingStage.ERROR.name) + pairingFailureFields(
            PairingFailure("bluetooth_failed", IOException("sensitive original details")),
            PairingStage.CONNECTING_BLUETOOTH, PairingIoPhase.CONNECT, 300, 287, PairingBondState.BONDED)
        assertEquals("CONNECTING_BLUETOOTH", fields["stage"])
        assertFalse(fields.toString().contains("sensitive original details"))
    }

    @Test fun causeChainIsBoundedWithoutRenderingMessages() {
        var error: Exception = IOException("hidden-0")
        repeat(20) { error = IOException("hidden-${it + 1}", error) }
        val fields = pairingFailureFields(error, PairingStage.CHECKING_BOARD, PairingIoPhase.READ,
            10, 5, PairingBondState.UNKNOWN)
        assertEquals(IOException::class.java.name, fields["causeTypes"])
        assertFalse(fields.toString().contains("hidden-"))
    }

    @Test fun connectionReadWriteAndBondingFailuresHaveDistinctActionableMessages() {
        val connect = pairingFailureMessage("bluetooth_failed", PairingStage.CONNECTING_BLUETOOTH, PairingIoPhase.CONNECT)
        val read = pairingFailureMessage("bluetooth_failed", PairingStage.CHECKING_BOARD, PairingIoPhase.READ)
        val write = pairingFailureMessage("bluetooth_failed", PairingStage.SENDING_CREDENTIALS, PairingIoPhase.WRITE)
        val bond = pairingFailureMessage("bluetooth_timeout", PairingStage.PAIRING_BLUETOOTH, null)
        assertEquals(4, setOf(connect, read, write, bond).size)
        assertTrue(connect.contains("先靠近小车"))
        assertTrue(connect.contains("已开机"))
        assertTrue(connect.contains("蓝牙服务开启"))
        assertTrue(read.contains("读取小车响应"))
        assertTrue(write.contains("发送蓝牙请求"))
        assertFalse(write.contains("发送配网请求"))
        assertTrue(write.contains("先查询结果"))
        assertTrue(bond.contains("系统的配对提示"))
        assertTrue(pairingFailureMessage("protocol_error", PairingStage.CHECKING_BOARD, PairingIoPhase.READ)
            .contains("状态响应格式"))
    }

    @Test fun timeoutCancellationAndPermissionErrorsKeepTheirExistingClassification() = runBlocking {
        val timeout = runCatching { withTimeout(1) { delay(1000) } }.exceptionOrNull() as Exception
        assertEquals("bluetooth_timeout", pairingFailureCode(timeout))
        assertEquals("cancelled", pairingFailureCode(CancellationException("private cancellation text")))
        assertEquals("permission_denied", pairingFailureCode(SecurityException("private platform text")))
        assertEquals("permission_denied", pairingFailureCode(PairingFailure("permission_denied",
            SecurityException("private platform text"))))
        assertEquals("bluetooth_eof", pairingFailureCode(PairingFailure("bluetooth_eof")))
        assertEquals(pairingErrorMessage("cancelled"),
            pairingFailureMessage("cancelled", PairingStage.CONNECTING_BLUETOOTH, PairingIoPhase.CONNECT))
    }
}
