package com.example.followingcar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One explicit request provisions the user's already enabled system hotspot over Bluetooth.
 * This controller never creates, changes, or closes a Wi-Fi hotspot. */
@SuppressLint("MissingPermission")
class BluetoothHotspotController(
    context: Context,
    private val canMutate: () -> Boolean,
    private val onHotspotLost: () -> Unit,
    private val onPaired: (String) -> Unit,
    private val onHotspotLeaseChanged: (Boolean) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutableState = MutableStateFlow(PairingState(supported = adapter != null &&
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
        recoveryLoading = true))
    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    private var closed = false
    private var registered = false
    private var operation: Job? = null
    private var discoveryDeadline: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var bondWaiter: CompletableDeferred<Boolean>? = null
    private var bondingAddress: String? = null
    private var recoveredCredentialsMayHaveBeenSent = false
    @Volatile private var credentialWriteGate: PairingCredentialWriteGate? = null
    private val credentialsMayHaveBeenSent: Boolean
        get() = recoveredCredentialsMayHaveBeenSent || credentialWriteGate?.hasStarted == true
    private var definitiveResultReceived = false
    private var readOnlyOperation = false
    private var diagnostic = DiagnosticContext.NONE
    private var operationStartedNanos = 0L
    private var ioPhase: PairingIoPhase? = null
    private var ioElapsedMs: Long? = null
    private val recoveryStore = PairingRecoveryStore(app)
    private var recoveryReady = false
    private var recoveryBlocked = false
    private var firstFailureCode: String? = null
    private var pairedAfterOperation: Pair<String, String>? = null
    private var newRequestPrepared = false
    private var preparedRequestId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || !hasPermissions()) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> device?.let(::rememberDevice)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null && device.address == bondingAddress) {
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> bondWaiter?.complete(true)
                        BluetoothDevice.BOND_NONE -> bondWaiter?.complete(false)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finishDiscovery()
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
            }
        }
    }

    init {
        scope.launch {
            when (val saved = recoveryStore.load()) {
                is PairingRecoveryLoad.Pending -> if (HotspotLease.state.value.requestId == null) {
                    HotspotLease.restore(saved.record.address, saved.record.requestId)
                    firstFailureCode = saved.record.firstFailureCode
                    mutableState.value = state.value.copy(status = "发现上次配网记录，请恢复并核对连接",
                        firstError = saved.record.firstFailureCode?.let(::pairingErrorMessage))
                }
                PairingRecoveryLoad.Blocked -> {
                    recoveryBlocked = true
                    HotspotLease.markUnknown()
                }
                PairingRecoveryLoad.Empty -> Unit
            }
            recoveryReady = true
            mutableState.value = state.value.copy(recoveryLoading = false,
                error = if (recoveryBlocked) pairingErrorMessage("recovery_storage_failed") else state.value.error)
        }
        scope.launch {
            var wasActive = false
            HotspotLease.state.collect { lease ->
                val previous = mutableState.value
                mutableState.value = previous.copy(hotspotActive = lease.active,
                    boardIp = lease.boardIp, confirmationRequired = lease.unconfirmed,
                    selectedAddress = lease.address ?: previous.selectedAddress,
                    stage = if (previous.busy) previous.stage else when {
                        lease.unconfirmed -> PairingStage.UNKNOWN
                        lease.active && lease.boardIp != null -> PairingStage.PAIRED
                        else -> previous.stage
                    })
                if (wasActive && !lease.active && !HotspotLease.explicitlyStopping) {
                    mutableState.value = state.value.copy(stage = PairingStage.ERROR,
                        status = "手机热点已停止", error = pairingErrorMessage("hotspot_stopped"))
                    runCatching { onHotspotLost() }
                }
                wasActive = lease.active
            }
        }
        scope.launch {
            while (true) {
                // Observation has no ownership or enable/disable side effects. It also notices
                // system Settings changes while a Bluetooth request or paired SSH link is live.
                if (HotspotLease.state.value.active || state.value.busy) observeSystemHotspot()
                delay(1_000)
            }
        }
        refresh()
    }

    fun refresh() = onMain {
        observeSystemHotspot()
        val permission = hasPermissions()
        val enabled = permission && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        mutableState.value = state.value.copy(permissionGranted = permission, bluetoothEnabled = enabled)
        if (permission) {
            runCatching {
                registerReceiver()
                adapter?.bondedDevices?.forEach(::rememberDevice)
            }.onFailure { reject("读取蓝牙状态失败，请重新确认附近设备权限。") }
        }
        if ((!permission || !enabled) && operation?.isActive == true) {
            operation?.cancel()
            closeSocket()
        }
    }

    fun selectDevice(address: String) = onMain {
        if (!state.value.busy && !state.value.hotspotActive && !state.value.confirmationRequired &&
            BluetoothAdapter.checkBluetoothAddress(address))
            mutableState.value = state.value.copy(selectedAddress = address, error = null)
    }

    fun discoverDevices() = onMain {
        refresh()
        pairingRequestAllowed(state.value, canMutate())?.let { reject(it); return@onMain }
        if (adapter == null) return@onMain
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            stage(PairingStage.DISCOVERING, "正在查找蓝牙设备…")
            if (!adapter.startDiscovery()) throw PairingFailure("discovery_failed")
            discoveryDeadline = scope.launch { delay(15_000); runCatching { adapter.cancelDiscovery() }; finishDiscovery() }
        } catch (_: Exception) {
            finishDiscovery()
            reject("无法发现蓝牙设备，请确认附近设备权限和蓝牙开关。")
        }
    }

    internal fun start(address: String? = null, credentials: SystemHotspotCredentials? = null) = onMain {
        refresh()
        if (!recoveryAvailable()) return@onMain
        pairingRequestAllowed(state.value, canMutate())?.let { reject(it); return@onMain }
        val target = address ?: state.value.selectedAddress
        if (!BluetoothAdapter.checkBluetoothAddress(target)) { reject("开发板蓝牙地址无效。"); return@onMain }
        if (credentials == null || !validPairingCredentials(credentials.ssid, credentials.password)) {
            reject("请填写系统热点名称和密码，与手机热点设置保持一致。")
            return@onMain
        }
        val observed = currentSystemHotspotObservations(app)
        if (observed.isEmpty()) {
            reject("请先在系统设置中开启 2.4 GHz 手机热点，再返回配对。")
            return@onMain
        }
        val ssid = credentials.ssid
        val psk = credentials.password
        Diagnostics.protectSecret(psk)
        Diagnostics.protectSecret(ssid)
        mutableState.value = state.value.copy(selectedAddress = target)
        beginOperation {
            val status = statusReliably(target, allowBond = true)
            if (status.opt("controlAvailable") != true || status.opt("operationInProgress") == true)
                throw PairingFailure("board_busy")
            val previousId = status.opt("recoveryRequestId") as? String
            if (previousId != null) {
                if (!isPairingRequestId(previousId)) throw PairingFailure("protocol_error")
                if (!recoveryStore.save(PairingRecoveryRecord(previousId, target, PairingRecoveryPhase.UNKNOWN)))
                    throw PairingFailure("recovery_storage_failed")
                HotspotLease.restore(target, previousId)
                recoveredCredentialsMayHaveBeenSent = true
                recoverResult(target, previousId)
                if (HotspotLease.state.value.requestId != null) return@beginOperation
                // The same explicit one-click intent may continue only after a proved expired lease.
                if (!definitiveResultReceived) throw PairingFailure("protocol_error")
                log("previous_request_reconciled", fields = mapOf("requestId" to previousId,
                    "firstCode" to firstFailureCode))
                // The old cause is now recorded against the old request. Capability/AP checks
                // and a new submission below must establish their own first cause.
                firstFailureCode = null
                mutableState.value = state.value.copy(firstError = null)
                recoveredCredentialsMayHaveBeenSent = false
                definitiveResultReceived = false
            }
            // Older board helpers deliberately suppress the default route for local-only APs.
            // A system-tethering request must advertise its intent rather than inherit that mode.
            if (status.opt("supportsSystemHotspot") != true)
                throw PairingFailure("system_hotspot_update_required")
            ensureCanMutate()
            if (!sameSystemHotspotObservations(observed, currentSystemHotspotObservations(app)))
                throw PairingFailure("hotspot_stopped")
            val requestId = UUID.randomUUID().toString()
            newRequestPrepared = true
            preparedRequestId = requestId
            val binding = HotspotLease.pending(target, requestId, observed)
            val writeGate = PairingCredentialWriteGate(binding, requestId)
            credentialWriteGate = writeGate
            if (!recoveryStore.save(PairingRecoveryRecord(requestId, target, PairingRecoveryPhase.PENDING)))
                throw PairingFailure("recovery_storage_failed")
            // Disk I/O can outlive this hotspot. Never dispatch credentials for a lost lease.
            ensureCanMutate()
            HotspotLease.requireCredentialLease(writeGate, currentSystemHotspotObservations(app))
            stage(PairingStage.SENDING_CREDENTIALS, "正在通过加密蓝牙发送热点配置…")
            submitPairingOnce(submit = {
                writeFrame(JSONObject().put("v", 1).put("requestId", requestId).put("cmd", "provision")
                    .put("ssid", ssid).put("psk", psk).put("internetAccess", true), writeGate)
                stage(PairingStage.WAITING_NETWORK, "等待开发板连接手机热点…")
                withTimeout(100_000) {
                    while (true) {
                        val reply = readReply(requestId)
                        when (reply.optString("type")) {
                            "progress" -> {
                                if (reply.optString("stage") !in setOf("accepted", "connecting"))
                                    throw PairingFailure("protocol_error")
                                log("progress", reply.optString("stage"))
                            }
                            "result" -> { applyResult(reply); break }
                            else -> throw PairingFailure("protocol_error")
                        }
                    }
                }
            }, recover = { failure ->
                // A rejected/cancelled queued write cannot have changed the board network.
                if (!writeGate.hasStarted) throw failure
                rememberFailure(failure)
                closeSocket()
                recoverResult(target, requestId)
            })
        }
    }

    /** Board read-only probe. No hotspot mutation, provision request or SSH connection. */
    fun checkBluetooth(address: String? = null) = onMain {
        refresh()
        if (!recoveryAvailable()) return@onMain
        // Do not inspect through a pairing that is active or awaits recovery confirmation.
        val lease = HotspotLease.state.value
        pairingRequestAllowed(state.value.copy(hotspotActive = lease.active,
            confirmationRequired = lease.unconfirmed), canMutate())?.let { reject(it); return@onMain }
        val target = address ?: state.value.selectedAddress
        if (!BluetoothAdapter.checkBluetoothAddress(target)) { reject("开发板蓝牙地址无效。"); return@onMain }
        mutableState.value = state.value.copy(selectedAddress = target)
        beginOperation(readOnly = true) {
            val status = statusReliably(target, allowBond = true)
            ensureCanMutate()
            val available = status.opt("controlAvailable") == true && status.opt("operationInProgress") != true
            mutableState.value = state.value.copy(stage = PairingStage.IDLE, error = null,
                status = if (available) "蓝牙服务正常，可开始配网" else "蓝牙服务正常，小车控制尚未空闲")
            log("bluetooth_check_completed", fields = mapOf("readOnly" to true,
                "controlAvailable" to status.optBoolean("controlAvailable"),
                "operationInProgress" to status.optBoolean("operationInProgress")))
        }
    }

    /** Read-only recovery of the exact request after a lost reply; never resends credentials. */
    fun retryStatus() = onMain {
        refresh()
        if (!recoveryReady) { reject("正在读取上次配网记录…"); return@onMain }
        if (state.value.busy || !canMutate()) { reject("请先结束控制并断开 SSH，再查询配对结果。"); return@onMain }
        if (!state.value.permissionGranted || !state.value.bluetoothEnabled) { reject("请开启蓝牙并允许附近设备权限。"); return@onMain }
        val lease = HotspotLease.state.value
        if (recoveryBlocked || lease.requestId == null || lease.address == null) {
            beginOperation {
                recoveredCredentialsMayHaveBeenSent = true
                repairRecoveryRecord()
            }
            return@onMain
        }
        beginOperation {
            recoveredCredentialsMayHaveBeenSent = true
            recoverResult(lease.address, lease.requestId)
        }
    }

    fun cancel() = onMain {
        if (state.value.stage == PairingStage.DISCOVERING) {
            runCatching { adapter?.cancelDiscovery() }; finishDiscovery()
        }
        credentialWriteGate?.cancelBeforeWrite()
        operation?.cancel()
        closeSocket()
    }

    fun stopHotspot() = onMain {
        if (!recoveryAvailable()) return@onMain
        if (!canMutate() || state.value.busy) { reject("请先结束控制并断开 SSH，再清除配对记录。"); return@onMain }
        if (state.value.confirmationRequired) { reject("配网结果尚未确认，请先查询结果。"); return@onMain }
        beginOperation {
            if (!recoveryStore.clear(HotspotLease.state.value.requestId)) throw PairingFailure("recovery_storage_failed")
            HotspotLease.stop()
            mutableState.value = state.value.copy(stage = PairingStage.IDLE, status = "已清除配对记录，系统热点保持原样", error = null)
            log("pairing_record_cleared")
        }
    }

    /** Detaches this observer without changing the user's system hotspot. */
    fun close() = onMain {
        closed = true
        credentialWriteGate?.cancelBeforeWrite()
        if (!readOnlyOperation) {
            if (credentialsMayHaveBeenSent && !definitiveResultReceived) HotspotLease.markUnknown()
            else if (state.value.busy && HotspotLease.state.value.boardIp == null) releaseUnsentHotspot()
        }
        operation?.cancel()
        closeSocket()
        discoveryDeadline?.cancel()
        runCatching { adapter?.cancelDiscovery() }
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        scope.cancel()
        notifyLease(false)
    }

    private fun beginOperation(readOnly: Boolean = false, block: suspend () -> Unit) {
        readOnlyOperation = readOnly
        recoveredCredentialsMayHaveBeenSent = false
        credentialWriteGate = null
        definitiveResultReceived = false
        newRequestPrepared = false
        preparedRequestId = null
        pairedAfterOperation = null
        if (HotspotLease.state.value.requestId == null) firstFailureCode = null
        diagnostic = Diagnostics.newContext()
        operationStartedNanos = System.nanoTime()
        ioPhase = null
        ioElapsedMs = null
        mutableState.value = state.value.copy(busy = true, stage = PairingStage.IDLE, error = null,
            firstError = firstFailureCode?.let(::pairingErrorMessage),
            status = if (readOnly) "正在准备蓝牙服务检查…" else "正在准备配网…")
        try {
            // Start while the explicit click is still foreground, before a system pairing dialog.
            onHotspotLeaseChanged(true)
        } catch (failure: Exception) {
            val fields = pairingFailureFields(failure, state.value.stage, ioPhase,
                elapsedMillis(operationStartedNanos), ioElapsedMs, bondState())
            mutableState.value = state.value.copy(busy = false, stage = PairingStage.ERROR,
                error = "无法保持配对连接服务，请返回 App 后重试。")
            log("failed", "service_unavailable", fields + mapOf("readOnly" to readOnly))
            readOnlyOperation = false
            return
        }
        operation = scope.launch {
            try {
                block()
            } catch (failure: Exception) {
                // Cancel wins before any queued executor is allowed to start credential I/O.
                credentialWriteGate?.cancelBeforeWrite()
                val failedStage = state.value.stage
                val code = pairingFailureCode(failure)
                val failureFields = pairingFailureFields(failure, failedStage, ioPhase,
                    elapsedMillis(operationStartedNanos), ioElapsedMs, bondState())
                var unknown = !readOnly && pairingFailureRequiresConfirmation(credentialsMayHaveBeenSent, definitiveResultReceived)
                if (!readOnly && !credentialsMayHaveBeenSent && newRequestPrepared) {
                    // No credential write was started. It is safe to remove only our unsent record.
                    if (!withContext(NonCancellable) { recoveryStore.clear(preparedRequestId) }) {
                        recoveryBlocked = true
                        unknown = true
                    }
                }
                if (failure !is CancellationException) rememberFailure(failure)
                if (!readOnly) {
                    if (unknown) {
                        HotspotLease.markUnknown()
                        persistUnknown()
                    }
                    else if (!definitiveResultReceived) releaseUnsentHotspot()
                }
                if (!closed) mutableState.value = state.value.copy(
                    stage = if (unknown) PairingStage.UNKNOWN else PairingStage.ERROR,
                    confirmationRequired = if (readOnly) state.value.confirmationRequired else unknown,
                    status = if (unknown) "配网结果尚未确认，请通过蓝牙查询"
                        else if (readOnly) "蓝牙检查未完成" else "配对未完成",
                    error = pairingFailureMessage(code, failedStage, ioPhase))
                // Keep the originating stage; the UI has already transitioned to ERROR/UNKNOWN.
                log("failed", code, failureFields + mapOf("readOnly" to readOnly))
            } finally {
                closeSocket()
                if (!closed) mutableState.value = state.value.copy(busy = false)
                notifyLease(false)
                operation = null
                readOnlyOperation = false
                val completed = pairedAfterOperation
                pairedAfterOperation = null
                if (completed != null && !closed) main.post {
                    val lease = HotspotLease.state.value
                    if (!closed && operation == null && lease.requestId == completed.first &&
                        lease.active && !lease.unconfirmed && lease.boardIp == completed.second) {
                        runCatching { onPaired(completed.second) }
                            .onFailure { reject("网络配对已完成，请点击核对并重新连接 SSH。") }
                    }
                }
            }
        }
    }

    private fun releaseUnsentHotspot() {
        val requestId = preparedRequestId
        if (requestId != null) HotspotLease.stopIfRequestMatches(requestId) else HotspotLease.stop()
    }

    private fun recoveryAvailable(): Boolean {
        if (!recoveryReady || recoveryBlocked) {
            reject(if (!recoveryReady) "正在读取上次配网记录…" else pairingErrorMessage("recovery_storage_failed"))
            return false
        }
        return true
    }

    private fun rememberFailure(failure: Exception) {
        if (firstFailureCode == null) firstFailureCode = pairingFailureCode(failure)
        mutableState.value = state.value.copy(firstError = firstFailureCode?.let(::pairingErrorMessage))
        log("recovery_trigger", pairingFailureCode(failure), mapOf("firstCode" to firstFailureCode))
    }

    private suspend fun persistUnknown() {
        val lease = HotspotLease.state.value
        if (lease.requestId == null || lease.address == null) return
        // PENDING is already durable before any write. Even cancellation leaves it recoverable.
        withContext(NonCancellable) {
            if (!recoveryStore.save(PairingRecoveryRecord(lease.requestId, lease.address,
                    PairingRecoveryPhase.UNKNOWN, firstFailureCode = firstFailureCode)))
                log("recovery_storage_failed")
        }
    }

    /** Corrupt/missing local records can only be replaced using a verified board recovery state. */
    private suspend fun repairRecoveryRecord() {
        when (val saved = recoveryStore.load()) {
            is PairingRecoveryLoad.Pending -> {
                recoveryBlocked = false
                HotspotLease.restore(saved.record.address, saved.record.requestId)
                recoverResult(saved.record.address, saved.record.requestId)
                return
            }
            else -> Unit
        }
        val target = state.value.selectedAddress
        val status = statusReliably(target)
        val requestId = status.opt("recoveryRequestId") as? String
        if (requestId != null && isPairingRequestId(requestId)) {
            if (!recoveryStore.clear() || !recoveryStore.save(PairingRecoveryRecord(requestId, target,
                    PairingRecoveryPhase.UNKNOWN))) throw PairingFailure("recovery_storage_failed")
            recoveryBlocked = false
            HotspotLease.restore(target, requestId)
            recoverResult(target, requestId)
        } else if (status.opt("recoveryState") == "clear" && status.opt("controlAvailable") == true &&
            status.opt("operationInProgress") == false) {
            if (!recoveryStore.clear()) throw PairingFailure("recovery_storage_failed")
            recoveryBlocked = false
            definitiveResultReceived = true
            HotspotLease.stop()
            mutableState.value = state.value.copy(stage = PairingStage.IDLE,
                confirmationRequired = false, status = "旧配网记录已安全解除，可以重新一键配对", error = null)
            log("recovery_record_repaired")
        } else throw PairingFailure("recovery_unavailable")
    }

    private suspend fun statusReliably(address: String, queryRequestId: String? = null,
        allowBond: Boolean = false): JSONObject = withPairingReadRetry(onRetry = { attempt, failure ->
            rememberFailure(failure)
            closeSocket()
            stage(PairingStage.RECOVERING, "蓝牙连接中断，正在自动重连（$attempt/3）…")
            log("retry", fields = mapOf("attempt" to attempt, "readOnly" to true))
        }) {
            closeSocket()
            connectBluetooth(address, allowBond)
            stage(PairingStage.CHECKING_BOARD, if (queryRequestId == null) "正在检查小车配网服务…" else "正在核对上次配网结果…")
            requestStatus(queryRequestId)
        }

    private suspend fun recoverResult(address: String, requestId: String) {
        log("recovery_started")
        val reply = awaitPairingRecovery(onPending = {
            stage(PairingStage.RECOVERING, "正在自动核对配网结果，请稍候…")
        }) {
            val status = statusReliably(address, requestId)
            val result = status.optJSONObject("lastResult") ?: throw PairingFailure("request_not_found")
            if (result.opt("requestId") != requestId || result.opt("v") != 1)
                throw PairingFailure("protocol_error")
            when (result.optString("type")) {
                "progress" -> null
                "result" -> if (result.opt("definitive") == true) result else null
                else -> throw PairingFailure("protocol_error")
            }
        }
        applyResult(reply)
        log("recovery_completed")
    }

    private suspend fun connectBluetooth(address: String, allowBond: Boolean) {
        ensureCanMutate()
        val bluetooth = adapter ?: throw PairingFailure("bluetooth_disabled")
        if (!hasPermissions()) throw PairingFailure("permission_denied")
        if (!bluetooth.isEnabled) throw PairingFailure("bluetooth_disabled")
        bluetooth.cancelDiscovery()
        val device = bluetooth.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            if (!allowBond) throw PairingFailure("bond_failed")
            stage(PairingStage.PAIRING_BLUETOOTH, "请确认手机系统的蓝牙配对提示…")
            bondingAddress = address
            val waiter = CompletableDeferred<Boolean>()
            bondWaiter = waiter
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond() &&
                        device.bondState != BluetoothDevice.BOND_BONDED) throw PairingFailure("bond_failed")
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        val bonded = try { withTimeout(60_000) { waiter.await() } }
                        catch (failure: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            throw PairingFailure("bond_timeout", failure)
                        }
                        if (!bonded) throw PairingFailure("bond_failed")
                    }
                }
            } finally { bondWaiter = null; bondingAddress = null }
        }
        ensureCanMutate()
        stage(PairingStage.CONNECTING_BLUETOOTH, "正在连接开发板蓝牙服务…")
        val link = device.createRfcommSocketToServiceRecord(UUID.fromString(PAIRING_SERVICE_UUID))
        socket = link
        socketCall(PairingIoPhase.CONNECT, 25_000) { link.connect() }
    }

    private suspend fun requestStatus(queryRequestId: String? = null): JSONObject {
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject().put("v", 1).put("requestId", requestId).put("cmd", "status")
        queryRequestId?.let { request.put("queryRequestId", it) }
        writeFrame(request)
        val reply = readReply(requestId, 15_000)
        if (reply.optString("type") != "status" || reply.opt("controlAvailable") !is Boolean ||
            reply.opt("operationInProgress") !is Boolean ||
            !reply.optString("boardId").matches(Regex("[A-Za-z0-9._:-]{1,128}")) ||
            reply.optString("band") != "2.4GHz") throw PairingFailure("protocol_error")
        val recoveryId = reply.opt("recoveryRequestId")
        if (recoveryId != null && recoveryId != JSONObject.NULL &&
            (recoveryId !is String || !isPairingRequestId(recoveryId))) throw PairingFailure("protocol_error")
        val recoveryState = reply.opt("recoveryState")
        if (recoveryState != null && recoveryState != JSONObject.NULL &&
            recoveryState !in setOf("clear", "pending", "unavailable")) throw PairingFailure("protocol_error")
        return reply
    }

    private suspend fun applyResult(reply: JSONObject) {
        if (reply.opt("type") != "result" || reply.opt("ok") !is Boolean ||
            reply.opt("definitive") !is Boolean || reply.opt("networkChanged") !is Boolean)
            throw PairingFailure("protocol_error")
        if (!reply.getBoolean("ok")) {
            if (!pairingResultIsDefinitive(false, reply.opt("definitive") as? Boolean,
                    reply.opt("networkChanged") == true)) {
                if (firstFailureCode == null) firstFailureCode = pairingBoardErrorCode(
                    (reply.opt("firstError") as? String) ?: reply.opt("error"))
                throw PairingFailure("network_address_unconfirmed")
            }
            val submittedThisOperation = newRequestPrepared && credentialWriteGate?.hasStarted == true &&
                preparedRequestId == reply.optString("requestId")
            // A terminal rollback may carry the original failure even if its initial reply was lost.
            if (firstFailureCode == null && reply.opt("firstError") is String)
                firstFailureCode = pairingBoardErrorCode(reply.opt("firstError"))
            if (!recoveryStore.clear(HotspotLease.state.value.requestId)) throw PairingFailure("recovery_storage_failed")
            definitiveResultReceived = true
            HotspotLease.confirmFailure()
            val code = pairingBoardErrorCode(reply.opt("error"))
            // A verified negative outcome releases only our pairing record, never the hotspot.
            HotspotLease.stop()
            log("network_released", code)
            if (pairingLeaseExpired(code)) {
                mutableState.value = state.value.withReleasedPairingOutcome(code,
                    submittedThisOperation, firstFailureCode)
                if (mutableState.value.stage == PairingStage.ERROR) log("provision_failed_recovered", code,
                    mapOf("firstCode" to firstFailureCode, "networkRestored" to (code == "network_restored")))
                return
            }
            throw PairingFailure(code)
        }
        if (reply.opt("definitive") != true) throw PairingFailure("protocol_error")
        stage(PairingStage.VERIFYING_NETWORK, "正在核对开发板的热点地址…")
        var verified: SystemHotspotObservation? = null
        repeat(20) {
            if (verified == null) {
                verified = verifiedSystemHotspotObservation(reply.optString("ip"), reply.optString("gateway"),
                    reply.optInt("prefixLength", -1), currentSystemHotspotObservations(app))
                if (verified == null) delay(250)
            }
        }
        val verifiedHotspot = verified ?: throw PairingFailure("address_unverified")
        val ip = reply.optString("ip")
        val lease = HotspotLease.state.value
        if (lease.requestId == null || lease.address == null) throw PairingFailure("state_changed")
        if (!recoveryStore.save(PairingRecoveryRecord(lease.requestId, lease.address, PairingRecoveryPhase.PAIRED,
                firstFailureCode = firstFailureCode))) throw PairingFailure("recovery_storage_failed")
        ensureCanMutate()
        if (verifiedHotspot !in currentSystemHotspotObservations(app) ||
            HotspotLease.state.value.requestId != lease.requestId)
            throw PairingFailure("hotspot_stopped")
        definitiveResultReceived = true
        HotspotLease.paired(ip, verifiedHotspot)
        mutableState.value = state.value.copy(stage = PairingStage.PAIRED, boardIp = ip,
            confirmationRequired = false, status = "开发板已接入手机热点", error = null)
        log("paired")
        pairedAfterOperation = lease.requestId to ip
    }

    private suspend fun writeFrame(frame: JSONObject, writeGate: PairingCredentialWriteGate? = null) {
        ioPhase = PairingIoPhase.WRITE
        ioElapsedMs = null
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAIRING_FRAME_BYTES) throw PairingFailure("request_too_large")
        val link = socket ?: throw PairingFailure("bluetooth_eof")
        socketCall(PairingIoPhase.WRITE, 10_000, onCancel = { writeGate?.cancelBeforeWrite() }) {
            val output = link.outputStream
            // Runs on the I/O executor immediately before write, after every scheduling delay.
            // Lease loss and this transition share a lock; cancellation arbitrates on the gate.
            if (writeGate != null && !HotspotLease.beginCredentialWrite(writeGate,
                    currentSystemHotspotObservations(app)))
                throw PairingFailure("hotspot_stopped")
            output.write(bytes)
            output.flush()
        }
    }

    private suspend fun readReply(requestId: String, timeout: Long = 100_000): JSONObject {
        ioPhase = PairingIoPhase.READ
        ioElapsedMs = null
        val link = socket ?: throw PairingFailure("bluetooth_eof")
        val text = socketCall(PairingIoPhase.READ, timeout) { readPairingFrame(link.inputStream) }
        val reply = try { JSONObject(text) } catch (failure: Exception) { throw PairingFailure("protocol_error", failure) }
        if (reply.opt("v") != 1 || reply.opt("requestId") != requestId) throw PairingFailure("protocol_error")
        return reply
    }

    /** Timeout/cancel closes the socket from another thread, including a blocked Bluetooth write. */
    private suspend fun <T> socketCall(phase: PairingIoPhase, timeout: Long,
        onCancel: () -> Unit = {}, action: () -> T): T {
        ioPhase = phase
        ioElapsedMs = null
        val started = System.nanoTime()
        var completed = false
        log("io_started", fields = mapOf("ioPhase" to phase.name.lowercase(), "timeoutMs" to timeout,
            "bondState" to bondState().name))
        try {
            return withTimeout(timeout) {
                val ownedSocket = socket
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        onCancel()
                        IO.execute { runCatching { ownedSocket?.close() } }
                    }
                    IO.execute {
                        try {
                            if (!continuation.isActive) return@execute
                            val result = action()
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (failure is PairingFailure) failure else PairingFailure(
                                    if (failure is SecurityException) "permission_denied" else "bluetooth_failed", failure))
                        }
                    }
                }
            }.also { completed = true }
        } finally {
            ioElapsedMs = elapsedMillis(started)
            log("io_finished", fields = mapOf("ioPhase" to phase.name.lowercase(),
                "elapsedMs" to ioElapsedMs, "completed" to completed, "bondState" to bondState().name))
        }
    }

    private fun bondState(): PairingBondState = runCatching {
        if (!hasPermissions() || !BluetoothAdapter.checkBluetoothAddress(state.value.selectedAddress)) PairingBondState.UNKNOWN
        else when (adapter?.getRemoteDevice(state.value.selectedAddress)?.bondState) {
            BluetoothDevice.BOND_NONE -> PairingBondState.NOT_BONDED
            BluetoothDevice.BOND_BONDING -> PairingBondState.BONDING
            BluetoothDevice.BOND_BONDED -> PairingBondState.BONDED
            else -> PairingBondState.UNKNOWN
        }
    }.getOrDefault(PairingBondState.UNKNOWN)

    private fun observeSystemHotspot() {
        HotspotLease.observe(currentSystemHotspotObservations(app))
    }

    private fun ensureCanMutate() {
        if (closed || !canMutate()) throw PairingFailure("state_changed")
        if (!hasPermissions()) throw PairingFailure("permission_denied")
    }

    private fun hasPermissions() = listOf(Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES).all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerReceiver() {
        if (registered) return
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        registered = true
    }

    private fun rememberDevice(device: BluetoothDevice) {
        val peer = runCatching { PairingDevice(device.name?.take(80) ?: "未命名蓝牙设备", device.address,
            device.bondState == BluetoothDevice.BOND_BONDED) }.getOrNull() ?: return
        mutableState.value = state.value.copy(devices = (state.value.devices.filterNot { it.address == peer.address } + peer).take(40))
    }

    private fun finishDiscovery() {
        discoveryDeadline?.cancel(); discoveryDeadline = null
        if (state.value.stage == PairingStage.DISCOVERING) mutableState.value = state.value.copy(
            busy = false, stage = PairingStage.IDLE, status = "选择开发板后点击一键配对")
    }

    private fun stage(stage: PairingStage, status: String) {
        mutableState.value = state.value.copy(stage = stage, busy = true, status = status, error = null)
        log("stage", stage.name.lowercase())
    }

    private fun reject(message: String) { mutableState.value = state.value.copy(error = message) }
    private fun notifyLease(active: Boolean) {
        runCatching { onHotspotLeaseChanged(active) }.onFailure {
            log("service_callback_failed")
            if (!closed) reject("连接保活服务不可用，请保持 App 在前台。")
        }
    }
    private fun closeSocket() { val old = socket; socket = null; IO.execute { runCatching { old?.close() } } }
    private fun log(event: String, code: String? = null, fields: Map<String, Any?> = emptyMap()) =
        Diagnostics.event("pairing.$event", diagnostic, source = "bluetooth_hotspot",
            fields = mapOf("stage" to state.value.stage.name, "code" to code,
                "requestId" to HotspotLease.state.value.requestId) + fields, critical = true)
    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }

    companion object {
        private val IO = Executors.newCachedThreadPool { runnable -> Thread(runnable, "pairing-bluetooth").apply { isDaemon = true } }
    }
}

