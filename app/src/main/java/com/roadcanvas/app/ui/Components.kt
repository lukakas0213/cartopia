package com.roadcanvas.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object RcColors {
    val Fog = Color(0xFF111111)
    val Sheet = Color(0xFF1C1C1C)
    val Sheet2 = Color(0xFF262626)
    val Line = Color(0xFF2E2E2E)
    val Ink = Color.White
    val Ink2 = Color(0xFFA0A0A0)
    val Accent = Color(0xFF3D7CFF)
    val Locked = Color(0xFF8E8E8E)
    val Penalty = Color(0xFFFF8A3D)
}

object RcType {
    val Hero = TextStyle(color = RcColors.Ink, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
    val Value = TextStyle(color = RcColors.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    val Title = TextStyle(color = RcColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    val Body = TextStyle(color = RcColors.Ink, fontSize = 17.sp)
    val Label = TextStyle(color = RcColors.Ink2, fontSize = 15.sp)
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, sub: String? = null) {
    Row(
        modifier
            .background(RcColors.Sheet, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text, style = RcType.Body.copy(fontWeight = FontWeight.Bold))
        if (sub != null) Text(sub, style = RcType.Label)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = RcType.Label)
        Text(value, style = RcType.Value)
    }
}

/** Pill button: [filled] is the primary action (white), otherwise an outlined one. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(64.dp)
            .background(if (filled && enabled) RcColors.Ink else Color.Transparent, shape)
            .border(2.dp, if (enabled) RcColors.Ink else RcColors.Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = RcType.Body.copy(
                fontWeight = FontWeight.Bold,
                color = when {
                    !enabled -> RcColors.Ink2
                    filled -> RcColors.Fog
                    else -> RcColors.Ink
                }
            )
        )
    }
}

fun formatDuration(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
