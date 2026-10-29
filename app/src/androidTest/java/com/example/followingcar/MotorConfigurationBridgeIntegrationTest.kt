package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SSH and production codec; every bridge request explicitly uses in-memory simulation. */
@RunWith(AndroidJUnit4::class)
class MotorConfigurationBridgeIntegrationTest {
    @Test fun simulatedMotorConfigurationUsesStdinWritesAndConfirmsReadback() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runMotorConfigurationSimulation=true",
            args.getString("runMotorConfigurationSimulation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.getSharedPreferences("board_connection", 0)
        val password = context.getSharedPreferences("board_credentials", 0).getString("password", null)
        require(!password.isNullOrEmpty()) { "Save App credentials before the simulation test" }
        val client = BoardSshClient()
        val temporaryBridge = args.getString("motorSimulationBridge")
        if (temporaryBridge != null) require(temporaryBridge.matches(
            Regex("/tmp/followingcar-motor-config-[A-Za-z0-9]+/app_motor_config_bridge\\.py")))
        suspend fun query(plan: MotorConfigurationWritePlan? = null): MotorConfigurationReply {
            if (temporaryBridge == null) return client.motorConfigurationRequest(plan, simulation = true)
            val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
            val result = client.execute("/usr/bin/python3 -B $temporaryBridge --request --simulate",
                kind = if (plan == null) "motor_config_read" else "motor_config_write",
                diagnostic = diagnostic, input = motorConfigurationRequest(diagnostic.operationId, plan))
            return parseMotorConfigurationReply(result, diagnostic.operationId, plan, simulation = true)
        }
        try {
            withTimeout(90_000) {
                client.connect(BoardConnection(args.getString("boardHost") ?: saved.getString("host", null)!!,
                    saved.getString("port", "22")!!.toInt(), saved.getString("username", "topeet")!!, password))
                val initial = query()
                assertTrue(initial.error, initial.ok)
                val snapshot = requireNotNull(initial.snapshot)
                assertTrue(snapshot.writeAllowed)
                val fields = listOf(MotorConfigurationField.MAX_OUTPUT_CURRENT, MotorConfigurationField.MAX_FORWARD_SPEED)
                val plan = MotorConfigurationWritePlan(snapshot.snapshotId, fields.zip(listOf(1251L, 110000L))
                    .map { (field, value) -> MotorConfigurationChange(field, snapshot.values.getValue(field), value) })
                val written = query(plan)
                assertTrue(written.error, written.ok)
                assertTrue(written.writeMayHaveOccurred)
                assertEquals(2, written.results.size)
                assertTrue(written.results.all { it.outcome == MotorConfigurationWriteOutcome.VERIFIED })
                plan.changes.forEach { assertEquals(it.raw, written.snapshot!!.values[it.field]) }

                // A new simulation process starts with the fixed seed. A stale snapshot is refused
                // before any write, and the refusal remains an application error, not a lost SSH.
                val stale = plan.copy(snapshotId = written.snapshot!!.snapshotId)
                val rejected = query(stale)
                assertFalse(rejected.ok)
                assertFalse(rejected.writeMayHaveOccurred)
                assertTrue(rejected.results.all { it.outcome == MotorConfigurationWriteOutcome.NOT_ATTEMPTED })
                assertTrue(client.isConnected)
                val fresh = query()
                assertEquals(snapshot.values, fresh.snapshot!!.values)
            }
        } finally {
            client.disconnect()
        }
    }
}
