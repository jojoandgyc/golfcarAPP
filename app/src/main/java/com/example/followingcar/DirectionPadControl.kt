package com.example.followingcar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private enum class PadDirection(val label: String, val symbol: String, val x: Float, val forward: Float) {
    FORWARD("前进", "▲", 0f, 1f),
    LEFT("左转", "◀", -1f, 0f),
    RIGHT("右转", "▶", 1f, 0f),
    REVERSE("后退", "▼", 0f, -1f),
}

/** One held direction at a time; a second finger cannot take over an active press. */
@Composable
internal fun DirectionPadControl(
    enabled: Boolean,
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val held = remember { mutableStateOf<PadDirection?>(null) }
    val latestOnMove = rememberUpdatedState(onMove)
    val latestOnRelease = rememberUpdatedState(onRelease)

    fun release() {
        if (held.value != null) {
            held.value = null
            latestOnRelease.value()
        }
    }

    DisposableEffect(Unit) { onDispose { release() } }

    Column(modifier.testTag("directionPad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(80.dp))
            DirectionKey(PadDirection.FORWARD, enabled, held.value == PadDirection.FORWARD,
                held, latestOnMove.value, latestOnRelease.value)
            Spacer(Modifier.size(80.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectionKey(PadDirection.LEFT, enabled, held.value == PadDirection.LEFT,
                held, latestOnMove.value, latestOnRelease.value)
            Button(onClick = {
                if (held.value == null) latestOnRelease.value() else release()
            }, enabled = enabled,
                modifier = Modifier.size(80.dp).testTag("directionStop"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Text("停止")
            }
            DirectionKey(PadDirection.RIGHT, enabled, held.value == PadDirection.RIGHT,
                held, latestOnMove.value, latestOnRelease.value)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(80.dp))
            DirectionKey(PadDirection.REVERSE, enabled, held.value == PadDirection.REVERSE,
                held, latestOnMove.value, latestOnRelease.value)
            Spacer(Modifier.size(80.dp))
        }
    }
}

@Composable
private fun DirectionKey(
    direction: PadDirection,
    enabled: Boolean,
    active: Boolean,
    held: androidx.compose.runtime.MutableState<PadDirection?>,
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    val latestOnMove = rememberUpdatedState(onMove)
    val latestOnRelease = rememberUpdatedState(onRelease)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(80.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .testTag("direction${direction.name}")
            .semantics {
                contentDescription = "按住${direction.label}，松手停车"
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (held.value != null) {
                            // Ignore the entire second press, even if the first finger lifts.
                            while (true) {
                                val event = awaitPointerEvent()
                                val primary = event.changes.firstOrNull { it.id == down.id }
                                event.changes.forEach { it.consume() }
                                if (primary == null || !primary.pressed) break
                            }
                            return@awaitEachGesture
                        }
                        held.value = direction
                        latestOnMove.value(direction.x, direction.forward)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val primary = event.changes.firstOrNull { it.id == down.id }
                                if (primary == null || !primary.pressed || primary.isConsumed ||
                                    primary.position.x !in 0f..size.width.toFloat() ||
                                    primary.position.y !in 0f..size.height.toFloat()
                                ) {
                                    primary?.consume()
                                    break
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            if (held.value == direction) {
                                held.value = null
                                latestOnRelease.value()
                            }
                        }
                    }
                } finally {
                    if (held.value == direction) {
                        held.value = null
                        latestOnRelease.value()
                    }
                }
            },
        color = if (active) colors.primary else colors.surfaceVariant,
        contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = if (active) 8.dp else 2.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(direction.symbol, style = MaterialTheme.typography.titleLarge)
            Text(direction.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
