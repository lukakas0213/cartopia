package com.cartopia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Theme A colours, matching the village page while it loads. */
internal val Sea = Color(0xFFBEE5F2)
internal val Ink2 = Color(0xFF7E6855)

/** Shown for the moment it takes [com.cartopia.app.core.AppCore] to read the map index from the assets. */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Sea), contentAlignment = Alignment.Center) {
        Text("지도 불러오는 중…", style = TextStyle(color = Ink2, fontSize = 20.sp))
    }
}
