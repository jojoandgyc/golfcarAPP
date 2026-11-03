package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.followingcar.ui.theme.FollowingCarTheme
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fake elapsed time and a real collected snapshot; no network, control or device operations. */
class BoardNetworkSnapshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localAcksStayOnlineBetweenUiTicksAndExpireWithoutFurtherEvidence() =
        verifyAckCadence(BoardRoute.LOCAL)

    @Test fun remoteAcksStayOnlineBetweenUiTicksAndExpireWithoutFurtherEvidence() =
        verifyAckCadence(BoardRoute.REMOTE)

    @Test fun cancellingRefreshPreservesEvidenceButNeverRevivesAFailedRoute() {
        val clock = AtomicLong(100_000L)
        val store = BoardNetworkStatusStore(clock::get)
        store.update { it.copy(local = it.local.online(clock.get())) }
        showBothCards(store, BoardRoute.LOCAL)
        assertBothTitles("小车在线 · 局域网")

        compose.runOnIdle {
            clock.addAndGet(500L)
            store.update { it.copy(local = it.local.checking()) }
        }
        assertBothTitles("小车在线 · 局域网")
        compose.onNodeWithTag("networkLocalStatusRefreshing").assertExists()
        compose.onNodeWithTag("networkCompactRefreshing").assertExists()

        compose.runOnIdle {
            clock.addAndGet(500L)
            store.update { it.copy(local = it.local.cancelChecking()) }
        }
        assertBothTitles("小车在线 · 局域网")
        compose.onNodeWithTag("networkLocalStatusRefreshing").assertDoesNotExist()
        compose.onNodeWithTag("networkCompactRefreshing").assertDoesNotExist()

        compose.runOnIdle {
            clock.addAndGet(500L)
            store.update { it.copy(local = it.local.unavailable(clock.get(), ReachabilityFailure.TIMEOUT)) }
        }
        assertBothTitles("小车暂不可达")
        compose.runOnIdle {
            clock.addAndGet(500L)
            store.update { it.copy(local = it.local.checking()) }
        }
        assertBothTitles("小车暂不可达")
        compose.onNodeWithTag("networkLocalStatusRefreshing").assertExists()
        compose.onNodeWithTag("networkLocalStatusFailure").assertTextEquals(ReachabilityFailure.TIMEOUT.label)

        compose.runOnIdle {
            clock.addAndGet(500L)
            store.update { it.copy(local = it.local.cancelChecking()) }
            store.tick()
            val snapshot = store.snapshot.value
            assertEquals(clock.get(), snapshot.nowElapsedMs)
            assertEquals(100_000L, snapshot.health.local.lastOnlineElapsedMs)
            assertFalse(snapshot.health.local.onlineEvidenceValid)
            assertFalse(snapshot.health.local.isFreshOnline(snapshot.nowElapsedMs))
        }
        assertBothTitles("小车暂不可达")
        compose.onNodeWithTag("networkLocalStatusRefreshing").assertDoesNotExist()
        compose.onNodeWithTag("networkCompactRefreshing").assertDoesNotExist()
        compose.onNodeWithTag("networkLocalStatusFailure").assertTextEquals(ReachabilityFailure.TIMEOUT.label)
    }

    private fun verifyAckCadence(route: BoardRoute) {
        val clock = AtomicLong(100_000L)
        val store = BoardNetworkStatusStore(clock::get)
        showBothCards(store, route)
        assertBothTitles("小车状态待确认")
        val path = if (route == BoardRoute.LOCAL) "局域网" else "公网"

        // No UI tick is published between these ACKs. Previously their newer evidence was
        // paired with the older one-second ticker value and was temporarily considered invalid.
        repeat(6) {
            compose.runOnIdle {
                clock.addAndGet(500L)
                store.update { it.updateRoute(route, it.route(route).online(clock.get())) }
                val snapshot = store.snapshot.value
                assertEquals(clock.get(), snapshot.nowElapsedMs)
                assertEquals(clock.get(), snapshot.health.route(route).lastOnlineElapsedMs)
                assertTrue(snapshot.health.route(route).isFreshOnline(snapshot.nowElapsedMs))
            }
            assertBothTitles("小车在线 · $path")
        }

        // The fix must retain freshness expiry when the ACK stream actually stops.
        compose.runOnIdle {
            clock.addAndGet(BOARD_ONLINE_FRESHNESS_MS)
            store.tick()
        }
        assertBothTitles("小车在线 · $path")
        compose.runOnIdle {
            clock.incrementAndGet()
            store.tick()
            val snapshot = store.snapshot.value
            assertEquals(Reachability.STALE, snapshot.health.route(route).effectiveStatus(snapshot.nowElapsedMs))
        }
        assertBothTitles("小车状态待确认")
        val routeTag = if (route == BoardRoute.LOCAL) "networkLocalStatus" else "networkRemoteStatus"
        compose.onNodeWithTag(routeTag).assertTextEquals("$path：状态已过期，待确认")
    }

    private fun showBothCards(store: BoardNetworkStatusStore, route: BoardRoute) {
        compose.setContent {
            val snapshot by store.snapshot.collectAsState()
            val refreshing = snapshot.health.local.probeInProgress || snapshot.health.remote.probeInProgress
            FollowingCarTheme {
                Column {
                    BoardNetworkStatusCard(snapshot.health, snapshot.nowElapsedMs,
                        connected = true, remoteMode = route == BoardRoute.REMOTE,
                        canReconnectRemote = false, refreshing = refreshing,
                        onRefresh = { error("Rendering a snapshot must not refresh") },
                        onReconnectRemote = { error("Rendering a snapshot must not reconnect") })
                    BoardNetworkStatusCard(snapshot.health, snapshot.nowElapsedMs,
                        connected = true, remoteMode = route == BoardRoute.REMOTE,
                        canReconnectRemote = false, refreshing = refreshing, compact = true,
                        onRefresh = { error("Rendering a snapshot must not refresh") },
                        onReconnectRemote = { error("Rendering a snapshot must not reconnect") })
                }
            }
        }
    }

    private fun assertBothTitles(expected: String) {
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals(expected)
        compose.onNodeWithTag("networkHealthCompactSummary").assertTextEquals(expected)
    }
}
