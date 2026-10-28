package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake callbacks only: these tests never connect to the board or issue motor commands. */
@RunWith(AndroidJUnit4::class)
class JoystickControlTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun upwardDragMovesForwardAndReleaseReturnsToStopWithoutScrolling() {
        var output = Offset.Zero
        var releases = 0
        var scrollPosition = { -1 }
        compose.setContent {
            val scroll = rememberScrollState()
            scrollPosition = { scroll.value }
            MaterialTheme {
                Column(Modifier.verticalScroll(scroll)) {
                    JoystickControl(true, { x, y -> output = Offset(x, y) }, { releases++; output = Offset.Zero })
                    Spacer(Modifier.height(1000.dp))
                }
            }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(center.x, height * 0.2f))
        }
        compose.runOnIdle {
            assertEquals(0f, output.x, 0.01f)
            assertTrue(output.y > 0.8f)
            assertEquals(0, scrollPosition())
        }
        compose.onNodeWithTag("joystick").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(Offset.Zero, output)
            assertEquals(1, releases)
        }
    }

    @Test
    fun diagonalInputIsClampedToTheUnitCircle() {
        var output = Offset.Zero
        compose.setContent { MaterialTheme { JoystickControl(true, { x, y -> output = Offset(x, y) }, {}) } }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.95f, height * 0.05f))
        }
        compose.runOnIdle {
            assertTrue(output.x > 0.6f)
            assertTrue(output.y > 0.6f)
            assertEquals(1f, output.getDistance(), 0.001f)
        }
        compose.onNodeWithTag("joystick").performTouchInput { up() }
    }

    @Test
    fun secondFingerCannotTakeOverAfterTheFirstFingerReleases() {
        var output = Offset.Zero
        var moves = 0
        compose.setContent {
            MaterialTheme { JoystickControl(true, { x, y -> output = Offset(x, y); moves++ }, { output = Offset.Zero }) }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(0, center)
            moveTo(0, Offset(center.x, height * 0.2f))
            down(1, Offset(width * 0.8f, center.y))
            up(0)
        }
        var movesAtRelease = 0
        compose.runOnIdle { assertEquals(Offset.Zero, output); movesAtRelease = moves }
        compose.onNodeWithTag("joystick").performTouchInput {
            moveTo(1, Offset(width * 0.8f, height * 0.8f))
            up(1)
        }
        compose.runOnIdle {
            assertEquals(Offset.Zero, output)
            assertEquals(movesAtRelease, moves)
        }
    }

    @Test
    fun disablingWhileHeldStopsAndIgnoresFurtherMoves() {
        val enabled = mutableStateOf(true)
        var output = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme { JoystickControl(enabled.value, { x, y -> output = Offset(x, y) }, { releases++; output = Offset.Zero }) }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
        }
        compose.runOnIdle { assertTrue(output.x > 0.8f); enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Offset.Zero, output); assertEquals(1, releases) }
        compose.onNodeWithTag("joystick").performTouchInput { moveTo(topCenter); up() }
        compose.runOnIdle { assertEquals(Offset.Zero, output) }
    }

    @Test
    fun movingOutsideTheControlStopsUntilANewGesture() {
        var output = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme { JoystickControl(true, { x, y -> output = Offset(x, y) }, { releases++; output = Offset.Zero }) }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
            moveTo(Offset(width + 20f, center.y))
        }
        compose.runOnIdle { assertEquals(Offset.Zero, output); assertEquals(1, releases) }
        compose.onNodeWithTag("joystick").performTouchInput { moveTo(center); up() }
        compose.runOnIdle { assertEquals(Offset.Zero, output); assertEquals(1, releases) }
    }

    @Test
    fun cancelEventReturnsTheControlToStop() {
        var output = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme { JoystickControl(true, { x, y -> output = Offset(x, y) }, { releases++; output = Offset.Zero }) }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
            cancel()
        }
        compose.runOnIdle { assertEquals(Offset.Zero, output); assertEquals(1, releases) }
    }

    @Test
    fun removingTheComponentWhileHeldStops() {
        val shown = mutableStateOf(true)
        var output = Offset.Zero
        var releases = 0
        compose.setContent {
            MaterialTheme {
                if (shown.value) JoystickControl(true, { x, y -> output = Offset(x, y) }, { releases++; output = Offset.Zero })
            }
        }
        compose.onNodeWithTag("joystick").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
        }
        compose.runOnIdle { assertTrue(output.x > 0.8f); shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Offset.Zero, output); assertEquals(1, releases) }
    }
}
