package com.drzenith.kixtra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.drzenith.kixtra.chat.parseKickTime
import com.drzenith.kixtra.data.Category
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.data.Video
import java.time.Duration
import java.time.Instant
import java.util.Locale

// ---------- Formatting ----------

fun compact(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
    else -> n.toString()
}

fun hms(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

fun ago(time: String?): String {
    val t = parseKickTime(time) ?: return ""
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 60 -> "${d.toMinutes().coerceAtLeast(1)}m ago"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        d.toDays() < 30 -> "${d.toDays()}d ago"
        d.toDays() < 365 -> "${d.toDays() / 30}mo ago"
        else -> "${d.toDays() / 365}y ago"
    }
}

// ---------- Building blocks ----------

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyBox(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Avatar(url: String?, size: Int = 40) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun Badge(text: String, color: Color, textColor: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text,
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier, overlay: @Composable () -> Unit = {}) {
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

@Composable
fun StreamCard(item: StreamItem, onClick: () -> Unit, onChannel: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Thumb(item.thumbnail) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge("LIVE", LiveRed)
                Badge("${compact(item.viewers)} viewers", Color(0xCC000000))
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.clickable(onClick = onChannel)) { Avatar(item.avatar, 36) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(item.username, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onChannel))
                item.category?.let { Text(it, color = KickGreen, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun VideoCard(video: Video, progressMs: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.width(160.dp)) {
            Thumb(video.thumbnail) {
                Box(Modifier.fillMaxSize().padding(4.dp)) {
                    if (video.isLive) Badge("LIVE", LiveRed, modifier = Modifier.align(Alignment.TopStart))
                    if (video.durationMs > 0) {
                        Badge(hms(video.durationMs), Color(0xCC000000), modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
                if (progressMs > 0 && video.durationMs > 0) {
                    val frac = (progressMs.toFloat() / video.durationMs).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                        Box(Modifier.fillMaxWidth(frac).height(3.dp).background(KickGreen))
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            video.category?.let { Text(it, color = KickGreen, fontSize = 12.sp) }
            Text(
                listOfNotNull(
                    if (video.views > 0) "${compact(video.views)} views" else null,
                    ago(video.startTime).ifBlank { null },
                ).joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun CategoryCard(cat: Category, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(model = cat.banner, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
        Text("${compact(cat.viewers)} viewers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
fun ChannelRow(avatar: String?, name: String, subtitle: String?, live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (live) Badge("LIVE", LiveRed)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
}
