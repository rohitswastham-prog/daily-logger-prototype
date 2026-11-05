package com.dailylog.prototype.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Primary = Color(0xFF0B6B66)
    val Ink = Color(0xFF17302E)
    val Muted = Color(0xFF55676A)
    val Tint = Color(0xFFE3F1EF)
    val Surface = Color(0xFFF5F8F7)
    val Border = Color(0xFFDCE5E3)
    val NoteFill = Color(0xFFFFF4E5)
    val NoteBorder = Color(0xFFF5D9AE)
    val NoteInk = Color(0xFF5C3300)
    val Danger = Color(0xFFB42318)
    val DangerFill = Color(0xFFFDECEA)
    val Recording = Color(0xFFC2410C)
}

@Composable
fun PrototypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = C.Primary,
                onPrimary = Color.White,
                secondary = C.Primary,
                background = Color.White,
                surface = Color.White,
                onSurface = C.Ink,
                onBackground = C.Ink,
                surfaceVariant = C.Surface,
                outline = C.Border,
                error = C.Danger,
            ),
        content = content,
    )
}
