package com.whopclipper.phone.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whopclipper.phone.Clip
import com.whopclipper.phone.Thumbs
import kotlinx.coroutines.delay

/** Decorative still: opening details and playing are explicit actions on the containing screen. */
@Composable
fun ClipThumbnail(clip: Clip, allowNetwork: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val revision by Thumbs.revision.collectAsStateWithLifecycle()
    var bitmap by remember(clip.id, clip.videoId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.id, clip.videoId, allowNetwork, revision) {
        bitmap = Thumbs.thumbnail(context, clip, allowNetwork)
    }
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val still = bitmap
        if (still != null) {
            Image(remember(still) { still.asImageBitmap() }, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Canvas(Modifier.size(32.dp)) {
                drawRoundRect(foreground, topLeft = Offset(size.width * .2f, size.height * .05f),
                    size = Size(size.width * .6f, size.height * .9f), cornerRadius = CornerRadius(5.dp.toPx()),
                    style = Stroke(1.5.dp.toPx()))
                drawLine(foreground, Offset(size.width * .38f, size.height * .81f),
                    Offset(size.width * .62f, size.height * .81f), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

/**
 * Mount only after Play is tapped. The WebView belongs to this composition, never a ViewModel.
 * Ordinary recomposition and handled rotation keep it; leaving details releases it completely.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClipPlayer(clip: Clip, onOpenExternal: (String) -> Unit, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openExternal by rememberUpdatedState(onOpenExternal)
    var webView by remember(clip.id, clip.videoId) { mutableStateOf<WebView?>(null) }
    var loading by remember(clip.id, clip.videoId) { mutableStateOf(true) }
    var error by remember(clip.id, clip.videoId) {
        mutableStateOf(if (clip.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) null else "This clip is still uploading.")
    }
    LaunchedEffect(clip.id, clip.videoId) {
        delay(20_000)
        if (loading && error == null) error = "The player is taking too long to load. Try opening this clip in YouTube."
    }
    DisposableEffect(lifecycle, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    view?.evaluateJavascript("if(window.whopPlayer && window.whopPlayer.pauseVideo) window.whopPlayer.pauseVideo();", null)
                    view?.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onPause()
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.widthIn(max = 247.5.dp).fillMaxWidth().aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (error == null) {
                key(clip.id, clip.videoId) {
                AndroidView(
                    factory = { context ->
                        try {
                            WebView(context).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (!request.isForMainFrame) return false
                                        val uri = request.url
                                        // Only a display status is received; no Java/Kotlin interface is exposed to web content.
                                        if (uri.scheme == "whop-player") {
                                            when (uri.host) {
                                                "ready" -> loading = false
                                                "error" -> error = "YouTube couldn't play this clip here. It may be private or unavailable."
                                            }
                                            return true
                                        }
                                        if (uri.scheme in listOf("https", "http")) openExternal(uri.toString())
                                        return true
                                    }

                                    private fun isPlayer(request: WebResourceRequest): Boolean = request.isForMainFrame ||
                                        (request.url.host in listOf("www.youtube.com", "www.youtube-nocookie.com") &&
                                            request.url.path?.startsWith("/embed/") == true)

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, received: WebResourceError) {
                                        if (isPlayer(request)) error = "Couldn't load the video. Check your connection or open it in YouTube."
                                    }

                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        if (isPlayer(request) && response.statusCode >= 400) error = "YouTube couldn't load this clip. Try opening it in YouTube."
                                    }

                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        view.tag = RendererGone
                                        error = "The video player stopped. You can still open this clip in YouTube."
                                        return true
                                    }
                                }
                                loadDataWithBaseURL("https://${context.packageName}/", playerHtml(clip.videoId), "text/html", "utf-8", null)
                            }
                        } catch (_: RuntimeException) {
                            webView?.destroy()
                            webView = null
                            error = "The phone's web player isn't available. Open this clip in YouTube."
                            FrameLayout(context)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        (view as? WebView)?.let {
                            if (webView === it) webView = null
                            (it.parent as? ViewGroup)?.removeView(it)
                            if (it.tag !== RendererGone) {
                                it.stopLoading()
                                it.loadUrl("about:blank")
                                it.onPause()
                                it.webChromeClient = null
                                it.webViewClient = WebViewClient()
                            }
                            it.removeAllViews()
                            it.destroy()
                        }
                    }
                )
                }
            }
            if (error != null) {
                Text(error.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            } else if (loading) {
                Column(Modifier.background(Color.Black.copy(alpha = .8f), RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    Text("Loading video…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TextButton(onClick = { openExternal(clip.watchUrl) }, enabled = clip.videoId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Open in YouTube")
        }
    }
}

private object RendererGone

private fun playerHtml(videoId: String): String = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="referrer" content="strict-origin-when-cross-origin">
    <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
    <body><iframe id="player" src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&enablejsapi=1"
    allow="autoplay; encrypted-media; picture-in-picture" referrerpolicy="strict-origin-when-cross-origin"></iframe>
    <script>
    function onYouTubeIframeAPIReady() {
      window.whopPlayer = new YT.Player('player', {events:{
        onReady:function(){window.location.href='whop-player://ready';},
        onError:function(){window.location.href='whop-player://error';}
      }});
    }
    </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
""".trimIndent()
