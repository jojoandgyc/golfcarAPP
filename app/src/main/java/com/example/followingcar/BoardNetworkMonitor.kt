package com.example.followingcar

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Passive observer only: never changes Wi-Fi, creates networks, binds the process, or stops control.
 * Default internet loss is not evidence that a local-only hotspot/Direct link disappeared.
 */
class BoardNetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PhoneNetworkState())
    val state: StateFlow<PhoneNetworkState> = mutableState.asStateFlow()
    private val networks = mutableMapOf<Network, Observation>()
    private var defaultNetwork: Network? = null
    @Volatile private var closed = false
    private var allRegistered = false
    private var defaultRegistered = false
    private var lastSignature: Pair<Network?, Map<Network, Observation>>? = null

    private val allCallback = callback(isDefault = false)
    private val defaultCallback = callback(isDefault = true)

    init { onMain { register() } }

    /** Refreshes OS metadata only. A missing internet route does not invalidate car evidence. */
    fun refresh() = onMain { snapshot() }

    private fun callback(isDefault: Boolean) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onMain {
            if (isDefault) defaultNetwork = network
            readObservation(network)?.let { networks[network] = it }
            logChange("available", network, isDefault)
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onMain {
            val next = observation(capabilities, networks[network]?.interfaceName)
            if (networks[network] != next) {
                networks[network] = next
                logChange("capabilities", network, isDefault)
            }
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onMain {
            val previous = networks[network] ?: readObservation(network) ?: return@onMain
            val next = previous.copy(interfaceName = linkProperties.interfaceName?.take(32))
            if (previous != next) {
                networks[network] = next
                logChange("link", network, isDefault)
            }
            publish()
        }

        override fun onLost(network: Network) = onMain {
            logChange("lost", network, isDefault)
            if (defaultNetwork == network) defaultNetwork = null
            // A default callback can lose a network which still exists as a local route.
            if (!isDefault) networks.remove(network)
            publish()
        }
    }

    private fun register() {
        if (manager == null) return unavailable()
        try {
            // Clear INTERNET and NOT_VPN defaults: observe local-only routes and VPN separately too.
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), allCallback, main)
            allRegistered = true
            manager.registerDefaultNetworkCallback(defaultCallback, main)
            defaultRegistered = true
            snapshot()
        } catch (failure: Exception) {
            Diagnostics.event("network.observer_unavailable", fields =
                mapOf("exceptionType" to failure.javaClass.simpleName), critical = true)
            if (allRegistered) runCatching { manager.unregisterNetworkCallback(allCallback) }
            if (defaultRegistered) runCatching { manager.unregisterNetworkCallback(defaultCallback) }
            allRegistered = false
            defaultRegistered = false
            unavailable()
        }
    }

    @Suppress("DEPRECATION") // Enumerate existing routes; no requestNetwork side effect.
    private fun snapshot() {
        try {
            val currentManager = manager ?: return unavailable()
            defaultNetwork = currentManager.activeNetwork
            val current = currentManager.allNetworks.mapNotNull { network ->
                readObservation(network)?.let { network to it }
            }.toMap()
            networks.clear()
            networks.putAll(current)
            publish()
        } catch (failure: Exception) {
            Diagnostics.event("network.observer_read_failed", fields =
                mapOf("exceptionType" to failure.javaClass.simpleName))
            unavailable()
        }
    }

    private fun readObservation(network: Network): Observation? = runCatching {
        val capabilities = manager?.getNetworkCapabilities(network) ?: return null
        observation(capabilities, manager.getLinkProperties(network)?.interfaceName?.take(32))
    }.getOrNull()

    private fun observation(capabilities: NetworkCapabilities, interfaceName: String?): Observation {
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val transport = listOfNotNull(
            "VPN".takeIf { vpn },
            "Wi-Fi".takeIf { wifi },
            "移动网络".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            "有线网络".takeIf { ethernet },
        ).joinToString("＋").ifBlank { "其他网络" }
        return Observation(transport, wifi, internet,
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), interfaceName,
            usable = internet || wifi || vpn || ethernet)
    }

    private fun publish() {
        // IMS/MMS-only cellular networks do not provide an App data route.
        val usable = networks.filterValues { it.usable }
        val signature = defaultNetwork to usable
        if (signature == lastSignature && state.value.observed) return
        lastSignature = signature
        val default = defaultNetwork?.let(usable::get)
        mutableState.value = PhoneNetworkState(observed = true, available = usable.isNotEmpty(),
            internetValidated = default?.let { it.internet && it.validated } == true,
            transportLabel = default?.transport ?: usable.values.map { it.transport }.distinct()
                .joinToString("／").ifBlank { "无默认网络" },
            wifiAvailable = usable.values.any { it.wifi }, revision = state.value.revision + 1)
        Diagnostics.event("network.phone_state", fields = mapOf(
            "revision" to state.value.revision, "available" to state.value.available,
            "internetValidated" to state.value.internetValidated, "wifiAvailable" to state.value.wifiAvailable,
            "transport" to state.value.transportLabel, "defaultNetwork" to defaultNetwork?.networkHandle,
        ), critical = true)
    }

    private fun logChange(event: String, network: Network, isDefault: Boolean) {
        val current = networks[network]
        Diagnostics.event("network.$event", fields = mapOf(
            "networkHandle" to network.networkHandle, "defaultCallback" to isDefault,
            "transport" to current?.transport, "internet" to current?.internet,
            "validated" to current?.validated, "interface" to current?.interfaceName,
        ), critical = event == "lost")
    }

    private fun unavailable() {
        mutableState.value = PhoneNetworkState(revision = state.value.revision + 1)
    }

    override fun close() {
        if (closed) return
        closed = true
        val dispose = Runnable {
            if (allRegistered) runCatching { manager?.unregisterNetworkCallback(allCallback) }
            if (defaultRegistered) runCatching { manager?.unregisterNetworkCallback(defaultCallback) }
            allRegistered = false
            defaultRegistered = false
            networks.clear()
            main.removeCallbacksAndMessages(null)
        }
        if (Looper.myLooper() == main.looper) dispose.run() else main.post(dispose)
    }

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }

    private data class Observation(val transport: String, val wifi: Boolean, val internet: Boolean,
        val validated: Boolean, val interfaceName: String?, val usable: Boolean)
}
