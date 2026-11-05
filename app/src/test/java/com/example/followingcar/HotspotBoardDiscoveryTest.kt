package com.example.followingcar

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HotspotBoardDiscoveryTest {
    private val link = LocalDiscoveryLink("swlan0", 19, ip("192.168.57.1"), 24)
    private val instance = DnsName(listOf("followingcar", "_followingcar", "_tcp", "local"))
    private val target = DnsName(listOf("followingcar-rk", "local"))
    private val query = MdnsQuestion(BOARD_MDNS_SERVICE, 12)
    private fun ip(value: String) = InetAddress.getByName(value)
    private fun observation(name: String = "swlan0", up: Boolean = true, loop: Boolean = false,
        point: Boolean = false, address: String = "192.168.57.1", prefix: Int = 24) =
        HotspotInterfaceObservation(name, 19, up, loop, point, listOf(LocalAddressPrefix(ip(address), prefix)))

    @Test fun systemHotspotWithoutWifiNetworkStillSuppliesSourceBoundEndpoint() {
        val links = selectHotspotDiscoveryLinks(listOf(observation(), observation("rmnet_data0", address = "10.2.3.4")))
        assertEquals(listOf(link), links)
        val endpoint = selectLocalEndpoint("192.168.57.12", 22, links)!!
        assertNull(endpoint.network)
        assertEquals(link.address, endpoint.localAddress)
        assertTrue(localEndpointIsCurrent(endpoint, links))
        assertNull(selectLocalEndpoint("172.16.16.82", 22, links))
        assertNull(selectLocalEndpoint("board.example.com", 22, links))
    }

    @Test fun interfaceNameAloneCannotAuthorizeApAndOnlyTwoCurrentInterfacesAreSelected() {
        val invalid = listOf(observation(up = false), observation(loop = true), observation(point = true),
            observation("tun0"), observation("rmnet0"), observation("eth0"), observation("swlan0evil"),
            observation(address = "8.8.8.8"), observation(address = "169.254.2.1"), observation(prefix = 0))
        assertTrue(selectHotspotDiscoveryLinks(invalid).isEmpty())
        assertTrue(selectHotspotDiscoveryLinks(listOf(observation()), setOf("swlan0")).isEmpty())
        assertEquals(2, selectHotspotDiscoveryLinks(listOf(observation("swlan0"), observation("ap0"), observation("wlan1"))).size)
    }

    @Test fun endpointMustStillMatchItsSpecificSourcePrefixAndAddressFamily() {
        val endpoint = selectLocalEndpoint("192.168.57.12", 22, listOf(link))!!
        assertFalse(localEndpointIsCurrent(endpoint, emptyList()))
        assertFalse(localEndpointIsCurrent(endpoint, listOf(link.copy(address = ip("192.168.57.2")))))
        assertFalse(localEndpointIsCurrent(endpoint.copy(localAddress = null), listOf(link)))
        assertFalse(localEndpointIsCurrent(endpoint, listOf(link.copy(interfaceIndex = 20))))
        assertNull(selectLocalEndpoint("fe80::2%19", 22, listOf(link)))
        val ipv6 = link.copy(address = ip("fe80::1"), prefixLength = 64)
        assertNotNull(selectLocalEndpoint("fe80::2%19", 22, listOf(ipv6)))
        assertNull(selectLocalEndpoint("fe80::2%20", 22, listOf(ipv6)))
        assertNull(selectLocalEndpoint("192.168.57.1", 22, listOf(link)))
        assertNull(selectLocalEndpoint("192.168.57.255", 22, listOf(link)))
    }

    @Test fun sourceAddressParticipatesInEndpointDeduplication() {
        val first = LocalBoardEndpoint("192.168.57.12", localAddress = link.address)
        assertEquals(2, orderedLocalEndpoints(listOf(first, first,
            first.copy(localAddress = ip("192.168.57.2")))).size)
    }

    @Test fun queryUsesTheServicePtrAndAnEchoableNonzeroTransactionId() {
        val bytes = hotspotMdnsQuery(0x1234, query)
        assertEquals(0x12, bytes[0].toInt() and 255)
        assertEquals(0x34, bytes[1].toInt() and 255)
        assertEquals(1, bytes[5].toInt())
        assertEquals(listOf(0, 12, 0, 1), bytes.takeLast(4).map { it.toInt() and 255 })
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("_followingcar"))
    }

    @Test fun matchingSrvTxtAndOnLinkAddressProduceOnlyAnUntrustedSshHint() {
        val records = HotspotMdnsRecords(link)
        val parsed = parseHotspotMdnsReply(packet(), 7)!!
        records.accept(parsed, ip("192.168.57.12"), 100)
        val candidate = records.candidates(100).single()
        assertEquals("192.168.57.12", candidate.endpoint.host)
        assertEquals("hotspot_mdns", candidate.endpoint.source)
        assertEquals(link.address, candidate.endpoint.localAddress)
        assertNull(candidate.endpoint.network)
        assertEquals(10_100L, candidate.expiresAtMillis)
    }

    @Test fun splitResponsesRequestMissingSrvTxtAndAddressInsteadOfGuessing() {
        val records = HotspotMdnsRecords(link)
        records.accept(parseHotspotMdnsReply(packet(listOf(ptr())), 7)!!, ip("192.168.57.12"), 0)
        assertEquals(setOf(12, 16, 33), records.questions(0).map { it.type }.toSet())
        assertTrue(records.candidates(0).isEmpty())
        records.accept(parseHotspotMdnsReply(packet(listOf(srv(), txt())), 7)!!, ip("192.168.57.12"), 100)
        assertTrue(records.questions(100).contains(MdnsQuestion(target, 1)))
        records.accept(parseHotspotMdnsReply(packet(listOf(address())), 7)!!, ip("192.168.57.12"), 200)
        assertEquals(1, records.candidates(200).size)
    }

    @Test fun incorrectIdentityIncompleteRecordsCrossSenderMixingAndOffLinkAddressesAreRejected() {
        val invalid = listOf(listOf(ptr(), srv(), address()), listOf(ptr(), txt(), address()),
            listOf(ptr(), srv(), txt("wrong-board"), address()),
            listOf(ptr(), srv(), txt(fingerprint = "wrong-key"), address()),
            listOf(ptr(), srv(), txt(), address("117.50.34.249")),
            listOf(ptr(), srv(), txt(), address("192.168.57.1")))
        for (value in invalid) {
            val records = HotspotMdnsRecords(link)
            records.accept(parseHotspotMdnsReply(packet(value), 7)!!, ip("192.168.57.12"), 0)
            assertTrue(records.candidates(0).isEmpty())
        }
        val mixed = HotspotMdnsRecords(link)
        mixed.accept(parseHotspotMdnsReply(packet(listOf(ptr(), srv())), 7)!!, ip("192.168.57.12"), 0)
        mixed.accept(parseHotspotMdnsReply(packet(listOf(txt(), address())), 7)!!, ip("192.168.57.13"), 0)
        assertTrue(mixed.candidates(0).isEmpty())
        mixed.accept(parseHotspotMdnsReply(packet(), 7)!!, ip("10.0.0.1"), 0)
        assertTrue(mixed.candidates(0).isEmpty())
    }

    @Test fun zeroTtlRemovesEvidenceExpiryIsEnforcedAndHugeTtlCannotExtendCache() {
        val records = HotspotMdnsRecords(link)
        records.accept(parseHotspotMdnsReply(packet(ttl = 1), 7)!!, ip("192.168.57.12"), 0)
        assertEquals(1, records.candidates(999).size)
        assertTrue(records.candidates(1_000).isEmpty())
        records.accept(parseHotspotMdnsReply(packet(ttl = 0xffffffffL), 7)!!, ip("192.168.57.12"), 2_000)
        assertEquals(32_000L, records.candidates(2_000).single().expiresAtMillis)
        records.accept(parseHotspotMdnsReply(packet(ttl = 0), 7)!!, ip("192.168.57.12"), 2_001)
        assertTrue(records.candidates(2_001).isEmpty())
    }

    @Test fun malformedCountsFlagsLengthsPointersAndDuplicateTxtFailTheEntireDatagram() {
        val valid = packet()
        fun altered(index: Int, value: Int) = valid.copyOf().also { it[index] = value.toByte() }
        val invalid = listOf(ByteArray(11), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            altered(0, 1), altered(2, 0), altered(2, 0x86), altered(4, 0xff), altered(6, 0xff),
            altered(12, 0xc0), // Self/forward pointer from the first question is forbidden.
            packet(listOf(ptr(), srv(), txtRaw(listOf("id=$BOARD_DISCOVERY_ID", "ID=$BOARD_DISCOVERY_ID")), address())),
            ByteArray(HOTSPOT_MDNS_MAX_PACKET + 1))
        invalid.forEachIndexed { index, bytes -> assertNull("Malformed case $index", parseHotspotMdnsReply(bytes, 7)) }
    }

    @Test fun validBackwardCompressionWorksButPointerIntoNonNameBytesDoesNot() {
        val compressed = ptr().copy(ownerBytes = byteArrayOf(0xc0.toByte(), 12))
        assertNotNull(parseHotspotMdnsReply(packet(listOf(compressed, srv(), txt(), address())), 7))
        val invalid = compressed.copy(ownerBytes = byteArrayOf(0xc0.toByte(), 13))
        assertNull(parseHotspotMdnsReply(packet(listOf(invalid, srv(), txt(), address())), 7))
    }

    @Test fun concurrentDiscoverySharesOneFlightAndCacheIsInvalidatedByIpChangeOrExpiry() = runBlocking {
        var now = 100L
        var current = listOf(link)
        val flight = LocalDiscoveryFlight { now }
        val calls = AtomicInteger()
        val endpoint = selectLocalEndpoint("192.168.57.12", 22, current)!!
        suspend fun discover() = flight.discover({ current }) {
            calls.incrementAndGet(); delay(30)
            LocalDiscoveryBatch(listOf(endpoint), now + 1_000)
        }
        val first = async { discover() }
        val second = async { discover() }
        assertEquals(first.await(), second.await())
        assertEquals(1, calls.get())
        now = 1_101
        discover()
        assertEquals(2, calls.get())
        current = listOf(link.copy(address = ip("192.168.58.1")))
        assertTrue(discover().isEmpty())
        assertEquals(3, calls.get())
    }

    @Test fun permissionFailureReturnsNoCandidatesWithoutNetworkMutation() = runBlocking {
        val calls = AtomicInteger()
        val result = discoverHotspotBoards(listOf(link), clock = { 0L }, socketFactory = {
            calls.incrementAndGet(); throw SecurityException("denied")
        })
        assertTrue(result.isEmpty())
        assertEquals(1, calls.get())
    }

    @Test fun cancellingDiscoveryClosesItsBlockedSocket() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val socket = object : HotspotMdnsSocket {
            override fun send(query: ByteArray) = Unit
            override fun receive(): HotspotMdnsDatagram? { entered.complete(Unit); released.await(); return null }
            override fun close() { closed.set(true); released.countDown() }
        }
        val job = launch { discoverHotspotBoards(listOf(link), clock = { System.nanoTime() / 1_000_000L }, socketFactory = { socket }) }
        withTimeout(1_000) { entered.await() }
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(closed.get())
    }

    private data class Record(val name: DnsName, val type: Int, val data: ByteArray,
        val ownerBytes: ByteArray? = null)
    private fun ptr() = Record(BOARD_MDNS_SERVICE, 12, wireName(instance))
    private fun srv() = Record(instance, 33, byteArrayOf(0, 0, 0, 0, 0, 22) + wireName(target))
    private fun txt(id: String = BOARD_DISCOVERY_ID, fingerprint: String = BOARD_DISCOVERY_SSH_FINGERPRINT) =
        txtRaw(listOf("id=$id", "sshfp=$fingerprint"))
    private fun txtRaw(entries: List<String>) = Record(instance, 16, entries.flatMap {
        val bytes = it.toByteArray(); listOf(bytes.size.toByte()) + bytes.toList()
    }.toByteArray())
    private fun address(value: String = "192.168.57.12") = Record(target, 1, ip(value).address)
    private fun wireName(value: DnsName): ByteArray = value.labels.flatMap {
        val bytes = it.toByteArray(); listOf(bytes.size.toByte()) + bytes.toList()
    }.toByteArray() + byteArrayOf(0)
    private fun packet(records: List<Record> = listOf(ptr(), srv(), txt(), address()), ttl: Long = 10): ByteArray {
        val out = ByteArrayOutputStream()
        fun word(value: Int) { out.write(value ushr 8); out.write(value and 255) }
        word(7); word(0x8400); word(1); word(records.size); word(0); word(0)
        out.write(wireName(BOARD_MDNS_SERVICE)); word(12); word(1)
        records.forEach { record ->
            out.write(record.ownerBytes ?: wireName(record.name)); word(record.type); word(1)
            word((ttl ushr 16).toInt()); word(ttl.toInt()); word(record.data.size); out.write(record.data)
        }
        return out.toByteArray()
    }
}
