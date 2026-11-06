package com.example.followingcar

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android service/notification/wake lock, isolated VM with no connection or control I/O. */
@RunWith(AndroidJUnit4::class)
class FollowBackgroundServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FollowingCarApplication
    private val delegateField = FollowingCarApplication::class.java.getDeclaredField("boardViewModel\$delegate")
        .apply { isAccessible = true }
    private var originalDelegate: Any? = null
    private lateinit var isolated: IsolatedApplication
    private lateinit var vm: BoardViewModel
    private lateinit var directory: File
    private val store = ViewModelStore()
    private val syntheticOwner = Job()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var binding: ServiceConnection? = null
    private val controller = AndroidFollowBackgroundController(app)
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private var testEngine: DiagnosticEngine? = null

    @Before fun isolateOwner() {
        instrumentation.runOnMainSync {
            @Suppress("UNCHECKED_CAST")
            val old = delegateField.get(app) as Lazy<BoardViewModel>
            if (old.isInitialized()) {
                val state = old.value.state.value
                assumeTrue("Never replace an active or connected real owner", !state.connected &&
                    !state.manualActive && !state.followBlocksControl && !state.busy && !state.pairingBusy)
            }
            originalDelegate = old
            val id = "follow-background-test-${UUID.randomUUID()}"
            directory = File(app.cacheDir, id).apply { mkdirs() }
            isolated = IsolatedApplication(app, id, File(directory, "no-backup"))
            originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
            originalEngine?.flush()
            testEngine = DiagnosticEngine(DiagnosticFileStore(File(directory, "diagnostics"))).also { engineField.set(Diagnostics, it) }
            vm = BoardViewModel(isolated, controller)
            store.put("isolated-background-owner", vm)
            delegateField.set(app, lazy { vm })
            field("followJob").set(vm, syntheticOwner)
            state().value = vm.state.value.copy(followActive = true, followUncertain = false,
                followReconnectable = false, followTerminal = false, connected = false,
                followStatus = FollowStatus("RUNNING", false, FollowCamera.CONFIG))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun restoreOwner() {
        if (originalDelegate == null) return
        instrumentation.runOnMainSync { controller.stop() }
        binding?.let { runCatching { app.unbindService(it) } }
        binding = null
        scenario?.close()
        instrumentation.runOnMainSync {
            syntheticOwner.cancel()
            store.clear()
            delegateField.set(app, originalDelegate)
        }
        instrumentation.waitForIdleSync()
        testEngine?.flush()
        engineField.set(Diagnostics, originalEngine)
        testEngine?.close()
        isolated.clearPreferences()
        directory.deleteRecursively()
    }

    @Test fun foregroundRetainsOwnerAcrossRecreateAndNotificationStopsOnlyThatOwner() = runBlocking {
        instrumentation.runOnMainSync { controller.start() }
        val service = bind()
        await { service.isForeground && service.cpuHeld }
        assertSame(vm, service.controlOwner)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        delay(3_000) // Real onPause/onStop, beyond the board's ordinary 2 s lease.
        assertTrue(service.isForeground)
        assertTrue(service.cpuHeld)
        assertFalse(vm.state.value.followStopPending)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.recreate()
        scenario!!.onActivity { assertSame(vm, (it.application as FollowingCarApplication).boardViewModel) }
        assertFalse("Backgrounding/recreating is not a follow stop", vm.state.value.followStopPending)
        assertTrue(service.cpuHeld)
        val notification = requireNotNull(service.notificationSnapshot)
        assertEquals(listOf("返回控制", "停止跟随"), notification.actions.map { it.title.toString() })
        // Exercise the real immutable notification PendingIntent even when notification permission
        // is denied; no system permission or battery/network setting is changed by this test.
        notification.actions.single { it.title.toString() == "停止跟随" }.actionIntent.send()
        await { vm.state.value.followStopPending }
        assertFalse(vm.state.value.connected)
        assertNull(field("followChannel").get(vm))
        assertEquals(ControlStopSource.USER_STOP, field("firstFollowStopSource").get(vm))
        instrumentation.runOnMainSync {
            state().value = vm.state.value.copy(followActive = false, followStopping = false, followTerminal = true)
        }
        await { !service.isForeground && !service.cpuHeld }
        assertNull(service.notificationSnapshot)
    }

    @Test fun queuedStartCancelledBeforeDeliveryDoesNotKeepCpuOrForegroundService() = runBlocking {
        instrumentation.runOnMainSync {
            controller.start()
            controller.stop()
        }
        val service = bind()
        instrumentation.waitForIdleSync()
        delay(6_000) // The FGS promotion deadline is asynchronous; an immediate assertion missed the crash.
        assertFalse(service.isForeground)
        assertFalse(service.cpuHeld)
        assertNull(service.notificationSnapshot)
        assertFalse(vm.state.value.followStopPending)
    }

    @Test fun cancelledQueuedStartAndStaleNotificationCannotStopReplacement() = runBlocking {
        instrumentation.runOnMainSync {
            controller.start()
            controller.stop()
            controller.start()
        }
        val service = bind()
        await { service.isForeground && service.cpuHeld }
        val oldStop = requireNotNull(service.notificationSnapshot).actions
            .single { it.title.toString() == "停止跟随" }.actionIntent
        instrumentation.runOnMainSync {
            controller.stop()
            controller.start()
        }
        await {
            service.isForeground && service.cpuHeld && service.notificationSnapshot?.actions
                ?.single { it.title.toString() == "停止跟随" }?.actionIntent != oldStop
        }
        val replacement = requireNotNull(service.notificationSnapshot)
        oldStop.send()
        // A delayed old ACTION_START still owes promotion, but must preserve the replacement's
        // notification and lock. This fixed invalid token cannot match a live gate generation.
        instrumentation.runOnMainSync {
            ContextCompat.startForegroundService(app, Intent(app, FollowBackgroundService::class.java)
                .setAction(FollowBackgroundService.ACTION_START)
                .putExtra(FollowBackgroundService.EXTRA_GENERATION, Long.MIN_VALUE))
        }
        delay(6_000)
        assertTrue(service.isForeground)
        assertTrue(service.cpuHeld)
        assertSame(replacement, service.notificationSnapshot)
        assertFalse(vm.state.value.followStopPending)
        assertNull(field("followChannel").get(vm))
        instrumentation.runOnMainSync { controller.stop() }
        await { !service.isForeground && !service.cpuHeld }
        assertNull(service.notificationSnapshot)
    }

    private suspend fun bind(): FollowBackgroundService.LocalBinder {
        val result = CompletableDeferred<FollowBackgroundService.LocalBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                result.complete(binder as FollowBackgroundService.LocalBinder)
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        binding = connection
        instrumentation.runOnMainSync {
            check(app.bindService(Intent(app, FollowBackgroundService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        return withTimeout(5_000) { result.await() }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(20)
    }

    private fun field(name: String) = BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state() = field("mutableState").get(vm) as MutableStateFlow<BoardUiState>

    private class IsolatedApplication(base: Context, private val prefix: String, private val files: File) : Application() {
        private val preferences = linkedSetOf<String>()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = files
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val key = "$prefix-$name"
            preferences += key
            return baseContext.getSharedPreferences(key, mode)
        }
        fun clearPreferences() { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
