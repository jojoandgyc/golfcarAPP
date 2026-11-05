package com.example.followingcar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class LocalDiscoveryLink(
    val interfaceName: String, val interfaceIndex: Int, val address: InetAddress,
    val prefixLength: Int, val network: Network? = null,
)

internal data class HotspotInterfaceObservation(
    val name: String, val index: Int, val up: Boolean, val loopback: Boolean,
    val pointToPoint: Boolean, val addresses: List<LocalAddressPrefix>,
)

/** Name is an allowlist, never evidence of a running hotspot without its current IP/prefix. */
internal fun selectHotspotDiscoveryLinks(observations: List<HotspotInterfaceObservation>,
    registeredInterfaces: Set<String> = emptySet()): List<LocalDiscoveryLink> = observations
    .filter { it.up && !it.loopback && !it.pointToPoint && it.index > 0 &&
        it.name !in registeredInterfaces && it.name.matches(Regex("(?:(?:wlan|swlan|softap|ap)[0-9]{1,2}|p2p(?:[0-9]{1,2}|-wlan[0-9]{1,2}-[0-9]{1,2}))")) }
    .sortedBy { if (it.name.startsWith("swlan") || it.name.startsWith("ap") || it.name.startsWith("softap")) 0 else 1 }
    .mapNotNull { device -> device.addresses.firstOrNull {
        it.address is Inet4Address && privateIpv4(it.address) && it.prefixLength in 8..30
    }?.let { LocalDiscoveryLink(device.name, device.index, it.address, it.prefixLength) } }
    .take(2)

private fun privateIpv4(address: InetAddress): Boolean {
    val bytes = address.address.map { it.toInt() and 255 }
    return bytes.size == 4 && (bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
        bytes[0] == 192 && bytes[1] == 168)
}

/** All operations are observations: no WifiManager/network request or process-wide binding. */
@Suppress("DEPRECATION")
internal fun currentLocalDiscoveryLinks(context: Context): List<LocalDiscoveryLink> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val registered = runCatching { manager?.allNetworks.orEmpty().take(16).flatMap { network ->
        val capabilities = manager?.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
        val properties = manager.getLinkProperties(network) ?: return@flatMap emptyList()
        val name = properties.interfaceName ?: return@flatMap emptyList()
        val device = NetworkInterface.getByName(name) ?: return@flatMap emptyList()
        if (!device.isUp || device.isLoopback || device.isPointToPoint) return@flatMap emptyList()
        properties.linkAddresses.take(16).filter { it.prefixLength in 1..it.address.address.size * 8 &&
            !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isMulticastAddress }
            .map { LocalDiscoveryLink(name, device.index, it.address, it.prefixLength, network) }
    } }.getOrDefault(emptyList())
    val devices = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().take(64).mapNotNull { device ->
        runCatching { HotspotInterfaceObservation(device.name, device.index, device.isUp, device.isLoopback,
            device.isPointToPoint, device.interfaceAddresses.take(16).map {
                LocalAddressPrefix(it.address, it.networkPrefixLength.toInt()) }) }.getOrNull()
    } }.getOrDefault(emptyList())
    return registered + selectHotspotDiscoveryLinks(devices, registered.map { it.interfaceName }.toSet())
}

