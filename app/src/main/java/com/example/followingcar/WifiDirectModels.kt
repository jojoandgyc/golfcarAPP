package com.example.followingcar

import java.net.InetAddress

/** Peer identifiers and IP addresses are UI-only and must never be included in diagnostics. */
data class WifiDirectPeer(val name: String, val address: String, val ipAddress: String?, val status: Int) {
    override fun toString(): String = "WifiDirectPeer(status=$status, hasIp=${ipAddress != null})"
}

/** Credentials are ephemeral. No model in this file is Serializable or persisted to preferences. */
data class WifiDirectState(
    val supported: Boolean = false,
    val permissionGranted: Boolean = false,
    val wifiEnabled: Boolean = false,
    val busy: Boolean = false,
    val discovering: Boolean = false,
    val groupExists: Boolean = false,
    val isGroupOwner: Boolean = false,
    val networkName: String? = null,
    val passphrase: String? = null,
    val ownerAddress: String? = null,
    val ownerPrefixLength: Int? = null,
    val clients: List<WifiDirectPeer> = emptyList(),
    val peers: List<WifiDirectPeer> = emptyList(),
    val status: String = "点击刷新 Wi-Fi Direct 状态",
    val error: String? = null,
    val groupInfoKnown: Boolean = false,
    /** A framework mutation may finish after our deadline; only an explicit refresh clears this. */
    val confirmationRequired: Boolean = false,
) {
    override fun toString(): String = "WifiDirectState(supported=$supported, permissionGranted=$permissionGranted, " +
        "wifiEnabled=$wifiEnabled, busy=$busy, discovering=$discovering, groupExists=$groupExists, " +
        "isGroupOwner=$isGroupOwner, groupInfoKnown=$groupInfoKnown, confirmationRequired=$confirmationRequired, " +
        "clientCount=${clients.size}, peerCount=${peers.size})"
}

internal enum class WifiDirectAction { CREATE, REMOVE, DISCOVER, STOP_DISCOVERY, INVITE }

internal data class WifiDirectGroupObservation(val exists: Boolean, val known: Boolean, val owner: Boolean)

internal fun wifiDirectGroupObservation(groupPresent: Boolean, groupOwner: Boolean,
    connectionFormed: Boolean, connectionOwner: Boolean): WifiDirectGroupObservation {
    val known = if (!groupPresent) !connectionFormed else connectionFormed && groupOwner == connectionOwner
    return WifiDirectGroupObservation(groupPresent || connectionFormed, known,
        known && groupPresent && groupOwner && connectionOwner)
}

/** Framework ActionListener success is deliberately not an input: only observed state confirms. */
internal fun wifiDirectActionConfirmed(action: WifiDirectAction, current: WifiDirectState,
    address: String? = null): Boolean = when (action) {
    WifiDirectAction.CREATE -> current.groupInfoKnown && current.groupExists && current.isGroupOwner
    WifiDirectAction.REMOVE -> current.groupInfoKnown && !current.groupExists
    WifiDirectAction.DISCOVER -> current.discovering
    WifiDirectAction.STOP_DISCOVERY -> !current.discovering
    WifiDirectAction.INVITE -> current.groupInfoKnown && current.groupExists && current.isGroupOwner &&
        current.clients.any { it.address.equals(address, true) }
}

internal fun wifiDirectMutationError(state: WifiDirectState, canMutate: Boolean): String? = when {
    !canMutate -> "请先结束控制并断开 SSH，再修改 Wi-Fi Direct 网络。"
    !state.supported -> "此手机不支持 Wi-Fi Direct。"
    !state.permissionGranted -> "请先允许附近 Wi-Fi 设备权限。"
    state.confirmationRequired -> "上次系统直连操作的结果尚未确认，请先点击刷新状态。"
    state.busy -> "上一项 Wi-Fi Direct 操作尚未完成。"
    !state.wifiEnabled -> "请先在系统设置中开启 Wi-Fi。"
    else -> null
}

/** Peers are optional. Confirm both group/radio and discovery after the user's explicit refresh. */
internal fun wifiDirectRefreshConfirmsMutation(state: WifiDirectState, explicitRefresh: Boolean,
    coreReceived: Boolean, discoveryReceived: Boolean): Boolean = explicitRefresh && coreReceived &&
    discoveryReceived && state.permissionGranted && (state.groupInfoKnown || !state.wifiEnabled)

