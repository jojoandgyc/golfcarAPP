package com.example.followingcar

/** Fixed, read-only command. Execute only after the existing pinned SSH authentication succeeds. */
internal const val BOARD_LAN_ADDRESSES_COMMAND = "ip -o -4 address show dev wlan0"

/**
 * Readback supplies address hints, not a new identity proof. The caller must additionally verify
 * the phone's actual Wi-Fi on-link prefix and authenticate the pinned SSH key at each candidate.
 * No shell text, interface name or arguments come from these returned values.
 */
internal fun parseBoardLanAddresses(stdout: String): List<String> {
    if (stdout.length > 8 * 1024 || stdout.toByteArray(Charsets.UTF_8).size > 8 * 1024) return emptyList()
    val record = Regex("^\\s*[0-9]+:\\s+wlan0\\s+inet\\s+([0-9.]+)/([0-9]{1,2})(?:\\s.*)?$")
    val result = linkedSetOf<String>()
    for (line in stdout.lineSequence()) {
        val match = record.matchEntire(line) ?: continue
        val prefix = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..32 } ?: continue
        val parts = match.groupValues[1].split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith('0')) }) continue
        val octets = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } }
        if (octets.any { it == null }) continue
        val bytes = octets.filterNotNull()
        // Unspecified/this-network, loopback, link-local, multicast and reserved blocks are not
        // usable DHCP/WLAN peers. The actual on-link check remains the caller's responsibility.
        if (bytes[0] == 0 || bytes[0] == 127 || bytes[0] >= 224 || (bytes[0] == 169 && bytes[1] == 254)) continue
        val value = bytes.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        if (prefix <= 30) {
            val hostMask = (1L shl (32 - prefix)) - 1
            val host = value and hostMask
            if (host == 0L || host == hostMask) continue
        }
        result += bytes.joinToString(".")
        if (result.size == 4) break
    }
    return result.toList()
}
