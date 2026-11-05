package com.example.followingcar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** A current AP interface observation, never ownership of the user's hotspot or its credentials. */
internal data class SystemHotspotObservation(
    val interfaceName: String,
    val interfaceIndex: Int,
    val address: String,
    val prefixLength: Int,
)

/** Existing discovery filters out registered STA and VPN links. P2P is not system tethering.
 * An interface alone cannot attest its SSID, passphrase, band, or Internet forwarding. */
internal fun systemHotspotObservations(links: List<LocalDiscoveryLink>,
    registeredInterfaces: Set<String> = emptySet()): List<SystemHotspotObservation> =
    links.asSequence().filter { link ->
        link.network == null && link.interfaceName !in registeredInterfaces &&
            link.address is Inet4Address && link.interfaceIndex > 0 &&
            link.interfaceName.matches(Regex("(?:wlan|swlan|softap|ap)[0-9]+")) &&
            link.prefixLength in 8..30 && privateHotspotAddress(link.address.address)
    }.mapNotNull { link ->
        link.address.hostAddress?.let {
            SystemHotspotObservation(link.interfaceName, link.interfaceIndex, it, link.prefixLength)
        }
    }.distinct().sortedWith(compareBy({ it.interfaceIndex }, { it.interfaceName }, { it.address }))
        .take(2).toList()

@Suppress("DEPRECATION")
internal fun currentSystemHotspotObservations(context: Context): List<SystemHotspotObservation> = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@runCatching emptyList<SystemHotspotObservation>()
    val registeredInterfaces = mutableSetOf<String>()
    for (network in manager.allNetworks) {
        val name = manager.getLinkProperties(network)?.interfaceName
        if (!name.isNullOrBlank()) registeredInterfaces += name
        else if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            // A still-registered STA with temporarily missing LinkProperties cannot authorize
            // treating its wlan interface as an AP. A vanished network (null capabilities) can.
            return@runCatching emptyList<SystemHotspotObservation>()
        }
    }
    // Discovery deliberately permits a fallback candidate after a CM error; credentials must
    // use this stricter observation and must never turn that fallback into STA/AP certainty.
    systemHotspotObservations(currentLocalDiscoveryLinks(context), registeredInterfaces)
}.getOrDefault(emptyList())

internal fun sameSystemHotspotObservations(
    expected: List<SystemHotspotObservation>, current: List<SystemHotspotObservation>,
): Boolean = expected.isNotEmpty() && expected.toSet() == current.toSet()

/** A board result must identify a currently observed phone gateway, not merely any private IP. */
internal fun verifiedSystemHotspotObservation(
    ip: String?, gateway: String?, prefixLength: Int, current: List<SystemHotspotObservation>,
): SystemHotspotObservation? = current.singleOrNull { observation ->
    verifiedPairingBoardIp(ip, gateway, prefixLength,
        listOf(PairingLocalAddress(observation.address, observation.prefixLength))) != null
}

/** Interface enumeration can briefly return no rows. Confirm a missing established AP twice,
 * but invalidate an observed replacement immediately. This never grants a credential write. */
internal class SystemHotspotContinuity {
    private var missingSinceNanos: Long? = null

    fun reset() { missingSinceNanos = null }

    fun isLost(expected: SystemHotspotObservation, current: List<SystemHotspotObservation>,
        nowNanos: Long): Boolean {
        if (expected in current) { reset(); return false }
        if (current.isNotEmpty()) { reset(); return true }
        val since = missingSinceNanos
        if (since == null || nowNanos < since) {
            missingSinceNanos = nowNanos
            return false
        }
        return nowNanos - since >= 750_000_000L
    }
}

private fun privateHotspotAddress(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    return first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168
}
