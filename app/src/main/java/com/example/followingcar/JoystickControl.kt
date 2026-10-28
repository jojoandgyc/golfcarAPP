package com.example.followingcar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/**
 * A hold-to-drive control. x is right-positive, y is forward-positive, with a unit-circle limit.
 * Only the original finger can drive; another finger never takes over an interrupted gesture.
 */
@Composable
fun JoystickControl(
    enabled: Boolean,
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    controlSize: Dp = 256.dp,
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnRelease by rememberUpdatedState(onRelease)
    val colors = MaterialTheme.colorScheme

    fun release() {
        position = Offset.Zero
        if (active) {
            active = false
            latestOnRelease()
        }
    }

    DisposableEffect(Unit) {
        onDispose { release() }
    }

    Box(
        modifier = modifier.size(controlSize)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .testTag("joystick")
            .semantics(mergeDescendants = true) {
                contentDescription = "小车摇杆，上推前进，下拉后退，左右转向，松手停止"
                stateDescription = when {
                    !enabled -> "未启用"
                    active -> "控制中"
                    else -> "已回中，停止"
                }
                if (!enabled) disabled()
                customActions = listOf(CustomAccessibilityAction("停止小车") {
                    position = Offset.Zero
                    active = false
                    latestOnRelease()
                    true
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        active = true
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val travel = min(size.width, size.height) * TRAVEL_FRACTION

                        fun update(pointer: Offset) {
                            val raw = Offset((pointer.x - center.x) / travel, (center.y - pointer.y) / travel)
                            position = raw / maxOf(1f, raw.getDistance())
                            latestOnMove(position.x, position.y)
                        }

                        try {
                            update(down.position)
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
                                // Main-pass consumption happens before the containing scroll view.
                                event.changes.forEach { it.consume() }
                                update(primary.position)
                            }
                        } finally {
                            release()
                        }
                    }
                } finally {
                    // Covers pointer-input cancellation, including enabled changing to false.
                    release()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val diameter = min(size.width, size.height)
            val radius = diameter * 0.48f
            val travel = diameter * TRAVEL_FRACTION
            val handle = center + Offset(position.x * travel, -position.y * travel)
            drawCircle(colors.surfaceVariant, radius)
            drawCircle(colors.outlineVariant, radius, style = Stroke(2.dp.toPx()))
            drawCircle(colors.outlineVariant.copy(alpha = 0.5f), travel, style = Stroke(1.dp.toPx()))
            drawLine(colors.outlineVariant, center - Offset(travel, 0f), center + Offset(travel, 0f), 1.dp.toPx())
            drawLine(colors.outlineVariant, center - Offset(0f, travel), center + Offset(0f, travel), 1.dp.toPx())
            drawCircle(colors.primary.copy(alpha = 0.15f), diameter * 0.18f, handle)
            drawCircle(if (active) colors.primary else colors.primaryContainer, diameter * 0.145f, handle)
            drawCircle(colors.primary, diameter * 0.145f, handle, style = Stroke(2.dp.toPx()))
        }
        Text("前进", Modifier.align(Alignment.TopCenter).padding(top = 12.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text("后退", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text("左", Modifier.align(Alignment.CenterStart).padding(start = 14.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text("右", Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        if (!active) Text("停止", color = colors.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

private const val TRAVEL_FRACTION = 0.32f
