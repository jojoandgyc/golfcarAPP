package com.example.followingcar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val BOARD_DISCOVERY_SERVICE_TYPE = "_followingcar._tcp."
internal const val BOARD_DISCOVERY_ID = "followingcar-rk-140761"
internal const val BOARD_DISCOVERY_SSH_FINGERPRINT = "vRTxsHA9fDeRZQ6YTXKYPyngRhvcAJEIF9+XnfxPx0g"
internal const val MAX_LOCAL_BOARD_CANDIDATES = 6

/** An untrusted address hint. Only the existing pinned SSH authentication can verify the board. */
internal data class LocalBoardEndpoint(
    val host: String,
    val port: Int = 22,
    val source: String = "mdns",
    val network: Network? = null,
)

/** Pure filtering: TXT records are not an identity proof and must never update online state. */
internal fun matchingBoardAdvertisement(serviceType: String, attributes: Map<String, ByteArray>): Boolean {
    fun text(key: String) = attributes[key]?.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)
    return serviceType.trimEnd('.').removePrefix(".").lowercase() == BOARD_DISCOVERY_SERVICE_TYPE.trimEnd('.') &&
        text("id") == BOARD_DISCOVERY_ID &&
        text("sshfp")?.removePrefix("SHA256:") == BOARD_DISCOVERY_SSH_FINGERPRINT
}

/** Preserve the receiving Wi-Fi interface scope for link-local IPv6; never resolve a hostname. */
internal fun localDiscoveryAddress(address: InetAddress, interfaceIndex: Int): String? {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
    if (address is Inet4Address && address.address.all { (it.toInt() and 255) == 255 }) return null
    return when {
        address is Inet6Address && address.isLinkLocalAddress -> {
            if (interfaceIndex <= 0) null
            else Inet6Address.getByAddress(null, address.address, interfaceIndex).hostAddress
        }
        else -> address.hostAddress?.substringBefore('%')
    }
}

internal data class LocalAddressPrefix(val address: InetAddress, val prefixLength: Int)

/** TXT advertisements cannot send authentication attempts through Wi-Fi to arbitrary Internet IPs. */
internal fun isOnLocalDiscoveryLink(address: InetAddress, localAddresses: List<LocalAddressPrefix>): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
    if (localAddresses.any { it.address.address.contentEquals(address.address) }) return false
    // IPv6 link-local addresses are inherently scoped to the receiving interface; the formatter
    // still requires that interface's actual index before they can become an endpoint.
    if (address is Inet6Address && address.isLinkLocalAddress) return localAddresses.isNotEmpty()
    val target = address.address
    return localAddresses.any { local ->
        val own = local.address.address
        val bits = local.prefixLength
        if (own.size != target.size || bits !in 1..(target.size * 8)) return@any false
        fun bit(bytes: ByteArray, index: Int): Int = (bytes[index / 8].toInt() ushr (7 - index % 8)) and 1
        if ((0 until bits).any { bit(own, it) != bit(target, it) }) return@any false
        // Network and directed-broadcast addresses are not peers. /31 and /32 have no broadcast.
        if (address is Inet4Address && bits <= 30) {
            val hostBits = (bits until 32).map { bit(target, it) }
            if (hostBits.all { it == 0 } || hostBits.all { it == 1 }) return@any false
        }
        true
    }
}

internal fun orderedLocalEndpoints(endpoints: List<LocalBoardEndpoint>): List<LocalBoardEndpoint> = endpoints
    .filter { it.host.isNotBlank() && it.host.none(Char::isWhitespace) && it.port in 1..65535 }
    .distinctBy { Triple(it.host, it.port, it.network) }
    .sortedBy { if (':' in it.host) 1 else 0 }
    .take(MAX_LOCAL_BOARD_CANDIDATES)

/**
 * One bounded discovery, restricted to existing Wi-Fi Networks. No network requests, Wi-Fi
 * changes, process binding, IP scans, SSH operations or credential handling occur here.
 */
