package com.pledgex.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(20.dp)
private val CutShape = androidx.compose.foundation.shape.CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)

/** A grouped dark surface with a hairline, or a Solana-gradient border when `glow`. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, glow: Brush? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .shadow(if (glow != null) 18.dp else 8.dp, CardShape, ambientColor = Purple, spotColor = if (glow != null) Mint else Cyan)
            .clip(CardShape).background(CardFill)
            .border(if (glow != null) 1.5.dp else 1.dp, glow ?: NeonEdge, CardShape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    brush: Brush = SolanaGradient,
    onClick: () -> Unit,
) {
    val on = enabled && !busy
    Box(
        modifier.fillMaxWidth().height(54.dp)
            .then(if (on) Modifier.shadow(14.dp, CutShape, ambientColor = Purple, spotColor = Mint) else Modifier)
            .clip(CutShape)
            .background(if (on || busy) brush else Brush.horizontalGradient(listOf(CardHigh, CardHigh)))
            .clickable(enabled = on, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = OnGradient)
        else Text(text.uppercase(), color = if (on) OnGradient else TextLo, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = Grotesk, letterSpacing = 1.2.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, color: Color = TextHi, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(CutShape).background(Surface1)
            .border(1.dp, color.copy(alpha = 0.45f), CutShape).clickable(enabled = enabled && !busy, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Mint)
        else Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A rounded pill with an optional status dot, as in the header. */
@Composable
fun Pill(text: String, dot: Color? = null, color: Color = Mint, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun Overline(text: String, color: Color = TextLo) =
    Text(text.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Heading(text: String, size: Int = 22) = Text(text, color = TextHi, fontSize = size.sp, fontFamily = Grotesk, fontWeight = FontWeight.Bold)

@Composable
fun Body(text: String, color: Color = TextLo, size: Int = 14) = Text(text, color = color, fontSize = size.sp, lineHeight = (size + 6).sp)

@Composable
fun Mono(text: String, color: Color = Mint, size: Int = 15) =
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

@Composable
fun StatTile(label: String, value: String, color: Color, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(CardFill).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = TextLo, fontSize = 12.sp)
        Text(value, color = color, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(sub, color = TextDim, fontSize = 11.sp)
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = TextHi) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextLo, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A square tile with a flat line icon, tinted like the old catalog icons. */
@Composable
fun IconTile(glyph: Glyph, tint: Color, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { FlatIcon(glyph, tint, Modifier.size(size * 0.5f)) }
}

/** The habit ring: a dim track and a Solana-gradient arc. */
@Composable
fun Ring(progress: Float, modifier: Modifier, done: Boolean, failed: Boolean = false) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        progress.coerceIn(0f, 1f), androidx.compose.animation.core.tween(900), label = "ring",
    )
    val glow by androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(
        0.25f, 0.6f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "glow",
    )
    Canvas(modifier) {
        if (done) drawCircle(Mint.copy(alpha = glow * 0.25f), radius = size.minDimension / 2 + 6.dp.toPx())
        val stroke = 16.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(Color(0xFF26262B), -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        val brush = when {
            failed -> Brush.sweepGradient(listOf(Burn, Amber, Burn))
            done -> Brush.sweepGradient(listOf(Mint, Cyan, Mint))
            else -> Brush.sweepGradient(listOf(Purple, Mint, Purple))
        }
        if (shown > 0f) drawArc(brush, -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun openUrl(): (String) -> Unit {
    val context = LocalContext.current
    return { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun short(address: String) = "${address.take(4)}…${address.takeLast(4)}"

fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s >= 86_400 -> "%dd %02dh %02dm".format(s / 86_400, s % 86_400 / 3600, s % 3600 / 60)
        s >= 3600 -> "%dh %02dm %02ds".format(s / 3600, s % 3600 / 60, s % 60)
        else -> "%02dm %02ds".format(s / 60, s % 60)
    }
}

/** "2 hours", "1 hour", "40 seconds", "90 minutes": for rules, not countdowns. */
fun humanDuration(sec: Long): String = when {
    sec % 3600 == 0L -> (sec / 3600).let { if (it == 1L) "1 hour" else "$it hours" }
    sec % 60 == 0L -> (sec / 60).let { if (it == 1L) "1 minute" else "$it minutes" }
    else -> "$sec seconds"
}

fun ago(unixSec: Long, nowSec: Long): String {
    val d = (nowSec - unixSec).coerceAtLeast(0)
    return when {
        d < 60 -> "${d}s ago"
        d < 3600 -> "${d / 60}m ago"
        d < 86_400 -> "${d / 3600}h ago"
        else -> "${d / 86_400}d ago"
    }
}

const val EXPLORER = "https://explorer.solana.com"
fun explorerTx(sig: String) = "$EXPLORER/tx/$sig?cluster=devnet"
fun explorerAddress(a: String) = "$EXPLORER/address/$a?cluster=devnet"
