package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    // Keep follows' live status and the Live list fresh while Home is on screen, and refresh
    // right away when you come back to the app (the loop pauses while the app is in the
    // background, then runs again on return).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(follows) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                val followsAge = now - state.followsLoadedAt
                if (state.follows.data == null || followsAge > 60_000 || state.follows.data?.size != follows.size) {
                    // Old enough that the list may be wrong: show that it's updating.
                    if (state.follows.data != null && followsAge > 3 * 60_000) state.followsVisibleRefresh = true
                    refreshFollows(state, follows, actions.store, scope)
                }
                val s = state.streams
                if (s.items.isNotEmpty() && now - s.loadedAt > 3 * 60_000 &&
                    (now - s.loadedAt > 10 * 60_000 || state.streamsGrid.firstVisibleItemIndex < 6)
                ) {
                    refreshStreams(state, scope)
                }
                delay(15_000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(TABS[state.tab].label) },
            actions = {
                if (state.tab == 0) {
                    IconButton(onClick = { refreshFollows(state, follows, actions.store, scope) }) {
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

/** Fetches each followed channel; works out when offline ones were last live. */
private suspend fun fetchFollows(list: List<FollowedChannel>, store: Store): List<Channel> = coroutineScope {
    val now = System.currentTimeMillis()
    list.map { f ->
        async {
            val ch = runCatching { KickApi.channel(f.slug) }.getOrElse {
                Channel(0, f.slug, f.username, f.avatar, null, 0, false, null, null)
            }
            if (ch.live != null) {
                store.saveLastLive(f.slug, now)
                ch.copy(lastLiveMs = now)
            } else {
                val fromApi = ch.lastLiveMs ?: if (ch.id != 0L) KickApi.lastLiveFromVideos(f.slug) else null
                fromApi?.let { store.saveLastLive(f.slug, it) }
                ch.copy(lastLiveMs = listOfNotNull(fromApi, store.lastLive(f.slug)).maxOrNull())
            }
        }
    }.awaitAll()
}

private fun refreshFollows(state: Screen.Home, follows: List<FollowedChannel>, store: Store, scope: CoroutineScope) {
    if (state.follows.loading) return
    state.follows.loading = true
    state.follows.error = null
    scope.launch {
        try {
            val channels = fetchFollows(follows, store)
            state.follows.data = channels
            state.followsLoadedAt = System.currentTimeMillis()
            loadFollowThumbs(state, channels.filter { it.live != null })
        } catch (e: Exception) {
            state.follows.error = e.message
        } finally {
            state.follows.loading = false
            state.followsVisibleRefresh = false
        }
    }
}

/**
 * Live follows' thumbnails come from the same source as the Live tab (its stream list), since the
 * channel endpoint's thumbnails don't load. Channels not in the top of that list are looked up via
 * search, which returns streams in the same format.
 */
private suspend fun loadFollowThumbs(state: Screen.Home, live: List<Channel>) {
    if (live.isEmpty()) return
    val wanted = live.map { it.slug.lowercase() }.toMutableSet()
    val found = HashMap<String, String>()
    var cursor: String? = null
    for (page in 0 until 2) {
        val p = runCatching { KickApi.liveStreams(cursor) }.getOrNull() ?: break
        p.items.forEach { s ->
            val key = s.slug.lowercase()
            if (key in wanted && s.thumbnail != null) found[key] = s.thumbnail
        }
        wanted.removeAll(found.keys)
        cursor = p.next ?: break
        if (wanted.isEmpty()) break
    }
    if (wanted.isNotEmpty()) {
        coroutineScope {
            live.filter { it.slug.lowercase() in wanted }.map { ch ->
                async {
                    runCatching { KickApi.search(ch.username) }.getOrNull()?.streams
                        ?.firstOrNull { it.slug.equals(ch.slug, true) }?.thumbnail
                        ?.let { ch.slug.lowercase() to it }
                }
            }.awaitAll().filterNotNull().forEach { (k, v) -> found[k] = v }
        }
    }
    state.followThumbs.putAll(found)
}

/** Followed channels split into live (most viewers first) and offline (most recently live first). */
private fun splitFollows(state: Screen.Home, follows: List<FollowedChannel>): Pair<List<Channel>, List<Channel>> {
    val followedSlugs = follows.map { it.slug.lowercase() }.toSet()
    val channels = (state.follows.data ?: emptyList()).filter { it.slug.lowercase() in followedSlugs }
    val live = channels.filter { it.live != null }.sortedByDescending { it.live?.viewers ?: 0 }
    val offline = channels.filter { it.live == null }
        .sortedWith(compareByDescending<Channel> { it.lastLiveMs ?: 0L }.thenBy { it.username.lowercase() })
    return live to offline
}

/** Prefer the Live tab's thumbnail for a channel (those load reliably), else the channel's own. */
private fun thumbFor(state: Screen.Home, slug: String, fallback: String?): String? =
    state.followThumbs[slug.lowercase()]
        ?: state.streams.items.firstOrNull { it.slug.equals(slug, true) }?.thumbnail
        ?: fallback

/** Full-width grid item (headers, footers, messages). */
fun LazyGridScope.full(key: String? = null, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowingTab(state: Screen.Home, follows: List<FollowedChannel>, actions: Actions, scope: CoroutineScope) {
    if (follows.isEmpty()) {
        EmptyBox("No follows yet. Open a channel and tap Follow. Follows are saved on this device.")
        return
    }
    val (live, offline) = splitFollows(state, follows)

    // Pull down to refresh. Only show the spinner for refreshes you started by pulling,
    // not the automatic background ones.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.follows.loading) { if (!state.follows.loading) pulled = false }

    PullToRefreshBox(
        isRefreshing = (pulled || state.followsVisibleRefresh) && state.follows.loading,
        onRefresh = {
            pulled = true
            refreshFollows(state, follows, actions.store, scope)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.followsList) {
        if (state.follows.data == null && state.follows.loading) full { LoadingBox() }
        if (live.isNotEmpty()) full("h_live") { SectionTitle("Live now") }
        items(live, key = { "live_" + it.slug }) { ch ->
            val l = ch.live!!
            StreamCard(
                item = StreamItem(ch.slug, ch.username, l.title, l.viewers, l.category, thumbFor(state, ch.slug, l.thumbnail), ch.avatar, startTime = l.startTime, livestreamId = l.id),
                onClick = { actions.playLive(ch) },
                onChannel = { actions.openChannel(ch.slug) },
            )
        }
        if (offline.isNotEmpty()) full("h_off") { SectionTitle("Offline") }
        items(offline, key = { "off_" + it.slug }) { ch ->
            val subtitle = listOfNotNull(
                ch.lastLiveMs?.let { "Last live ${agoMs(it)}" },
                if (ch.followers > 0) "${compact(ch.followers)} followers" else null,
            ).joinToString(" • ").ifBlank { null }
            ChannelRow(ch.avatar, ch.username, subtitle, false) { actions.openChannel(ch.slug) }
        }
    }
    }
}

// ---------- Live ----------

private fun loadStreams(state: Screen.Home, scope: CoroutineScope) {
    val f = state.streamFilters
    state.streams.loadMore(scope) { cursor -> KickApi.liveStreams(cursor, null, f.language, f.sort) }
}

private fun refreshStreams(state: Screen.Home, scope: CoroutineScope) {
    val f = state.streamFilters
    state.streams.refresh(scope) { cursor -> KickApi.liveStreams(cursor, null, f.language, f.sort) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    val follows by actions.store.follows.collectAsState()
    LaunchedEffect(Unit) { if (state.streams.items.isEmpty()) loadStreams(state, scope) }
    StreamFilterRow(state.streamFilters) {
        state.streams.reset()
        loadStreams(state, scope)
    }
    val hide = actions.store.hideMature
    val liveFollows = splitFollows(state, follows).first
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.streams.refreshing) { if (!state.streams.refreshing) pulled = false }
    PullToRefreshBox(
        isRefreshing = pulled && state.streams.refreshing,
        onRefresh = {
            pulled = true
            refreshStreams(state, scope)
            refreshFollows(state, follows, actions.store, scope)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.streamsGrid) {
        if (liveFollows.isNotEmpty()) {
            full("h_follows") { SectionTitle("Live from your follows") }
            full("follows_row") {
                LazyRow {
                    rowItems(liveFollows, key = { "f_" + it.slug }) { ch ->
                        val l = ch.live!!
                        Column(Modifier.width(280.dp)) {
                            StreamCard(
                                item = StreamItem(ch.slug, ch.username, l.title, l.viewers, l.category, thumbFor(state, ch.slug, l.thumbnail), ch.avatar, startTime = l.startTime, livestreamId = l.id),
                                onClick = { actions.playLive(ch) },
                                onChannel = { actions.openChannel(ch.slug) },
                            )
                        }
                    }
                }
            }
            full("h_all") { SectionTitle("All live streams") }
        }
        items(state.streams.items.filter { !(hide && it.mature) }, key = { it.slug }) { s ->
            StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
        }
        full { PagedFooter(state.streams, "Nobody is live with these filters") { loadStreams(state, scope) } }
    }
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
