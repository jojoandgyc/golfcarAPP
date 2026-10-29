package com.example.followingcar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only. No SSH connect or wireless operation; wireless services are unavailable to these VMs. */
@RunWith(AndroidJUnit4::class)
class MotorRecoveryPersistenceTest {
    @Test fun durableLatchSurvivesViewModelRecreationAndOnlyRecoveryAccessRemains() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("motor_recovery", Context.MODE_PRIVATE)
        val originalExists = preferences.contains(MOTOR_ACTION_RECOVERY_KEY)
        val original = preferences.all[MOTOR_ACTION_RECOVERY_KEY]
        val token = "motor-recovery-test-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, token).also { check(it.mkdirs()) }
        val application = OfflineApplication(context, token, directory)
        val stores = mutableListOf<ViewModelStore>()
        try {
            assertTrue(preferences.edit().putBoolean(MOTOR_ACTION_RECOVERY_KEY, true).commit())
            withContext(Dispatchers.Main) {
                repeat(2) {
                    val store = ViewModelStore().also(stores::add)
                    val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
                    assertTrue(vm.state.value.motorActionUnconfirmed)
                    assertTrue(vm.state.value.canRecoverConnection)
                    assertTrue(vm.state.value.canManagePairing)
                    assertTrue(vm.state.value.canManageWifiDirect)
                    // Model a completed transport recovery; no client/network method is called.
                    stateFlow(vm).value = vm.state.value.copy(connected = true, remoteProfileLoading = false)
                    assertTrue(vm.state.value.motorActionUnconfirmed)
                    assertTrue(vm.state.value.canReadMotorConfiguration)
                    assertFalse(vm.state.value.canConfigureMotor)
                    assertFalse(vm.state.value.canEnableDrive)
                    assertFalse(vm.state.value.canStartFollow)
                    assertFalse(vm.state.value.canExecuteTerminal)
                    assertFalse(vm.state.value.canToggleVideoService)
                    store.clear()
                    assertTrue(preferences.getBoolean(MOTOR_ACTION_RECOVERY_KEY, false))
                }
            }
        } finally {
            withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
            val restore = preferences.edit()
            if (!originalExists) restore.remove(MOTOR_ACTION_RECOVERY_KEY) else when (original) {
                is Boolean -> restore.putBoolean(MOTOR_ACTION_RECOVERY_KEY, original)
                is String -> restore.putString(MOTOR_ACTION_RECOVERY_KEY, original)
                is Int -> restore.putInt(MOTOR_ACTION_RECOVERY_KEY, original)
                is Long -> restore.putLong(MOTOR_ACTION_RECOVERY_KEY, original)
                is Float -> restore.putFloat(MOTOR_ACTION_RECOVERY_KEY, original)
                is Set<*> -> restore.putStringSet(MOTOR_ACTION_RECOVERY_KEY, original.filterIsInstance<String>().toSet())
                else -> error("Unexpected original preference type")
            }
            check(restore.commit()) { "Unable to restore original motor recovery preference" }
            application.deleteTestPreferences()
            directory.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(vm: BoardViewModel): MutableStateFlow<BoardUiState> =
        BoardViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.get(vm) as MutableStateFlow<BoardUiState>

    private class OfflineApplication(base: Context, private val prefix: String,
        private val privateDirectory: File) : Application() {
        private val names = ConcurrentHashMap.newKeySet<String>()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = privateDirectory
        override fun stopService(service: Intent): Boolean = true
        override fun getSystemService(name: String): Any? = when (name) {
            Context.BLUETOOTH_SERVICE, Context.WIFI_SERVICE, Context.WIFI_P2P_SERVICE -> null
            else -> super.getSystemService(name)
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name == "motor_recovery") return baseContext.getSharedPreferences(name, mode)
            val isolated = "$prefix-$name"
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun deleteTestPreferences() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
