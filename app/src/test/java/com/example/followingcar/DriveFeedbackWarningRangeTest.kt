package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class DriveFeedbackWarningRangeTest {
    @Test fun negotiatedEightyRangeKeepsRawSpeedsAndDoesNotClaimStandstill() {
        val now = 1_000_000_000L
        for (rpm in listOf(-80, -7, -1, 1, 7, 80)) {
            val ack = DriveAck(0, 0, false, feedback = DriveFeedback(rpm, -rpm, 0, 0, 0, 0, "hardware"),
                feedbackReceivedNanos = now, feedbackAgeAtReceiptMs = 0)
            assertTrue(ack.hasMinorStoppedFeedbackNoise(now, maxRpm = 80))
            assertFalse(ack.stopped)
            assertEquals(rpm, ack.feedback!!.leftRpm)
            assertFalse(ack.copy(feedback = ack.feedback.copy(leftRpm = 81))
                .hasMinorStoppedFeedbackNoise(now, maxRpm = 80))
            assertFalse(ack.copy(feedback = ack.feedback.copy(leftError = 1))
                .hasMinorStoppedFeedbackNoise(now, maxRpm = 80))
            assertFalse(ack.copy(standstillPending = true).hasMinorStoppedFeedbackNoise(now, maxRpm = 80))
        }
    }
}
