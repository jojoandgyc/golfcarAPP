package com.example.followingcar

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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

/** Service controls plus the board's full-frame RGB/depth player, sized for the driving page. */
@Composable
internal fun BoardCameraPreview(
    state: BoardUiState,
    onVideoServiceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var playerStatus by remember(state.host, state.videoPreviewRevision, retryKey) {
        mutableStateOf("正在连接画面…")
    }
    val running = state.videoServiceState == VideoServiceState.RUNNING
    val canPreview = state.connected && !state.videoServiceBusy && !state.followBlocksControl &&
        state.videoServiceState != VideoServiceState.STOPPED && state.videoServiceError == null
    val status = when {
        !state.connected -> "连接开发板后可开启视频"
        state.videoServiceBusy -> state.videoServiceMessage
        state.videoServiceError != null -> "视频操作失败，可重试"
        state.followBlocksControl -> "自动跟随尚未释放摄像头"
        state.videoServiceState == VideoServiceState.STOPPED -> "视频服务已关闭"
        running -> playerStatus
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
            } else if (running) {
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
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (canPreview) {
                // A successful service start reloads the player even if the IP is unchanged.
                key(state.host, state.videoPreviewRevision, retryKey) {
                    BoardVideoPlayer(state.host, onStatusChange = { playerStatus = it },
                        modifier = Modifier.fillMaxSize())
                }
            } else {
                val message = when {
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
private fun BoardVideoPlayer(boardHost: String, onStatusChange: (String) -> Unit, modifier: Modifier) {
    val host = boardHost.trim()
    if (!isBoardIpv4(host)) {
        Text("请先在连接页填写开发板的 IPv4 地址。", modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    // The MediaMTX player fills the WebView, without the /preview page's heading and telemetry.
    // A trailing slash avoids redirecting the main frame before reader.js resolves its WHEP URL.
    val previewUrl = "http://$host:8889/rgbd/?controls=false&muted=true&autoplay=true&playsinline=true"
    val currentHost = rememberUpdatedState(host)
    val currentUrl = rememberUpdatedState(previewUrl)
    val currentOnStatusChange = rememberUpdatedState(onStatusChange)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var pageFailed by remember { mutableStateOf(false) }

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
                // The board page receives WebRTC video. It needs no phone camera or microphone.
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url
                    if (target.toString() == "about:blank") return false
                    return target.scheme != "http" || target.host != currentHost.value || target.port != 8889
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                    error: WebResourceError) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        currentOnStatusChange.value("画面连接失败，可重连")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                    errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        currentOnStatusChange.value("视频服务返回 HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url == currentUrl.value && !pageFailed) {
                        currentOnStatusChange.value("播放器已加载")
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
            pageFailed = false
            currentOnStatusChange.value("正在连接画面…")
            webView.onResume()
            webView.loadUrl(previewUrl)
        } else {
            // Unloading closes the page's WebRTC connection.
            webView.loadUrl("about:blank")
            webView.onPause()
            currentOnStatusChange.value("预览已暂停")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
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
