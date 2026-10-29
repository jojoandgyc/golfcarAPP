package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's JSON implementation only; no network, camera, or motor access. */
@RunWith(AndroidJUnit4::class)
class FollowModelsTest {
    @Test
    fun completeBoardConfigurationFixturesParseForEveryCameraProfile() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FollowCamera.entries.forEach { camera ->
            val stdout = assets.open("follow_config/follow-config-${camera.argument}.txt")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val config = parseFollowConfiguration(stdout, camera)
            assertEquals(camera, config.cameraProfile)
            assertEquals(1.2, config.targetDistanceMeters, 0.0)
            assertEquals(200.0, config.maxRpm, 0.0)
            assertEquals("/dev/ttyS0", config.motorPort)
            assertEquals(2, config.models.size)
            assertFalse(JSONObject(config.rawJson).getBoolean("hardware_opened"))
            assertEquals(
                if (camera == FollowCamera.ASTRA_PRO) "openni_astra" else "orbbec_sdk_v2",
                config.depthBackend,
            )
        }
    }

    @Test
    fun configurationAcceptsLauncherHintAndPrettyJsonWithBracesInsideStrings() {
        val json = configuration().apply {
            getJSONObject("camera").put("device", "/dev/video{0}\\\"profile")
        }
        val config = parseFollowConfiguration(
            "camera_profile=astra-pro backend=openni_astra\n${json.toString(2)}\n", FollowCamera.ASTRA_PRO,
        )
        assertEquals(FollowCamera.ASTRA_PRO, config.cameraProfile)
        assertEquals(1.2, config.targetDistanceMeters, 0.0)
        assertEquals(200.0, config.maxRpm, 0.0)
        assertEquals(10.0, config.parkingCurrentAmps, 0.0)
        assertEquals("/dev/video{0}\\\"profile", config.cameraDetails["device"])
        assertEquals(2, config.models.size)
        assertFalse(JSONObject(config.rawJson).getBoolean("hardware_opened"))
    }

    @Test
    fun configurationRejectsIncompleteOrAmbiguousOutput() {
        val json = configuration().toString()
        listOf(json.dropLast(1), "$json\n{\"another\":true}", "$json\nwarning after JSON", "no JSON").forEach {
            assertRejected { parseFollowConfiguration(it, FollowCamera.CONFIG) }
        }
    }

    @Test
    fun configurationMustBeAnExplicitHardwareFreeContinuousCheck() {
        listOf(
            configuration().put("hardware_opened", true),
            configuration().put("hardware_opened", "false"),
            configuration().apply { remove("hardware_opened") },
            configuration().put("chain", "legacy"),
            configuration().put("execute", true),
            configuration().put("execute", 0),
        ).forEach { assertRejected { parseFollowConfiguration(it.toString(), FollowCamera.CONFIG) } }
    }

    @Test
    fun realModeStaticPreflightRequiresMatchingModeAndNeverOpenedHardware() {
        val real = configuration().put("execute", true)
        val parsed = parseFollowConfiguration(real.toString(), FollowCamera.ASTRA_PRO, expectedExecute = true)
        assertTrue(parsed.execute)
        assertRejected { parseFollowConfiguration(configuration().toString(), FollowCamera.ASTRA_PRO, expectedExecute = true) }
        assertRejected { parseFollowConfiguration(real.put("hardware_opened", true).toString(), FollowCamera.ASTRA_PRO, expectedExecute = true) }
    }

    @Test
    fun configurationRejectsMissingMistypedAndOutOfRangeValues() {
        val invalid = listOf(
            configuration().put("effective_target_distance_m", "1.2"),
            configuration().put("effective_target_distance_m", false),
            configuration().put("effective_target_distance_m", -1),
            configuration().apply { remove("models") },
            configuration().apply { getJSONObject("controller").put("max_rpm", -1) },
            configuration().apply { getJSONObject("controller").put("forward_max_rpm", 201) },
            configuration().put("motor_startup_parking_current_a", -1),
            configuration().put("motor_startup_parking_current_a", 61),
        )
        invalid.forEach { assertRejected { parseFollowConfiguration(it.toString(), FollowCamera.CONFIG) } }
        assertRejected {
            parseFollowConfiguration(configuration().toString().replace("1.2", "1e999"), FollowCamera.CONFIG)
        }
    }

    @Test
    fun freshTelemetryAndFalseStopAckRemainDistinctFromUnknown() {
        val json = status().apply {
            put("target", JSONObject().put("trackId", 7).put("ageMs", 80))
            put("range", JSONObject().put("distanceM", 1.8).put("ageMs", 95))
            put("command", JSONObject().put("leftRpm", 20).put("rightRpm", -20).put("simulated", false))
            put("feedback", JSONObject().put("leftRpm", 18.5).put("rightRpm", -18).put("ageMs", 60))
            getJSONObject("stop").put("requested", true).put("acknowledged", false)
        }
        val parsed = parseFollowStatus(json)
        assertEquals("7", parsed.targetTrackId)
        assertEquals(80L, parsed.targetAgeMs)
        assertEquals(1.8, parsed.distanceMeters!!, 0.0)
        assertEquals(-18.0, parsed.feedbackRightRpm!!, 0.0)
        assertTrue(parsed.stopRequested)
        assertEquals(false, parsed.stopAcknowledged)
        assertNull(parsed.physicalStopped)
    }

    @Test
    fun absentTelemetryNeverBecomesZeroOrSuccessfulStop() {
        val json = status().apply {
            put("target", JSONObject.NULL)
            put("range", JSONObject.NULL)
            put("feedback", JSONObject.NULL)
            put("statusAgeMs", JSONObject.NULL)
        }
        val parsed = parseFollowStatus(json)
        assertNull(parsed.targetTrackId)
        assertNull(parsed.distanceMeters)
        assertNull(parsed.commandLeftRpm)
        assertNull(parsed.feedbackLeftRpm)
        assertNull(parsed.stopAcknowledged)
        assertNull(parsed.physicalStopped)
        assertNull(parsed.statusAgeMs)
    }

    @Test
    fun freshSearchObservationCanHaveNoSelectedTarget() {
        val parsed = parseFollowStatus(status().apply {
            put("behavior", "SEARCH")
            put("target", JSONObject().put("trackId", JSONObject.NULL).put("detected", false).put("ageMs", 30))
        })
        assertEquals("SEARCH", parsed.behavior)
        assertNull(parsed.targetTrackId)
        assertEquals(false, parsed.targetDetected)
        assertEquals(30L, parsed.targetAgeMs)
        assertNull(parsed.distanceMeters)
    }

    @Test
    fun lostTargetMayKeepItsIdentityWithoutClaimingAFreshDetection() {
        val parsed = parseFollowStatus(status().apply {
            put("target", JSONObject().put("trackId", 7).put("detected", false).put("ageMs", 30))
        })
        assertEquals("7", parsed.targetTrackId)
        assertEquals(false, parsed.targetDetected)
        val unknown = parseFollowStatus(status().apply {
            put("target", JSONObject().put("trackId", 7).put("ageMs", 30))
        })
        assertNull(unknown.targetDetected)
    }

    @Test
    fun statusRejectsUnknownModesNonFiniteNumbersAndInvalidAges() {
        listOf(
            status().put("runState", "OK"),
            status().put("execute", "false"),
            status().put("camera", "unrecognized"),
            status().put("processExited", 1),
            status().put("range", JSONObject().put("distanceM", "1.2").put("ageMs", 1)),
            status().put("range", JSONObject().put("distanceM", 1.2).put("ageMs", -1)),
            status().put("range", JSONObject().put("distanceM", 1.2).put("ageMs", 1.5)),
            status().put("feedback", JSONObject().put("leftRpm", 1).put("rightRpm", 2)),
            status().apply { getJSONObject("stop").put("acknowledged", "true") },
            status().apply { getJSONObject("stop").put("physicalStopped", true) },
        ).forEach { assertRejected { parseFollowStatus(it) } }
    }

    @Test
    fun availabilityRequiresARealBooleanAndSingleObject() {
        val available = parseFollowAvailability("{\"type\":\"availability\",\"controlAvailable\":true,\"reason\":null}")
        assertTrue(available.controlAvailable)
        assertNull(available.reason)
        val occupied = parseFollowAvailability("{\"type\":\"availability\",\"controlAvailable\":false,\"reason\":\"另一会话占用\"}")
        assertFalse(occupied.controlAvailable)
        assertEquals("另一会话占用", occupied.reason)
        listOf(
            "{\"type\":\"availability\",\"controlAvailable\":\"true\"}",
            "{\"type\":\"availability\"}",
            "{\"type\":\"other\",\"controlAvailable\":true}",
            "{\"type\":\"availability\",\"controlAvailable\":true} extra",
        ).forEach { assertRejected { parseFollowAvailability(it) } }
    }

    @Test
    fun startupAndFinalStatusWaitAreCompatibleAdditionsNotTerminalEvidence() {
        val old = parseFollowStatus(status())
        assertNull(old.startup)
        assertFalse(old.finalStatusPending)
        assertNull(old.exitCode)
        assertNull(old.logDir)
        val json = status().put("runState", "STOPPING").put("processExited", true)
            .put("finalStatusPending", true).put("exitCode", -15).put("logDir", "/logs/run-123")
            .put("startup", startup().put("stage", "future_stage").put("futureField", "ignored"))
            .put("futureResponseField", JSONObject().put("value", true))
        val parsed = parseFollowStatus(json)
        assertEquals("future_stage", parsed.startup!!.stage)
        assertEquals(1_200L, parsed.startup!!.stageElapsedMs)
        assertEquals(30_000L, parsed.startup!!.totalTimeoutMs)
        assertFalse(parsed.startup!!.completed)
        assertTrue(parsed.finalStatusPending)
        assertTrue(parsed.processExited)
        assertEquals(-15, parsed.exitCode)
        assertEquals("/logs/run-123", parsed.logDir)
        assertNull(parseFollowStatus(status().put("startup", JSONObject.NULL)).startup)
    }

    @Test
    fun malformedStartupAndLifecycleTypesDoNotBecomePlausibleProgress() {
        listOf(
            status().put("finalStatusPending", "true"),
            status().put("exitCode", 1.5),
            status().put("exitCode", Long.MAX_VALUE),
            status().put("logDir", 12),
            status().put("startup", "camera"),
            status().put("startup", startup().put("stageElapsedMs", -1)),
            status().put("startup", startup().put("totalElapsedMs", 0.5)),
            status().put("startup", startup().put("completed", "false")),
            status().put("startup", startup().apply { remove("stageTimeoutMs") }),
        ).forEach { assertRejected { parseFollowStatus(it) } }
    }

    @Test
    fun commandStopAndSimulationRemainSeparateFromRealWheelFeedback() {
        val parsed = parseFollowStatus(status().put("command", JSONObject()
            .put("leftRpm", 0).put("rightRpm", 0).put("simulated", true).put("stop", true)))
        assertEquals(true, parsed.commandStop)
        assertEquals(true, parsed.commandSimulated)
        assertNull(parsed.feedbackLeftRpm)
        assertNull(parsed.physicalStopped)
        assertRejected { parseFollowStatus(status().put("command", JSONObject()
            .put("leftRpm", 0).put("rightRpm", 0).put("simulated", false).put("stop", "true"))) }
    }

    @Test
    fun diagnosticTextIsRedactedBeforeItCanReachTheUi() {
        val secret = "model-test-secret-73912"
        Diagnostics.protectSecret(secret)
        val parsed = parseFollowStatus(status().put("fault", "close failed with $secret")
            .put("reason", "unknown_reason $secret").put("logDir", "/logs/$secret"))
        assertFalse(parsed.fault!!.contains(secret))
        assertFalse(parsed.reason!!.contains(secret))
        assertFalse(parsed.logDir!!.contains(secret))
        assertTrue(parsed.reason!!.contains("unknown_reason"))
        assertFalse(parseFollowAvailability(JSONObject().put("type", "availability")
            .put("controlAvailable", false).put("reason", "busy $secret").toString()).reason!!.contains(secret))
    }

    private fun startup() = JSONObject().put("stage", "camera").put("stageElapsedMs", 1_200)
        .put("totalElapsedMs", 4_600).put("stageTimeoutMs", 15_000).put("totalTimeoutMs", 30_000)
        .put("completed", false)

    private fun configuration() = JSONObject(
        """{
            "chain":"continuous", "execute":false, "hardware_opened":false,
            "controller":{"max_rpm":200,"forward_max_rpm":200},
            "effective_target_distance_m":1.2, "motor_startup_parking_current_a":10,
            "motor_port":"/dev/ttyS0", "depth_backend":"openni_astra",
            "models":["/models/person.rknn","/models/reid.rknn"],
            "camera":{"width":"640","height":"480","device":"/dev/video0"}
        }""".trimIndent(),
    )

    private fun status() = JSONObject(
        """{
            "runState":"RUNNING", "execute":true, "camera":"astra-pro",
            "behavior":"FOLLOW", "reason":"follow_range", "statusAgeMs":20,
            "stop":{"requested":false,"acknowledged":null,"physicalStopped":null},
            "fault":null, "processExited":false
        }""".trimIndent(),
    )

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Malformed or unsafe follow data was accepted")
        } catch (_: IOException) {
            // Malformed data is reported instead of silently becoming zero/false.
        }
    }
}
