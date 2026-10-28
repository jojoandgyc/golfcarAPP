package com.example.followingcar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pointer interactions use fake callbacks; no SSH session or motor is opened. */
@RunWith(AndroidJUnit4::class)
class DirectionPadControlTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun holdingForwardMovesAndReleasingStops() {
        var target = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme {
                DirectionPadControl(true, { x, y -> target = Offset(x, y) },
                    { target = Offset.Zero; releases++ })
            }
        }
        compose.onNodeWithTag("directionFORWARD").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(Offset(0f, 1f), target) }
        compose.onNodeWithTag("directionFORWARD").performTouchInput { up() }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(1, releases) }
    }

    @Test
    fun cancellationAndDisablingWhileHeldStopMovement() {
        val enabled = mutableStateOf(true)
        var target = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme {
                DirectionPadControl(enabled.value, { x, y -> target = Offset(x, y) },
                    { target = Offset.Zero; releases++ })
            }
        }
        compose.onNodeWithTag("directionLEFT").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(Offset(-1f, 0f), target); enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(1, releases) }
        compose.onNodeWithTag("directionLEFT").performTouchInput { up() }
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("directionREVERSE").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(Offset(0f, -1f), target) }
        compose.onNodeWithTag("directionREVERSE").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(2, releases) }
    }

    @Test
    fun secondFingerCannotContinueDrivingAfterOriginalFingerLifts() {
        var target = Offset.Zero
        var moves = 0
        compose.setContent {
            MaterialTheme {
                DirectionPadControl(true, { x, y -> target = Offset(x, y); moves++ },
                    { target = Offset.Zero })
            }
        }
        compose.onNodeWithTag("directionRIGHT").performTouchInput {
            down(0, center)
            down(1, Offset(width * 0.75f, center.y))
            up(0)
        }
        var movesAtRelease = 0
        compose.runOnIdle { assertEquals(Offset.Zero, target); movesAtRelease = moves }
        compose.onNodeWithTag("directionRIGHT").performTouchInput {
            moveTo(1, center)
            up(1)
        }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(movesAtRelease, moves) }
    }

    @Test
    fun movingOutsideKeyAndCenterStopBothReleaseTheTarget() {
        var target = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme {
                DirectionPadControl(true, { x, y -> target = Offset(x, y) },
                    { target = Offset.Zero; releases++ })
            }
        }
        compose.onNodeWithTag("directionFORWARD").performTouchInput {
            down(center)
            moveTo(Offset(width + 20f, center.y))
        }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(1, releases) }
        compose.onNodeWithTag("directionFORWARD").performTouchInput { up() }
        compose.onNodeWithTag("directionLEFT").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(Offset(-1f, 0f), target) }
        compose.onNodeWithTag("directionStop").performTouchInput { down(1, center); up(1) }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(2, releases) }
        compose.onNodeWithTag("directionLEFT").performTouchInput { up() }
        compose.runOnIdle { assertEquals(Offset.Zero, target); assertEquals(2, releases) }
    }
}
