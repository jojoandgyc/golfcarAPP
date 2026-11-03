package com.example.followingcar

/** Internet access on the phone is separate from reachability of the car. */
data class PhoneNetworkState(
    val observed: Boolean = false,
    val available: Boolean = false,
    val internetValidated: Boolean = false,
    val transportLabel: String = "未知",
    val wifiAvailable: Boolean = false,
    val revision: Long = 0,
    // Opaque OS identity, not an address. Capability-only changes must not restart video.
    val defaultNetworkHandle: Long? = null,
) {
    fun statusLabel(): String = when {
        !observed -> "手机网络状态待确认"
        internetValidated -> "手机可访问互联网 · $transportLabel"
        available -> "手机已连接网络，互联网待确认 · $transportLabel"
        else -> "手机暂无系统网络；本地热点需单独确认"
    }
}

enum class BoardRoute { LOCAL, REMOTE }
enum class Reachability { UNKNOWN, CHECKING, ONLINE, STALE, UNREACHABLE, NOT_CONFIGURED }

/** Fixed, non-sensitive failure descriptions. A failed path never proves the car is powered off. */
enum class ReachabilityFailure(val label: String) {
    TIMEOUT("连接确认超时"),
    UNREACHABLE("此连接路径暂不可达"),
    AUTHENTICATION("小车身份认证失败，请检查连接凭据"),
    IDENTITY("连接身份校验失败，请检查证书或小车身份"),
    CONFIGURATION("连接配置不可用"),
    UNKNOWN("未能确认小车在线"),
}

const val BOARD_ONLINE_FRESHNESS_MS = 30_000L

/**
 * ONLINE is supported only by a fresh authenticated car connection or a valid control ACK.
 * All times are elapsed-realtime timestamps, not wall clock. Do not persist them across boots.
 */
data class RouteHealth(
    val status: Reachability = Reachability.UNKNOWN,
    val lastCheckedElapsedMs: Long? = null,
    val lastOnlineElapsedMs: Long? = null,
    val failure: ReachabilityFailure? = null,
    // Last-seen history is not automatically usable as current evidence after a failure/change.
    val onlineEvidenceValid: Boolean = status == Reachability.ONLINE,
    // Refresh activity is separate from the last confirmed result and its expiry.
    val probeInProgress: Boolean = false,
) {
    fun isFreshOnline(now: Long): Boolean {
        val success = lastOnlineElapsedMs ?: return false
        return onlineEvidenceValid && status in setOf(Reachability.ONLINE, Reachability.CHECKING) &&
            now >= success && now - success <= BOARD_ONLINE_FRESHNESS_MS
    }

    fun effectiveStatus(now: Long): Reachability = when {
        isFreshOnline(now) -> Reachability.ONLINE
        status == Reachability.ONLINE -> Reachability.STALE
        else -> status
    }

    fun checking(): RouteHealth = copy(probeInProgress = true)

    fun cancelChecking(): RouteHealth = copy(probeInProgress = false)

    fun online(now: Long): RouteHealth = copy(status = Reachability.ONLINE,
        lastCheckedElapsedMs = now, lastOnlineElapsedMs = now, failure = null,
        onlineEvidenceValid = true, probeInProgress = false)

    fun unavailable(now: Long, reason: ReachabilityFailure = ReachabilityFailure.UNREACHABLE): RouteHealth =
        copy(status = Reachability.UNREACHABLE, lastCheckedElapsedMs = now, failure = reason,
            onlineEvidenceValid = false, probeInProgress = false)

    fun invalidate(): RouteHealth = copy(
        status = if (lastOnlineElapsedMs != null) Reachability.STALE else Reachability.UNKNOWN,
        failure = null,
        onlineEvidenceValid = false,
        probeInProgress = false,
    )

    /** A slow, older probe must not overwrite a successful ACK observed after it began. */
    fun acceptProbe(result: RouteHealth, probeStartedAt: Long): RouteHealth =
        if ((lastCheckedElapsedMs ?: Long.MIN_VALUE) > probeStartedAt) this
        else result.copy(lastOnlineElapsedMs = result.lastOnlineElapsedMs ?: lastOnlineElapsedMs,
            probeInProgress = false)

    fun lastOnlineAgeSeconds(now: Long): Long? = lastOnlineElapsedMs
        ?.takeIf { now >= it }?.let { (now - it) / 1_000L }

    fun statusLabel(now: Long): String = when (effectiveStatus(now)) {
        Reachability.UNKNOWN -> "尚未确认"
        Reachability.CHECKING -> "正在检查"
        Reachability.ONLINE -> "可达"
        Reachability.STALE -> "状态已过期，待确认"
        Reachability.UNREACHABLE -> "暂不可达"
        Reachability.NOT_CONFIGURED -> "未配置"
    }
}

data class BoardNetworkHealth(
    val phone: PhoneNetworkState = PhoneNetworkState(),
    val local: RouteHealth = RouteHealth(),
    val remote: RouteHealth = RouteHealth(status = Reachability.NOT_CONFIGURED),
) {
    fun route(route: BoardRoute): RouteHealth = if (route == BoardRoute.LOCAL) local else remote

    fun updateRoute(route: BoardRoute, value: RouteHealth): BoardNetworkHealth =
        if (route == BoardRoute.LOCAL) copy(local = value) else copy(remote = value)

    fun onlineLabel(now: Long): String = when {
        local.isFreshOnline(now) || remote.isFreshOnline(now) -> "小车在线"
        local.effectiveStatus(now) == Reachability.UNREACHABLE &&
            remote.effectiveStatus(now) in setOf(Reachability.UNREACHABLE, Reachability.NOT_CONFIGURED) -> "小车暂不可达"
        else -> "小车状态待确认"
    }

    fun remoteFallbackAvailable(now: Long): Boolean = remote.isFreshOnline(now)
}
