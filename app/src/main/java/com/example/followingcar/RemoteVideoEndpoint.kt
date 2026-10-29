package com.example.followingcar

import java.net.URI
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A receive-only HTTPS origin. Identity callbacks return values already loaded on an IO thread. */
internal class RemoteVideoEndpoint(
    val host: String,
    val port: Int = 8443,
    val credentialId: String,
    val privateKey: () -> PrivateKey?,
    val certificateChain: () -> Array<X509Certificate>?,
) {
    init {
        require(isRemoteVideoHost(host)) { "远程视频主机地址无效" }
        require(port in 1..65535) { "远程视频端口无效" }
        require(credentialId.isNotBlank()) { "远程视频凭据尚未配置" }
    }

    val previewUrl: String = "https://$host:$port/player/"
    val identity: String = "$host:$port/$credentialId"

    fun acceptsClientCertificate(requestHost: String?, requestPort: Int): Boolean =
        requestHost.equals(host, ignoreCase = true) && requestPort == port

    fun allowsNavigation(url: String): Boolean = url == "about:blank" ||
        parsedAllowedUrl(url)?.rawPath in setOf("/player/", "/player/index.html")

    fun allowsResource(url: String): Boolean {
        val path = parsedAllowedUrl(url)?.rawPath ?: return false
        return path in setOf("/player/", "/player/index.html", "/player/player.js", "/player/whep.js",
            "/player/player.css", "/rgbd/whep") || WHEP_SESSION.matches(path)
    }

    private fun parsedAllowedUrl(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.host.equals(host, ignoreCase = true) &&
            (if (it.port == -1) 443 else it.port) == port && it.rawUserInfo == null &&
            it.rawQuery == null && it.rawFragment == null
    }

    override fun toString(): String = "RemoteVideoEndpoint(host=$host, port=$port)"

    private companion object {
        val WHEP_SESSION = Regex("/rgbd/whep/[a-zA-Z0-9_-]{1,128}")
    }
}

internal fun isRemoteVideoHost(host: String): Boolean {
    if (host.isBlank() || host.length > 253 || host != host.trim() || host.endsWith('.')) return false
    val labels = host.split('.')
    if (labels.size < 2) return false
    if (labels.all { label -> label.all { it in '0'..'9' } }) {
        return labels.size == 4 && labels.all { it.length in 1..3 && it.toIntOrNull() in 0..255 }
    }
    return labels.all { it.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")) }
}
