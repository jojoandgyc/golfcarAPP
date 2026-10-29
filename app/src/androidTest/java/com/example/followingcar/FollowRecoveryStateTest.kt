package com.example.followingcar

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** No activity, SSH connection, camera, motor or production preferences are used. */
@RunWith(AndroidJUnit4::class)
class FollowRecoveryStateTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private lateinit var application: IsolatedApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val syntheticJobs = mutableListOf<Job>()

    @Before fun isolateStorage() {
        val id = UUID.randomUUID().toString()
        application = IsolatedApplication(target, "follow-recovery-test-$id")
        directory = File(target.cacheDir, "follow-recovery-test-$id")
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreStorage() = runBlocking {
        try {
            syntheticJobs.forEach { it.cancel() }
            withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
            if (::engine.isInitialized) engine.flush()
        } finally {
            engineField.set(Diagnostics, originalEngine)
            if (::engine.isInitialized) engine.close()
            if (::application.isInitialized) application.deleteTestPreferences()
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun cancelledDisconnectAlwaysReleasesBusyAndPreservesUnconfirmedFirstFault() = runBlocking {
        val context = DiagnosticContext("cancel-${UUID.randomUUID()}", "old-connection", "old-run")
        val first = Diagnostics.failure(context, "follow.ack_timeout", IOException("first ACK timeout"))
        val pendingFollow = Job().also(syntheticJobs::add)
        val (vm, owner) = withContext(Dispatchers.Main) {
            val vm = newViewModel()
            setField(vm, "followDiagnostic", context)
            stateFlow(vm).value = vm.state.value.copy(followActive = true,
                followFirstFailure = first, driveConfirmedStopped = false)
            persistRecovery(vm, first)
            setField(vm, "followJob", pendingFollow)
            vm.disconnect()
            assertTrue(vm.state.value.busy)
            assertTrue(getField(vm, "disconnecting") as Boolean)
            vm to (getField(vm, "operation") as Job)
        }

        // The synthetic follow owner never completes by itself, so disconnect is waiting in
        // its startup join. Cancel that owner rather than waiting for the production deadline.
        withContext(Dispatchers.Main) { owner.cancel() }
        withTimeout(5_000) { owner.join() }

        withContext(Dispatchers.Main) {
            assertFalse(vm.state.value.busy)
            assertFalse(getField(vm, "disconnecting") as Boolean)
            assertFalse(vm.state.value.connected)
            assertEquals("已断开", vm.state.value.status)
            assertTrue(vm.state.value.followUncertain)
            assertEquals(first, vm.state.value.followFirstFailure)
            assertFalse("Cancelling cleanup cannot confirm a stop", vm.state.value.driveConfirmedStopped)
            assertTrue(pendingFollow.isCancelled)
        }
        val saved = application.getSharedPreferences("follow_recovery", Context.MODE_PRIVATE)
        assertEquals("true", saved.getString("pending", null))
        assertEquals(context.runId, saved.getString("runId", null))
        assertEquals(first.event, saved.getString("failureEvent", null))
        assertEquals(first.message, saved.getString("failureMessage", null))
        assertTrue(engine.snapshot().events.any { it.contains("connection.disconnect_completed") })
    }

    @Test fun realPreferencesRestorePendingFaultAfterLogRotationAndLaterSameOperationError() = runBlocking {
        val context = DiagnosticContext("restore-${UUID.randomUUID()}", "original-connection", "original-run")
        val first = Diagnostics.failure(context, "follow.ack_timeout", IOException("original first failure"))
        val firstStore = withContext(Dispatchers.Main) {
            val vm = newViewModel()
            setField(vm, "followDiagnostic", context)
            stateFlow(vm).value = vm.state.value.copy(followUncertain = true, followFirstFailure = first)
            persistRecovery(vm, first)
            stores.last()
        }
        // Flush asynchronous preferences to disk on the instrumentation thread only.
        val preferences = application.getSharedPreferences("follow_recovery", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("test_flush", true).commit())
        repeat(10) { index ->
            Diagnostics.failure(DiagnosticContext("inspection-$index-${UUID.randomUUID()}"),
                "follow.availability_failed", IOException("later inspection"))
        }
        assertFalse(engine.snapshot().failures.any { it.context.operationId == context.operationId })
        withContext(Dispatchers.Main) { firstStore.clear() }
        engine.close()
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)

        val restarted = withContext(Dispatchers.Main) { newViewModel() }
        assertTrue(restarted.state.value.followUncertain)
        assertEquals(first, restarted.state.value.followFirstFailure)
        assertEquals(context, getField(restarted, "followDiagnostic"))
        assertFalse(restarted.state.value.canStartFollow)
        assertFalse(restarted.state.value.canEnableDrive)

        val later = Diagnostics.failure(context, "follow.cleanup_failed", IOException("later same-operation error"))
        assertNotEquals("The ten-entry history has genuinely rotated", first, later)
        val retained = withContext(Dispatchers.Main) {
            val method = BoardViewModel::class.java.getDeclaredMethod("recordFollowFailure",
                String::class.java, Throwable::class.java, Map::class.java).apply { isAccessible = true }
            method.invoke(restarted, "follow.cleanup_failed", IOException("another cleanup error"), emptyMap<String, Any?>())
        }
        assertEquals(first, retained)
        assertEquals(first, restarted.state.value.followFirstFailure)
        assertEquals(first.event, preferences.getString("failureEvent", null))
        assertEquals(first.message, preferences.getString("failureMessage", null))
    }

    private fun newViewModel(): BoardViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
    }

    private fun persistRecovery(vm: BoardViewModel, first: DiagnosticFailure) {
        BoardViewModel::class.java.getDeclaredMethod("persistFollowRecovery", DiagnosticFailure::class.java)
            .apply { isAccessible = true }.invoke(vm, first)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(vm: BoardViewModel) = getField(vm, "mutableState") as MutableStateFlow<BoardUiState>

    private fun getField(vm: BoardViewModel, name: String): Any? =
        BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)

    private fun setField(vm: BoardViewModel, name: String, value: Any) {
        BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private class IsolatedApplication(base: Context, private val prefix: String) : Application() {
        private val preferenceNames = linkedSetOf<String>()
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun deleteTestPreferences() { preferenceNames.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
