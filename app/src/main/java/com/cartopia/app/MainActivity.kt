package com.cartopia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.cartopia.app.core.AppCore
import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.service.TripService
import com.cartopia.app.ui.LoadingScreen
import com.cartopia.app.ui.VillageScreen

/**
 * The only screen. It shows [LoadingScreen] until the map index is read, then the 3D village ([VillageScreen]),
 * which serves both gears. All state lives in [AppCore], so closing this Activity never stops a drive.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppCore get() = (application as CartopiaApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            if (state.loaded) VillageScreen(vm) else LoadingScreen()
        }
        qaDrive(intent)
    }

    override fun onStart() {
        super.onStart()
        TripService.start(this) // allowed now, while on screen; it keeps drives recording once the app is off it
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        qaDrive(intent)
    }

    /** QA on an emulator: `--es qa_route "lat,lon;lat,lon;..." [--ef qa_speed 8]` drives that route. */
    private fun qaDrive(intent: Intent?) {
        val raw = intent?.getStringExtra("qa_route") ?: return
        intent.removeExtra("qa_route")
        val route = raw.split(';').mapNotNull { p ->
            val (lat, lon) = p.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            GeoPoint(lat.trim().toDoubleOrNull() ?: return@mapNotNull null, lon.trim().toDoubleOrNull() ?: return@mapNotNull null)
        }
        val speed = intent.getFloatExtra("qa_speed", 4f).toDouble()
        lifecycleScope.launch {
            vm.state.first { it.loaded }
            vm.startQaDrive(route, speed)
        }
    }
}
