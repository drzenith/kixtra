package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun loadMore(state: Screen.CategoryPage, scope: CoroutineScope) {
    if (state.loading || state.end) return
    state.loading = true
    state.error = null
    scope.launch {
        try {
            if (state.id == 0L) state.id = KickApi.category(state.slug)?.id ?: 0L
            if (state.id == 0L) throw IllegalStateException("Unknown category")
            val page = KickApi.liveStreams(state.cursor, state.id)
            val known = state.streams.map { it.slug }.toSet()
            state.streams.addAll(page.items.filter { it.slug !in known })
            state.cursor = page.next
            if (page.next == null) state.end = true
        } catch (e: Exception) {
            state.error = e.message ?: "Couldn't load streams"
        } finally {
            state.loading = false
        }
    }
}

@Composable
fun CategoryScreen(state: Screen.CategoryPage, actions: Actions) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (state.streams.isEmpty()) loadMore(state, scope) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.back() }) { Text("←") }
            Text(state.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), state = state.list) {
            items(state.streams, key = { it.slug }) { s ->
                StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
            }
            item {
                when {
                    state.error != null -> ErrorBox(state.error!!) { loadMore(state, scope) }
                    state.loading -> LoadingBox()
                    state.streams.isEmpty() && state.end -> EmptyBox("Nobody is live here right now")
                    !state.end && state.streams.isNotEmpty() -> LaunchedEffect(state.streams.size) { loadMore(state, scope) }
                }
            }
        }
    }
}
