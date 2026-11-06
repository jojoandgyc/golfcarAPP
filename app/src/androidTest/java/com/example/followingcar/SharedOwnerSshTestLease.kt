package com.example.followingcar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Closes only a connection established by a read-only test, without sending video/control commands.
 * The caller must start with a disconnected, idle shared owner and remember each successful connect.
 * A replaced connection or a newly active operation is deliberately left alone.
 */
internal class SharedOwnerSshTestLease(private val model: BoardViewModel) {
    private val client = BoardViewModel::class.java.getDeclaredField("client").apply {
        isAccessible = true
    }.get(model) as BoardSshClient
    private var ownedConnectionId: String? = null

    init {
        check(!client.isConnected && !model.state.value.connected && !model.state.value.busy)
    }

    fun rememberEstablishedConnection() {
        if (client.isConnected && model.state.value.connected && idle()) {
            ownedConnectionId = checkNotNull(client.diagnosticConnectionId)
        }
    }

    suspend fun closeIfStillOwnedAndIdle() {
        val closed = withContext(Dispatchers.Main) {
            val expected = ownedConnectionId
            if (expected == null || client.diagnosticConnectionId != expected || !idle()) false
            else {
                // Do not use VM.disconnect(): that action can stop the board's video service.
                client.disconnect("read_only_test_complete")
                ownedConnectionId = null
                true
            }
        }
        if (closed) withTimeout(5_000) { model.state.first { !it.connected } }
    }

    private fun idle(): Boolean = model.state.value.let {
        !it.busy && !it.manualActive && !it.followBlocksControl && !it.driveCleanupUnconfirmed &&
            !it.motorActionUnconfirmed && !it.motorActionCancellable && !it.videoServiceBusy &&
            !it.followConfigurationLoading && !it.pairingBusy && !model.motorConfiguration.value.busy &&
            !model.parkingPage.value.busy
    }
}
