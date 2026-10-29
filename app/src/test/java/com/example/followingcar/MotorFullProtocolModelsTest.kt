package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class MotorFullProtocolModelsTest {
    private fun snapshot(writeAllowed: Boolean = true, stopAllowed: Boolean = writeAllowed) =
        MotorConfigurationSnapshot("a".repeat(64), 1L,
            MotorConfigurationField.entries.associateWith { if (it.choices.isNotEmpty()) it.choices.keys.first() else it.minRaw },
            writeAllowed = writeAllowed, stopAllowed = stopAllowed)

    @Test fun completeCatalogHasNoOverlapOrReservedAddress() {
        assertEquals(69, MotorConfigurationField.entries.size)
        assertEquals(8, MotorAction.entries.size)
        val addresses = MotorConfigurationField.entries.flatMap { it.address until it.address + it.words }
        assertEquals(addresses.size, addresses.distinct().size)
        val reserved = setOf(0x61, 0x62, 0x63, 0x67, 0x88, 0xcc, 0xd0, 0xd1, 0x106, 0x107, 0x108, 0x109)
        assertTrue(addresses.none { it in reserved })
        assertEquals(19, MotorConfigurationField.entries.count { it.id.startsWith("pid_") })
        assertEquals(6, MotorConfigurationField.entries.count { it.phasePair })
        assertEquals(50, MotorConfigurationField.entries.count { it.supportsTemporary })
    }

    @Test fun phasePairsUseCorrectByteOrderAndRejectValuesOutsideEachByte() {
        val phase = MotorConfigurationField.RIGHT_PHASE_1_2
        for (low in 0L..6L) for (high in 0L..6L) {
            val raw = low or (high shl 8)
            assertEquals(raw, phase.parse("$low,$high"))
            assertEquals(raw, phase.parse(phase.display(raw)))
        }
        listOf("7,1", "1,7", "-1,0", "0x0201", "513").forEach { assertNull(phase.parse(it)) }
        assertFalse(phase.accepts(0x0107))
    }

    @Test fun signedAnglesAndAllQ15ValuesRoundTripWithoutRounding() {
        val angle = MotorConfigurationField.LEFT_FOC_COMPENSATION_ANGLE
        assertEquals(-18000L, angle.parse("-180.00"))
        assertEquals(-1234L, angle.parse("-12.34"))
        assertNull(angle.parse("180.01"))
        val pid = MotorConfigurationField.PID_SPEED_P
        listOf(0L, 1L, 32768L, 326780L, 327680L, 0xffffffffL).forEach { raw ->
            assertEquals(raw, pid.parse(pid.display(raw)))
        }
        assertEquals(32768L, pid.parse("1"))
        assertNull(pid.parse("0.1")) // Not exactly representable; never silently round a tuning value.
        assertEquals("1", MotorConfigurationField.PID_HALL_PARKING_SPEED_P.display(1))
        assertFalse(pid.supportsTemporary)
    }

    @Test fun reservedModesAndImpossibleWordValuesAreRejected() {
        assertNull(MotorConfigurationField.SYSTEM_MODE.parse("0"))
        assertNull(MotorConfigurationField.SYSTEM_MODE.parse("2"))
        assertEquals(4L, MotorConfigurationField.SYSTEM_MODE.parse("4"))
        assertEquals(65535L, MotorConfigurationField.DIFFERENTIAL_TURN_MAX_SPEED.parse("65535"))
        assertNull(MotorConfigurationField.DIFFERENTIAL_TURN_MAX_SPEED.parse("65536"))
        assertTrue(MotorConfigurationField.DIFFERENTIAL_TURN_MAX_SPEED.note.orEmpty().contains("110000"))
        assertEquals(65535L, MotorConfigurationField.RS485_TIMEOUT_S.parse("65535"))
    }

    @Test fun temporaryModeAllowsAllDocumentedFc06FieldsIncludingThreeDoubleWordSpeeds() {
        val base = snapshot()
        val current = MotorConfigurationField.OVERVOLTAGE_THRESHOLD
        val temporary = MotorConfigurationUiState(base,
            draftValues = mapOf(current to "48"), writeMode = MotorConfigurationWriteMode.TEMPORARY)
        assertEquals(MotorConfigurationWriteMode.TEMPORARY, motorConfigurationWritePlan(temporary)!!.writeMode)
        val speeds = listOf(MotorConfigurationField.MAX_FORWARD_SPEED,
            MotorConfigurationField.MAX_REVERSE_SPEED, MotorConfigurationField.MIN_SPEED)
        speeds.forEach { field ->
            assertEquals(2, field.words)
            assertTrue(field.supportsTemporary)
            assertTrue(field.note.orEmpty().contains("两帧 FC06"))
            val plan = motorConfigurationWritePlan(temporary.copy(draftValues = mapOf(field to "110000")))!!
            assertEquals(MotorConfigurationWriteMode.TEMPORARY, plan.writeMode)
            assertEquals(MotorConfigurationChange(field, 0, 110000), plan.changes.single())
        }
        MotorConfigurationField.entries.filter { it.id.startsWith("pid_") }.forEach { field ->
            assertFalse(field.supportsTemporary)
            assertNull(motorConfigurationWritePlan(temporary.copy(draftValues = mapOf(field to "1"))))
        }
    }

    @Test fun serialSettingsAreExplicitAndDoNotIncludeArbitraryPaths() {
        assertTrue(MotorSerialSettings().isValid)
        for (code in 0..3) assertTrue(MotorSerialSettings(parityCode = code).isValid)
        assertEquals("N", MotorSerialSettings(parityCode = 3).parity)
        assertEquals(2, MotorSerialSettings(parityCode = 3).stopBits)
        assertFalse(MotorSerialSettings(slave = 0).isValid)
        assertFalse(MotorSerialSettings(slave = 255).isValid)
        assertFalse(MotorSerialSettings(baudRate = 9601).isValid)
    }

    @Test fun movingSnapshotPermitsStopButNotStartingAnAction() {
        val moving = MotorConfigurationUiState(snapshot(false, true))
        assertNotNull(motorActionPlan(moving, MotorAction.RIGHT_STOP, "1", "0"))
        assertNotNull(motorActionPlan(moving, MotorAction.PHASE_LEARNING, "0", "0"))
        assertNull(motorActionPlan(moving, MotorAction.RIGHT_TARGET_SPEED, "100", "100"))
        assertNull(motorActionPlan(moving, MotorAction.PHASE_LEARNING, "1", "100"))
        assertNull(motorActionPlan(moving.copy(requiresRead = true), MotorAction.RIGHT_STOP, "1", "0"))
    }

    @Test fun motionAndLearningHaveDistinctBoundedDurations() {
        val ready = MotorConfigurationUiState(snapshot())
        assertNotNull(motorActionPlan(ready, MotorAction.RIGHT_TARGET_SPEED, "100", "100"))
        assertNull(motorActionPlan(ready, MotorAction.RIGHT_TARGET_SPEED, "100", "3001"))
        assertNotNull(motorActionPlan(ready, MotorAction.PHASE_LEARNING, "1", "60000"))
        assertNull(motorActionPlan(ready, MotorAction.PHASE_LEARNING, "1", "60001"))
        assertNull(motorActionPlan(ready, MotorAction.PHASE_LEARNING, "0", "100"))
        assertNotNull(motorActionPlan(ready, MotorAction.FACTORY_RESET, "1", "0"))
        assertNull(motorActionPlan(ready.copy(connection = MotorSerialSettings(slave = 2)), MotorAction.RIGHT_STOP, "0", "0"))
    }
}
