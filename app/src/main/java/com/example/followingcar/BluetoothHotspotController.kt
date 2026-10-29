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
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SparseIntArray
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** One explicit request owns one authenticated Bluetooth link and one ephemeral local hotspot.
 * UI observers can be recreated without closing a hotspot used by an active SSH connection. */
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
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val mutableState = MutableStateFlow(PairingState(supported = adapter != null && wifi != null))
    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    private var closed = false
    private var registered = false
    private var operation: Job? = null
    private var discoveryDeadline: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var bondWaiter: CompletableDeferred<Boolean>? = null
    private var bondingAddress: String? = null
    private var credentialsMayHaveBeenSent = false
    private var definitiveResultReceived = false
    private var readOnlyOperation = false
    private var diagnostic = DiagnosticContext.NONE
    private var operationStartedNanos = 0L
    private var ioPhase: PairingIoPhase? = null
    private var ioElapsedMs: Long? = null

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
                if (wasActive != lease.active) notifyLease(lease.active)
                if (wasActive && !lease.active && !HotspotLease.explicitlyStopping) {
                    mutableState.value = state.value.copy(stage = PairingStage.ERROR,
                        status = "手机热点已停止", error = pairingErrorMessage("hotspot_stopped"))
                    runCatching { onHotspotLost() }
                }
                wasActive = lease.active
            }
        }
        refresh()
    }

    fun refresh() = onMain {
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
        if (!state.value.busy && !state.value.hotspotActive && BluetoothAdapter.checkBluetoothAddress(address))
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

    fun start(address: String? = null) = onMain {
        refresh()
        pairingRequestAllowed(state.value, canMutate())?.let { reject(it); return@onMain }
        val target = address ?: state.value.selectedAddress
        if (!BluetoothAdapter.checkBluetoothAddress(target)) { reject("开发板蓝牙地址无效。"); return@onMain }
        mutableState.value = state.value.copy(selectedAddress = target)
        beginOperation {
            connectBluetooth(target, allowBond = true)
            stage(PairingStage.CHECKING_BOARD, "正在确认开发板控制空闲…")
            val status = requestStatus()
            if (status.opt("controlAvailable") != true || status.opt("operationInProgress") == true)
                throw PairingFailure("board_busy")
            ensureCanMutate()
            stage(PairingStage.STARTING_HOTSPOT, "正在创建手机配对热点…")
            createHotspot()
            ensureCanMutate()
            val reservation = HotspotLease.reservation ?: throw PairingFailure("hotspot_stopped")
            val config = reservation.softApConfiguration
            @Suppress("DEPRECATION")
            val ssid = config.ssid ?: throw PairingFailure("invalid_hotspot_configuration")
            val psk = config.passphrase ?: throw PairingFailure("invalid_hotspot_configuration")
            if (config.securityType != SoftApConfiguration.SECURITY_TYPE_WPA2_PSK &&
                config.securityType != SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                throw PairingFailure("unsupported_hotspot_security")
            if (!validPairingCredentials(ssid, psk)) throw PairingFailure("invalid_hotspot_configuration")
            Diagnostics.protectSecret(psk)
            Diagnostics.protectSecret(ssid)
            val requestId = UUID.randomUUID().toString()
            HotspotLease.pending(target, requestId)
            stage(PairingStage.SENDING_CREDENTIALS, "正在通过加密蓝牙发送热点配置…")
            // Set before write: a partial write followed by EOF cannot prove that the board did not act.
            credentialsMayHaveBeenSent = true
            writeFrame(JSONObject().put("v", 1).put("requestId", requestId).put("cmd", "provision")
                .put("ssid", ssid).put("psk", psk))
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
        }
    }

    /** Board read-only probe. No hotspot reservation, provision request or SSH connection. */
    fun checkBluetooth(address: String? = null) = onMain {
        refresh()
        // Do not inspect through a lease that is active or awaits recovery confirmation.
        val lease = HotspotLease.state.value
        pairingRequestAllowed(state.value.copy(hotspotActive = lease.active,
            confirmationRequired = lease.unconfirmed), canMutate())?.let { reject(it); return@onMain }
        val target = address ?: state.value.selectedAddress
        if (!BluetoothAdapter.checkBluetoothAddress(target)) { reject("开发板蓝牙地址无效。"); return@onMain }
        mutableState.value = state.value.copy(selectedAddress = target)
        beginOperation(readOnly = true) {
            connectBluetooth(target, allowBond = true)
            stage(PairingStage.CHECKING_BOARD, "正在只读检查小车蓝牙服务…")
            val status = requestStatus()
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
        if (state.value.busy || !canMutate()) { reject("请先结束控制并断开 SSH，再查询配对结果。"); return@onMain }
        if (!state.value.permissionGranted || !state.value.bluetoothEnabled) { reject("请开启蓝牙并允许附近设备权限。"); return@onMain }
        val lease = HotspotLease.state.value
        if (lease.requestId == null || lease.address == null) {
            reject("当前没有待查询的配网请求。"); return@onMain
        }
        beginOperation {
            credentialsMayHaveBeenSent = true
            connectBluetooth(lease.address, allowBond = false)
            stage(PairingStage.CHECKING_BOARD, "正在查询上次配网结果…")
            val status = requestStatus(lease.requestId)
            val result = status.optJSONObject("lastResult") ?: throw PairingFailure("request_not_found")
            if (result.optString("requestId") != lease.requestId) throw PairingFailure("protocol_error")
            if (result.optString("type") == "progress" && status.opt("operationInProgress") == true)
                throw PairingFailure("provisioning_pending")
            applyResult(result)
        }
    }

    fun cancel() = onMain {
        if (state.value.stage == PairingStage.DISCOVERING) {
            runCatching { adapter?.cancelDiscovery() }; finishDiscovery()
        }
        operation?.cancel()
        closeSocket()
    }

    fun stopHotspot() = onMain {
        if (!canMutate() || state.value.busy) { reject("请先结束控制并断开 SSH，再关闭热点。"); return@onMain }
        if (state.value.confirmationRequired) { reject("配网结果尚未确认，请先查询结果再关闭热点。"); return@onMain }
        HotspotLease.stop()
        mutableState.value = state.value.copy(stage = PairingStage.IDLE, status = "配对热点已关闭", error = null)
        log("hotspot_closed")
    }

    /** Detaches this observer. A live or unconfirmed network remains owned by the process lease. */
    fun close() = onMain {
        closed = true
        if (!readOnlyOperation) {
            if (credentialsMayHaveBeenSent && !definitiveResultReceived) HotspotLease.markUnknown()
            else if (state.value.busy && HotspotLease.state.value.boardIp == null) HotspotLease.stop()
        }
        operation?.cancel()
        closeSocket()
        discoveryDeadline?.cancel()
        runCatching { adapter?.cancelDiscovery() }
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        scope.cancel()
        if (!HotspotLease.state.value.active) notifyLease(false)
    }

    private fun beginOperation(readOnly: Boolean = false, block: suspend () -> Unit) {
        readOnlyOperation = readOnly
        credentialsMayHaveBeenSent = false
        definitiveResultReceived = false
        diagnostic = Diagnostics.newContext()
        operationStartedNanos = System.nanoTime()
        ioPhase = null
        ioElapsedMs = null
        mutableState.value = state.value.copy(busy = true, stage = PairingStage.IDLE, error = null,
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
                val failedStage = state.value.stage
                val code = pairingFailureCode(failure)
                val failureFields = pairingFailureFields(failure, failedStage, ioPhase,
                    elapsedMillis(operationStartedNanos), ioElapsedMs, bondState())
                val unknown = !readOnly && pairingFailureRequiresConfirmation(credentialsMayHaveBeenSent, definitiveResultReceived)
                if (!readOnly) {
                    if (unknown) HotspotLease.markUnknown()
                    else if (!definitiveResultReceived) HotspotLease.stop()
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
                if (!HotspotLease.state.value.active) notifyLease(false)
                operation = null
                readOnlyOperation = false
            }
        }
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
                if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) throw PairingFailure("bond_failed")
                if (!withTimeout(60_000) { waiter.await() }) throw PairingFailure("bond_failed")
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
            !reply.optString("boardId").matches(Regex("[A-Za-z0-9._:-]{1,128}")) ||
            reply.optString("band") != "2.4GHz") throw PairingFailure("protocol_error")
        return reply
    }

    private suspend fun applyResult(reply: JSONObject) {
        if (reply.opt("ok") !is Boolean) throw PairingFailure("protocol_error")
        if (!reply.getBoolean("ok")) {
            if (!pairingResultIsDefinitive(false, reply.opt("definitive") as? Boolean,
                    reply.opt("networkChanged") == true)) throw PairingFailure("network_address_unconfirmed")
            definitiveResultReceived = true
            HotspotLease.confirmFailure()
            // Keep the hotspot for explicit review; the user can safely close it after this reply.
            val code = pairingBoardErrorCode(reply.opt("error"))
            throw PairingFailure(code)
        }
        if (reply.opt("definitive") != true) throw PairingFailure("protocol_error")
        if (!HotspotLease.state.value.active) {
            definitiveResultReceived = true
            HotspotLease.confirmFailure()
            throw PairingFailure("hotspot_stopped")
        }
        stage(PairingStage.VERIFYING_NETWORK, "正在核对开发板的热点地址…")
        var verified: String? = null
        repeat(20) {
            if (verified == null) {
                verified = verifiedPairingBoardIp(reply.optString("ip"), reply.optString("gateway"),
                    reply.optInt("prefixLength", -1),
                    newPairingLocalAddresses(HotspotLease.baselineAddresses, localAddresses()))
                if (verified == null) delay(250)
            }
        }
        val ip = verified ?: throw PairingFailure("address_unverified")
        if (!HotspotLease.state.value.active) throw PairingFailure("hotspot_stopped")
        definitiveResultReceived = true
        HotspotLease.paired(ip)
        mutableState.value = state.value.copy(stage = PairingStage.PAIRED, boardIp = ip,
            confirmationRequired = false, busy = false, status = "开发板已接入手机热点", error = null)
        log("paired")
        main.post { if (!closed && HotspotLease.state.value.boardIp == ip) {
            runCatching { onPaired(ip) }.onFailure { reject("网络配对已完成，请手动连接 SSH。") }
        } }
    }

    private suspend fun createHotspot() = withTimeout(30_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val leaseToken = UUID.randomUUID().toString()
            HotspotLease.request(leaseToken, localAddresses())
            continuation.invokeOnCancellation { main.post { HotspotLease.cancelRequest(leaseToken) } }
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (!continuation.isActive || closed || !canMutate() || !HotspotLease.accepts(leaseToken)) {
                        HotspotLease.cancelRequest(leaseToken)
                        runCatching { reservation.close() }
                        if (continuation.isActive) continuation.resumeWithException(PairingFailure("state_changed"))
                        return
                    }
                    HotspotLease.acquire(reservation, leaseToken)
                    continuation.resume(Unit)
                }
                override fun onStopped() { HotspotLease.stoppedBySystem(leaseToken) }
                override fun onFailed(reason: Int) {
                    HotspotLease.cancelRequest(leaseToken)
                    val code = when (reason) {
                        ERROR_NO_CHANNEL -> "hotspot_no_channel"
                        ERROR_INCOMPATIBLE_MODE -> "hotspot_incompatible"
                        ERROR_TETHERING_DISALLOWED -> "hotspot_denied"
                        else -> "hotspot_failed"
                    }
                    if (continuation.isActive) continuation.resumeWithException(PairingFailure(code))
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 36) {
                    val ssid = "FollowingCar-" + UUID.randomUUID().toString().take(8)
                    val password = UUID.randomUUID().toString().replace("-", "")
                    val config = SoftApConfiguration.Builder().setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray()))
                        .setPassphrase(password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                        .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_2GHZ, 0) }).build()
                    wifi!!.startLocalOnlyHotspotWithConfiguration(config, app.mainExecutor, callback)
                } else {
                    // Android 15 exposes no public band selector/getter. The 2.4 GHz board confirms
                    // actual association; absence of an API is never represented as verified 2.4 GHz.
                    wifi!!.startLocalOnlyHotspot(callback, main)
                }
            } catch (failure: Exception) {
                HotspotLease.cancelRequest(leaseToken)
                if (continuation.isActive) continuation.resumeWithException(
                    PairingFailure(if (failure is SecurityException) "permission_denied" else "hotspot_failed", failure))
            }
        }
    }

    private suspend fun writeFrame(frame: JSONObject) {
        ioPhase = PairingIoPhase.WRITE
        ioElapsedMs = null
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAIRING_FRAME_BYTES) throw PairingFailure("request_too_large")
        val link = socket ?: throw PairingFailure("bluetooth_eof")
        socketCall(PairingIoPhase.WRITE, 10_000) { link.outputStream.write(bytes); link.outputStream.flush() }
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
    private suspend fun <T> socketCall(phase: PairingIoPhase, timeout: Long, action: () -> T): T {
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
                    continuation.invokeOnCancellation { IO.execute { runCatching { ownedSocket?.close() } } }
                    IO.execute {
                        try {
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

    private fun localAddresses(): List<PairingLocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { device ->
            device.interfaceAddresses.filter { it.address is Inet4Address }.mapNotNull { entry ->
                entry.address.hostAddress?.let { PairingLocalAddress(it, entry.networkPrefixLength.toInt()) }
            }
        }
    }.getOrDefault(emptyList())

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
            fields = mapOf("stage" to state.value.stage.name, "code" to code) + fields, critical = true)
    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }

    companion object {
        private val IO = Executors.newCachedThreadPool { runnable -> Thread(runnable, "pairing-bluetooth").apply { isDaemon = true } }
    }
}

