package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun loadChannel(state: Screen.ChannelPage, scope: CoroutineScope) {
    state.channel.loading = true
    state.channel.error = null
    scope.launch {
        try {
            state.channel.data = KickApi.channel(state.slug)
        } catch (e: Exception) {
            state.channel.error = e.message ?: "Couldn't load channel"
        } finally {
            state.channel.loading = false
        }
    }
    state.videos.loading = true
    state.videos.error = null
    scope.launch {
        try {
            state.videos.data = KickApi.videos(state.slug)
        } catch (e: Exception) {
            state.videos.error = e.message ?: "Couldn't load videos"
        } finally {
            state.videos.loading = false
        }
    }
}

private fun loadClips(state: Screen.ChannelPage, ch: Channel, scope: CoroutineScope) {
    val f = state.clipFilters
    state.clips.loadMore(scope) { cursor -> KickApi.channelClips(ch, f.sort, f.time, cursor) }
}

@Composable
fun ChannelScreen(state: Screen.ChannelPage, actions: Actions) {
    val scope = rememberCoroutineScope()
    val follows by actions.store.follows.collectAsState()
    LaunchedEffect(Unit) { if (state.channel.data == null) loadChannel(state, scope) }

    val ch = state.channel.data
    val videos = state.videos.data

    // Deep link to a specific VOD: play it once both channel and videos are in.
    LaunchedEffect(ch, videos) {
        val uuid = state.autoPlayUuid
        if (uuid != null && !state.autoPlayed && ch != null && videos != null) {
            videos.firstOrNull { it.uuid == uuid }?.let {
                state.autoPlayed = true
                actions.playVod(ch, it)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(ch?.username ?: state.slug, onBack = { actions.back() }) {
            IconButton(onClick = {
                loadChannel(state, scope)
                state.clips.reset()
            }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        if (ch == null) {
            if (state.channel.error != null) ErrorBox(state.channel.error ?: "") { loadChannel(state, scope) } else LoadingBox()
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(360.dp), Modifier.fillMaxSize(), state = state.grid) {
            full("header") { Header(ch, follows.any { it.slug.equals(ch.slug, true) }, actions) }
            ch.live?.let { live ->
                full("live") {
                    Column(Modifier.padding(top = 4.dp)) {
                        StreamCard(
                            StreamItem(
                                ch.slug, ch.username, live.title, live.viewers, live.category,
                                // Prefer the Live-tab-style thumbnail if Home already has one.
                                (actions.nav.stack.firstOrNull() as? Screen.Home)?.followThumbs?.get(ch.slug.lowercase()) ?: live.thumbnail,
                                ch.avatar,
                            ),
                            onClick = { actions.playLive(ch) },
                            onChannel = {},
                        )
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { actions.playLive(ch) }) { Text("Watch live") }
                            OutlinedButton(onClick = { actions.watchFromStart(ch) }) { Text("Watch from start") }
                        }
                    }
                }
            }
            full("tabs") {
                TabRow(selectedTabIndex = state.tab) {
                    Tab(selected = state.tab == 0, onClick = { state.tab = 0 }, text = { Text("Videos") })
                    Tab(selected = state.tab == 1, onClick = { state.tab = 1 }, text = { Text("Clips") })
                }
            }
            if (state.tab == 0) {
                when {
                    videos != null && videos.isEmpty() -> full { EmptyBox("No videos") }
                    videos != null -> itemsIndexed(videos, key = { i, v -> "v_${v.key}_$i" }) { _, v ->
                        VideoCard(v, actions.store.position(v.key)) {
                            if (v.isLive && ch.live != null) actions.watchFromStart(ch) else actions.playVod(ch, v)
                        }
                    }
                    state.videos.error != null -> full { ErrorBox(state.videos.error ?: "") { loadChannel(state, scope) } }
                    else -> full { LoadingBox() }
                }
            } else {
                full("clipfilters") {
                    ClipFilterRow(state.clipFilters) {
                        state.clips.reset()
                        loadClips(state, ch, scope)
                    }
                }
                items(state.clips.items, key = { "clip_" + it.id }) { c ->
                    ClipCard(c, onClick = { actions.playClip(c) }, onChannel = {})
                }
                full("clipfooter") { PagedFooter(state.clips, "No clips") { loadClips(state, ch, scope) } }
            }
        }
    }
}

@Composable
private fun Header(ch: Channel, followed: Boolean, actions: Actions) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(ch.avatar, 64)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.username, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ch.verified) Text("  ✓", color = AccentCyan, fontWeight = FontWeight.Bold)
                }
                if (ch.followers > 0) {
                    Text("${compact(ch.followers)} followers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            if (followed) {
                OutlinedButton(onClick = { actions.toggleFollow(ch.slug, ch.username, ch.avatar) }) { Text("Following") }
            } else {
                Button(onClick = { actions.toggleFollow(ch.slug, ch.username, ch.avatar) }) { Text("Follow") }
            }
        }
        ch.bio?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
