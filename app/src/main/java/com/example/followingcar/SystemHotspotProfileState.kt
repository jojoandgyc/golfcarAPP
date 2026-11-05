package com.example.followingcar

/** Display metadata only. The password never enters saved Compose state or diagnostics. */
internal data class SystemHotspotProfileState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val configured: Boolean = false,
    val ssid: String = "",
    val error: String? = null,
    val savedRevision: Long = 0,
) {
    override fun toString() = "SystemHotspotProfileState(loading=$loading, saving=$saving, configured=$configured)"
}