internal class BoardLocalDiscovery(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION") // Enumerate existing networks, including Wi-Fi without validated Internet.
    suspend fun discover(): List<LocalBoardEndpoint> = withContext(Dispatchers.Main.immediate) {
        val manager = nsd ?: return@withContext emptyList()
        val networks = connectivity?.allNetworks.orEmpty().filter(::isWifi).take(4)
        if (networks.isEmpty()) return@withContext emptyList()
        val session = Session(manager)
        try {
            session.start(networks)
            // Service-info callbacks collect all addresses instead of resolveService's first address.
            // Leave room for cleanup inside the recovery executor's six-second outer deadline.
            delay(5_500)
            orderedLocalEndpoints(session.endpoints().filter { it.network?.let(::isWifi) == true })
        } finally {
            // Cancellation must unregister callbacks and release multicast reception as well.
            withContext(NonCancellable + Dispatchers.Main.immediate) { session.close() }
        }
    }

    private fun isWifi(network: Network): Boolean = runCatching {
        connectivity?.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }.getOrDefault(false)

    private inner class Session(private val manager: NsdManager) {
        private var closed = false
        private var multicastLock: WifiManager.MulticastLock? = null
        private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
        private val callbacks = mutableMapOf<Pair<Network, String>, NsdManager.ServiceInfoCallback>()
        private val candidates = mutableMapOf<Pair<Network, String>, List<LocalBoardEndpoint>>()

        fun start(networks: List<Network>) {
            // Some devices filter multicast at the Wi-Fi driver. This lock never changes association.
            multicastLock = runCatching {
                wifi?.createMulticastLock("followingcar-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            for (network in networks) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        if (closed) runCatching { manager.stopServiceDiscovery(this) }
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        discoveries.remove(this)
                    }
                    override fun onDiscoveryStopped(serviceType: String) { discoveries.remove(this) }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!closed && isWifi(network)) watch(network, serviceInfo)
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val key = network to serviceInfo.serviceName
                        candidates.remove(key)
                        callbacks.remove(key)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
                    }
                }
                discoveries += listener
                try {
                    manager.discoverServices(BOARD_DISCOVERY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                        network, app.mainExecutor, listener)
                } catch (_: Exception) {
                    discoveries.remove(listener)
                }
            }
        }

        private fun watch(network: Network, info: NsdServiceInfo) {
            if (info.serviceType.trimEnd('.').removePrefix(".").lowercase() != BOARD_DISCOVERY_SERVICE_TYPE.trimEnd('.')) return
            if (info.network != null && info.network != network) return
            val key = network to info.serviceName
            if (callbacks.containsKey(key) || callbacks.size >= 16) return
            // Resolve on the exact network which supplied this announcement, never the default route.
            info.network = network
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (callbacks[key] === this) {
                        callbacks.remove(key)
                        candidates.remove(key)
                    }
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() { if (callbacks[key] === this) candidates.remove(key) }
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    if (closed || callbacks[key] !== this || !isWifi(network) || (updated.network != null && updated.network != network)) return
                    if (!matchingBoardAdvertisement(updated.serviceType, updated.attributes) || updated.port !in 1..65535) {
                        candidates.remove(key)
                        return
                    }
                    val properties = connectivity?.getLinkProperties(network)
                    val interfaceName = properties?.interfaceName
                    val interfaceIndex = runCatching { interfaceName?.let { NetworkInterface.getByName(it)?.index } ?: 0 }
                        .getOrDefault(0)
                    val prefixes = properties?.linkAddresses.orEmpty().map { LocalAddressPrefix(it.address, it.prefixLength) }
                    candidates[key] = updated.hostAddresses.take(32).filter { isOnLocalDiscoveryLink(it, prefixes) }.mapNotNull { address ->
                        localDiscoveryAddress(address, interfaceIndex)?.let { LocalBoardEndpoint(it, updated.port, network = network) }
                    }
                }
            }
            callbacks[key] = callback
            try { manager.registerServiceInfoCallback(info, app.mainExecutor, callback) }
            catch (_: Exception) { callbacks.remove(key) }
        }

        fun endpoints(): List<LocalBoardEndpoint> = candidates.values.flatten()

        fun close() {
            if (closed) return
            closed = true
            discoveries.toList().forEach { runCatching { manager.stopServiceDiscovery(it) } }
            discoveries.clear()
            callbacks.values.toList().forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
            candidates.clear()
            multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
            multicastLock = null
        }
    }
}