/** Literal parsing only; an untrusted hostname must never invoke system/cellular DNS. */
internal fun localEndpointAddress(host: String): InetAddress? = runCatching {
    val literal = host.substringBefore('%')
    if (':' in literal) {
        require(literal.length <= 45 && literal.matches(Regex("[0-9a-fA-F:.]+")))
        if ('%' in host) require(host.substringAfter('%').matches(Regex("[1-9][0-9]{0,8}")))
        InetAddress.getByName(literal).also { require(it is Inet6Address) }
    } else {
        val parts = literal.split('.')
        require('%' !in host && parts.size == 4 && parts.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
        InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
    }
}.getOrNull()

private fun onLink(host: String, address: InetAddress, link: LocalDiscoveryLink): Boolean {
    if (address.address.size != link.address.address.size) return false
    if (address is Inet6Address && address.isLinkLocalAddress && '%' in host &&
        host.substringAfter('%').toIntOrNull() != link.interfaceIndex) return false
    return isOnLocalDiscoveryLink(address, listOf(LocalAddressPrefix(link.address, link.prefixLength)))
}

internal fun selectLocalEndpoint(host: String, port: Int, links: List<LocalDiscoveryLink>,
    source: String = "saved"): LocalBoardEndpoint? {
    if (port !in 1..65535) return null
    val address = localEndpointAddress(host) ?: return null
    if (links.any { it.address.address.contentEquals(address.address) }) return null
    val link = links.firstOrNull { onLink(host, address, it) } ?: return null
    val scoped = localDiscoveryAddress(address, link.interfaceIndex) ?: return null
    return LocalBoardEndpoint(scoped, port, source, link.network,
        localAddress = link.address.takeIf { link.network == null },
        localInterfaceIndex = link.interfaceIndex.takeIf { link.network == null })
}

internal fun localEndpointIsCurrent(endpoint: LocalBoardEndpoint, links: List<LocalDiscoveryLink>): Boolean {
    val address = localEndpointAddress(endpoint.host) ?: return false
    if (endpoint.port !in 1..65535 || links.any { it.address.address.contentEquals(address.address) }) return false
    return links.any { link ->
        val routeMatches = if (endpoint.network != null) endpoint.network == link.network && endpoint.localAddress == null
            else link.network == null && endpoint.localAddress != null && endpoint.localAddress == link.address &&
                endpoint.localInterfaceIndex == link.interfaceIndex
        routeMatches && onLink(endpoint.host, address, link)
    }
}

internal const val HOTSPOT_MDNS_MAX_PACKET = 4096
internal val BOARD_MDNS_SERVICE = DnsName(listOf("_followingcar", "_tcp", "local"))
internal data class DnsName(val labels: List<String>)
internal data class MdnsQuestion(val name: DnsName, val type: Int)
internal data class MdnsRecord(val name: DnsName, val type: Int, val ttl: Long,
    val target: DnsName? = null, val port: Int? = null,
    val attributes: Map<String, String>? = null, val address: InetAddress? = null)
internal data class MdnsReply(val questions: List<MdnsQuestion>, val records: List<MdnsRecord>)

/** RFC 6762 5.1/6.7 one-shot query: ephemeral source port, copied ID, unicast response. */
internal fun hotspotMdnsQuery(id: Int, question: MdnsQuestion): ByteArray {
    require(id in 1..65535 && question.type in setOf(1, 12, 16, 33))
    val output = ByteArrayOutputStream()
    fun word(value: Int) { output.write(value ushr 8); output.write(value and 255) }
    word(id); word(0); word(1); repeat(3) { word(0) }
    var total = 1
    question.name.labels.forEach { label ->
        val bytes = label.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..63)
        total += bytes.size + 1
        require(total <= 255)
        output.write(bytes.size); output.write(bytes)
    }
    output.write(0); word(question.type); word(1)
    return output.toByteArray()
}

/** Strict bounded decoder. A malformed datagram cannot supply even a partial candidate. */
internal fun parseHotspotMdnsReply(bytes: ByteArray, id: Int): MdnsReply? = runCatching {
    require(bytes.size in 12..HOTSPOT_MDNS_MAX_PACKET)
    fun word(at: Int): Int { require(at >= 0 && at + 2 <= bytes.size)
        return ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255) }
    require(word(0) == id && word(2) and 0x8000 != 0 && word(2) and 0x7a0f == 0)
    val questionCount = word(4)
    val recordCount = word(6) + word(8) + word(10)
    require(questionCount in 1..8 && recordCount in 1..64)
    val nameOffsets = mutableSetOf<Int>()
    fun name(start: Int): Pair<DnsName, Int> {
        var cursor = start
        var end = -1
        var length = 1
        val visited = mutableSetOf<Int>()
        val labels = mutableListOf<String>()
        while (true) {
            require(cursor in bytes.indices && visited.add(cursor) && visited.size <= 128)
            val size = bytes[cursor].toInt() and 255
            if (size and 0xc0 == 0xc0) {
                val pointer = word(cursor) and 0x3fff
                require(pointer in 12 until cursor && pointer in nameOffsets)
                nameOffsets += cursor
                if (end < 0) end = cursor + 2
                cursor = pointer
                continue
            }
            require(size and 0xc0 == 0 && size <= 63)
            nameOffsets += cursor
            cursor++
            if (size == 0) return DnsName(labels) to if (end < 0) cursor else end
            require(cursor + size <= bytes.size && labels.size < 32)
            length += size + 1
            require(length <= 255)
            val label = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, cursor, size)).toString()
            require(label.none { it.code < 32 || it.code == 127 })
            labels += label.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
            cursor += size
        }
        @Suppress("UNREACHABLE_CODE") error("invalid name")
    }
    var cursor = 12
    val questions = List(questionCount) {
        val (name, end) = name(cursor)
        cursor = end
        val type = word(cursor)
        require(word(cursor + 2) == 1)
        cursor += 4
        MdnsQuestion(name, type)
    }
    val records = mutableListOf<MdnsRecord>()
    repeat(recordCount) {
        val (owner, end) = name(cursor)
        cursor = end
        val type = word(cursor)
        require(word(cursor + 2) == 1) // Legacy unicast cannot set the cache-flush bit.
        val ttl = ((word(cursor + 4).toLong() shl 16) or word(cursor + 6).toLong())
        val size = word(cursor + 8)
        cursor += 10
        val limit = cursor + size
        require(limit <= bytes.size)
        when (type) {
            1 -> { require(size == 4)
                records += MdnsRecord(owner, type, ttl, address = InetAddress.getByAddress(bytes.copyOfRange(cursor, limit))) }
            12 -> { val (target, next) = name(cursor); require(next == limit)
                records += MdnsRecord(owner, type, ttl, target = target) }
            33 -> { require(size >= 7); word(cursor); word(cursor + 2)
                val port = word(cursor + 4)
                val (target, next) = name(cursor + 6); require(next == limit)
                records += MdnsRecord(owner, type, ttl, target = target, port = port) }
            16 -> {
                require(size in 1..1300)
                val attributes = mutableMapOf<String, String>()
                var at = cursor
                while (at < limit) {
                    val count = bytes[at++].toInt() and 255
                    require(at + count <= limit && attributes.size < 16)
                    if (count > 0) {
                        val entry = bytes.copyOfRange(at, at + count)
                        val equals = entry.indexOf('='.code.toByte())
                        val keyBytes = if (equals < 0) entry else entry.copyOfRange(0, equals)
                        require(keyBytes.isNotEmpty() && keyBytes.all { (it.toInt() and 255) in 0x20..0x7e })
                        val key = keyBytes.toString(Charsets.US_ASCII).lowercase(java.util.Locale.ROOT)
                        require(key !in attributes)
                        val value = if (equals < 0) "" else entry.copyOfRange(equals + 1, entry.size).toString(Charsets.UTF_8)
                        attributes[key] = value
                    }
                    at += count
                }
                records += MdnsRecord(owner, type, ttl, attributes = attributes)
            }
        }
        cursor = limit
    }
    require(cursor == bytes.size)
    MdnsReply(questions, records)
}.getOrNull()

