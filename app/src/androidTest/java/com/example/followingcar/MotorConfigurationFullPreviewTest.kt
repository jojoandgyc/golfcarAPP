package com.example.followingcar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.followingcar.ui.theme.FollowingCarTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in visual review of the full bridge fixture. Every callback stays in memory. */
@RunWith(AndroidJUnit4::class)
class MotorConfigurationFullPreviewTest {
    @get:Rule val compose = createComposeRule()

    private var keyboard: SoftwareKeyboardController? = null
    private var focus: FocusManager? = null

    @Test fun captureAllCategoriesAndExtendedEditors() {
        assumeTrue("Opt in with -e captureMotorFull true",
            InstrumentationRegistry.getArguments().getString("captureMotorFull") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets.open("motor-config-snapshot-v2.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val reply = parseMotorConfigurationReply(
            CommandResult(command = "offline-full-motor-preview", stdout = fixture, stderr = "", exitCode = 0),
            requestId = "android-fixture", simulation = true)
        val snapshot = requireNotNull(reply.snapshot)
        assertEquals(69, snapshot.values.size)
        val state = mutableStateOf(MotorConfigurationUiState(snapshot = snapshot,
            message = "模拟器完整协议快照 · 仅在内存中编辑"))
        var configurationWrites = 0
        var actionWrites = 0
        compose.setContent {
            FollowingCarTheme(dynamicColor = false) {
                keyboard = LocalSoftwareKeyboardController.current
                focus = LocalFocusManager.current
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets)) {
                        Row(Modifier.fillMaxWidth().height(48.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            listOf("连接", "跟随", "手动", "电机", "终端").forEach { Text(it) }
                        }
                    MotorConfigurationPage(state.value, connected = true, accessAllowed = true,
                        blockedReason = null,
                        onRead = { state.value = MotorConfigurationUiState(snapshot = snapshot) },
                        onEdit = { field, value -> state.value = state.value.copy(
                            draftValues = state.value.draftValues + (field to value)) },
                        onDiscard = { state.value = state.value.copy(draftValues = emptyMap()) },
                        onApply = { configurationWrites++ },
                        onConnectionChange = { state.value = state.value.copy(connection = it) },
                        onWriteModeChange = { state.value = state.value.copy(writeMode = it) },
                        onAction = { actionWrites++ },
                        modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }

        val categories = listOf(
            "OVERVIEW" to "motorConfigurationLeftStatus",
            "MOTOR" to "motorConfigurationField_max_output_current",
            "PROTECTION" to "motorConfigurationField_voltage_protection_enabled",
            "BRAKE" to "motorConfigurationField_right_brake_output_type",
            "COMMUNICATION" to "motorConfigurationField_rs485_address",
            "PID" to "motorConfigurationField_pid_speed_p",
            "OPERATIONS" to "motorActionSelector")
        categories.forEachIndexed { index, (category, reachableTag) ->
            tab(category)
            capture("%02d-%s.png".format(index + 1, category.lowercase()))
            compose.onNodeWithTag(reachableTag).performScrollTo().assertIsDisplayed()
        }

        tab("MOTOR")
        searchAndEdit("left_foc_compensation_angle", "-12.34")
        capture("08-signed-angle.png")
        searchAndEdit("right_phase_1_2", "1,2")
        capture("09-phase-pair.png")
        tab("PID")
        searchAndEdit("pid_speed_p", "1.000030517578125")
        capture("10-pid-q15.png")

        tab("COMMUNICATION")
        compose.onNodeWithTag("motorConnectionToggle").performScrollTo().performClick()
        listOf("motorConnectionSlave", "motorConnectionBaud", "motorConnectionParity")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertIsEnabled() }
        compose.onNodeWithTag("motorConnectionApply").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("motorConnectionToggle").performScrollTo()
        capture("11-serial-connection.png")
        compose.runOnIdle {
            assertEquals("-12.34", state.value.draftValues[MotorConfigurationField.LEFT_FOC_COMPENSATION_ANGLE])
            assertEquals("1,2", state.value.draftValues[MotorConfigurationField.RIGHT_PHASE_1_2])
            assertEquals("1.000030517578125", state.value.draftValues[MotorConfigurationField.PID_SPEED_P])
            assertEquals(0, configurationWrites)
            assertEquals(0, actionWrites)
        }
    }

    private fun tab(name: String) {
        hideKeyboard()
        compose.onNodeWithTag("motorConfigurationTab_$name").performScrollTo().performClick()
    }

    private fun searchAndEdit(id: String, text: String) {
        compose.onNodeWithTag("motorConfigurationSearch").performScrollTo().performTextReplacement(id)
        hideKeyboard()
        compose.onNodeWithTag("motorConfigurationField_$id").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performTextReplacement(text)
        hideKeyboard()
        compose.onNodeWithTag("motorConfigurationField_$id").performScrollTo().assertIsDisplayed()
    }

    private fun hideKeyboard() = compose.runOnIdle { focus?.clearFocus(); keyboard?.hide() }

    private fun capture(filename: String) {
        hideKeyboard()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // System IME / inset animation runs outside Compose's test clock.
        Thread.sleep(300L)
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "motor-full-preview")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, filename).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
