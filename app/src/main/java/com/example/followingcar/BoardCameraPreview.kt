package com.example.followingcar

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONTokener

/** Service controls plus the board's full-frame RGB/depth player, sized for the driving page. */
@Composable
internal fun BoardCameraPreview(
    state: BoardUiState,
    onVideoServiceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    remoteVideo: RemoteVideoEndpoint? = null,
    phoneNetwork: PhoneNetworkState = PhoneNetworkState(),
    player: @Composable (String, RemoteVideoEndpoint?, (String) -> Unit, Modifier, PhoneNetworkState) -> Unit =
        { host, endpoint, onStatus, playerModifier, phone ->
            BoardVideoPlayer(host, onStatus, playerModifier, endpoint, phone)
        },
    onCameraChange: (ManualVideoCamera) -> Unit = {},
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var playerStatus by remember(state.host, remoteVideo?.identity, state.videoPreviewRevision, retryKey) {
        mutableStateOf("正在连接画面…")
    }
    val running = state.videoServiceState == VideoServiceState.RUNNING
    val canPreview = state.canPreviewVideo(remoteVideo != null)
    val status = when {
        state.usesRemoteVideo && remoteVideo == null -> "公网视频未配置；当前控制连接可继续使用"
        !state.connected && canPreview -> "控制未连接 · $playerStatus"
        !state.connected -> "连接开发板后可开启视频"
        state.videoServiceBusy -> state.videoServiceMessage
        state.videoServiceError != null -> "视频操作失败，可重试"
        state.followBlocksControl -> "自动跟随尚未释放摄像头"
        state.videoServiceState == VideoServiceState.STOPPED -> "视频服务已关闭"
        canPreview -> "${state.activeVideoCamera?.label ?: "视频"} · $playerStatus"
        else -> "视频服务状态未确认"
    }
    Column(modifier.testTag("cameraPreview")) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RGB 左 · 深度右", style = MaterialTheme.typography.bodySmall)
                Text(status, Modifier.testTag("cameraStatus"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.videoServiceError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.connected && state.videoServiceState == VideoServiceState.UNKNOWN) {
                // An unconfirmed stop must remain retryable; UNKNOWN does not mean stopped.
                TextButton(onClick = { onVideoServiceEnabledChange(false) },
                    enabled = state.canToggleVideoService,
                    modifier = Modifier.testTag("videoServiceStop")) { Text("关闭") }
            } else if (running || canPreview) {
                TextButton(onClick = { retryKey += 1 },
                    enabled = canPreview,
                    modifier = Modifier.testTag("cameraRetry")) { Text("重连") }
            }
            TextButton(onClick = { onVideoServiceEnabledChange(!running) },
                enabled = state.canToggleVideoService,
                modifier = Modifier.testTag("videoServiceToggle")) {
                Text(if (state.videoServiceBusy) "处理中…" else if (running) "关闭视频" else "开启视频")
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp).testTag("manualVideoCameraSelector"),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ManualVideoCamera.entries.forEach { camera ->
                FilterChip(selected = state.manualVideoCamera == camera,
                    onClick = { onCameraChange(camera) }, enabled = state.canSelectManualVideoCamera,
                    label = { Text(camera.label, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f).testTag("manualVideoCamera_${camera.argument}"))
            }
        }
        if (!state.canSelectManualVideoCamera) {
            val hint = when {
                state.followBlocksControl -> "跟随占用相机，结束后可切换"
                state.manualActive -> "先停用手动控制，再切换相机"
                state.videoServiceBusy -> "视频操作完成后可切换相机"
                state.connected && state.videoServiceState != VideoServiceState.STOPPED -> "先关闭视频，再切换相机"
                else -> "当前操作完成后可切换相机"
            }
            Text(hint, Modifier.fillMaxWidth().height(20.dp).testTag("manualVideoCameraHint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (canPreview) {
                // A successful service start reloads the player even if the IP is unchanged.
                key(state.host, remoteVideo?.identity, state.videoPreviewRevision, retryKey) {
                    player(state.host, remoteVideo, { playerStatus = it }, Modifier.fillMaxSize(), phoneNetwork)
                }
            } else {
                val message = when {
                    state.usesRemoteVideo && remoteVideo == null -> "公网视频未配置；当前控制连接可继续使用"
                    !state.connected -> "连接开发板后显示实时画面"
                    state.videoServiceBusy -> state.videoServiceMessage
                    state.videoServiceError != null -> "视频操作失败：${state.videoServiceError}\n可重试；详细原因见“故障”"
                    state.followBlocksControl -> "请先结束自动跟随，再开启视频"
                    else -> "视频已关闭\n开启视频或启用手动控制后显示画面"
                }
                Text(message,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp)
                        .testTag(if (!state.connected) "cameraConnectHint" else if (state.videoServiceError != null)
                            "videoServiceError" else "cameraUnavailable"),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = if (state.videoServiceError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Receive-only player: unloading it releases WebRTC without requesting phone camera access. */
@Composable
@Suppress("DEPRECATION")
private fun BoardVideoPlayer(boardHost: String, onStatusChange: (String) -> Unit, modifier: Modifier,
    remoteVideo: RemoteVideoEndpoint? = null, phoneNetwork: PhoneNetworkState = PhoneNetworkState()) {
    val host = remoteVideo?.host ?: boardHost.trim()
    val diagnostic = remember(host, remoteVideo?.identity) { Diagnostics.newContext() }
    if (remoteVideo == null && !isBoardIpv4(host)) {
        Text("请先在连接页填写开发板的 IPv4 地址。", modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    // The MediaMTX player fills the WebView, without the /preview page's heading and telemetry.
    // A trailing slash avoids redirecting the main frame before reader.js resolves its WHEP URL.
    val previewUrl = remoteVideo?.previewUrl
        ?: "http://$host:8889/rgbd/?controls=false&muted=true&autoplay=true&playsinline=true"
    val currentHost = rememberUpdatedState(host)
    val currentRemote = rememberUpdatedState(remoteVideo)
    val currentUrl = rememberUpdatedState(previewUrl)
    val currentOnStatusChange = rememberUpdatedState(onStatusChange)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var pageFailed by remember { mutableStateOf(false) }
    var pageNetworkFailure by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var pageRetryAttempt by remember { mutableIntStateOf(0) }
    var observedNetwork by remember { mutableStateOf(phoneNetwork.defaultNetworkHandle) }
    var observedNetworkLoss by remember { mutableStateOf(false) }
    var lastFailureSequence by remember { mutableLongStateOf(0L) }
    var documentGeneration by remember { mutableLongStateOf(0L) }

    @SuppressLint("SetJavaScriptEnabled")
    val webView = remember(context) {
        WebView(context).apply {
            // A wrap-content WebView gives this absolute-positioned, 100%-height player a
            // zero-height CSS viewport. Match the 8:3 Compose container on both axes.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                private fun readFailureEvents(view: WebView) {
                    val requestedDocument = documentGeneration
                    view.evaluateJavascript("document.documentElement.dataset.errorEvents || '[]'") { encoded ->
                        if (view.url != currentUrl.value || requestedDocument != documentGeneration)
                            return@evaluateJavascript
                        try {
                            if (encoded.length > 8192) return@evaluateJavascript
                            val raw = JSONTokener(encoded).nextValue() as? String ?: return@evaluateJavascript
                            val events = JSONArray(raw)
                            if (events.length() > 8) return@evaluateJavascript
                            for (index in 0 until events.length()) {
                                val event = events.optJSONObject(index) ?: continue
                                val sequence = event.optLong("sequence", 0)
                                val code = remoteVideoFailureCode("FollowingCarVideoError:${event.optString("code")}")
                                    ?: continue
                                if (sequence <= lastFailureSequence) continue
                                lastFailureSequence = sequence
                                Diagnostics.event("video.player.remote_failure", diagnostic,
                                    fields = mapOf("failureCode" to code, "sequence" to sequence), critical = true)
                            }
                        } catch (_: Exception) { /* Only fixed diagnostic codes are accepted. */ }
                    }
                }
                // The board page receives WebRTC video. It needs no phone camera or microphone.
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (currentRemote.value == null || view.url != currentUrl.value) return
                    remoteVideoFailureCode(title)?.let {
                        readFailureEvents(view)
                        return
                    }
                    // Only fixed status codes from our player reach diagnostics; no URL, ICE or SDP data.
                    if (title?.startsWith("FollowingCarVideo:") != true) return
                    val code = title?.removePrefix("FollowingCarVideo:") ?: return
                    val message = when (code) {
                        "connecting" -> "正在连接远程画面…"
                        "playing" -> "远程画面已连接"
                        "retrying" -> "画面连接中断，正在重连…"
                        "media_stalled" -> "画面停止更新，正在重连…"
                        "quota_wait" -> "视频中转资源繁忙，稍后自动重试"
                        "authentication_failed" -> "远程视频认证失败，请检查凭据"
                        "unavailable" -> "暂无远程画面，可重连"
                        "closed" -> "预览已暂停"
                        else -> return
                    }
                    Diagnostics.event("video.player.remote_state", diagnostic,
                        fields = mapOf("playerState" to code), critical = code in setOf("authentication_failed", "media_stalled"))
                    readFailureEvents(view)
                    currentOnStatusChange.value(message)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    documentGeneration++
                    lastFailureSequence = 0
                    loaded = false
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url
                    if (target.toString() == "about:blank") return false
                    currentRemote.value?.let { return !it.allowsNavigation(target.toString()) }
                    return target.scheme != "http" || target.host != currentHost.value || target.port != 8889
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val endpoint = currentRemote.value ?: return null
                    if (request.url.toString() == "about:blank" || endpoint.allowsResource(request.url.toString())) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }

                override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                    val endpoint = currentRemote.value
                    if (endpoint == null || !endpoint.acceptsClientCertificate(request.host, request.port)) {
                        request.cancel()
                        return
                    }
                    try {
                        val key = endpoint.privateKey()
                        val chain = endpoint.certificateChain()
                        if (key == null || chain.isNullOrEmpty()) {
                            request.cancel()
                            pageFailed = true
                            currentOnStatusChange.value("远程视频凭据不可用，请重新导入")
                        } else {
                            chain.forEach { it.checkValidity() }
                            request.proceed(key, chain)
                        }
                    } catch (_: Exception) {
                        request.cancel()
                        pageFailed = true
                        Diagnostics.event("video.player.client_identity_failed", diagnostic, critical = true)
                        currentOnStatusChange.value("远程视频凭据无效，请重新导入")
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    // Never turn a failed HTTPS certificate check into a trusted video session.
                    handler.cancel()
                    pageFailed = true
                    Diagnostics.event("video.player.tls_failed", diagnostic,
                        fields = mapOf("errorCode" to error.primaryError), critical = true)
                    currentOnStatusChange.value("视频证书校验失败，连接已拒绝")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                    error: WebResourceError) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        pageNetworkFailure = error.errorCode in setOf(
                            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
                            WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO,
                        )
                        Diagnostics.event("video.player.load_failed", diagnostic,
                            fields = mapOf("errorCode" to error.errorCode, "host" to currentHost.value), critical = true)
                        currentOnStatusChange.value("画面连接失败，可重连")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                    errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        Diagnostics.event("video.player.http_failed", diagnostic,
                            fields = mapOf("httpStatus" to errorResponse.statusCode), critical = true)
                        currentOnStatusChange.value("视频服务返回 HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url == currentUrl.value && !pageFailed) {
                        loaded = true
                        pageRetryAttempt = 0
                        Diagnostics.event("video.player.page_loaded", diagnostic,
                            fields = mapOf("width" to view.width, "height" to view.height))
                        if (currentRemote.value == null) currentOnStatusChange.value("播放器已加载")
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(webView, foreground, previewUrl) {
        if (foreground) {
            Diagnostics.event("video.player.load_started", diagnostic,
                fields = mapOf("host" to host, "mode" to if (remoteVideo == null) "local" else "remote"))
            pageFailed = false
            pageNetworkFailure = false
            loaded = false
            observedNetwork = phoneNetwork.defaultNetworkHandle
            observedNetworkLoss = false
            currentOnStatusChange.value("正在连接画面…")
            webView.onResume()
            webView.loadUrl(previewUrl)
        } else {
            Diagnostics.event("video.player.paused", diagnostic)
            // Unloading closes the page's WebRTC connection.
            webView.loadUrl("about:blank")
            webView.onPause()
            currentOnStatusChange.value("预览已暂停")
        }
    }
    // Keep the WebView and its quota cooldown. A default route change only rebuilds receive-only ICE.
    LaunchedEffect(webView, foreground, phoneNetwork.defaultNetworkHandle, phoneNetwork.internetValidated) {
        if (remoteVideo == null || !foreground || !phoneNetwork.observed) return@LaunchedEffect
        val handle = phoneNetwork.defaultNetworkHandle
        if (handle == null || !phoneNetwork.internetValidated) {
            observedNetworkLoss = true
            return@LaunchedEffect
        }
        if (observedNetwork == handle && !observedNetworkLoss) return@LaunchedEffect
        delay(500)
        observedNetwork = handle
        observedNetworkLoss = false
        Diagnostics.event("video.player.network_recovery", diagnostic,
            fields = mapOf("pageReload" to pageNetworkFailure, "transport" to phoneNetwork.transportLabel))
        if (pageNetworkFailure) {
            pageFailed = false
            pageNetworkFailure = false
            loaded = false
            webView.loadUrl(previewUrl)
        } else if (loaded) {
            webView.evaluateJavascript(
                "if(typeof window.followingCarNetworkChanged==='function'){window.followingCarNetworkChanged();}", null)
        }
    }
    // A failed initial HTML request has no JS reader to recover it. Retry transport failures only;
    // certificate, authentication and HTTP errors remain visible and are never bypassed.
    LaunchedEffect(webView, foreground, pageNetworkFailure, phoneNetwork.internetValidated) {
        if (remoteVideo == null || !foreground || !pageNetworkFailure || !phoneNetwork.internetValidated)
            return@LaunchedEffect
        val retryDelay = minOf(30_000L, 2_000L * (1L shl pageRetryAttempt.coerceAtMost(4)))
        delay(retryDelay)
        pageRetryAttempt++
        Diagnostics.event("video.player.page_retry", diagnostic,
            fields = mapOf("attempt" to pageRetryAttempt, "delayMs" to retryDelay))
        pageFailed = false
        pageNetworkFailure = false
        loaded = false
        webView.loadUrl(previewUrl)
    }
    DisposableEffect(webView) {
        onDispose {
            Diagnostics.event("video.player.disposed", diagnostic)
            webView.stopLoading()
            if (remoteVideo != null) WebView.clearClientCertPreferences(null)
            webView.loadUrl("about:blank")
            webView.onPause()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = { webView },
            update = { it.visibility = if (pageFailed) View.INVISIBLE else View.VISIBLE },
            modifier = Modifier.fillMaxSize().testTag("cameraWebView"))
        if (pageFailed) {
            Text("暂时没有画面\n请检查网络，或关闭视频后重新开启",
                modifier = Modifier.align(Alignment.Center).padding(12.dp).testTag("cameraUnavailable"),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun isBoardIpv4(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.length in 1..3 && octet.all { it in '0'..'9' } &&
            (octet.toIntOrNull() ?: -1) in 0..255
    }
}
