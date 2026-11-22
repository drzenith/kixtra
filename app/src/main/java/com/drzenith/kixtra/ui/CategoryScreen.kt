package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope

private fun loadStreams(state: Screen.CategoryPage, scope: CoroutineScope) {
    val f = state.filters
    state.streams.loadMore(scope) { cursor ->
        if (state.id == 0L) state.id = KickApi.category(state.slug)?.id ?: 0L
        if (state.id == 0L) throw IllegalStateException("Unknown category")
        KickApi.liveStreams(cursor, state.id, f.language, f.sort)
    }
}

private fun loadClips(state: Screen.CategoryPage, scope: CoroutineScope) {
    val f = state.clipFilters
    state.clips.loadMore(scope) { cursor -> KickApi.categoryClips(state.slug, f.sort, f.time, cursor) }
}

@Composable
fun CategoryScreen(state: Screen.CategoryPage, actions: Actions) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BackBar(state.name, onBack = { actions.back() })
        TabRow(selectedTabIndex = state.tab) {
            Tab(selected = state.tab == 0, onClick = { state.tab = 0 }, text = { Text("Live") })
            Tab(selected = state.tab == 1, onClick = { state.tab = 1 }, text = { Text("Clips") })
        }
        if (state.tab == 0) {
            StreamFilterRow(state.filters) {
                state.streams.reset()
                loadStreams(state, scope)
            }
            val hide = state.filters.hideMature
            LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.grid) {
                items(state.streams.items.filter { !(hide && it.mature) }, key = { it.slug }) { s ->
                    StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
                }
                full { PagedFooter(state.streams, "Nobody is live here with these filters") { loadStreams(state, scope) } }
            }
        } else {
            ClipFilterRow(state.clipFilters) {
                state.clips.reset()
                loadClips(state, scope)
            }
            LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), state = state.grid) {
                items(state.clips.items, key = { it.id }) { c ->
                    ClipCard(c, onClick = { actions.playClip(c) }, onChannel = { actions.openChannel(c.channelSlug) })
                }
                full { PagedFooter(state.clips, "No clips") { loadClips(state, scope) } }
            }
        }
    }
}
