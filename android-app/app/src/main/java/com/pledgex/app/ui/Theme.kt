package com.pledgex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0B12)
val Card = Color(0xFF16161F)
val CardHigh = Color(0xFF1F1F2B)
val Purple = Color(0xFF9945FF)
val Teal = Color(0xFF14F195)
val Amber = Color(0xFFFFB547)
val Danger = Color(0xFFFF5C7A)
val TextHi = Color(0xFFF4F4F8)
val TextLo = Color(0xFF9A9AB0)
val Line = Color(0xFF2A2A38)

@Composable
fun PledgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, onPrimary = Color.White, secondary = Teal,
            background = Ink, surface = Card, onSurface = TextHi, onBackground = TextHi,
            surfaceVariant = CardHigh, onSurfaceVariant = TextLo, error = Danger, outline = Line,
        ),
        content = content,
    )
}
