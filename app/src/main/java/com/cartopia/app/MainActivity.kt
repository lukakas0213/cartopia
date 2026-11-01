package com.cartopia.app

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cartopia.app.ui.DriveScreen
import com.cartopia.app.ui.RcColors
import com.cartopia.app.ui.RcType
import com.cartopia.app.ui.ResultScreen
import com.cartopia.app.ui.VillageScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val baseMap by produceState<ImageBitmap?>(null) {
                value = withContext(Dispatchers.IO) {
                    assets.open("map/base_map.png").use { BitmapFactory.decodeStream(it).asImageBitmap() }
                }
            }
            val map = baseMap
            if (!state.loaded || map == null) {
                Box(Modifier.fillMaxSize().background(RcColors.Fog), contentAlignment = Alignment.Center) {
                    Text("지도를 준비하고 있어요", style = RcType.Label)
                }
                return@setContent
            }
            when (state.screen) {
                Screen.DRIVE -> DriveScreen(state, vm.blockMap, map, onDemo = vm::startDemoDrive, onVillage = vm::openVillage)
                Screen.RESULT -> ResultScreen(state, vm.blockMap, map, onVillage = vm::openVillage, onMap = vm::openMap)
                Screen.VILLAGE -> VillageScreen(vm, onMap = vm::openMap)
            }
        }
    }
}
