package com.example.followingcar

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All framework calls and callbacks are serialized on main; credentials stay in this process. */
@SuppressLint("MissingPermission") // Every entry and callback checks permission; platform races are caught.
class WifiDirectController(context: Context, private val canMutate: () -> Boolean) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val supported = app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    private val mutableState = MutableStateFlow(WifiDirectState(supported = supported,
        permissionGranted = hasPermission(), confirmationRequired = WifiDirectChannelLease.confirmationRequired))
    val state: StateFlow<WifiDirectState> = mutableState.asStateFlow()
    // A status refresh is read-only; callers must not confuse it with group creation/removal.
    internal val mutationPending: Boolean get() = pending != null
    @Volatile private var closed = false
    private var lease: WifiDirectChannelLease? = null
    private var registered = false
    private var sequence = 0L
    private var pending: PendingAction? = null
    private var query: Query? = null
    private var latestQuery: Query? = null
    private var queryAgain = false
    private var confirmationQueued = false
    private val channelLost: () -> Unit = { onMain { invalidateChannel() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Extras never authorize a mutation. Re-read the framework's current state.
            onMain {
                if (!hasPermission()) permissionLost()
                else if (lease != null) requestSnapshot()
            }
        }
    }

    fun refresh() = refreshState(confirmMutation = true)

    /** Lifecycle/permission/broadcast reads must not acknowledge an unfinished framework mutation. */
    fun refreshStatus() = refreshState(confirmMutation = false)

    private fun refreshState(confirmMutation: Boolean) = onMain {
        if (prepare()) {
            if (pending == null) mutableState.value = state.value.copy(error = null, status = "正在读取直连状态…")
            requestSnapshot(confirmMutation)
        }
    }

    fun createGroup() = begin(WifiDirectAction.CREATE)
    fun removeGroup() = begin(WifiDirectAction.REMOVE)
    fun discoverPeers() = begin(WifiDirectAction.DISCOVER)
    fun stopDiscovery() = begin(WifiDirectAction.STOP_DISCOVERY)
    fun invitePeer(address: String) = begin(WifiDirectAction.INVITE, address)

    /** Closing the UI observer must not tear down a group used by an existing SSH connection. */
    fun close() {
        if (closed) return
        closed = true
        val dispose = Runnable {
            // The platform action outlives this UI observer; a new observer must re-confirm it.
            markUnconfirmedMutation()
            main.removeCallbacksAndMessages(null)
            pending = null
            query = null
            latestQuery = null
            queryAgain = false
            confirmationQueued = false
            if (registered) runCatching { app.unregisterReceiver(receiver) }
            registered = false
            WifiDirectChannelLease.release(lease, channelLost)
            lease = null
            mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(status = "直连状态查看已关闭", error = null)
        }
        if (Looper.myLooper() == main.looper) dispose.run() else main.post(dispose)
    }

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }

    private fun hasPermission(): Boolean = app.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
        PackageManager.PERMISSION_GRANTED

    private fun prepare(): Boolean {
        if (!supported) {
            mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(status = "此手机不支持 Wi-Fi Direct")
            return false
        }
        if (!hasPermission()) { permissionLost(); return false }
        try {
            mutableState.value = state.value.copy(permissionGranted = true,
                wifiEnabled = app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
                confirmationRequired = state.value.confirmationRequired || WifiDirectChannelLease.confirmationRequired)
            if (lease == null) lease = WifiDirectChannelLease.acquire(app, channelLost)
            if (lease == null) {
                mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(
                    status = "Wi-Fi Direct 服务暂不可用", error = "请稍后点击刷新状态。")
                return false
            }
            if (!registered) {
                val filter = IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                }
                // Wi-Fi broadcasts can originate from a privileged service outside the system UID.
                // All actions are protected system broadcasts; their payloads are not trusted here.
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                registered = true
            }
            return true
        } catch (error: SecurityException) {
            securityFailed("initialize")
        } catch (error: RuntimeException) {
            frameworkFailed("initialize", error)
        }
        return false
    }

    private fun begin(action: WifiDirectAction, address: String? = null) = onMain {
        if (!canMutate()) { reject("请先结束控制并断开 SSH，再修改 Wi-Fi Direct 网络。"); return@onMain }
        if (!prepare()) return@onMain
        wifiDirectMutationError(state.value, canMutate())?.let { reject(it); return@onMain }
        val operation = PendingAction(++sequence, action, address)
        pending = operation
        mutableState.value = state.value.copy(busy = true, error = null, status = "正在确认直连组状态…")
        log("requested", action)
        operation.timeout = Runnable {
            if (pending !== operation || closed) return@Runnable
            markUnconfirmedMutation()
            cancelQuery()
            mutableState.value = state.value.copy(groupInfoKnown = false)
            finish("等待系统确认超时，请刷新状态；这不代表先前请求已被取消。")
            log("timeout", action)
        }.also { main.postDelayed(it, if (action == WifiDirectAction.INVITE) 30_000L else 20_000L) }
        // No mutation is issued from an old UI snapshot, especially invitation vs GO negotiation.
        requestSnapshot()
    }

    private fun requestSnapshot(confirmMutation: Boolean = false) {
        if (closed) return
        if (!hasPermission()) { permissionLost(); return }
        val active = lease ?: return
        if (query != null) {
            queryAgain = true
            confirmationQueued = confirmationQueued || confirmMutation
            return
        }
        val request = Query(++sequence, active,
            confirmMutation = (confirmMutation || confirmationQueued) && state.value.confirmationRequired && pending == null)
        confirmationQueued = false
        query = request
        latestQuery?.auxiliaryTimeout?.let(main::removeCallbacks)
        latestQuery = request
        mutableState.value = state.value.copy(busy = true)
        request.timeout = Runnable {
            if (query !== request || closed) return@Runnable
            markUnconfirmedMutation()
            cancelQuery()
            mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(
                status = "直连状态未确认", error = "读取系统直连状态超时，请点击刷新。")
            finish("读取系统直连状态超时，请点击刷新。")
            log("query_timeout")
        }.also { main.postDelayed(it, 5_000L) }
        try {
            active.manager.requestGroupInfo(active.channel) { group -> receive(request, 1) { request.group = group } }
            active.manager.requestConnectionInfo(active.channel) { info -> receive(request, 2) { request.info = info } }
            active.manager.requestP2pState(active.channel) { value -> receive(request, 4) { request.enabled = value == WifiP2pManager.WIFI_P2P_STATE_ENABLED } }
        } catch (error: SecurityException) {
            securityFailed("query")
        } catch (error: RuntimeException) {
            cancelQuery()
            frameworkFailed("query", error)
        }
        if (query !== request) return
        request.auxiliaryTimeout = Runnable {
            if (latestQuery === request && request.received.and(24) != 24 && !closed)
                discoveryUnavailable("discovery_query_timeout")
        }.also { main.postDelayed(it, 5_000L) }
        // Peer discovery can be restricted by a device's Location setting independently of
        // NEARBY_WIFI_DEVICES. Its failure is never evidence that the GO group disappeared.
        try {
            active.manager.requestDiscoveryState(active.channel) { value -> receiveAuxiliary(request, 8) {
                request.discovering = value == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
            } }
        } catch (error: SecurityException) { securityFailed("discovery_state", auxiliary = true) }
          catch (error: RuntimeException) { discoveryUnavailable("discovery_state_failed") }
        try {
            active.manager.requestPeers(active.channel) { list -> receiveAuxiliary(request, 16) {
                request.peers = list.deviceList.map(::peer)
            } }
        } catch (error: SecurityException) { securityFailed("peers", auxiliary = true) }
          catch (error: RuntimeException) { discoveryUnavailable("peers_failed") }
    }

    private fun receive(request: Query, part: Int, update: () -> Unit) = onMain {
        if (query !== request || lease !== request.lease) return@onMain
        if (!hasPermission()) { permissionLost(); return@onMain }
        try {
            update()
            request.received = request.received or part
            if (request.received.and(7) == 7) completeSnapshot(request)
        } catch (error: SecurityException) {
            securityFailed("query_result")
        } catch (error: RuntimeException) {
            cancelQuery()
            frameworkFailed("query_result", error)
        }
    }

    private fun receiveAuxiliary(request: Query, part: Int, update: () -> Unit) = onMain {
        if (latestQuery !== request || lease !== request.lease) return@onMain
        if (!hasPermission()) { permissionLost(); return@onMain }
        try {
            update()
            request.received = request.received or part
            if (request.received.and(24) == 24) request.auxiliaryTimeout?.let(main::removeCallbacks)
            mutableState.value = state.value.copy(
                peers = request.peers ?: state.value.peers,
                discovering = request.discovering ?: state.value.discovering,
            )
            val operation = pending
            if (part == 8 && operation?.accepted == true && operation.action in setOf(WifiDirectAction.DISCOVER, WifiDirectAction.STOP_DISCOVERY) &&
                wifiDirectActionConfirmed(operation.action, state.value, operation.address)) finish(null)
            confirmMutationIfObserved(request)
        } catch (error: SecurityException) { securityFailed("discovery_result", auxiliary = true) }
          catch (error: RuntimeException) { discoveryUnavailable("discovery_result_failed") }
    }

    private fun completeSnapshot(request: Query) {
        request.timeout?.let(main::removeCallbacks)
        query = null
        val group = request.group
        val info = request.info
        val formed = info?.groupFormed == true
        val observed = wifiDirectGroupObservation(group != null, group?.isGroupOwner == true,
            formed, info?.isGroupOwner == true)
        val exists = observed.exists
        val consistent = observed.known
        val owner = observed.owner
        val credentials = if (owner) group?.passphrase?.takeIf(String::isNotBlank) else null
        credentials?.let(Diagnostics::protectSecret)
        val clients = if (consistent && group != null) group.clientList.map(::peer) else emptyList()
        val summary = when {
            !request.enabled -> "Wi-Fi Direct 未开启，请检查系统 Wi-Fi"
            !consistent -> "直连组状态正在变化，请刷新确认"
            owner -> "手机直连组已就绪，已接入 ${clients.size} 台设备"
            exists -> "手机当前是其他直连组的客户端"
            request.discovering ?: state.value.discovering -> "正在发现附近的直连设备"
            else -> "尚未创建直连组"
        }
        mutableState.value = state.value.copy(permissionGranted = true, wifiEnabled = request.enabled,
            discovering = request.discovering ?: state.value.discovering, groupExists = exists, groupInfoKnown = consistent,
            isGroupOwner = owner, networkName = if (owner) group?.networkName else null,
            passphrase = credentials, ownerAddress = if (consistent && formed) info?.groupOwnerAddress?.hostAddress else null,
            ownerPrefixLength = if (owner) ownerPrefixLength(group, info?.groupOwnerAddress) else null,
            clients = clients, peers = request.peers ?: state.value.peers, busy = pending != null, status = summary)
        if (!request.enabled) mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(wifiEnabled = false)
        confirmMutationIfObserved(request)
        log("state", fields = mapOf("groupExists" to exists, "isGroupOwner" to owner,
            "clientCount" to clients.size, "peerCount" to state.value.peers.size))
        val operation = pending
        if (operation != null) {
            if (!operation.dispatched) dispatch(operation)
            else if (operation.accepted &&
                (operation.action !in setOf(WifiDirectAction.DISCOVER, WifiDirectAction.STOP_DISCOVERY) || request.discovering != null) &&
                wifiDirectActionConfirmed(operation.action, state.value, operation.address)) finish(null)
        }
        if (queryAgain) {
            // Coalesced broadcasts must not discard a user refresh before its discovery reply.
            confirmationQueued = confirmationQueued || (request.confirmMutation && state.value.confirmationRequired)
            queryAgain = false
            requestSnapshot()
        }
    }

    private fun dispatch(operation: PendingAction) {
        if (pending !== operation) return
        val active = lease ?: return
        // The user may have connected SSH or started another operation while preflight was pending.
        val error = wifiDirectMutationError(state.value.copy(busy = false), canMutate())
            ?: wifiDirectGroupMutationError(state.value, operation.action, operation.address)
        if (error != null) { finish(error); return }
        if (!hasPermission()) { permissionLost(); return }
        operation.dispatched = true
        mutableState.value = state.value.copy(status = actionPendingMessage(operation.action))
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onMain {
                if (pending !== operation || lease !== active) return@onMain
                if (!hasPermission()) { permissionLost(); return@onMain }
                operation.accepted = true
                log("accepted", operation.action)
                // An accepted request is not evidence of a formed group or an attached client.
                requestSnapshot()
            }
            override fun onFailure(reason: Int) = onMain {
                if (pending !== operation || lease !== active) return@onMain
                log("rejected", operation.action, mapOf("reasonCode" to reason))
                finish(wifiDirectFailureMessage(reason))
            }
        }
        try {
            when (operation.action) {
                WifiDirectAction.CREATE -> {
                    val random = SecureRandom()
                    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
                    fun randomText(count: Int) = buildString { repeat(count) { append(alphabet[random.nextInt(alphabet.length)]) } }
                    val password = randomText(16)
                    Diagnostics.protectSecret(password)
                    // The board uses a 2.4 GHz radio. This creates an autonomous phone GO;
                    // it never negotiates GO ownership and never replaces an existing group.
                    val config = WifiP2pConfig.Builder().setNetworkName("DIRECT-${randomText(2)}-FollowingCar")
                        .setPassphrase(password).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .enablePersistentMode(false).build()
                    active.manager.createGroup(active.channel, config, listener)
                }
                WifiDirectAction.REMOVE -> active.manager.removeGroup(active.channel, listener)
                WifiDirectAction.DISCOVER -> active.manager.discoverPeers(active.channel, listener)
                WifiDirectAction.STOP_DISCOVERY -> active.manager.stopPeerDiscovery(active.channel, listener)
                WifiDirectAction.INVITE -> {
                    // Android documents connect() on an existing group as an invitation.
                    // Preflight above requires a confirmed phone GO; there is no fallback connect.
                    val config = WifiP2pConfig().apply {
                        deviceAddress = requireNotNull(operation.address)
                        wps.setup = WpsInfo.PBC
                    }
                    active.manager.connect(active.channel, config, listener)
                }
            }
        } catch (error: SecurityException) {
            if (!hasPermission()) permissionLost()
            else if (operation.action in setOf(WifiDirectAction.DISCOVER, WifiDirectAction.STOP_DISCOVERY)) {
                finish("系统限制了设备发现，请检查系统位置开关后重试。")
                discoveryUnavailable("discovery_action_restricted")
            } else securityFailed("action")
        } catch (error: RuntimeException) {
            frameworkFailed("action", error)
        }
    }

    private fun finish(error: String?) {
        val finished = pending
        finished?.timeout?.let(main::removeCallbacks)
        pending = null
        mutableState.value = state.value.copy(busy = query != null, error = error,
            status = if (error != null) "Wi-Fi Direct 操作未完成" else state.value.status)
        if (finished != null && error == null) log("confirmed", finished.action)
    }

    private fun reject(message: String) { mutableState.value = state.value.copy(error = message) }

    private fun markUnconfirmedMutation() {
        if (pending?.dispatched == true || state.value.confirmationRequired) {
            WifiDirectChannelLease.confirmationRequired = true
            mutableState.value = state.value.copy(confirmationRequired = true)
        }
    }

    private fun confirmMutationIfObserved(request: Query) {
        if (latestQuery !== request || pending != null || !state.value.confirmationRequired) return
        if (wifiDirectRefreshConfirmsMutation(state.value, request.confirmMutation,
                request.received.and(7) == 7, request.received.and(8) == 8)) {
            WifiDirectChannelLease.confirmationRequired = false
            mutableState.value = state.value.copy(confirmationRequired = false, error = null)
            log("mutation_state_confirmed")
        }
    }

    private fun cancelQuery() {
        query?.timeout?.let(main::removeCallbacks)
        latestQuery?.auxiliaryTimeout?.let(main::removeCallbacks)
        query = null
        latestQuery = null
        queryAgain = false
        confirmationQueued = false
    }

    private fun securityFailed(phase: String, auxiliary: Boolean = false) {
        if (!hasPermission()) { permissionLost(); return }
        if (auxiliary) { discoveryUnavailable("discovery_restricted"); return }
        markUnconfirmedMutation()
        cancelQuery()
        val message = "系统限制了此直连操作，请检查 Wi-Fi、附近设备权限和系统位置开关后刷新。"
        finish(message)
        mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(permissionGranted = true,
            status = "直连状态未确认", error = message)
        log("system_restricted", fields = mapOf("phase" to phase))
    }

    private fun discoveryUnavailable(phase: String) {
        // Retain confirmed group identity and credentials: a scan is independent of a live group.
        mutableState.value = state.value.copy(error = "暂时无法读取设备发现信息；可检查系统位置开关后刷新，现有直连组不受此提示影响。")
        log("discovery_unavailable", fields = mapOf("phase" to phase))
    }

    private fun permissionLost() {
        markUnconfirmedMutation()
        cancelQuery()
        pending?.timeout?.let(main::removeCallbacks)
        pending = null
        WifiDirectChannelLease.release(lease, channelLost)
        lease = null
        mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(permissionGranted = false,
            status = "需要附近 Wi-Fi 设备权限", error = "请允许权限后点击刷新状态。")
        log("permission_missing")
    }

    private fun invalidateChannel() {
        markUnconfirmedMutation()
        cancelQuery()
        pending?.timeout?.let(main::removeCallbacks)
        pending = null
        lease = null
        mutableState.value = state.value.withoutSensitiveWifiDirectData().copy(
            status = "Wi-Fi Direct 服务连接已中断", error = "请点击刷新重新读取；不会自动创建或恢复直连组。")
        log("channel_lost")
    }

    private fun frameworkFailed(phase: String, error: RuntimeException) {
        markUnconfirmedMutation()
        log("framework_error", pending?.action, mapOf("phase" to phase, "exceptionType" to error.javaClass.name))
        // Exception messages may contain device identities or credentials; never display or log them.
        finish("系统直链接口暂不可用，请刷新状态后重试。")
    }

    private fun peer(device: WifiP2pDevice) = WifiDirectPeer(device.deviceName.orEmpty(),
        device.deviceAddress.orEmpty(), device.ipAddress?.hostAddress, device.status)

    private fun ownerPrefixLength(group: WifiP2pGroup?, owner: InetAddress?): Int? {
        if (group == null || owner == null) return null
        return try {
            val interfaceName = group.getInterface()?.takeIf(String::isNotBlank) ?: return null
            NetworkInterface.getByName(interfaceName)?.interfaceAddresses
                ?.firstOrNull { it.address?.address?.contentEquals(owner.address) == true }
                ?.networkPrefixLength?.toInt()?.takeIf { it in 0..(owner.address.size * 8) }
        } catch (_: Exception) {
            // Interface inspection may be restricted; retain the confirmed GO and reported clients.
            null
        }
    }

    private fun log(event: String, action: WifiDirectAction? = null, fields: Map<String, Any?> = emptyMap()) {
        Diagnostics.event("wifi_direct.$event", fields = fields + mapOf("action" to action?.name))
    }

    private class PendingAction(val id: Long, val action: WifiDirectAction, val address: String?) {
        var dispatched = false
        var accepted = false
        var timeout: Runnable? = null
    }

    private class Query(val id: Long, val lease: WifiDirectChannelLease, val confirmMutation: Boolean) {
        var received = 0
        var group: WifiP2pGroup? = null
        var info: WifiP2pInfo? = null
        var enabled = false
        var discovering: Boolean? = null
        var peers: List<WifiDirectPeer>? = null
        var timeout: Runnable? = null
        var auxiliaryTimeout: Runnable? = null
    }
}

