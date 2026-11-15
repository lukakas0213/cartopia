package com.cartopia.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.cartopia.app.AppCore

/** Theme A colours, matching the village page while it loads. */
private val Sea = Color(0xFFBEE5F2)
private val Ink2 = Color(0xFF7E6855)

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Sea), contentAlignment = Alignment.Center) {
        Text("지도 불러오는 중…", style = TextStyle(color = Ink2, fontSize = 20.sp))
    }
}

/**
 * The whole app UI: the 3D village (three.js) bundled in assets/village. It drives on the same map it decorates,
 * so this one WebView stays alive across gears; the view model talks to it through [VillageBridge] and `window.__cartopia`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VillageScreen(vm: AppCore) {
    var web by remember { mutableStateOf<WebView?>(null) }
    val activity = LocalContext.current as? Activity
    // the page closes its open panel first; only on the home panel (or while driving) does back leave the app
    BackHandler {
        val w = web
        if (w == null) activity?.finish()
        else w.evaluateJavascript("window.__back ? window.__back() : false") { handled -> if (handled != "true") activity?.finish() }
    }
    // off screen (the navigation app took the window), the map stops drawing; the drive is recorded either way
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val w = web
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) w?.onPause()
            if (e == Lifecycle.Event.ON_START) w?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        vm.pageEvents.collect { json -> w.evaluateJavascript("window.__cartopia && window.__cartopia($json)", null) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Sea),
        factory = { ctx ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#BEE5F2"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                }
                addJavascriptInterface(VillageBridge(vm), "Cartopia")
                loadUrl("https://appassets.androidplatform.net/assets/village/index.html")
                web = this
            }
        },
        onRelease = { it.destroy() }
    )
}

class VillageBridge(private val vm: AppCore) {
    @JavascriptInterface fun getInit(): String = vm.pageInit()
    @JavascriptInterface fun getMiles(): Int = vm.milesForVillage()
    @JavascriptInterface fun earn(n: Int) = vm.earnMiles(n)
    @JavascriptInterface fun spend(n: Int): Boolean = vm.spendMiles(n)
    @JavascriptInterface fun saveState(json: String) = vm.saveVillageState(json)
    @JavascriptInterface fun takeTrips(): String = vm.takePendingTrips()
    @JavascriptInterface fun startDemo() = vm.startDemoDrive()
}
