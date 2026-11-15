package com.cartopia.app.ui

import android.webkit.JavascriptInterface
import com.cartopia.app.core.AppCore

/**
 * `window.Cartopia` on the village page. Every call arrives on the WebView's JS thread and goes straight to [AppCore];
 * the other direction (app -> page) is [AppCore.pageEvents], delivered to `window.__cartopia`.
 * The method names are part of the page contract (assets/village/village.js) and kept by proguard-rules.pro.
 */
class VillageBridge(private val vm: AppCore) {
    @JavascriptInterface fun getInit(): String = vm.pageInit()
    @JavascriptInterface fun getMiles(): Int = vm.milesForVillage()
    @JavascriptInterface fun earn(n: Int) = vm.earnMiles(n)
    @JavascriptInterface fun spend(n: Int): Boolean = vm.spendMiles(n)
    @JavascriptInterface fun saveState(json: String) = vm.saveVillageState(json)
    @JavascriptInterface fun takeTrips(): String = vm.takePendingTrips()
    @JavascriptInterface fun startDemo() = vm.startDemoDrive()
}
