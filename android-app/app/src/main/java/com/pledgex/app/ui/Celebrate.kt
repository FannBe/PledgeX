package com.pledgex.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Bit(val angle: Float, val speed: Float, val spin: Float, val color: Int, val w: Float, val h: Float)

/**
 * A short burst of Solana-coloured confetti from the top centre, drawn on one canvas
 * (no library). `key` changes start a new burst; it fades out on its own in ~1.8 s.
 */
@Composable
fun Confetti(key: Long, modifier: Modifier = Modifier.fillMaxSize()) {
    if (key == 0L) return
    val bits = remember(key) {
        val r = Random(key)
        val colors = listOf(Purple, Mint, Cyan, Gold)
        List(90) {
            Bit(
                angle = (r.nextFloat() * 140f + 20f) * (Math.PI / 180f).toFloat(),
                speed = 0.55f + r.nextFloat() * 0.75f,
                spin = r.nextFloat() * 720f - 360f,
                color = it % colors.size,
                w = 6f + r.nextFloat() * 6f,
                h = 10f + r.nextFloat() * 10f,
            )
        }
    }
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.animateTo(1f, tween(1800, easing = LinearEasing)) }
    if (t.value >= 1f) return
    val colors = listOf(Purple, Mint, Cyan, Gold)
    Canvas(modifier) {
        val origin = Offset(size.width / 2, size.height * 0.18f)
        val p = t.value
        bits.forEach { b ->
            val dist = b.speed * size.minDimension * 0.75f * p
            val x = origin.x + cos(b.angle) * dist * if (b.color % 2 == 0) 1f else -1f
            val y = origin.y - sin(b.angle) * dist * 0.6f + 900f * p * p // gravity
            rotate(b.spin * p, Offset(x, y)) {
                drawRect(colors[b.color].copy(alpha = 1f - p), Offset(x - b.w / 2, y - b.h / 2), Size(b.w, b.h))
            }
        }
    }
}
