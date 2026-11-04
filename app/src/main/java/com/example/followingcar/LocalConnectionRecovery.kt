package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal enum class LocalRecoveryStage { INITIAL, DISCOVERING, CANDIDATE, REMOTE }

internal data class LocalConnectionRecoveryResult(val route: BoardRoute, val endpoint: LocalBoardEndpoint? = null)

/**
 * A connection-establishment policy only. Callbacks MUST perform pinned SSH authentication only,
 * never issue commands. No persistence, state unlocking or control/session restart is performed.
 * A null initial endpoint requests discovery immediately. Advertisements remain untrusted until
 * connectLocal returns successfully. A wrong host key rejects that endpoint, not other candidates;
 * a password failure stops all further attempts because the actual board rejected our credential.
 */
internal suspend fun recoverLocalConnection(
    initialEndpoint: LocalBoardEndpoint?,
    discover: suspend () -> List<LocalBoardEndpoint>,
    connectLocal: suspend (LocalBoardEndpoint) -> Unit,
    connectRemote: (suspend () -> Unit)? = null,
    onProgress: suspend (LocalRecoveryStage, LocalBoardEndpoint?) -> Unit = { _, _ -> },
    localTimeoutMillis: Long = 4_000L,
    remoteTimeoutMillis: Long = 10_000L,
): LocalConnectionRecoveryResult {
    require(localTimeoutMillis in 1..4_000 && remoteTimeoutMillis in 1..10_000)
    var lastFailure: Exception = IOException("No authenticated board route available")
    val attempted = mutableSetOf<Triple<String, Int, android.net.Network?>>()

    suspend fun local(endpoint: LocalBoardEndpoint, stage: LocalRecoveryStage): Boolean {
        currentCoroutineContext().ensureActive()
        if (!attempted.add(Triple(endpoint.host, endpoint.port, endpoint.network))) return false
        onProgress(stage, endpoint)
        try {
            val completed = withTimeoutOrNull(localTimeoutMillis) {
                connectLocal(endpoint)
                true
            } == true
            currentCoroutineContext().ensureActive()
            if (!completed) throw SocketTimeoutException("Board connection attempt timed out")
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            lastFailure = failure
            if (recoveryFailureKind(failure) in setOf(ReachabilityFailure.AUTHENTICATION, ReachabilityFailure.CONFIGURATION)) throw failure
            return false
        }
    }

    if (initialEndpoint != null && local(initialEndpoint, LocalRecoveryStage.INITIAL))
        return LocalConnectionRecoveryResult(BoardRoute.LOCAL, initialEndpoint)

    currentCoroutineContext().ensureActive()
    onProgress(LocalRecoveryStage.DISCOVERING, null)
    val endpoints = try {
        // Independently bound even a faulty discovery provider; caller cancellation still propagates.
        withTimeoutOrNull(6_000) { discover() }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        lastFailure = failure
        emptyList()
    }
    for (endpoint in orderedLocalEndpoints(endpoints)) {
        if (local(endpoint, LocalRecoveryStage.CANDIDATE))
            return LocalConnectionRecoveryResult(BoardRoute.LOCAL, endpoint)
    }

    currentCoroutineContext().ensureActive()
    if (connectRemote != null) {
        onProgress(LocalRecoveryStage.REMOTE, null)
        val completed = withTimeoutOrNull(remoteTimeoutMillis) {
            connectRemote()
            true
        } == true
        currentCoroutineContext().ensureActive()
        if (!completed) throw SocketTimeoutException("Remote board connection attempt timed out")
        return LocalConnectionRecoveryResult(BoardRoute.REMOTE)
    }
    throw lastFailure
}

private fun recoveryFailureKind(failure: Exception): ReachabilityFailure {
    // A nested timeout must not cause repeated password submission after an authentication error.
    val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { next -> next === it } }.take(8)
    if (causes.filterIsInstance<JSchException>().any {
            val message = it.message.orEmpty().take(512).lowercase()
            "auth fail" in message || "auth cancel" in message
        }) return ReachabilityFailure.AUTHENTICATION
    return classifyReachabilityFailure(failure)
}
