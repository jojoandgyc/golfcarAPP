package com.example.followingcar

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class RemoteConnectionTest {
    @Test fun requiresDnsHostRatherThanUrlCredentialsOrIp() {
        assertEquals("car.nujabes.top", requireRemoteHost("Car.Nujabes.Top"))
        listOf("", "localhost", "10.4.4.13", "https://car.nujabes.top", "car.nujabes.top:2443",
            "user:secret@car.nujabes.top", " car.nujabes.top", "car.nujabes.top/", "-bad.example",
            "bad-.example", "bad..example", "bad\n.example").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { requireRemoteHost(invalid) }
        }
    }

    @Test fun validatesPortsAndImportSizeWithoutTruncating() {
        assertEquals(2443, requireRemotePort(2443))
        listOf(-1, 0, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { requireRemotePort(port) }
        }
        assertEquals(65536, readBounded(ByteArrayInputStream(ByteArray(65536)), 65536).size)
        assertThrows(IOException::class.java) {
            readBounded(ByteArrayInputStream(ByteArray(65537)), 65536)
        }
    }

    @Test fun connectionDescriptionNeverIncludesPasswords() {
        assertFalse(BoardConnection("relay.example.test", 2443, "topeet", "very-private-password")
            .toString().contains("very-private-password"))
        val bundle = RemoteCredentialBundle("relay.example.test", 2443, 8443,
            byteArrayOf(99, 88, 77), "private-p12-password".toCharArray())
        assertFalse(bundle.toString().contains("private-p12-password"))
        bundle.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), bundle.pkcs12)
        assertTrue(bundle.password.all { it == '\u0000' })
    }

    @Test fun mismatchedTlsDestinationIsRejectedBeforeConnecting() {
        val tls = RemoteTlsConfig(javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory,
            "relay.example.test")
        Socket().use { socket ->
            assertThrows(IllegalArgumentException::class.java) {
                openBoardSocket(socket, "another.example.test", 2443, 100, tls)
            }
            assertFalse(socket.isConnected)
        }
    }
}
