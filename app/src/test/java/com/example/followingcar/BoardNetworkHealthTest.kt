package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class BoardNetworkHealthTest {
    @Test fun phoneInternetAndServerAvailabilityNeverProveCarIsOnline() {
        val health = BoardNetworkHealth(phone = PhoneNetworkState(observed = true,
            available = true, internetValidated = true, transportLabel = "Wi-Fi", wifiAvailable = true))
        assertEquals("小车状态待确认", health.onlineLabel(100))
        assertFalse(health.remoteFallbackAvailable(100))
    }

    @Test fun onlyFreshAuthenticatedEvidenceIsOnlineAndClockResetCannotRefreshIt() {
        val route = RouteHealth().online(1_000)
        assertEquals(Reachability.ONLINE, route.effectiveStatus(31_000))
        assertEquals(Reachability.STALE, route.effectiveStatus(31_001))
        assertFalse(route.isFreshOnline(999))
        assertNull(route.lastOnlineAgeSeconds(999))
        assertEquals(30L, route.lastOnlineAgeSeconds(31_000))
    }

    @Test fun checkInProgressRetainsOnlyUnexpiredEvidence() {
        val route = RouteHealth().online(1_000).checking()
        assertTrue(route.isFreshOnline(10_000))
        assertTrue(route.probeInProgress)
        assertEquals(Reachability.STALE, route.effectiveStatus(32_000))
        assertEquals(Reachability.UNKNOWN, RouteHealth().checking().effectiveStatus(1_000))
    }

    @Test fun failedAuthenticationImmediatelyRemovesOnlineClaimButRetainsLastSeen() {
        val route = RouteHealth().online(1_000).unavailable(1_100, ReachabilityFailure.AUTHENTICATION)
        assertFalse(route.isFreshOnline(1_101))
        assertEquals(1_000L, route.lastOnlineElapsedMs)
        assertEquals(Reachability.UNREACHABLE, route.effectiveStatus(1_101))
        assertEquals("暂不可达", route.statusLabel(1_101))
    }

    @Test fun checkingAfterFailureCannotReviveRecentSuccessOnEitherPath() {
        for (path in BoardRoute.entries) {
            val previous = RouteHealth().online(9_000)
            val checking = previous.unavailable(9_500).checking()
            val health = BoardNetworkHealth().updateRoute(path, checking)
            assertFalse(health.route(path).isFreshOnline(10_000))
            assertEquals(Reachability.UNREACHABLE, health.route(path).effectiveStatus(10_000))
            assertEquals(ReachabilityFailure.UNREACHABLE, checking.failure)
            assertTrue(checking.probeInProgress)
            assertNotEquals("小车在线", health.onlineLabel(10_000))
            assertEquals(9_000L, checking.lastOnlineElapsedMs)
            assertEquals(1L, checking.lastOnlineAgeSeconds(10_000))
            assertTrue(checking.online(10_001).isFreshOnline(10_001))
        }
    }

    @Test fun checkingAfterInvalidationRetainsHistoryWithoutRestoringOnlineEvidence() {
        for (path in BoardRoute.entries) {
            val previous = RouteHealth().online(9_000)
            val checking = previous.invalidate().checking()
            val health = BoardNetworkHealth().updateRoute(path, checking)
            assertFalse(checking.isFreshOnline(10_000))
            assertFalse(health.remoteFallbackAvailable(10_000))
            assertEquals(Reachability.STALE, checking.effectiveStatus(10_000))
            assertEquals("小车状态待确认", health.onlineLabel(10_000))
            assertEquals(9_000L, checking.lastOnlineElapsedMs)
            assertTrue(checking.online(10_001).isFreshOnline(10_001))
        }
    }

    @Test fun localFailureCanOfferRemoteRecoveryWithoutChangingLocalEvidence() {
        val health = BoardNetworkHealth(local = RouteHealth().unavailable(100), remote = RouteHealth().online(100))
        assertEquals("小车在线", health.onlineLabel(110))
        assertTrue(health.remoteFallbackAvailable(110))
        assertEquals(Reachability.UNREACHABLE, health.local.effectiveStatus(110))
        assertEquals("小车状态待确认", health.onlineLabel(31_000))
        assertFalse(health.remoteFallbackAvailable(31_000))
    }

    @Test fun phoneDefaultInternetLossDoesNotInvalidateAnAuthenticatedLocalPath() {
        val original = BoardNetworkHealth(local = RouteHealth().online(1_000),
            phone = PhoneNetworkState(observed = true, available = true, internetValidated = true))
        val changed = original.copy(phone = PhoneNetworkState(observed = true, wifiAvailable = true))
        assertTrue(changed.local.isFreshOnline(1_100))
        assertEquals("小车在线", changed.onlineLabel(1_100))
        assertFalse(changed.phone.internetValidated)
    }

    @Test fun slowerProbeCannotOverwriteNewControlAck() {
        val newAck = RouteHealth().online(2_000)
        val timeout = RouteHealth().unavailable(3_000, ReachabilityFailure.TIMEOUT)
        assertEquals(newAck, newAck.acceptProbe(timeout, probeStartedAt = 1_500))
        assertEquals(timeout.copy(lastOnlineElapsedMs = 2_000),
            newAck.acceptProbe(timeout, probeStartedAt = 2_500))
    }

    @Test fun pathChangesInvalidateEvidenceWithoutClaimingPowerOff() {
        val health = BoardNetworkHealth(local = RouteHealth().online(10), remote = RouteHealth().online(20))
        val changed = health.updateRoute(BoardRoute.LOCAL, health.local.invalidate())
        assertEquals(Reachability.STALE, changed.local.effectiveStatus(30))
        assertTrue(changed.remote.isFreshOnline(30))
        assertEquals("小车在线", changed.onlineLabel(30))
        val unreachable = changed.copy(local = changed.local.unavailable(30), remote = changed.remote.unavailable(30))
        assertEquals("小车暂不可达", unreachable.onlineLabel(31))
    }

    @Test fun cancellingRefreshPreservesStableResultEvidenceAndFailureOnBothPaths() {
        val stableResults = listOf(RouteHealth(), RouteHealth().online(1_000),
            RouteHealth().online(1_000).invalidate(),
            RouteHealth().online(1_000).unavailable(1_100, ReachabilityFailure.TIMEOUT))
        for (path in BoardRoute.entries) for (original in stableResults) {
            val checking = original.checking().checking()
            val health = BoardNetworkHealth().updateRoute(path, checking)
            assertEquals(original.status, checking.status)
            assertEquals(original.failure, checking.failure)
            assertEquals(original.lastCheckedElapsedMs, checking.lastCheckedElapsedMs)
            assertEquals(original.isFreshOnline(1_200), checking.isFreshOnline(1_200))
            assertTrue(health.route(path).probeInProgress)
            assertEquals(original, health.route(path).cancelChecking())
        }
    }

    @Test fun retryNeverChangesConfirmedUnreachableSummaryOrHidesTheFailure() {
        val unavailable = RouteHealth().online(1_000).unavailable(1_100, ReachabilityFailure.TIMEOUT)
        val health = BoardNetworkHealth(local = unavailable, remote = unavailable)
        val checking = health.copy(local = health.local.checking(), remote = health.remote.checking())
        assertEquals("小车暂不可达", checking.onlineLabel(1_200))
        assertEquals(ReachabilityFailure.TIMEOUT, checking.local.failure)
        assertFalse(checking.local.isFreshOnline(1_200))
        assertFalse(checking.remoteFallbackAvailable(1_200))
        assertEquals(health, checking.copy(local = checking.local.cancelChecking(), remote = checking.remote.cancelChecking()))
    }

    @Test fun refreshAndCancellationCannotExtendFreshnessWithoutANewAck() {
        val refreshing = RouteHealth().online(1_000).checking()
        assertTrue(refreshing.isFreshOnline(31_000))
        assertFalse(refreshing.isFreshOnline(31_001))
        val cancelled = refreshing.cancelChecking()
        assertFalse(cancelled.isFreshOnline(31_001))
        assertEquals(Reachability.STALE, cancelled.effectiveStatus(31_001))
        assertEquals(1_000L, cancelled.lastOnlineElapsedMs)
        assertFalse(refreshing.isFreshOnline(999))
        assertFalse(cancelled.isFreshOnline(999))
    }

    @Test fun everyCompletedOutcomeClearsRefreshActivity() {
        val checking = RouteHealth().online(1_000).checking()
        assertFalse(checking.online(1_100).probeInProgress)
        assertFalse(checking.unavailable(1_100).probeInProgress)
        assertFalse(checking.invalidate().probeInProgress)
        val accepted = checking.acceptProbe(RouteHealth().online(1_100), 1_050)
        assertFalse(accepted.probeInProgress)
        assertTrue(accepted.isFreshOnline(1_200))
        val newerAck = checking.online(1_200)
        assertEquals(newerAck, newerAck.acceptProbe(RouteHealth().unavailable(1_300), 1_050))
    }
}