private fun actionPendingMessage(action: WifiDirectAction): String = when (action) {
    WifiDirectAction.CREATE -> "正在创建手机直连组，等待系统确认…"
    WifiDirectAction.REMOVE -> "正在解散直连组，等待系统确认…"
    WifiDirectAction.DISCOVER -> "正在请求发现设备…"
    WifiDirectAction.STOP_DISCOVERY -> "正在请求停止发现…"
    WifiDirectAction.INVITE -> "已请求邀请，等待设备确认并加入手机组…"
}

private fun wifiDirectFailureMessage(reason: Int): String = when (reason) {
    WifiP2pManager.P2P_UNSUPPORTED -> "系统不支持此 Wi-Fi Direct 操作。"
    WifiP2pManager.BUSY -> "系统 Wi-Fi Direct 正忙，请稍后刷新并重试。"
    WifiP2pManager.ERROR -> "系统未能完成直连请求，请检查 Wi-Fi 和设备状态。"
    else -> "直连请求未成功（系统代码 $reason）。"
}

/** Channel.close() permits Android to remove app-created connections. Keep this app-context-only
 * lease for the process lifetime so leaving an Activity cannot disconnect a car's network. */
private class WifiDirectChannelLease(val manager: WifiP2pManager) {
    lateinit var channel: WifiP2pManager.Channel
    var observer: (() -> Unit)? = null

    companion object {
        private var current: WifiDirectChannelLease? = null
        // No credentials or identities: survives observer recreation and service-channel replacement.
        @Volatile var confirmationRequired: Boolean = false

        fun acquire(app: Context, observer: () -> Unit): WifiDirectChannelLease? {
            current?.let { it.observer = observer; return it }
            val manager = app.getSystemService(WifiP2pManager::class.java) ?: return null
            val result = WifiDirectChannelLease(manager)
            val channel = manager.initialize(app, Looper.getMainLooper()) {
                if (current === result) {
                    current = null
                    result.observer?.invoke()
                    result.observer = null
                }
            } ?: return null
            result.channel = channel
            result.observer = observer
            current = result
            return result
        }

        fun release(lease: WifiDirectChannelLease?, observer: () -> Unit) {
            if (lease?.observer === observer) lease.observer = null
        }
    }
}
