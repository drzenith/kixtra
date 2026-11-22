package com.drzenith.kixtra.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val emoteRegex = Regex("""\[emote:(\d+):([^\]]*)]""")

/**
 * Chat list. Sticks to the newest message while you're at the bottom; scroll up to read back and
 * a "Latest messages" button appears to jump down again.
 *
 * [overlay] = drawn on top of the video: no panel background, white text with a shadow.
 */
@Composable
fun ChatView(
    messages: List<ChatMsg>,
    status: String,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
    showStatus: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout: item 0 is the newest message, drawn at the bottom.
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24 }
    }
    var follow by remember { mutableStateOf(true) }
    // Your own scrolling decides whether we keep following new messages.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = atBottom
    }
    val newest = messages.lastOrNull()?.id
    LaunchedEffect(newest) {
        if (follow && !listState.isScrollInProgress && messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(if (overlay) modifier else modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (showStatus) {
            Text(
                status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
                items(messages.asReversed(), key = { it.id }) { m -> ChatLine(m, overlay) }
            }
            if (!follow && messages.isNotEmpty()) {
                Text(
                    "↓ Latest messages",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
                        .clickable {
                            follow = true
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
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

private val overlayShadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 3f)

@Composable
private fun ChatLine(m: ChatMsg, overlay: Boolean) {
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
        color = if (overlay) Color.White else Color.Unspecified,
        style = if (overlay) TextStyle(shadow = overlayShadow) else TextStyle.Default,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
