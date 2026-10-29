package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Loopback synthetic TLS endpoints only: no server, development board or motor access. */
@RunWith(AndroidJUnit4::class)
class RemoteTlsTransportTest {
    @Test fun importedAndroidKeyStoreHandleCompletesMutualTls() =
        verifyImportedKeyStoreTls("remote-client-test-only.p12")

    @Test fun importedEcAndroidKeyStoreHandleCompletesMutualTls() =
        verifyImportedKeyStoreTls("remote-client-ec-test-only.p12")

    private fun verifyImportedKeyStoreTls(fixture: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID()
        val repository = RemoteCredentials(app, id)
        try {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                .open(fixture).use { it.readBytes() }
            val json = JSONObject().put("schemaVersion", 1).put("host", "relay.example.test")
                .put("controlPort", 2443).put("videoPort", 8443)
                .put("clientPkcs12Base64", Base64.getEncoder().encodeToString(bytes))
                .put("clientPkcs12Password", "fixture-password")
            repository.importProfile(json.toString().byteInputStream())
            val identity = repository.getClientIdentity()!!
            val store = KeyStore.getInstance("PKCS12").apply {
                load(bytes.inputStream(), "fixture-password".toCharArray())
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val client = SSLContext.getInstance("TLS").apply {
                init(arrayOf(RemoteClientKeyManager(identity)), trust.trustManagers, null)
            }
            withServer(context(fixture)) { server, served ->
                LocalSocket().use { raw ->
                    openBoardSocket(raw, "relay.example.test", server.localPort, 3000,
                        RemoteTlsConfig(client.socketFactory, "relay.example.test")).use { socket ->
                        socket.outputStream.write(42)
                        assertEquals(42, socket.inputStream.read())
                    }
                }
                assertTrue(served.await(3, TimeUnit.SECONDS))
            }
        } finally {
            repository.clear()
            File(app.cacheDir, "remote-credential-test-$id").deleteRecursively()
        }
    }

    @Test fun mutualTlsAuthenticatesHostAndKeepsNodelay() {
        withServer { server, served ->
            LocalSocket().use { raw ->
                val tls = RemoteTlsConfig(context().socketFactory, "relay.example.test")
                (openBoardSocket(raw, "relay.example.test", server.localPort, 3000, tls) as SSLSocket).use { socket ->
                    assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
                    assertTrue(socket.tcpNoDelay)
                    socket.outputStream.write(42)
                    assertEquals(42, socket.inputStream.read())
                }
            }
            assertTrue(served.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun trustedCertificateWithWrongHostnameStillFails() {
        withServer { server, _ ->
            LocalSocket().use { raw ->
                assertThrows(SSLException::class.java) {
                    openBoardSocket(raw, "different.example.test", server.localPort, 3000,
                        RemoteTlsConfig(context().socketFactory, "different.example.test"))
                }
            }
        }
    }

    @Test fun platformTrustRejectsUntrustedServerCertificate() {
        withServer { server, _ ->
            LocalSocket().use { raw ->
                val untrusted = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                assertThrows(SSLException::class.java) {
                    openBoardSocket(raw, "relay.example.test", server.localPort, 3000,
                        RemoteTlsConfig(untrusted.socketFactory, "relay.example.test"))
                }
            }
        }
    }

    @Test fun closingOwnedRawSocketInterruptsBlockedTlsHandshake() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val raw = LocalSocket()
            val future = executor.submit<Boolean> {
                try {
                    openBoardSocket(raw, "relay.example.test", server.localPort, 10_000,
                        RemoteTlsConfig(context().socketFactory, "relay.example.test"))
                    false
                } catch (_: Exception) { true }
            }
            server.soTimeout = 3000
            server.accept().use {
                // The server intentionally sends no ServerHello. Closing raw must wake TLS.
                val started = System.nanoTime()
                raw.close()
                assertTrue(future.get(2, TimeUnit.SECONDS))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            }
        }
        executor.shutdownNow()
    }

    private fun context(fixture: String = "remote-client-test-only.p12"): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open(fixture).use {
            store.load(it, "fixture-password".toCharArray())
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-password".toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
    }

    private fun withServer(serverContext: SSLContext = context(), block: (SSLServerSocket, CountDownLatch) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                server.needClientAuth = true
                server.soTimeout = 4000
                val served = CountDownLatch(1)
                val worker = executor.submit {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 3000
                            socket.startHandshake()
                            socket.outputStream.write(socket.inputStream.read())
                            served.countDown()
                        }
                    } catch (_: Exception) { /* Client-side negative tests terminate handshakes. */ }
                }
                block(server, served)
                worker.get(5, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }

    /** Keep certificate hostname resolution separate from the loopback-only test route. */
    private class LocalSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val port = (endpoint as InetSocketAddress).port
            super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout)
        }
    }
}
