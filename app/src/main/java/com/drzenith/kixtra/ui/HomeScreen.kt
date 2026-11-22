package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class HomeTab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    HomeTab("Following", Icons.Filled.Favorite),
    HomeTab("Live", Icons.Filled.PlayArrow),
    HomeTab("Categories", Icons.Filled.List),
    HomeTab("Clips", Icons.Filled.Star),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: Screen.Home, actions: Actions) {
    val scope = rememberCoroutineScope()
    val follows by actions.store.follows.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(TABS[state.tab].label) },
            actions = {
                if (state.tab == 0) {
                    IconButton(onClick = { refreshFollows(state, follows, scope) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = { actions.openSearch() }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                IconButton(onClick = { actions.openSettings() }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                0 -> FollowingTab(state, follows, actions, scope)
                1 -> LiveTab(state, actions, scope)
                2 -> CategoriesTab(state, actions, scope)
                else -> ClipsTab(state, actions, scope)
            }
        }
        NavigationBar {
            TABS.forEachIndexed { i, tab ->
                NavigationBarItem(
                    selected = state.tab == i,
                    onClick = { state.tab = i },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

// ---------- Following ----------

private suspend fun fetchFollows(list: List<FollowedChannel>): List<Channel> = coroutineScope {
    list.map { f ->
        async {
            runCatching { KickApi.channel(f.slug) }.getOrElse {
                Channel(0, f.slug, f.username, f.avatar, null, 0, false, null, null)
            }
        }
    }.awaitAll()
}

private fun refreshFollows(state: Screen.Home, follows: List<FollowedChannel>, scope: CoroutineScope) {
    if (state.follows.loading) return
    state.follows.loading = true
    state.follows.error = null
    scope.launch {
        try {
            state.follows.data = fetchFollows(follows)
        } catch (e: Exception) {
            state.follows.error = e.message
        } finally {
            state.follows.loading = false
        }
    }
}

/** Full-width grid item (headers, footers, messages). */
fun LazyGridScope.full(key: String? = null, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun FollowingTab(state: Screen.Home, follows: List<FollowedChannel>, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(follows) { refreshFollows(state, follows, scope) }
    if (follows.isEmpty()) {
        EmptyBox("No follows yet. Open a channel and tap Follow. Follows are saved on this device.")
        return
    }
    val data = state.follows.data
    val followedSlugs = follows.map { it.slug.lowercase() }.toSet()
    val channels = (data ?: emptyList()).filter { it.slug.lowercase() in followedSlugs }
    val live = channels.filter { it.live != null }.sortedByDescending { it.live?.viewers ?: 0 }
    val offline = channels.filter { it.live == null }.sortedBy { it.username.lowercase() }

    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.followsList) {
        if (data == null && state.follows.loading) full { LoadingBox() }
        if (live.isNotEmpty()) full("h_live") { SectionTitle("Live now") }
        items(live, key = { "live_" + it.slug }) { ch ->
            val l = ch.live!!
            StreamCard(
                item = StreamItem(ch.slug, ch.username, l.title, l.viewers, l.category, l.thumbnail, ch.avatar),
                onClick = { actions.playLive(ch) },
                onChannel = { actions.openChannel(ch.slug) },
            )
        }
        if (offline.isNotEmpty()) full("h_off") { SectionTitle("Offline") }
        items(offline, key = { "off_" + it.slug }) { ch ->
            ChannelRow(ch.avatar, ch.username, if (ch.followers > 0) "${compact(ch.followers)} followers" else null, false) {
                actions.openChannel(ch.slug)
            }
        }
    }
}

// ---------- Live ----------

private fun loadStreams(state: Screen.Home, scope: CoroutineScope) {
    val f = state.streamFilters
    state.streams.loadMore(scope) { cursor -> KickApi.liveStreams(cursor, null, f.language, f.sort) }
}

@Composable
private fun LiveTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(Unit) { if (state.streams.items.isEmpty()) loadStreams(state, scope) }
    StreamFilterRow(state.streamFilters) {
        state.streams.reset()
        loadStreams(state, scope)
    }
    val hide = state.streamFilters.hideMature
    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.streamsGrid) {
        items(state.streams.items.filter { !(hide && it.mature) }, key = { it.slug }) { s ->
            StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
        }
        full { PagedFooter(state.streams, "Nobody is live with these filters") { loadStreams(state, scope) } }
    }
}

// ---------- Categories ----------

private fun loadCategories(state: Screen.Home, scope: CoroutineScope) {
    state.categories.loadMore(scope) { cursor -> KickApi.categories(cursor?.toIntOrNull() ?: 1) }
}

@Composable
private fun CategoriesTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(Unit) { if (state.categories.items.isEmpty()) loadCategories(state, scope) }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), state = state.categoriesGrid) {
        items(state.categories.items, key = { it.slug }) { c ->
            CategoryCard(c) { actions.openCategory(c.slug, c.name, c.id) }
        }
        full { PagedFooter(state.categories, "No categories") { loadCategories(state, scope) } }
    }
}

// ---------- Clips ----------

private fun loadClips(state: Screen.Home, scope: CoroutineScope) {
    val f = state.clipFilters
    state.clips.loadMore(scope) { cursor -> KickApi.topClips(f.sort, f.time, cursor) }
}

@Composable
private fun ClipsTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(Unit) { if (state.clips.items.isEmpty()) loadClips(state, scope) }
    ClipFilterRow(state.clipFilters) {
        state.clips.reset()
        loadClips(state, scope)
    }
    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.clipsGrid) {
        items(state.clips.items, key = { it.id }) { c ->
            ClipCard(c, onClick = { actions.playClip(c) }, onChannel = { actions.openChannel(c.channelSlug) })
        }
        full { PagedFooter(state.clips, "No clips") { loadClips(state, scope) } }
    }
}