internal data class HotspotMdnsCandidate(val endpoint: LocalBoardEndpoint, val expiresAtMillis: Long)
internal class HotspotMdnsRecords(private val link: LocalDiscoveryLink) {
    private data class Entry(val sender: InetAddress, val record: MdnsRecord, val expires: Long)
    private val entries = mutableListOf<Entry>()
    fun accept(reply: MdnsReply, sender: InetAddress, nowMillis: Long) {
        if (!isOnLocalDiscoveryLink(sender, listOf(LocalAddressPrefix(link.address, link.prefixLength)))) return
        entries.removeAll { it.expires <= nowMillis }
        for (record in reply.records) {
            val value = record.copy(ttl = 0)
            entries.removeAll { it.sender == sender && it.record.copy(ttl = 0) == value }
            if (record.ttl > 0 && entries.size < 128 &&
                (sender in entries.map { it.sender } || entries.map { it.sender }.distinct().size < 8))
                entries += Entry(sender, record, nowMillis + minOf(record.ttl, 30L) * 1000)
        }
    }
    private fun instance(name: DnsName) = name.labels.size == 4 && name.labels.drop(1) == BOARD_MDNS_SERVICE.labels
    fun questions(nowMillis: Long): List<MdnsQuestion> {
        entries.removeAll { it.expires <= nowMillis }
        val queries = mutableListOf(MdnsQuestion(BOARD_MDNS_SERVICE, 12))
        entries.filter { it.record.type == 12 && it.record.name == BOARD_MDNS_SERVICE &&
            it.record.target?.let(::instance) == true }.take(6).forEach { ptr ->
            val name = ptr.record.target!!
            val matching = entries.filter { it.sender == ptr.sender }
            for (type in listOf(33, 16)) if (matching.none { it.record.name == name && it.record.type == type })
                queries += MdnsQuestion(name, type)
            matching.filter { it.record.name == name && it.record.type == 33 }.forEach { srv ->
                val target = srv.record.target!!
                if (target.labels.lastOrNull() == "local" && matching.none { it.record.name == target && it.record.type == 1 })
                    queries += MdnsQuestion(target, 1)
            }
        }
        return queries.distinct().take(16)
    }
    fun candidates(nowMillis: Long): List<HotspotMdnsCandidate> {
        entries.removeAll { it.expires <= nowMillis }
        val result = mutableListOf<HotspotMdnsCandidate>()
        entries.filter { it.record.type == 12 && it.record.name == BOARD_MDNS_SERVICE &&
            it.record.target?.let(::instance) == true }.forEach { ptr ->
            val name = ptr.record.target!!
            val matching = entries.filter { it.sender == ptr.sender }
            val srv = matching.filter { it.record.name == name && it.record.type == 33 }.singleOrNull() ?: return@forEach
            val txt = matching.filter { it.record.name == name && it.record.type == 16 }.singleOrNull() ?: return@forEach
            val target = srv.record.target!!
            if (srv.record.port !in 1..65535 || target.labels.lastOrNull() != "local") return@forEach
            val attributes = txt.record.attributes!!.mapValues { it.value.toByteArray(Charsets.UTF_8) }
            if (!matchingBoardAdvertisement(BOARD_DISCOVERY_SERVICE_TYPE, attributes)) return@forEach
            matching.filter { it.record.name == target && it.record.type == 1 }.forEach addresses@{ address ->
                val peer = address.record.address!!
                if (!isOnLocalDiscoveryLink(peer, listOf(LocalAddressPrefix(link.address, link.prefixLength)))) return@addresses
                result += HotspotMdnsCandidate(LocalBoardEndpoint(peer.hostAddress!!, srv.record.port!!,
                    "hotspot_mdns", localAddress = link.address, localInterfaceIndex = link.interfaceIndex),
                    minOf(ptr.expires, srv.expires, txt.expires, address.expires))
            }
        }
        return result.distinctBy { it.endpoint }.take(MAX_LOCAL_BOARD_CANDIDATES)
    }
}

