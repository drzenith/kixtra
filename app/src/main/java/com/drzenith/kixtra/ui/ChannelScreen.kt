package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.back() }) { Text("←") }
            Text(ch?.username ?: state.slug, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { loadChannel(state, scope) }) { Text("↻") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = state.list) {
            if (ch == null) {
                item {
                    when {
                        state.channel.error != null -> ErrorBox(state.channel.error!!) { loadChannel(state, scope) }
                        else -> LoadingBox()
                    }
                }
                return@LazyColumn
            }
            item { Header(ch, follows.any { it.slug.equals(ch.slug, true) }, actions) }
            ch.live?.let { live ->
                item {
                    Column(Modifier.padding(top = 4.dp)) {
                        StreamCard(
                            com.drzenith.kixtra.data.StreamItem(ch.slug, ch.username, live.title, live.viewers, live.category, live.thumbnail, ch.avatar),
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
            item { SectionTitle("Videos") }
            when {
                videos != null && videos.isEmpty() -> item { EmptyBox("No videos") }
                videos != null -> itemsIndexed(videos, key = { i, v -> "${v.key}_$i" }) { _, v ->
                    VideoCard(v, actions.store.position(v.key)) {
                        if (v.isLive && ch.live != null) actions.watchFromStart(ch) else actions.playVod(ch, v)
                    }
                }
                state.videos.error != null -> item { ErrorBox(state.videos.error!!) { loadChannel(state, scope) } }
                else -> item { LoadingBox() }
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
                    Text(ch.username, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (ch.verified) Text("  ✓", color = KickGreen, fontWeight = FontWeight.Bold)
                }
                if (ch.followers > 0) {
                    Text("${compact(ch.followers)} followers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            if (followed) {
                OutlinedButton(onClick = { actions.toggleFollow(ch.slug, ch.username, ch.avatar) }) { Text("Following") }
            } else {
                Button(
                    onClick = { actions.toggleFollow(ch.slug, ch.username, ch.avatar) },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("Follow") }
            }
        }
        ch.bio?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
