package com.example.followingcar

/** Ties only a user-selected Direct SSH connection to its confirmed GO group. */
internal data class DirectNetworkLease(val networkName: String, val ownerAddress: String) {
    override fun toString(): String = "DirectNetworkLease(confirmed=true)"
    fun isLost(state: WifiDirectState): Boolean {
        if (!state.supported || !state.permissionGranted || !state.wifiEnabled) return true
        // An in-flight refresh is not evidence of a disconnected radio.
        if (!state.groupInfoKnown) return false
        return !state.groupExists || !state.isGroupOwner ||
            state.networkName != networkName || state.ownerAddress != ownerAddress
    }

    companion object {
        fun from(state: WifiDirectState): DirectNetworkLease? {
            if (!state.supported || !state.permissionGranted || !state.wifiEnabled || !state.groupInfoKnown || state.confirmationRequired ||
                !state.groupExists || !state.isGroupOwner || state.networkName.isNullOrBlank() || state.ownerAddress.isNullOrBlank()) return null
            return DirectNetworkLease(state.networkName, state.ownerAddress)
        }
    }
}
