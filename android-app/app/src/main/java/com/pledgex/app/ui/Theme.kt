package com.pledgex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pledgex.app.R

// The Seeker look: pitch-black OLED, grouped dark surfaces, Solana purple → mint.
val Void = Color(0xFF04050A)
val Surface1 = Color(0xFF090C16)
val Card = Color(0xFF0E1220)
val CardHigh = Color(0xFF172036)
val Obsidian = Color(0xFF070912)
val Hairline = Color(0x3300F0FF)
val HairlineStrong = Color(0x5500F0FF)

val Purple = Color(0xFF9945FF)
val Mint = Color(0xFF14F195)
val Cyan = Color(0xFF00F0FF)
val Amber = Color(0xFFFF9F0A)
val Gold = Color(0xFFFFD60A)
val Burn = Color(0xFFFF453A)

val TextHi = Color(0xFFFFFFFF)
val TextLo = Color(0xFFA0B4D0)
val TextDim = Color(0xFF5F6E8A)
val OnGradient = Color(0xFF030D08)

val SolanaGradient = Brush.horizontalGradient(listOf(Purple, Mint))
val MintCyanGradient = Brush.horizontalGradient(listOf(Mint, Cyan))
val GlassBorder = Brush.horizontalGradient(listOf(Color(0x999945FF), Color(0x6600F0FF), Color(0x9914F195)))
val HeroBorder = Brush.verticalGradient(listOf(Color(0xEE14F195), Color(0x5500F0FF)))
/** Card fill: the old hero card's top-lit obsidian gradient. */
val CardFill = Brush.verticalGradient(listOf(Color(0xFF161B2E), Color(0xFF0C0F1C), Color(0xFF07080F)))
val NeonEdge = Brush.linearGradient(listOf(Color(0x669945FF), Color(0x3300F0FF), Color(0x6614F195)))

val Grotesk = FontFamily(Font(R.font.space_grotesk, FontWeight.Normal), Font(R.font.space_grotesk, FontWeight.Bold))
val Jakarta = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal), Font(R.font.plus_jakarta_sans, FontWeight.SemiBold))

@Composable
fun PledgeTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Jakarta, color = TextHi)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint, onPrimary = OnGradient, secondary = Purple,
            background = Void, surface = Card, onSurface = TextHi, onBackground = TextHi,
            surfaceVariant = CardHigh, onSurfaceVariant = TextLo, error = Burn, outline = HairlineStrong,
            surfaceContainerHigh = CardHigh, surfaceContainer = Card,
        ),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 16.sp), bodyMedium = base.copy(fontSize = 14.sp), bodySmall = base.copy(fontSize = 12.sp),
            titleLarge = base.copy(fontFamily = Grotesk, fontSize = 22.sp), titleMedium = base.copy(fontFamily = Grotesk, fontSize = 18.sp),
            labelLarge = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
