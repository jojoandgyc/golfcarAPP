package com.example.followingcar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

enum class PairingStage {
    IDLE, DISCOVERING, PAIRING_BLUETOOTH, CONNECTING_BLUETOOTH, CHECKING_BOARD,
    STARTING_HOTSPOT, SENDING_CREDENTIALS, WAITING_NETWORK, VERIFYING_NETWORK, PAIRED, UNKNOWN, ERROR,
}

data class PairingDevice(val name: String, val address: String, val bonded: Boolean) {
    override fun toString() = "PairingDevice(bonded=$bonded)"
}

/** UI state never contains hotspot credentials or protocol frames. */
data class PairingState(
    val supported: Boolean = false,
    val permissionGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val busy: Boolean = false,
    val stage: PairingStage = PairingStage.IDLE,
    val devices: List<PairingDevice> = emptyList(),
    val selectedAddress: String = DEFAULT_PAIRING_BOARD_ADDRESS,
    val hotspotActive: Boolean = false,
    val boardIp: String? = null,
    val confirmationRequired: Boolean = false,
    val status: String = "一键连接蓝牙并为开发板配置手机热点",
    val error: String? = null,
) {
    override fun toString() = "PairingState(stage=$stage, busy=$busy, hotspotActive=$hotspotActive, " +
        "confirmationRequired=$confirmationRequired, deviceCount=${devices.size})"
}

const val DEFAULT_PAIRING_BOARD_ADDRESS = "88:49:2D:14:07:61"
const val PAIRING_SERVICE_UUID = "6d4f3a10-8f71-4c5e-9c2a-7b36f0e1d842"
internal const val MAX_PAIRING_FRAME_BYTES = 4096

internal class PairingFailure(val code: String, cause: Throwable? = null) : Exception(code, cause)

internal enum class PairingIoPhase { CONNECT, READ, WRITE }
internal enum class PairingBondState { UNKNOWN, NOT_BONDED, BONDING, BONDED }

internal fun pairingFailureCode(failure: Exception): String = when (failure) {
    is TimeoutCancellationException -> "bluetooth_timeout"
    is CancellationException -> "cancelled"
    is PairingFailure -> failure.code
    is SecurityException -> "permission_denied"
    else -> "bluetooth_failed"
}

/** Only fixed enums, class names and durations cross the diagnostic boundary. Never Throwable text. */
internal fun pairingFailureFields(
    failure: Exception, failedStage: PairingStage, ioPhase: PairingIoPhase?,
    elapsedMs: Long, ioElapsedMs: Long?, bondState: PairingBondState,
): Map<String, Any?> = mapOf(
    "stage" to failedStage.name,
    "ioPhase" to ioPhase?.name?.lowercase(),
    "exceptionType" to failure.javaClass.name,
    "causeTypes" to generateSequence(failure.cause) { it.cause }.take(8)
        .map { it.javaClass.name }.distinct().joinToString(" > "),
    "elapsedMs" to elapsedMs.coerceAtLeast(0),
    "ioElapsedMs" to ioElapsedMs?.coerceAtLeast(0),
    "bondState" to bondState.name,
)

internal fun pairingFailureMessage(code: String, stage: PairingStage, ioPhase: PairingIoPhase?): String {
    if (code in setOf("bluetooth_failed", "bluetooth_timeout", "bluetooth_eof", "partial_reply")) {
        if (stage == PairingStage.PAIRING_BLUETOOTH)
            return "蓝牙配对未完成，请确认手机系统的配对提示后重试。"
        return when (ioPhase) {
            PairingIoPhase.CONNECT -> "无法连接小车蓝牙，请先靠近小车，确认小车已开机且蓝牙服务开启后重试。"
            PairingIoPhase.READ -> "蓝牙服务已连接，但读取小车响应失败或超时；配网结果未确认时请先查询结果。"
            PairingIoPhase.WRITE -> "蓝牙服务已连接，但发送蓝牙请求失败；配网结果未确认时请先查询结果。"
            null -> pairingErrorMessage(code)
        }
    }
    if (code == "protocol_error") return if (stage == PairingStage.CHECKING_BOARD)
        "蓝牙服务已连接，但小车状态响应格式不正确，请检查配网服务版本。"
    else "蓝牙配网响应格式不正确；配网结果未确认时请先查询结果。"
    return pairingErrorMessage(code)
}

internal fun pairingRequestAllowed(state: PairingState, canMutate: Boolean): String? = when {
    !canMutate -> "请先结束控制并断开 SSH，再配对网络。"
    state.busy -> "配对正在处理中。"
    !state.supported -> "此设备不支持蓝牙配对。"
    !state.permissionGranted -> "请允许附近设备权限。"
    !state.bluetoothEnabled -> "请先开启手机蓝牙。"
    state.confirmationRequired -> "配网结果尚未确认，请先查询配对结果。"
    state.hotspotActive -> "配对热点已开启，请使用已有连接或先关闭热点。"
    else -> null
}

/** Once a credential write may have begun, loss of its ACK is an unknown result, not rollback. */
internal fun pairingFailureRequiresConfirmation(credentialsMayHaveBeenSent: Boolean,
    definitiveResultReceived: Boolean) = credentialsMayHaveBeenSent && !definitiveResultReceived

internal fun validPairingCredentials(ssid: String, psk: String): Boolean =
    ssid.toByteArray(Charsets.UTF_8).size in 1..32 && ssid.none { it == '\u0000' || it == '\n' || it == '\r' } &&
        ((psk.length in 8..63 && psk.all { it.code in 32..126 }) ||
            (psk.length == 64 && psk.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }))

