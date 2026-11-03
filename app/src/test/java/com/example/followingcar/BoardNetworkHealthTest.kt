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
        assertEquals(Reachability.CHECKING, route.effectiveStatus(32_000))
        assertEquals(Reachability.CHECKING, RouteHealth().checking().effectiveStatus(1_000))
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
            assertEquals(Reachability.CHECKING, health.route(path).effectiveStatus(10_000))
            assertEquals("小车状态待确认", health.onlineLabel(10_000))
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
}
