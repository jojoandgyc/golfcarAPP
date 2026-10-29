package com.example.followingcar

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Public routing metadata only. Server root credentials never belong in an App profile. */
data class RemoteProfile(
    val host: String,
    val controlPort: Int = DEFAULT_REMOTE_CONTROL_PORT,
    val videoPort: Int = DEFAULT_REMOTE_VIDEO_PORT,
    val clientCertificateSha256: String,
    val certificateExpiresAtEpochMillis: Long,
)

const val DEFAULT_REMOTE_HOST = "car.nujabes.top"
const val DEFAULT_REMOTE_CONTROL_PORT = 2443
const val DEFAULT_REMOTE_VIDEO_PORT = 8443

/** The factory uses the platform trust store; endpoint verification remains mandatory here. */
class RemoteTlsConfig(val sslSocketFactory: SSLSocketFactory, val serverName: String) {
    init { requireRemoteHost(serverName) }
    override fun toString(): String = "RemoteTlsConfig(serverName=$serverName)"
}

internal fun requireRemoteHost(value: String): String {
    require(value.length in 3..253 && value == value.trim()) { "远程服务器域名无效" }
    val host = value.lowercase(Locale.ROOT)
    val labels = host.split('.')
    require(labels.size >= 2 && labels.all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    } && labels.last().any { it in 'a'..'z' }) { "远程服务器必须使用有效域名" }
    return host
}

internal fun requireRemotePort(value: Int): Int = value.also {
    require(it in 1..65535) { "远程端口必须在 1 到 65535 之间" }
}

/**
 * The caller owns rawSocket before entering this function. Closing it interrupts both TCP
 * connect and TLS handshake, so cancellation never waits on a TLS/JSch monitor.
 */
internal fun openBoardSocket(
    rawSocket: Socket,
    host: String,
    port: Int,
    timeoutMillis: Int,
    tls: RemoteTlsConfig?,
): Socket {
    if (tls != null) {
        require(requireRemoteHost(host) == requireRemoteHost(tls.serverName)) {
            "远程连接地址与证书域名不一致"
        }
    }
    rawSocket.tcpNoDelay = true
    rawSocket.connect(InetSocketAddress(host, port), timeoutMillis)
    if (tls == null) return rawSocket

    val secure = tls.sslSocketFactory.createSocket(rawSocket, tls.serverName, port, true) as? SSLSocket
        ?: throw IOException("无法建立加密远程连接")
    try {
        secure.useClientMode = true
        secure.tcpNoDelay = true
        secure.enabledProtocols = secure.supportedProtocols.filter {
            it == "TLSv1.3" || it == "TLSv1.2"
        }.toTypedArray().also { require(it.isNotEmpty()) { "设备不支持安全 TLS 协议" } }
        secure.sslParameters = secure.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(tls.serverName))
        }
        val previousTimeout = secure.soTimeout
        secure.soTimeout = timeoutMillis
        secure.startHandshake()
        secure.soTimeout = previousTimeout
        return secure
    } catch (failure: Exception) {
        runCatching { secure.close() }
        throw failure
    }
}