/** A bounded strict UTF-8 line reader; EOF, partial and oversized replies have distinct codes. */
internal fun readPairingFrame(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        if (value == -1) throw PairingFailure(if (bytes.size() == 0) "bluetooth_eof" else "partial_reply")
        if (value == 10) break
        if (bytes.size() >= MAX_PAIRING_FRAME_BYTES) throw PairingFailure("reply_too_large")
        bytes.write(value)
    }
    if (bytes.size() == 0) throw PairingFailure("empty_reply")
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (failure: Exception) { throw PairingFailure("invalid_reply_encoding", failure) }
}

internal data class PairingLocalAddress(val ip: String, val prefixLength: Int)

internal fun newPairingLocalAddresses(before: List<PairingLocalAddress>, after: List<PairingLocalAddress>) =
    after.filterNot { it in before }

internal fun pairingResultIsDefinitive(ok: Boolean, definitive: Boolean?, networkChanged: Boolean) =
    definitive == true && (ok || !networkChanged)

/** Remote error text is never suitable for UI/logs, even when it resembles an identifier. */
internal fun pairingBoardErrorCode(value: Any?): String = when (value) {
    "provisioning_busy", "worker_unavailable", "control_inspection_failed", "control_busy_or_unconfirmed",
    "control_lock_busy_or_unavailable", "control_process_running", "motor_serial_check_failed",
    "motor_serial_in_use", "invalid_control_lock", "process_check_unavailable",
    "networkmanager_command_failed", "command_timeout", "command_unavailable", "join_cancelled",
    "expected_only_wlan0_wifi_interface", "unexpected_wifi_device", "wifi_not_managed_or_state_unknown",
    "invalid_current_profile_uuid", "invalid_ip_observation", "profile_list_unconfirmed",
    "active_profile_list_unconfirmed", "profile_ownership_unconfirmed", "profile_uuid_collision",
    "unsafe_profile_directory", "loaded_profile_identity_unconfirmed", "profile_file_ownership_unconfirmed",
    "network_transition_unconfirmed", "unsafe_result_path", "root_required_for_complete_idle_probe",
    "join_requires_root", "wifi_not_ready_for_switch", "activation_not_confirmed",
    "network_address_unconfirmed", "unsafe_pairing_state", "pairing_state_unreadable",
    "invalid_hotspot_ssid", "invalid_wpa2_psk", "hotspot_not_visible_2g", "hotspot_unavailable",
    "outcome_unknown_after_restart", "network_restored", "internal_error", "join_failed" -> value as String
    else -> "board_failed"
}

/** The Bluetooth result cannot redirect saved SSH credentials outside the actual phone AP subnet. */
internal fun verifiedPairingBoardIp(ip: String?, gateway: String?, prefixLength: Int,
    localAddresses: List<PairingLocalAddress>): String? {
    val host = pairingIpv4(ip) ?: return null
    val owner = pairingIpv4(gateway) ?: return null
    if (prefixLength !in 1..30 || host == owner ||
        localAddresses.none { pairingIpv4(it.ip) == owner && it.prefixLength == prefixLength }) return null
    val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
    val network = owner and mask
    val broadcast = network or (mask xor 0xffffffffL)
    if (host and mask != network || host == network || host == broadcast) return null
    return ip
}

private fun pairingIpv4(value: String?): Long? {
    if (value == null || !value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
    val parts = value.split('.').map { it.toIntOrNull() ?: return null }
    if (parts.any { it !in 0..255 } || parts[0] == 0 || parts[0] == 127 || parts[0] >= 224 ||
        (parts[0] == 169 && parts[1] == 254)) return null
    return parts.fold(0L) { address, byte -> (address shl 8) or byte.toLong() }
}

internal fun pairingErrorMessage(code: String): String = when (code) {
    "control_busy", "control_unavailable", "board_busy" -> "开发板控制尚未空闲，未切换无线网络。"
    "bond_failed", "bond_timeout" -> "蓝牙配对未完成，请确认手机系统的配对提示后重试。"
    "permission_denied" -> "附近设备权限不可用，请重新授权。"
    "bluetooth_disabled" -> "手机蓝牙已关闭。"
    "bluetooth_timeout", "bluetooth_eof", "partial_reply" -> "蓝牙通信中断或超时。"
    "unsupported_hotspot_security" -> "系统热点的加密方式与开发板不兼容，需要 WPA2 热点。"
    "hotspot_no_channel", "hotspot_not_visible_2g", "hotspot_unavailable" ->
        "开发板未能使用该热点；本开发板仅支持 2.4 GHz，请检查热点频段。"
    "hotspot_incompatible" -> "系统当前的热点或 Wi-Fi Direct 与配对热点冲突，请先关闭后重试。"
    "hotspot_denied" -> "手机系统不允许创建配对热点。"
    "hotspot_stopped" -> "手机配对热点已停止，连接已失效。"
    "address_unverified" -> "开发板已返回地址，但无法确认属于手机热点；尚未连接 SSH。"
    "request_not_found" -> "开发板没有该次配网的确认记录，请检查现场网络。"
    "provisioning_pending" -> "开发板仍在连接热点，请稍后再次查询结果。"
    "network_address_unconfirmed" -> "开发板网络变更尚未确认，请查询结果或检查板端。"
    "network_restored" -> "开发板已恢复原网络，可以关闭配对热点后重新配对。"
    "cancelled" -> "配对已取消。"
    "state_changed" -> "控制或连接状态已变化，配对已停止。"
    else -> "配对未完成（$code）。"
}
