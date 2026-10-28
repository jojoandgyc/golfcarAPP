package com.example.followingcar

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Receive-only RGB/depth preview supplied by the board's own /preview page. */
@Composable
@Suppress("DEPRECATION")
internal fun BoardCameraPreview(boardHost: String, modifier: Modifier = Modifier) {
    val host = boardHost.trim()
    if (!isBoardIpv4(host)) {
        Text("请先在连接页填写开发板的 IPv4 地址。", modifier.testTag("cameraStatus"),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val previewUrl = "http://$host:8080/preview"
    val currentHost = rememberUpdatedState(host)
    val currentUrl = rememberUpdatedState(previewUrl)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var retryKey by remember { mutableIntStateOf(0) }
    var pageFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在连接视频服务…") }

    @SuppressLint("SetJavaScriptEnabled")
    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
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
                    return target.scheme != "http" || target.host != currentHost.value || target.port != 8080
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                    error: WebResourceError) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        status = "预览连接失败，请检查热点或在开发板手动启动视频服务。"
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                    errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && request.url.toString() == currentUrl.value) {
                        pageFailed = true
                        status = "预览服务返回 HTTP ${errorResponse.statusCode}，请稍后重连。"
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url == currentUrl.value && !pageFailed) {
                        status = "预览已打开；视频状态显示在画面内。"
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
    LaunchedEffect(webView, foreground, previewUrl, retryKey) {
        if (foreground) {
            pageFailed = false
            status = "正在连接视频服务…"
            webView.onResume()
            webView.loadUrl(previewUrl)
        } else {
            // Unloading closes the page's WebRTC and WebSocket connections.
            webView.loadUrl("about:blank")
            webView.onPause()
            status = "预览已暂停"
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

    Column(modifier.testTag("cameraPreview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f).testTag("cameraStatus"),
                style = MaterialTheme.typography.bodySmall,
                color = if (pageFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { retryKey += 1 }, modifier = Modifier.testTag("cameraRetry")) {
                Text("重连")
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("cameraWebView"))
    }
}

private fun isBoardIpv4(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.length in 1..3 && octet.all { it in '0'..'9' } &&
            (octet.toIntOrNull() ?: -1) in 0..255
    }
}
