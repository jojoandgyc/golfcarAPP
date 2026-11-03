package com.example.followingcar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BoardNetworkSnapshot(val health: BoardNetworkHealth, val nowElapsedMs: Long)

/** Publish evidence and its observation clock together; a fresh ACK must never use an old UI tick. */
internal class BoardNetworkStatusStore(private val clock: () -> Long) {
    private val mutableSnapshot = MutableStateFlow(BoardNetworkSnapshot(BoardNetworkHealth(), clock()))
    val snapshot = mutableSnapshot.asStateFlow()
    val health: BoardNetworkHealth get() = mutableSnapshot.value.health

    fun update(transform: (BoardNetworkHealth) -> BoardNetworkHealth) {
        mutableSnapshot.update { previous ->
            val next = transform(previous.health)
            BoardNetworkSnapshot(next, clock())
        }
    }

    /** Keeps the original bounded freshness when no ACK/probe arrives. */
    fun tick() = update { it }
}
