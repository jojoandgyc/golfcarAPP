package com.example.followingcar

/** Choosing a connection method is separate from connecting or changing a wireless group. */
internal enum class ConnectionMethod { LAN, WIFI_DIRECT, REMOTE }

/** Unconfirmed historical results never hide the settings needed to recover them. */
internal fun BoardUiState.connectionSelectionBlockReason(wirelessMutationPending: Boolean = false): String? = when {
    manualActive -> "手动控制尚未结束，请先停用手动控制。"
    followStopping -> "自动跟随正在停止，请等待停止结果。"
    followActive -> "自动跟随正在运行，请先停止跟随。"
    connected -> "当前已连接，请先断开 SSH。"
    pairingBusy -> "配对操作正在进行，请等待结束或取消等待。"
    videoServiceBusy -> "视频服务操作正在进行，请等待完成。"
    remoteProfileLoading -> "正在导入远程连接配置，请等待完成。"
    busy -> "连接或其他操作正在进行，请等待完成。"
    wirelessMutationPending -> "无线组操作正在进行，请等待结果确认。"
    else -> null
}

/**
 * Shared by the Connect button and the SSH entry point. This function only reads state;
 * it never confirms a lease, clears a control lock, or starts any network operation.
 * Credential text/port validation stays in the connection entry point.
 */
internal fun BoardUiState.connectionBlockReason(
    method: ConnectionMethod,
    wireless: WifiDirectState,
    wirelessMutationPending: Boolean = false,
): String? {
    connectionSelectionBlockReason(wirelessMutationPending)?.let { return it }
    if (method == ConnectionMethod.REMOTE) return when {
        remoteProfile == null -> "请先导入远程连接配置。"
        remoteProfileError != null -> "远程连接配置不可用，请重新导入有效配置。"
        else -> null
    }
    if (pairingUnconfirmed) return "上次配网结果尚未确认，请先恢复并核对连接；也可选择远程连接。"
    if (wireless.confirmationRequired) return "上次直连操作结果尚未确认，请先刷新无线状态；也可选择远程连接。"
    // A confirmed phone hotspot is a usable LAN, not permission to replace it with a Direct group.
    if (method == ConnectionMethod.LAN) return null
    return when {
        pairingHotspotActive -> "手机配对热点仍在使用，请先关闭配对热点，再使用 Wi-Fi 直连。"
        !canManageWifiDirect -> "控制或配网操作尚未结束，暂不能使用 Wi-Fi 直连。"
        wireless.busy -> "正在读取或处理直连状态，请等待完成。"
        wireless.discovering -> "请先停止搜索设备，再连接 Wi-Fi 直连小车。"
        !wireless.supported -> "此手机不支持 Wi-Fi Direct，请选择局域网或远程连接。"
        !wireless.permissionGranted -> "请先允许附近 Wi-Fi 设备权限，再刷新直连状态。"
        !wireless.wifiEnabled -> "请先在系统设置中开启 Wi-Fi。"
        DirectNetworkLease.from(wireless) == null -> "请先创建手机无线组，等待开发板加入并确认组状态。"
        wifiDirectHostForConnection(wireless, host.trim()) == null ->
            "请选择当前无线组内的小车地址；不能使用手机地址或原局域网地址。"
        else -> null
    }
}

/** Re-selecting the current method must not revoke an ongoing receive-only video preview. */
internal fun BoardUiState.withSelectedRemoteMode(remote: Boolean): BoardUiState =
    if (remoteMode == remote) this else copy(remoteMode = remote, error = null, result = null,
        videoPreviewWanted = false)
