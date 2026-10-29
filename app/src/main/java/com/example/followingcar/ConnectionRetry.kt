package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Retries only the initial connection, once, after a transient network failure.
 * The block must not send remote commands: a command may already have executed when its reply is lost.
 */
internal suspend fun <T> withInitialConnectionRetry(
    onRetry: suspend (nextAttempt: Int) -> Unit = {},
    connect: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return connect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (attempt >= 2 || !isTransientConnectionFailure(failure)) throw failure
            attempt += 1
            onRetry(attempt)
            delay(300L)
        }
    }
}

private fun isTransientConnectionFailure(failure: Throwable): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.toList()

    // Authentication, identity and negotiation failures need correction, even if a nested
    // exception also happens to mention a timeout. Cancellation must never reconnect.
    if (causes.any { cause ->
            cause is CancellationException || cause is InterruptedException ||
                permanentConnectionErrors.any { marker ->
                    cause.javaClass.simpleName.contains(marker, ignoreCase = true) ||
                        cause.message.orEmpty().contains(marker, ignoreCase = true)
                }
        }) return false

    return causes.any { cause ->
        val message = cause.message.orEmpty().lowercase()
        cause is SocketTimeoutException ||
            ((cause is IOException || cause is JSchException) &&
                transientNetworkErrors.any(message::contains)) ||
            // JSch may retain the socket timeout only in its message, without a cause.
            (cause is JSchException &&
                (message.contains("timed out") || message.contains("timeout")))
    }
}

private val permanentConnectionErrors = listOf(
    "auth", "password", "passphrase", "hostkey", "host key", "known_hosts",
    "algorithm", "algonego", "key exchange", "kex", "cipher", "signature",
    "permission denied", "eacces", "eperm",
)

private val transientNetworkErrors = listOf(
    "connection timed out", "connect timed out", "connect timeout", "socket timeout",
    "connection refused", "connection reset", "connection abort", "broken pipe",
    "econnrefused", "econnreset", "econnaborted", "etimedout", "epipe",
)
