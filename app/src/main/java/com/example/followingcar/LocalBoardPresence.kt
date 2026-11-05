package com.example.followingcar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class LocalBoardPresenceResult(val health: RouteHealth, val endpoint: LocalBoardEndpoint? = null)

/**
 * Shared discovery hints are not evidence of presence. Each candidate must pass pinned SSH
 * authentication and still belong to a current local interface after the asynchronous check.
 * No command is executed, no credential is persisted and no control owner is resumed here.
 */
internal suspend fun probeLocalBoardPresence(
    preferred: LocalBoardEndpoint?,
    saved: LocalBoardEndpoint?,
    discover: suspend () -> List<LocalBoardEndpoint>,
    isCurrent: (LocalBoardEndpoint) -> Boolean,
    authenticate: suspend (LocalBoardEndpoint) -> RouteHealth,
    clock: () -> Long,
    attemptTimeoutMillis: Long = 4_000L,
): LocalBoardPresenceResult {
    require(attemptTimeoutMillis in 1..4_000)
    var last = RouteHealth().unavailable(clock())
    val attempted = mutableSetOf<List<Any?>>()
    var credentialRejected = false

    suspend fun check(endpoint: LocalBoardEndpoint?): LocalBoardPresenceResult? {
        currentCoroutineContext().ensureActive()
        if (endpoint == null || !isCurrent(endpoint) || attempted.size >= MAX_LOCAL_BOARD_CANDIDATES) return null
        val key = listOf(endpoint.host, endpoint.port, endpoint.network, endpoint.localAddress)
        if (!attempted.add(key)) return null
        val result = try {
            withTimeoutOrNull(attemptTimeoutMillis) { authenticate(endpoint) }
                ?: RouteHealth().unavailable(clock(), ReachabilityFailure.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { RouteHealth().unavailable(clock(), classifyReachabilityFailure(failure)) }
        currentCoroutineContext().ensureActive()
        if (!isCurrent(endpoint)) {
            last = RouteHealth().unavailable(clock())
            return null
        }
        last = if (result.status == Reachability.ONLINE && !result.isFreshOnline(clock()))
            RouteHealth().unavailable(clock()) else result
        if (result.isFreshOnline(clock())) return LocalBoardPresenceResult(result, endpoint)
        credentialRejected = result.failure in setOf(ReachabilityFailure.AUTHENTICATION, ReachabilityFailure.CONFIGURATION)
        return null
    }

    check(preferred)?.let { return it }
    if (credentialRejected) return LocalBoardPresenceResult(last)
    check(saved)?.let { return it }
    if (credentialRejected) return LocalBoardPresenceResult(last)
    val candidates = try {
        withTimeoutOrNull(6_000L) { discover() }.orEmpty()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }
    currentCoroutineContext().ensureActive()
    for (endpoint in orderedLocalEndpoints(candidates)) {
        check(endpoint)?.let { return it }
        if (credentialRejected) break
    }
    return LocalBoardPresenceResult(last)
}
