package com.example.followingcar

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TransportDiagnosticPrivacyTest {
    @Test
    fun arbitraryCommandLabelsCannotBecomeLogFields() {
        assertEquals("follow_availability", safeDiagnosticCommandKind("follow_availability"))
        assertEquals("video_stop", safeDiagnosticCommandKind("video_stop"))
        assertEquals("terminal", safeDiagnosticCommandKind("echo token=private-value"))
        assertEquals("terminal", safeDiagnosticCommandKind(""))
    }

    @Test
    fun terminalFailurePreservesCodeLocationWithoutCommandOrOutput() {
        val original = IOException("failed command: export PASSWORD=secret-command",
            IllegalArgumentException("remote output: secret-output"))
        val safe = terminalDiagnosticFailure(original)

        assertFalse(safe.stackTraceToString().contains("secret-command"))
        assertFalse(safe.stackTraceToString().contains("secret-output"))
        assertFalse(safe.stackTraceToString().contains("export PASSWORD"))
        assertNull(safe.cause)
        assertArrayEquals(original.stackTrace, safe.stackTrace)
        assertEquals(true, safe.message.orEmpty().contains(original.javaClass.name))
    }
}