internal data class HotspotMdnsDatagram(val bytes: ByteArray, val sender: InetAddress, val port: Int)
internal interface HotspotMdnsSocket : Closeable {
    fun send(query: ByteArray)
    fun receive(): HotspotMdnsDatagram?
}

private class InterfaceMdnsSocket(link: LocalDiscoveryLink) : HotspotMdnsSocket {
    private val socket = MulticastSocket(null)
    init {
        try {
            require(link.network == null && link.address is Inet4Address)
            val device = NetworkInterface.getByName(link.interfaceName) ?: error("Interface unavailable")
            require(device.isUp && device.index == link.interfaceIndex &&
                device.inetAddresses.toList().any { it == link.address })
            socket.bind(InetSocketAddress(link.address, 0))
            socket.networkInterface = device
            socket.timeToLive = 255
            socket.soTimeout = 120
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    override fun send(query: ByteArray) {
        socket.send(DatagramPacket(query, query.size, InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte())), 5353))
    }
    override fun receive(): HotspotMdnsDatagram? {
        val packet = DatagramPacket(ByteArray(HOTSPOT_MDNS_MAX_PACKET + 1), HOTSPOT_MDNS_MAX_PACKET + 1)
        return try {
            socket.receive(packet)
            HotspotMdnsDatagram(packet.data.copyOf(packet.length), packet.address, packet.port)
        } catch (_: SocketTimeoutException) { null }
    }
    override fun close() = socket.close()
}

/** Bounded legacy-unicast mDNS. Cancellation closes the actual socket, including a blocked receive. */
internal suspend fun discoverHotspotBoards(links: List<LocalDiscoveryLink>, budgetMillis: Long = 5_200L,
    clock: () -> Long = SystemClock::elapsedRealtime,
    socketFactory: (LocalDiscoveryLink) -> HotspotMdnsSocket = ::InterfaceMdnsSocket,
): List<HotspotMdnsCandidate> = coroutineScope {
    require(budgetMillis in 1..5_500)
    links.filter { it.network == null && it.address is Inet4Address }.take(2).map { link -> async(Dispatchers.IO) {
        val socket = try { socketFactory(link) } catch (_: Exception) { return@async emptyList() }
        try {
            currentCoroutineContext().ensureActive()
            suspendCancellableCoroutine<List<HotspotMdnsCandidate>> { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                try {
                    val id = SecureRandom().nextInt(65535) + 1
                    val records = HotspotMdnsRecords(link)
                    val sent = mutableMapOf<MdnsQuestion, Long>()
                    val deadline = clock() + budgetMillis
                    var datagrams = 0
                    var queries = 0
                    while (continuation.isActive && clock() < deadline && datagrams < 128 && queries < 32) {
                        val now = clock()
                        records.questions(now).forEach { question ->
                            if (queries < 32 && (sent[question]?.let { now - it >= 1_000L } != false)) {
                                socket.send(hotspotMdnsQuery(id, question)); sent[question] = now; queries++
                            }
                        }
                        val packet = socket.receive() ?: continue
                        datagrams++
                        if (packet.port != 5353 || packet.bytes.size > HOTSPOT_MDNS_MAX_PACKET) continue
                        val reply = parseHotspotMdnsReply(packet.bytes, id) ?: continue
                        val received = clock()
                        if (reply.questions.any { question -> sent[question]?.let { received - it in 0..2_000 } != true }) continue
                        records.accept(reply, packet.sender, received)
                        val candidates = records.candidates(received)
                        if (candidates.isNotEmpty()) {
                            if (continuation.isActive) continuation.resume(candidates)
                            return@suspendCancellableCoroutine
                        }
                    }
                    if (continuation.isActive) continuation.resume(records.candidates(clock()))
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally { socket.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        finally { socket.close() }
    } }.awaitAll().flatten()
}
