package com.example.followingcar

import android.os.SystemClock
import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Authentication is the whole probe. This interface deliberately has no command/control method. */
internal interface BoardPresenceConnection {
    suspend fun authenticate(config: BoardConnection)
    fun close()
}

private class SshPresenceConnection(private val diagnostic: DiagnosticContext) : BoardPresenceConnection {
    private val client = BoardSshClient()
    override suspend fun authenticate(config: BoardConnection) = client.connect(config, diagnostic)
    override fun close() = client.disconnect("presence_probe_complete", diagnostic)
}

/** Owns a separate connection, so timeout/cancellation cannot close the active control session. */
internal class BoardReachabilityProbe(
    private val connectionFactory: (DiagnosticContext) -> BoardPresenceConnection = { SshPresenceConnection(it) },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMillis: Long = 6_000,
) {
    suspend fun check(config: BoardConnection, diagnostic: DiagnosticContext = Diagnostics.newContext()): RouteHealth {
        val connection = connectionFactory(diagnostic)
        try {
            val authenticated = withTimeoutOrNull(timeoutMillis) {
                connection.authenticate(config)
                true
            } == true
            currentCoroutineContext().ensureActive()
            return if (authenticated) RouteHealth().online(clock())
            else RouteHealth().unavailable(clock(), ReachabilityFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RouteHealth().unavailable(clock(), classifyReachabilityFailure(failure))
        } finally {
            connection.close()
        }
    }
}

/** Inspect bounded provider messages only for classification; never expose/log them here. */
internal fun classifyReachabilityFailure(failure: Throwable): ReachabilityFailure {
    val chain = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(5).toList()
    if (chain.any { it is SSLPeerUnverifiedException || it is GeneralSecurityException }) return ReachabilityFailure.IDENTITY
    if (chain.any { it is SocketTimeoutException }) return ReachabilityFailure.TIMEOUT
    // A truncated TLS handshake can be a transient network fault, not bad credentials.
    val tls = chain.filterIsInstance<SSLException>().map { it.message.orEmpty().take(512).lowercase() }
    if (tls.any { message -> listOf("certificate_unknown", "bad_certificate", "certificate_required", "unknown_ca")
            .any { it in message } }) return ReachabilityFailure.IDENTITY
    val ssh = chain.filterIsInstance<JSchException>().map { it.message.orEmpty().take(512).lowercase() }
    if (ssh.any { "auth fail" in it || "auth cancel" in it }) return ReachabilityFailure.AUTHENTICATION
    if (ssh.any { "hostkey" in it || "host key" in it }) return ReachabilityFailure.IDENTITY
    if (chain.any { it is IllegalArgumentException }) return ReachabilityFailure.CONFIGURATION
    if (chain.any { it is IOException || it is JSchException }) return ReachabilityFailure.UNREACHABLE
    return ReachabilityFailure.UNKNOWN
}