/** Process-local pairing evidence, not a Wi-Fi reservation. No method mutates a system network. */
private object HotspotLease {
    data class Snapshot(val active: Boolean = false, val unconfirmed: Boolean = false,
        val address: String? = null, val requestId: String? = null, val boardIp: String? = null)
    val state = MutableStateFlow(Snapshot())
    var explicitlyStopping = false
        private set
    private data class Binding(val observations: List<SystemHotspotObservation>)
    private var binding: Binding? = null
    private var verifiedHotspot: SystemHotspotObservation? = null
    private val continuity = SystemHotspotContinuity()
    @Synchronized fun pending(address: String, requestId: String,
        observations: List<SystemHotspotObservation>): Any {
        require(observations.isNotEmpty())
        val prepared = Binding(observations.toList())
        binding = prepared
        verifiedHotspot = null
        continuity.reset()
        explicitlyStopping = false
        state.value = Snapshot(address = address, requestId = requestId, unconfirmed = true)
        return prepared
    }
    @Synchronized fun restore(address: String, requestId: String) {
        binding = null
        verifiedHotspot = null
        continuity.reset()
        explicitlyStopping = false
        state.value = Snapshot(address = address, requestId = requestId, unconfirmed = true)
    }
    @Synchronized fun markUnknown() {
        binding = null
        verifiedHotspot = null
        continuity.reset()
        state.value = state.value.copy(active = false, unconfirmed = true, boardIp = null)
    }
    @Synchronized fun paired(ip: String, observed: SystemHotspotObservation) {
        binding = null
        verifiedHotspot = observed
        continuity.reset()
        explicitlyStopping = false
        state.value = state.value.copy(active = true, unconfirmed = false, boardIp = ip)
    }
    @Synchronized fun confirmFailure() {
        binding = null
        verifiedHotspot = null
        continuity.reset()
        state.value = state.value.copy(active = false, unconfirmed = false, boardIp = null)
    }
    @Synchronized fun requireCredentialLease(gate: PairingCredentialWriteGate,
        observations: List<SystemHotspotObservation>) {
        if (!gate.matches(credentialLease(observations))) throw PairingFailure("hotspot_stopped")
    }
    @Synchronized fun beginCredentialWrite(gate: PairingCredentialWriteGate,
        observations: List<SystemHotspotObservation>): Boolean =
        gate.beginWriting(credentialLease(observations))
    private fun credentialLease(observations: List<SystemHotspotObservation>): PairingCredentialLease {
        val expected = binding
        return PairingCredentialLease(expected != null &&
            sameSystemHotspotObservations(expected.observations, observations), expected, state.value.requestId)
    }
    @Synchronized fun stopIfRequestMatches(requestId: String) {
        if (state.value.requestId == requestId) stop()
    }
    @Synchronized fun stop() {
        explicitlyStopping = true
        binding = null
        verifiedHotspot = null
        continuity.reset()
        state.value = Snapshot()
    }
    @Synchronized fun observe(observations: List<SystemHotspotObservation>) {
        val pending = binding
        if (pending != null && !sameSystemHotspotObservations(pending.observations, observations))
            binding = null // A disappeared/replaced AP cannot revive the pending credential write.
        val verified = verifiedHotspot
        if (state.value.active && (verified == null || continuity.isLost(verified, observations, System.nanoTime()))) {
            verifiedHotspot = null
            explicitlyStopping = false
            state.value = state.value.copy(active = false, boardIp = null,
                unconfirmed = state.value.requestId != null || state.value.unconfirmed)
        }
    }
}