/** Called against a fresh group query immediately before the corresponding framework mutation. */
internal fun wifiDirectGroupMutationError(state: WifiDirectState, action: WifiDirectAction,
    address: String? = null): String? = when {
    !state.groupInfoKnown -> "直连组状态尚未确认，请刷新后重试。"
    action == WifiDirectAction.CREATE && state.groupExists -> if (state.isGroupOwner)
        "手机已有直连组，无需重复创建。" else "手机已加入其他直连组，请明确解散后再创建手机组。"
    action == WifiDirectAction.REMOVE && !state.groupExists -> "当前没有可解散的直连组。"
    action == WifiDirectAction.INVITE && (!state.groupExists || !state.isGroupOwner) ->
        "请先创建由手机担任组主的直连组，再邀请开发板。"
    action == WifiDirectAction.INVITE && !validWifiDirectPeerAddress(address) -> "设备地址无效，请重新发现设备。"
    action == WifiDirectAction.INVITE && state.clients.any { it.address.equals(address, true) } ->
        "该设备已经在手机直连组中。"
    action == WifiDirectAction.INVITE && state.peers.none { it.address.equals(address, true) } ->
        "设备已不在发现列表，请重新发现后选择。"
    else -> null
}

internal fun WifiDirectState.withoutSensitiveWifiDirectData(): WifiDirectState = copy(
    busy = false, discovering = false, groupExists = false, isGroupOwner = false,
    groupInfoKnown = false, networkName = null, passphrase = null, ownerAddress = null, ownerPrefixLength = null,
    clients = emptyList(), peers = emptyList(),
)

internal fun validWifiDirectPeerAddress(value: String?): Boolean {
    if (value == null || !value.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) return false
    return value.lowercase() !in setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff") &&
        value.take(2).toInt(16).and(1) == 0
}

/** These are the stable public WifiP2pDevice status constants, kept Android-free for unit tests. */
fun wifiDirectPeerStatusLabel(status: Int): String = when (status) {
    0 -> "已连接"
    1 -> "已邀请"
    2 -> "连接失败"
    3 -> "可邀请"
    4 -> "暂不可用"
    else -> "状态未知"
}

/** Accept only a real client literal IP. Never resolve a name, scan a subnet, or substitute the GO IP. */
fun usableWifiDirectClientAddress(address: String?, ownerAddress: String?): String? {
    val candidate = numericWifiDirectAddress(address) ?: return null
    if (candidate.isAnyLocalAddress || candidate.isLoopbackAddress || candidate.isMulticastAddress ||
        candidate.address.all { it == (-1).toByte() }) return null
    val owner = numericWifiDirectAddress(ownerAddress)
    if (owner != null && owner.address.contentEquals(candidate.address)) return null
    return address?.trim()
}

/** A Direct connection must target a reported client or a host inside the actual GO interface subnet. */
fun wifiDirectHostForConnection(state: WifiDirectState, address: String?): String? {
    if (!state.supported || !state.permissionGranted || !state.wifiEnabled || !state.groupInfoKnown ||
        !state.groupExists || !state.isGroupOwner || state.confirmationRequired) return null
    val owner = numericWifiDirectAddress(state.ownerAddress) ?: return null
    val host = usableWifiDirectClientAddress(address, state.ownerAddress) ?: return null
    val candidate = numericWifiDirectAddress(host) ?: return null
    if (state.clients.any { client ->
            numericWifiDirectAddress(client.ipAddress)?.address?.contentEquals(candidate.address) == true
        }) return host

    // Manual entry is supported only with the system's real IPv4 prefix, never an assumed /24.
    if (owner.address.size != 4 || candidate.address.size != 4) return null
    val prefix = state.ownerPrefixLength?.takeIf { it in 1..30 } ?: return null
    fun ipv4Number(value: InetAddress): Long = value.address.fold(0L) { result, byte ->
        (result shl 8) or (byte.toInt() and 255).toLong()
    }
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val network = ipv4Number(owner) and mask
    val destination = ipv4Number(candidate)
    val broadcast = network or (mask xor 0xffffffffL)
    return host.takeIf { (destination and mask) == network && destination != network && destination != broadcast }
}

private fun numericWifiDirectAddress(value: String?): InetAddress? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val ipv4 = text.split('.')
    val validV4 = ipv4.size == 4 && ipv4.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
    val v6Parts = text.split('%')
    val validV6 = v6Parts.size <= 2 && v6Parts[0].count { it == ':' } >= 2 &&
        v6Parts[0].all { it in "0123456789abcdefABCDEF:." } &&
        (v6Parts.size == 1 || v6Parts[1].matches(Regex("[a-zA-Z0-9_.-]+")))
    if (!validV4 && !validV6) return null
    return try { InetAddress.getByName(text) } catch (_: Exception) { null }
}
