package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local viewer and export gating need no SSH connection or actual share window. */
@RunWith(AndroidJUnit4::class)
class DiagnosticUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewerRemainsAccessibleWhileExportIsBlockedByControl() {
        val active = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DiagnosticLogCard(controlActive = active.value)
                }
            }
        }
        compose.onNodeWithTag("diagnosticExport").assertIsEnabled()
        compose.onNodeWithTag("diagnosticView").performClick()
        compose.onNodeWithTag("diagnosticEvents").assertExists()
        compose.onNodeWithTag("diagnosticFailures").assertExists()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithTag("diagnosticExport").assertIsNotEnabled()
        compose.onNodeWithTag("diagnosticView").assertIsEnabled()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithTag("diagnosticExport").assertIsEnabled()
    }
}
