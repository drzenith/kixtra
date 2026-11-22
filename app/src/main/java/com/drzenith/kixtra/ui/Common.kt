package com.drzenith.kixtra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.net.KickApi
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

// ---------- Top bars ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
    )
}

// ---------- Filters ----------

fun languageName(code: String): String {
    if (code.isBlank()) return "Any language"
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return if (name.isNullOrBlank() || name == code) code.uppercase() else name
}

/** Kick's list of stream language codes, fetched once. */
object Languages {
    val codes = mutableStateListOf<String>().apply { addAll(KickApi.DEFAULT_LANGUAGES) }
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val fresh = KickApi.languageCodes()
        codes.clear()
        codes.addAll(fresh.sortedBy { languageName(it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipMenu(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text("$label ▾", maxLines = 1) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = if (value == selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
    )
}

@Composable
fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun LanguageChip(selected: String, onSelect: (String) -> Unit) {
    LaunchedEffect(Unit) { Languages.ensureLoaded() }
    ChipMenu(
        languageName(selected),
        listOf("" to "Any language") + Languages.codes.map { it to languageName(it) },
        selected,
        onSelect,
    )
}

@Composable
fun StreamFilterRow(filters: StreamFilters, onChanged: () -> Unit) {
    FilterRow {
        LanguageChip(filters.language) { filters.language = it; onChanged() }
        ChipMenu(
            if (filters.sort == "featured") "Featured" else "Most viewers",
            listOf("viewer_count_desc" to "Most viewers", "featured" to "Featured"),
            filters.sort,
        ) { filters.sort = it; onChanged() }
        ToggleChip("Hide 18+", filters.hideMature) { filters.hideMature = !filters.hideMature }
    }
}

private val TIME_LABELS = listOf("day" to "Today", "week" to "This week", "month" to "This month", "all" to "All time")

@Composable
fun ClipFilterRow(filters: ClipFilters, onChanged: () -> Unit) {
    FilterRow {
        ChipMenu(
            if (filters.sort == "date") "Newest" else "Most viewed",
            listOf("view" to "Most viewed", "date" to "Newest"),
            filters.sort,
        ) { filters.sort = it; onChanged() }
        ChipMenu(
            TIME_LABELS.firstOrNull { it.first == filters.time }?.second ?: "This week",
            TIME_LABELS,
            filters.time,
        ) { filters.time = it; onChanged() }
    }
}

// ---------- Clips ----------

@Composable
fun ClipCard(clip: Clip, onClick: () -> Unit, onChannel: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Thumb(clip.thumbnail) {
            Box(Modifier.fillMaxSize().padding(6.dp)) {
                if (clip.durationMs > 0) {
                    Badge(hms(clip.durationMs), Color(0xCC000000), modifier = Modifier.align(Alignment.BottomEnd))
                }
                if (clip.views > 0) {
                    Badge("${compact(clip.views)} views", Color(0xCC000000), modifier = Modifier.align(Alignment.TopStart))
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.clickable(onClick = onChannel)) { Avatar(clip.channelAvatar, 36) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(clip.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(clip.channelName, ago(clip.createdAt).ifBlank { null }).joinToString(" • "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onChannel),
                )
                clip.category?.let { Text(it, color = KickGreen, fontSize = 12.sp) }
            }
        }
    }
}

// ---------- Paging ----------

/** Footer for a paged grid: spinner, error with retry, or an auto-trigger for the next page. */
@Composable
fun <T> PagedFooter(paged: Paged<T>, emptyText: String, load: () -> Unit) {
    when {
        paged.error != null -> ErrorBox(paged.error ?: "") { load() }
        paged.loading -> LoadingBox()
        paged.end && paged.items.isEmpty() -> EmptyBox(emptyText)
        !paged.end -> LaunchedEffect(paged.items.size) { load() }
    }
}

/** Grid cell width used for stream/clip cards: 1 column on phones, more on wide screens. */
val CARD_MIN_WIDTH = 320.dp
