package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class RemoteVideoEndpointTest {
    private fun endpoint(host: String = "car.nujabes.top", port: Int = 8443) =
        RemoteVideoEndpoint(host, port, "test-certificate", { null }, { null })

    @Test fun certificateIsOnlyOfferedToConfiguredTlsOrigin() {
        val endpoint = endpoint()
        assertTrue(endpoint.acceptsClientCertificate("car.nujabes.top", 8443))
        assertTrue(endpoint.acceptsClientCertificate("CAR.NUJABES.TOP", 8443))
        assertFalse(endpoint.acceptsClientCertificate("car.nujabes.top.attacker.test", 8443))
        assertFalse(endpoint.acceptsClientCertificate("car.nujabes.top", 443))
        assertFalse(endpoint.acceptsClientCertificate(null, 8443))
    }

    @Test fun navigationCannotEscapePlayerOrDowngradeTls() {
        val endpoint = endpoint()
        assertEquals("https://car.nujabes.top:8443/player/", endpoint.previewUrl)
        assertTrue(endpoint.allowsNavigation(endpoint.previewUrl))
        assertTrue(endpoint.allowsNavigation("https://car.nujabes.top:8443/player/index.html"))
        assertTrue(endpoint.allowsNavigation("about:blank"))
        listOf("http://car.nujabes.top:8443/player/", "https://car.nujabes.top/player/",
            "https://attacker.test:8443/player/", "https://user@car.nujabes.top:8443/player/",
            "https://car.nujabes.top:8443/player/?token=secret", "https://car.nujabes.top:8443/player/#fragment",
            "https://car.nujabes.top:8443/rgbd/whep", "data:text/html,hello", "file:///player/index.html",
            "https://car.nujabes.top:8443/player/../admin", "https://car.nujabes.top:8443/player/%2e%2e/admin")
            .forEach { assertFalse(it, endpoint.allowsNavigation(it)) }
    }

    @Test fun onlyPlayerAssetsAndWhepResourcesAreAllowed() {
        val endpoint = endpoint()
        listOf("/player/", "/player/index.html", "/player/player.js", "/player/whep.js", "/player/player.css",
            "/rgbd/whep", "/rgbd/whep/ae2a5b66-9810-4871-afdc-d7ec344572b9").forEach {
            assertTrue(it, endpoint.allowsResource("https://car.nujabes.top:8443$it"))
        }
        listOf("/api/config/get", "/rgbd/whip", "/other/whep", "/rgbd/whep/", "/rgbd/whep/id/child",
            "/rgbd/whep/%2e%2e", "/player/other.js", "/player/../../../admin").forEach {
            assertFalse(it, endpoint.allowsResource("https://car.nujabes.top:8443$it"))
        }
        assertFalse(endpoint.allowsResource("https://elsewhere.test:8443/player/player.js"))
    }

    @Test fun hostAndPortAreValidatedWithoutAllowingCredentialsOrPaths() {
        listOf("", " car.nujabes.top", "car.nujabes.top ", "car.nujabes.top.", "car.nujabes.top:443",
            "car.nujabes.top/player", "user@car.nujabes.top", "localhost", "256.1.1.1", "1.2.3",
            "a..example.com", "-bad.example.com").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { endpoint(value) }
        }
        assertEquals("https://43.162.101.137:8443/player/", endpoint("43.162.101.137").previewUrl)
        assertThrows(IllegalArgumentException::class.java) { endpoint(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { endpoint(port = 65536) }
    }
}
