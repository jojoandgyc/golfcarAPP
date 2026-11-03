package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class BoardNetworkStatusStoreTest {
    @Test fun ackBeforeNextPeriodicTickPublishesMatchingTimeForBothRoutes() {
        for (route in BoardRoute.entries) {
            var clock = 10_000L
            val store = BoardNetworkStatusStore { clock }
            store.tick()
            repeat(20) {
                clock += 500L // ACK arrives between the separate 1-second ticks used by the old UI.
                val ackAt = clock
                store.update { it.updateRoute(route, it.route(route).online(ackAt)) }
                val snapshot = store.snapshot.value
                assertEquals(clock, snapshot.nowElapsedMs)
                assertTrue(snapshot.health.route(route).isFreshOnline(snapshot.nowElapsedMs))
                assertEquals("小车在线", snapshot.health.onlineLabel(snapshot.nowElapsedMs))
            }
        }
    }

    @Test fun expirationStillUsesRealTimeWhenAcknowledgementsStop() {
        var clock = 1_000L
        val store = BoardNetworkStatusStore { clock }
        store.update { it.copy(local = it.local.online(clock)) }
        clock += BOARD_ONLINE_FRESHNESS_MS
        store.tick()
        assertEquals("小车在线", store.snapshot.value.let { it.health.onlineLabel(it.nowElapsedMs) })
        clock++
        store.tick()
        assertEquals("小车状态待确认", store.snapshot.value.let { it.health.onlineLabel(it.nowElapsedMs) })
    }

    @Test fun trueClockRewindNeverGetsClampedToOldEvidence() {
        var clock = 10_000L
        val store = BoardNetworkStatusStore { clock }
        store.update { it.copy(remote = it.remote.online(clock)) }
        clock = 500L
        store.tick()
        val snapshot = store.snapshot.value
        assertEquals(500L, snapshot.nowElapsedMs)
        assertFalse(snapshot.health.remote.isFreshOnline(snapshot.nowElapsedMs))
        assertEquals("小车状态待确认", snapshot.health.onlineLabel(snapshot.nowElapsedMs))
    }
}
