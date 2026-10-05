package com.pledgex.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/**
 * A shareable image of a settled pledge, with the transaction's Explorer link in the
 * text: the result can be checked by anyone, not just looked at.
 */
object ShareCard {
    fun share(context: Context, result: SettleResult) {
        val bitmap = draw(context, result)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "pledgex-result.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val link = "https://explorer.solana.com/tx/${result.signature}?cluster=devnet"
        val text = "I kept ${result.completed} of ${result.total} days on PledgeX. " +
            "${formatSkr(result.refund)} test SKR came back, ${formatSkr(result.burn)} burned. Proof: $link"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your result").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun draw(context: Context, r: SettleResult): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8f
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFF9945FF.toInt(), 0xFF14F195.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(40f, 40f, w - 40f, h - 40f), 56f, 56f, border)
        BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)?.let { logo ->
            c.drawBitmap(Bitmap.createScaledBitmap(logo, 220, 220, true), (w - 220) / 2f, 110f, null)
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 84f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val big = Paint(title).apply { textSize = 150f; shader = LinearGradient(300f, 0f, 780f, 0f, 0xFF9945FF.toInt(), 0xFF14F195.toInt(), Shader.TileMode.CLAMP) }
        val body = Paint(title).apply { textSize = 46f; typeface = Typeface.DEFAULT; color = 0xFFB0B0C0.toInt() }
        c.drawText("PledgeX", w / 2f, 420f, title)
        c.drawText("${r.completed} / ${r.total} days", w / 2f, 600f, big)
        c.drawText("${formatSkr(r.refund)} test SKR returned", w / 2f, 720f, body)
        c.drawText("${formatSkr(r.burn)} test SKR burned — nobody got it", w / 2f, 790f, body)
        c.drawText("Settled on Solana devnet · ${r.signature.take(8)}…", w / 2f, 940f, body.apply { textSize = 36f })
        return bmp
    }
}