/** Only this process-level lease owns the reservation. It deliberately outlives an Activity/ViewModel. */
private object HotspotLease {
    data class Snapshot(val active: Boolean = false, val unconfirmed: Boolean = false,
        val address: String? = null, val requestId: String? = null, val boardIp: String? = null)
    val state = MutableStateFlow(Snapshot())
    var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        private set
    var explicitlyStopping = false
        private set
    private var ownerToken: String? = null
    private var pendingToken: String? = null
    var baselineAddresses: List<PairingLocalAddress> = emptyList()
        private set
    fun request(token: String, baseline: List<PairingLocalAddress>) {
        if (reservation != null || pendingToken != null) throw PairingFailure("hotspot_incompatible")
        pendingToken = token
        baselineAddresses = baseline
    }
    fun accepts(token: String) = pendingToken == token && reservation == null
    fun cancelRequest(token: String) { if (pendingToken == token) pendingToken = null }
    fun acquire(value: WifiManager.LocalOnlyHotspotReservation, token: String) {
        check(accepts(token))
        pendingToken = null
        reservation = value
        ownerToken = token
        explicitlyStopping = false
        state.value = Snapshot(active = true)
    }
    fun pending(address: String, requestId: String) {
        state.value = state.value.copy(address = address, requestId = requestId, unconfirmed = true)
    }
    fun markUnknown() { state.value = state.value.copy(unconfirmed = true, boardIp = null) }
    fun paired(ip: String) { state.value = state.value.copy(unconfirmed = false, boardIp = ip) }
    fun confirmFailure() { state.value = state.value.copy(unconfirmed = false, boardIp = null) }
    fun stop() {
        explicitlyStopping = true
        val old = reservation
        reservation = null
        ownerToken = null
        state.value = Snapshot()
        runCatching { old?.close() }
    }
    fun stoppedBySystem(token: String) {
        if (token != ownerToken) return
        reservation = null
        ownerToken = null
        state.value = state.value.copy(active = false, boardIp = null)
    }
}
