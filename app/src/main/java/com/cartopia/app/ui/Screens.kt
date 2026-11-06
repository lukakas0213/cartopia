package com.cartopia.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.cartopia.app.AppViewModel
import com.cartopia.app.UiState
import com.cartopia.app.map.BlockMap

private val SheetShape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp)

@Composable
fun DriveScreen(state: UiState, blockMap: BlockMap, baseMap: ImageBitmap, onDemo: () -> Unit, onVillage: () -> Unit) {
    Row(Modifier.fillMaxSize().background(RcColors.Fog)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            MapCanvas(
                blockMap, baseMap, state.revealed, state.history, state.current, state.car,
                focus = state.car, zoom = if (state.driving) 1.6f else 1f, modifier = Modifier.fillMaxSize(), followCar = state.driving
            )
            Pill("내 지도 ${state.exploredPercent}%", Modifier.padding(24.dp))
            Text(
                "© OpenStreetMap contributors",
                style = RcType.Label.copy(fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 8.dp)
            )
        }
        Column(
            Modifier.width(420.dp).fillMaxHeight().background(RcColors.Sheet, SheetShape).padding(horizontal = 36.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val trip = state.trip
            if (trip != null) {
                Text("거리", style = RcType.Label)
                Text("%.1f km".format(trip.distanceKm), style = RcType.Hero)
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("안전 점수", "${trip.score}", Modifier.weight(1f))
                    Stat("시간", formatDuration(trip.elapsedSec), Modifier.weight(1f))
                    Stat("마일", "+${trip.estimatedMiles}", Modifier.weight(1f))
                }
                Divider()
                LabelRow("새로 연 블록", "+${trip.newBlocks}")
                LabelRow("모드", if (state.demo) "시연 주행" else "실제 주행")
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 104.dp, height = 64.dp).background(RcColors.Locked, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) { Text("잠금", style = RcType.Body.copy(fontWeight = FontWeight.Bold)) }
                    PillButton("도착하면 자동 저장", {}, Modifier.weight(1f), filled = false, enabled = true)
                }
            } else {
                Text("보유 마일", style = RcType.Label)
                Text("%,d".format(state.miles), style = RcType.Hero)
                Spacer(Modifier.height(24.dp))
                LabelRow("열린 블록", "${state.revealed.size}개")
                LabelRow("차량 신호", if (state.vehicleConnected) "연결됨" else "없음 · 시연 모드")
                Text(
                    if (state.vehicleConnected) "기어를 D로 바꾸면 주행 기록이 자동으로 시작돼요." else "차량 신호가 없어서 시연 주행으로 흐름을 보여 드려요.",
                    style = RcType.Label,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Spacer(Modifier.weight(1f))
                PillButton("내 마을로 가기", onVillage, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                PillButton("시연 주행 시작", onDemo, Modifier.fillMaxWidth(), filled = false, enabled = state.loaded)
            }
        }
    }
}

@Composable
fun ResultScreen(state: UiState, blockMap: BlockMap, baseMap: ImageBitmap, onVillage: () -> Unit, onMap: () -> Unit) {
    val r = state.result ?: return
    Row(Modifier.fillMaxSize().background(RcColors.Fog)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            MapCanvas(
                blockMap, baseMap, state.revealed, state.history, emptyList(), r.route.lastOrNull(),
                focus = r.route.getOrNull(r.route.size / 2), zoom = 1.25f, modifier = Modifier.fillMaxSize()
            )
            Pill("오늘의 드라이브", Modifier.padding(24.dp), sub = formatDuration(r.durationSec))
        }
        Column(
            Modifier.width(440.dp).fillMaxHeight().background(RcColors.Sheet, SheetShape).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Stat("거리", "%.1f km".format(r.miles.km), Modifier.weight(1f))
                Stat("안전 점수", "${r.score}", Modifier.weight(1f))
                Stat("새 블록", "+${r.miles.newBlocks}", Modifier.weight(1f))
            }
            Divider()
            Text("점수가 바뀐 이유", style = RcType.Label)
            if (r.events.isEmpty()) {
                Text("감점 없이 달렸어요", style = RcType.Body)
            } else {
                r.events.groupBy { it.kind }.forEach { (kind, list) ->
                    LabelRow("${kind.label} ${list.size}회", "−${kind.penalty * list.size}", valueColor = RcColors.Penalty)
                }
            }
            Divider()
            if (r.miles.withheld) {
                Text("주행 거리가 차량 기록과 달라서 이번 주행의 마일은 보류했어요.", style = RcType.Body)
            } else {
                Text("기본 ${r.miles.base} × 안전 ${"%.1f".format(r.miles.multiplier)} + 새 블록 ${r.miles.bonus}", style = RcType.Label)
                Text("+${r.miles.total} 마일", style = RcType.Hero.copy(fontSize = 52.sp))
            }
            Spacer(Modifier.weight(1f))
            PillButton("내 마을로 가기", onVillage, Modifier.fillMaxWidth())
            PillButton("지도로 돌아가기", onMap, Modifier.fillMaxWidth(), filled = false)
        }
    }
}

/** The 3D village (three.js) bundled in assets/village, talking to the app through [VillageBridge]. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VillageScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(RcColors.Fog)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

class VillageBridge(private val vm: AppViewModel) {
    @JavascriptInterface fun getMiles(): Int = vm.milesForVillage()
    @JavascriptInterface fun earn(n: Int) = vm.earnMiles(n)
    @JavascriptInterface fun spend(n: Int): Boolean = vm.spendMiles(n)
    @JavascriptInterface fun getState(): String = vm.villageState()
    @JavascriptInterface fun saveState(json: String) = vm.saveVillageState(json)
    @JavascriptInterface fun getOpenBlocks(): Int = vm.state.value.revealed.size
    // The back button lives in the page so it matches the village's look.
    @JavascriptInterface fun openMap() = vm.openMap()
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).height(1.dp).background(RcColors.Line))
}

@Composable
private fun LabelRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = RcColors.Ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = RcType.Label.copy(fontSize = 17.sp))
        Text(value, style = RcType.Body.copy(fontWeight = FontWeight.Bold, color = valueColor))
    }
}
