package com.pledgex.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Page(val glyph: Glyph, val color: Color, val kicker: String, val title: String, val body: String)

private val PAGES = listOf(
    Page(Glyph.Lock, Purple, "01 · PLEDGE", "Put your habit on the line",
        "Pick a habit, a number of days and a stake. The stake goes into an escrow on Solana that nobody can touch, not even us."),
    Page(Glyph.Bolt, Mint, "02 · KEEP THE DAY", "One tap a day",
        "Walk your steps, wake up by 6, or put the phone down. Then clock in with one tap. A key on this phone signs it, so no wallet popup."),
    Page(Glyph.Flame, Burn, "03 · SETTLE", "Kept days back. Missed days burn.",
        "At the end, every day you kept comes back to you. Every day you missed is burned. Nobody profits from it, and that's the point."),
)

/** First-run introduction: three short pages, skippable, never shown again once done. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().cyberBackground().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Skip", color = TextLo) }
            }
            AnimatedContent(targetState = page, label = "page") { i ->
                val p = PAGES[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier.size(150.dp).shadow(40.dp, CircleShape, ambientColor = p.color, spotColor = p.color)
                            .clip(CircleShape).background(Card).border(2.dp, p.color, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { FlatIcon(p.glyph, p.color, Modifier.size(70.dp)) }
                    Spacer(Modifier.height(18.dp))
                    Text(p.kicker, color = p.color, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text(p.title, color = TextHi, fontSize = 30.sp, fontFamily = Grotesk, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 34.sp)
                    Text(p.body, color = TextLo, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAGES.indices.forEach { i ->
                    Box(Modifier.height(8.dp).width(if (i == page) 26.dp else 8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (i == page) Mint else CardHigh))
                }
            }
            Spacer(Modifier.height(28.dp))
            GradientButton(if (page == PAGES.lastIndex) "Get started" else "Next") {
                if (page == PAGES.lastIndex) onDone() else page++
            }
            Spacer(Modifier.height(10.dp))
            Text("Solana devnet · test tokens · nothing here costs real money", color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
