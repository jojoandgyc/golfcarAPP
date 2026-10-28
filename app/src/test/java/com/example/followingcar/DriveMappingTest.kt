package com.example.followingcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DriveMappingTest {
    @Test fun cardinalDirectionsMatchTheLatestKeyboardScript() {
        assertEquals(WheelSpeeds(50, -50), joystickToWheels(0f, 1f, 50))
        assertEquals(WheelSpeeds(-50, 50), joystickToWheels(0f, -1f, 50))
        assertEquals(WheelSpeeds(-50, -50), joystickToWheels(-1f, 0f, 50))
        assertEquals(WheelSpeeds(50, 50), joystickToWheels(1f, 0f, 50))
    }

    @Test fun deadZoneAndInvalidCoordinatesStopBothWheels() {
        assertEquals(WheelSpeeds(), joystickToWheels(.05f, .05f, 50))
        assertEquals(WheelSpeeds(), joystickToWheels(Float.NaN, 1f, 50))
        assertEquals(WheelSpeeds(), joystickToWheels(1f, Float.POSITIVE_INFINITY, 50))
    }

    @Test fun mixedDirectionsAlwaysRespectTheSpeedLimit() {
        for (x in -20..20) for (y in -20..20) {
            val wheels = joystickToWheels(x / 10f, y / 10f, 50)
            assertTrue(abs(wheels.left) <= 50 && abs(wheels.right) <= 50)
            val reverse = joystickToWheels(-x / 10f, -y / 10f, 50)
            assertEquals(-wheels.left, reverse.left)
            assertEquals(-wheels.right, reverse.right)
        }
        assertEquals(WheelSpeeds(100, 100), joystickToWheels(1f, 0f, 1000))
    }
}
