package com.drzenith.kixtra.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

private val emoteRegex = Regex("""\[emote:(\d+):([^\]]*)]""")

@Composable
fun ChatView(messages: List<ChatMsg>, status: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        Text(
            status,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
            items(messages.asReversed(), key = { it.id }) { m -> ChatLine(m) }
        }
    }
}

private fun badgePrefix(badges: List<String>): String = buildString {
    if ("broadcaster" in badges) append("🎙 ")
    if ("moderator" in badges) append("🛡 ")
    if ("vip" in badges) append("💎 ")
    if ("og" in badges) append("👑 ")
    if (badges.any { it.startsWith("sub") }) append("★ ")
}

private fun userColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFA78BFA))

@Composable
private fun ChatLine(m: ChatMsg) {
    val (text, inline) = remember(m.id) {
        val inline = HashMap<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            val prefix = badgePrefix(m.badges)
            if (prefix.isNotEmpty()) append(prefix)
            withStyle(SpanStyle(color = userColor(m.color), fontWeight = FontWeight.Bold)) { append(m.user) }
            append(": ")
            var last = 0
            for (match in emoteRegex.findAll(m.text)) {
                append(m.text.substring(last, match.range.first))
                val id = match.groupValues[1]
                val key = "emote_$id"
                appendInlineContent(key, match.groupValues[2])
                if (key !in inline) {
                    inline[key] = InlineTextContent(Placeholder(22.sp, 22.sp, PlaceholderVerticalAlign.Center)) {
                        AsyncImage(
                            model = "https://files.kick.com/emotes/$id/fullsize",
                            contentDescription = match.groupValues[2],
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                last = match.range.last + 1
            }
            if (last < m.text.length) append(m.text.substring(last))
        }
        annotated to inline
    }
    Text(
        text,
        inlineContent = inline,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
