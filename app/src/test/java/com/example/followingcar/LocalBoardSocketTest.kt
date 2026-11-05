package com.example.followingcar

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.*
import org.junit.Test

class LocalBoardSocketTest {
    @Test fun localHotspotSourceIsBoundBeforeConnectingOnlyThisSocket() {
        val calls = mutableListOf<String>()
        val socket = object : Socket() {
            override fun setTcpNoDelay(on: Boolean) = Unit
            override fun bind(address: SocketAddress?) {
                calls += "bind:${(address as InetSocketAddress).address.hostAddress}:${address.port}"
            }
            override fun connect(address: SocketAddress?, timeout: Int) { calls += "connect:$timeout" }
        }
        assertSame(socket, openBoardSocket(socket, "192.168.201.95", 22, 4000, null,
            localAddress = InetAddress.getByName("192.168.201.231")))
        assertEquals(listOf("bind:192.168.201.231:0", "connect:4000"), calls)
    }

    @Test fun remoteTlsNeverBindsToTheLocalHotspotSource() {
        var bound = false
        val socket = object : Socket() {
            override fun setTcpNoDelay(on: Boolean) = Unit
            override fun bind(address: SocketAddress?) { bound = true }
            override fun connect(address: SocketAddress?, timeout: Int) { fail("No DNS or network operation is needed") }
        }
        val tls = RemoteTlsConfig(SSLSocketFactory.getDefault() as SSLSocketFactory, "relay.example.test")
        bindBoardSocketLocalRoute(socket, tls, null, InetAddress.getByName("192.168.201.231"))
        assertFalse(bound)
    }
}
