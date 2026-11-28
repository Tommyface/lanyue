package io.lanread.app.ui

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

/**
 * 视频章节播放页（v0.4.18；v0.4.26 修复 HLS）：视频源章节内容为 mp4/m3u8。
 * mp4/ts 直接 loadUrl（WebView 自带 <video> 控件）；
 * m3u8/HLS 流需 hls.js 播放器页（WebView 无法直接播放 HLS）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(nav: NavController, url: String, title: String) {
    val context = LocalContext.current
    val player = remember { mutableStateOf<WebView?>(null) }

    // v0.4.26：HLS/m3u8 流需 hls.js 播放器页（直接 loadUrl 无法播放）
    val isHls = url.contains(".m3u8") || url.contains("mime_type=video") || url.contains(".m3u8?")
    val playerHtml = if (isHls) {
        """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.7/dist/hls.min.js"><\/script>
<style>*{margin:0;padding:0;box-sizing:border-box}html,body{width:100%;height:100%;background:#000;display:flex;align-items:center;justify-content:center}video{width:100%;height:100%;object-fit:contain}</style>
</head><body>
<video id="v" controls autoplay playsinline webkit-playsinline></video>
<script>
var v=document.getElementById('v');
var url="$url";
if(v.canPlayType('application/vnd.apple.mpegurl')){v.src=url;v.play().catch(function(){});}
else if(window.Hls&&Hls.isSupported()){var h=new Hls({enableSoftwareAES:true});h.loadSource(url);h.attachMedia(v);h.on(Hls.Events.MANIFEST_PARSED,function(){v.muted=true;v.play().then(function(){v.muted=false;}).catch(function(){});});}
else{v.src=url;}
<\/script>
</body></html>""".replace("$url", url)
    } else null

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(title.ifBlank { "视频播放" }, fontSize = 16.sp, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    if (playerHtml != null) {
                        // HLS/m3u8：loadDataWithBaseURL 加载 hls.js 播放器页
                        loadDataWithBaseURL(null, playerHtml, "text/html", "utf-8", null)
                    } else {
                        // mp4/ts：直接 loadUrl（WebView 自带 <video> 控件）
                        loadUrl(url)
                    }
                    player.value = this
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose {
                player.value?.stopLoading()
                player.value?.destroy()
                player.value = null
            }
        }
    }
}
