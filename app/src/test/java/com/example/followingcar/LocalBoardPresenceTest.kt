package com.example.followingcar

import java.net.InetAddress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class LocalBoardPresenceTest {
    private val old = LocalBoardEndpoint("172.16.16.82", source = "saved")
    private val fresh = LocalBoardEndpoint("172.16.16.122")
    private fun online() = RouteHealth().online(100L)
    private fun failed(reason: ReachabilityFailure = ReachabilityFailure.UNREACHABLE) =
        RouteHealth().unavailable(100L, reason)

    @Test fun failedSavedAddressDoesNotHideAuthenticatedDhcpCandidate() = runBlocking {
        val attempts = mutableListOf<LocalBoardEndpoint>()
        val result = probeLocalBoardPresence(null, old, { listOf(old, fresh) }, { true }, {
            attempts += it
            if (it == old) failed() else online()
        }, { 100L })
        assertEquals(listOf(old, fresh), attempts)
        assertEquals(fresh, result.endpoint)
        assertTrue(result.health.isFreshOnline(100L))
    }

    @Test fun hotspotCandidateWithoutAndroidWifiNetworkIsStillAuthenticatedWithItsSourceAddress() = runBlocking {
        val hotspot = LocalBoardEndpoint("192.168.201.95", source = "hotspot_mdns",
            localAddress = InetAddress.getByName("192.168.201.231"))
        val result = probeLocalBoardPresence(null, null, { listOf(hotspot) }, { true }, {
            assertNull(it.network)
            assertEquals("192.168.201.231", it.localAddress!!.hostAddress)
            online()
        }, { 100L })
        assertEquals(hotspot, result.endpoint)
        assertTrue(result.health.isFreshOnline(100L))
    }

    @Test fun matchingAdvertisementWithWrongSshKeyNeverBecomesOnline() = runBlocking {
        val result = probeLocalBoardPresence(null, old, { listOf(fresh) }, { true }, {
            failed(ReachabilityFailure.IDENTITY)
        }, { 100L })
        assertNull(result.endpoint)
        assertFalse(result.health.isFreshOnline(100L))
        assertEquals(ReachabilityFailure.IDENTITY, result.health.failure)
    }

    @Test fun wrongKeyAtOldAddressStillAllowsTheRealBoardAtAnotherAddress() = runBlocking {
        val result = probeLocalBoardPresence(old, old, { listOf(fresh) }, { true }, {
            if (it == old) failed(ReachabilityFailure.IDENTITY) else online()
        }, { 100L })
        assertEquals(fresh, result.endpoint)
    }

    @Test fun expiredInterfaceIsNotReusedAndAnInterfaceLostDuringAuthenticationIsNotOnline() = runBlocking {
        val attempts = mutableListOf<LocalBoardEndpoint>()
        var freshInterfaceActive = true
        val result = probeLocalBoardPresence(old, old, { listOf(fresh) }, {
            it != old && freshInterfaceActive
        }, {
            attempts += it
            freshInterfaceActive = false
            online()
        }, { 100L })
        assertEquals(listOf(fresh), attempts)
        assertNull(result.endpoint)
        assertFalse(result.health.isFreshOnline(100L))
    }

    @Test fun passwordRejectionDoesNotResubmitTheCredentialToMoreCandidates() = runBlocking {
        val result = probeLocalBoardPresence(old, null, { error("Password rejection must stop discovery") },
            { true }, { failed(ReachabilityFailure.AUTHENTICATION) }, { 100L })
        assertNull(result.endpoint)
        assertEquals(ReachabilityFailure.AUTHENTICATION, result.health.failure)
    }

    @Test fun callerCancellationDoesNotContinueDiscoveryOrConvertToOffline() = runBlocking {
        var closed = false
        val result = withTimeoutOrNull(30) {
            probeLocalBoardPresence(old, null, { error("No discovery after cancellation") }, { true }, {
                try { awaitCancellation() } finally { closed = true }
            }, { 100L })
        }
        assertNull(result)
        assertTrue(closed)
    }

    @Test fun knownCurrentEndpointAvoidsDiscoveryButStillAuthenticatesEveryProbe() = runBlocking {
        var authentications = 0
        repeat(2) {
            val result = probeLocalBoardPresence(fresh, old, { error("Authenticated current endpoint suffices") },
                { true }, { authentications++; online() }, { 100L })
            assertEquals(fresh, result.endpoint)
        }
        assertEquals(2, authentications)
    }

    @Test fun staleSuccessOrInvalidEvidenceCannotBeReusedAsFreshPresence() = runBlocking {
        for (observation in listOf(RouteHealth().online(1), RouteHealth().online(40_000).copy(onlineEvidenceValid = false))) {
            val result = probeLocalBoardPresence(fresh, null, { emptyList() }, { true },
                { observation }, { 40_000L })
            assertNull(result.endpoint)
            assertFalse(result.health.isFreshOnline(40_000L))
        }
    }

    @Test fun stalledOldAddressIsBoundedAndDoesNotPreventAnotherCandidate() = runBlocking {
        var closed = false
        val result = probeLocalBoardPresence(old, null, { listOf(fresh) }, { true }, {
            if (it == old) try { awaitCancellation() } finally { closed = true }
            else online()
        }, { 100L }, attemptTimeoutMillis = 20L)
        assertTrue(closed)
        assertEquals(fresh, result.endpoint)
    }

    @Test fun allSourcesAreBoundedAndNoAdvertisementAloneCountsAsSuccess() = runBlocking {
        var attempts = 0
        val result = probeLocalBoardPresence(null, null,
            { (1..20).map { LocalBoardEndpoint("192.168.1.$it") } }, { true },
            { attempts++; failed() }, { 100L })
        assertEquals(MAX_LOCAL_BOARD_CANDIDATES, attempts)
        assertNull(result.endpoint)
        assertFalse(result.health.isFreshOnline(100L))
    }
}
