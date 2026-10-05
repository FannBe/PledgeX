package com.pledgex.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Flat, single-colour line icons drawn on a canvas (no icon library, no emoji). */
enum class Glyph { Walk, Alarm, PhoneOff, Lock, Flame, Shield, Bolt, Compass, Bars, Plus, Hourglass, Info, Check, Trophy }

@Composable
fun FlatIcon(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) { draw(glyph, color) }
}

private fun DrawScope.draw(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)
    fun line(vararg pts: Pair<Float, Float>) {
        for (i in 0 until pts.size - 1) drawLine(color, p(pts[i].first, pts[i].second), p(pts[i + 1].first, pts[i + 1].second), stroke.width, StrokeCap.Round)
    }
    fun poly(fill: Boolean, vararg pts: Pair<Float, Float>) {
        val path = Path().apply {
            moveTo(pts[0].first * w, pts[0].second * h)
            pts.drop(1).forEach { lineTo(it.first * w, it.second * h) }
            close()
        }
        if (fill) drawPath(path, color) else drawPath(path, color, style = stroke)
    }
    when (glyph) {
        Glyph.Walk -> {
            drawCircle(color, w * 0.09f, p(0.56f, 0.13f))
            line(0.53f to 0.28f, 0.46f to 0.56f)
            line(0.3f to 0.44f, 0.5f to 0.32f, 0.68f to 0.46f)
            line(0.46f to 0.56f, 0.33f to 0.9f)
            line(0.46f to 0.56f, 0.6f to 0.72f, 0.64f to 0.9f)
        }
        Glyph.Alarm -> {
            drawCircle(color, w * 0.32f, p(0.5f, 0.55f), style = stroke)
            line(0.5f to 0.38f, 0.5f to 0.55f, 0.63f to 0.63f)
            line(0.13f to 0.27f, 0.27f to 0.13f)
            line(0.87f to 0.27f, 0.73f to 0.13f)
        }
        Glyph.PhoneOff -> {
            drawRoundRect(color, p(0.3f, 0.08f), Size(0.4f * w, 0.84f * h), CornerRadius(w * 0.08f), style = stroke)
            line(0.44f to 0.8f, 0.56f to 0.8f)
            line(0.12f to 0.12f, 0.88f to 0.88f)
        }
        Glyph.Lock -> {
            drawRoundRect(color, p(0.2f, 0.44f), Size(0.6f * w, 0.48f * h), CornerRadius(w * 0.08f), style = stroke)
            drawArc(color, 180f, 180f, false, p(0.32f, 0.12f), Size(0.36f * w, 0.5f * h), style = stroke)
            line(0.32f to 0.37f, 0.32f to 0.44f); line(0.68f to 0.37f, 0.68f to 0.44f)
            line(0.5f to 0.62f, 0.5f to 0.74f)
        }
        Glyph.Flame -> {
            val path = Path().apply {
                moveTo(0.5f * w, 0.06f * h)
                cubicTo(0.78f * w, 0.32f * h, 0.9f * w, 0.56f * h, 0.74f * w, 0.8f * h)
                cubicTo(0.64f * w, 0.95f * h, 0.36f * w, 0.95f * h, 0.26f * w, 0.8f * h)
                cubicTo(0.12f * w, 0.58f * h, 0.26f * w, 0.4f * h, 0.4f * w, 0.3f * h)
                cubicTo(0.42f * w, 0.46f * h, 0.48f * w, 0.52f * h, 0.56f * w, 0.52f * h)
                cubicTo(0.62f * w, 0.36f * h, 0.58f * w, 0.2f * h, 0.5f * w, 0.06f * h)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.Shield -> {
            poly(false, 0.5f to 0.06f, 0.88f to 0.2f, 0.84f to 0.58f, 0.5f to 0.94f, 0.16f to 0.58f, 0.12f to 0.2f)
            line(0.34f to 0.5f, 0.46f to 0.62f, 0.66f to 0.38f)
        }
        Glyph.Bolt -> poly(true, 0.58f to 0.04f, 0.2f to 0.56f, 0.48f to 0.56f, 0.4f to 0.96f, 0.8f to 0.42f, 0.52f to 0.42f)
        Glyph.Compass -> {
            drawCircle(color, w * 0.42f, p(0.5f, 0.5f), style = stroke)
            poly(true, 0.68f to 0.32f, 0.56f to 0.56f, 0.32f to 0.68f, 0.44f to 0.44f)
        }
        Glyph.Bars -> listOf(0.12f to 0.55f, 0.4f to 0.2f, 0.68f to 0.4f).forEach { (x, top) ->
            drawRoundRect(color, p(x, top), Size(0.2f * w, (0.9f - top) * h), CornerRadius(w * 0.05f))
        }
        Glyph.Plus -> { line(0.5f to 0.18f, 0.5f to 0.82f); line(0.18f to 0.5f, 0.82f to 0.5f) }
        Glyph.Hourglass -> {
            line(0.22f to 0.1f, 0.78f to 0.1f); line(0.22f to 0.9f, 0.78f to 0.9f)
            poly(false, 0.28f to 0.1f, 0.72f to 0.1f, 0.5f to 0.5f); poly(false, 0.5f to 0.5f, 0.72f to 0.9f, 0.28f to 0.9f)
        }
        Glyph.Info -> {
            drawCircle(color, w * 0.42f, p(0.5f, 0.5f), style = stroke)
            line(0.5f to 0.45f, 0.5f to 0.72f); drawCircle(color, w * 0.05f, p(0.5f, 0.3f))
        }
        Glyph.Check -> line(0.18f to 0.52f, 0.42f to 0.76f, 0.84f to 0.26f)
        Glyph.Trophy -> {
            poly(false, 0.28f to 0.1f, 0.72f to 0.1f, 0.68f to 0.46f, 0.5f to 0.58f, 0.32f to 0.46f)
            line(0.5f to 0.58f, 0.5f to 0.76f); line(0.32f to 0.9f, 0.68f to 0.9f); line(0.4f to 0.76f, 0.6f to 0.76f)
            line(0.28f to 0.18f, 0.12f to 0.2f, 0.18f to 0.36f, 0.3f to 0.38f); line(0.72f to 0.18f, 0.88f to 0.2f, 0.82f to 0.36f, 0.7f to 0.38f)
        }
    }
}
