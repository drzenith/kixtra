package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: Screen.Home, actions: Actions) {
    val scope = rememberCoroutineScope()
    val follows by actions.store.follows.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Kixtra") },
            actions = {
                TextButton(onClick = { actions.openSearch() }) { Text("Search", fontSize = 15.sp) }
                TextButton(onClick = { actions.openSettings() }) { Text("⚙", fontSize = 20.sp) }
            },
        )
        TabRow(selectedTabIndex = state.tab) {
            listOf("Following", "Live", "Categories").forEachIndexed { i, label ->
                Tab(selected = state.tab == i, onClick = { state.tab = i }, text = { Text(label) })
            }
        }
        when (state.tab) {
            0 -> FollowingTab(state, follows, actions, scope)
            1 -> LiveTab(state, actions, scope)
            else -> CategoriesTab(state, actions, scope)
        }
    }
}

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

@Composable
private fun FollowingTab(state: Screen.Home, follows: List<FollowedChannel>, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(follows) { refreshFollows(state, follows, scope) }
    if (follows.isEmpty()) {
        EmptyBox("No follows yet. Open a channel and tap Follow — follows are saved on this device.")
        return
    }
    val data = state.follows.data
    val followedSlugs = follows.map { it.slug.lowercase() }.toSet()
    val channels = (data ?: emptyList()).filter { it.slug.lowercase() in followedSlugs }
    val live = channels.filter { it.live != null }.sortedByDescending { it.live?.viewers ?: 0 }
    val offline = channels.filter { it.live == null }.sortedBy { it.username.lowercase() }

    LazyColumn(Modifier.fillMaxSize(), state = state.followsList) {
        item {
            TextButton(onClick = { refreshFollows(state, follows, scope) }) {
                Text(if (state.follows.loading) "Refreshing…" else "↻ Refresh")
            }
        }
        if (data == null && state.follows.loading) item { LoadingBox() }
        if (live.isNotEmpty()) item { SectionTitle("Live now") }
        items(live, key = { "live_" + it.slug }) { ch ->
            val l = ch.live!!
            StreamCard(
                item = com.drzenith.kixtra.data.StreamItem(ch.slug, ch.username, l.title, l.viewers, l.category, l.thumbnail, ch.avatar),
                onClick = { actions.playLive(ch) },
                onChannel = { actions.openChannel(ch.slug) },
            )
        }
        if (offline.isNotEmpty()) item { SectionTitle("Offline") }
        items(offline, key = { "off_" + it.slug }) { ch ->
            ChannelRow(ch.avatar, ch.username, if (ch.followers > 0) "${compact(ch.followers)} followers" else null, false) {
                actions.openChannel(ch.slug)
            }
        }
    }
}

private fun loadMoreStreams(state: Screen.Home, scope: CoroutineScope) {
    if (state.streamsLoading || state.streamsEnd) return
    state.streamsLoading = true
    state.streamsError = null
    scope.launch {
        try {
            val page = KickApi.liveStreams(state.streamsCursor)
            val known = state.streams.map { it.slug }.toSet()
            state.streams.addAll(page.items.filter { it.slug !in known })
            state.streamsCursor = page.next
            if (page.next == null) state.streamsEnd = true
        } catch (e: Exception) {
            state.streamsError = e.message ?: "Couldn't load streams"
        } finally {
            state.streamsLoading = false
        }
    }
}

@Composable
private fun LiveTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(Unit) { if (state.streams.isEmpty()) loadMoreStreams(state, scope) }
    LazyColumn(Modifier.fillMaxSize(), state = state.streamsList) {
        items(state.streams, key = { it.slug }) { s ->
            StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
        }
        item {
            when {
                state.streamsError != null -> ErrorBox(state.streamsError!!) { loadMoreStreams(state, scope) }
                state.streamsLoading -> LoadingBox()
                !state.streamsEnd && state.streams.isNotEmpty() -> LaunchedEffect(state.streams.size) { loadMoreStreams(state, scope) }
            }
        }
    }
}

private fun loadMoreCategories(state: Screen.Home, scope: CoroutineScope) {
    val page = state.categoriesPage?.toIntOrNull() ?: return
    if (state.categoriesLoading) return
    state.categoriesLoading = true
    state.categoriesError = null
    scope.launch {
        try {
            val p = KickApi.categories(page)
            val known = state.categories.map { it.slug }.toSet()
            state.categories.addAll(p.items.filter { it.slug !in known })
            state.categoriesPage = p.next
        } catch (e: Exception) {
            state.categoriesError = e.message ?: "Couldn't load categories"
        } finally {
            state.categoriesLoading = false
        }
    }
}

@Composable
private fun CategoriesTab(state: Screen.Home, actions: Actions, scope: CoroutineScope) {
    LaunchedEffect(Unit) { if (state.categories.isEmpty()) loadMoreCategories(state, scope) }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), state = state.categoriesGrid) {
        items(state.categories, key = { it.slug }) { c ->
            CategoryCard(c) { actions.openCategory(c.slug, c.name, c.id) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                state.categoriesError != null -> ErrorBox(state.categoriesError!!) { loadMoreCategories(state, scope) }
                state.categoriesLoading -> LoadingBox()
                state.categoriesPage != null && state.categories.isNotEmpty() ->
                    LaunchedEffect(state.categories.size) { loadMoreCategories(state, scope) }
            }
        }
    }
}
