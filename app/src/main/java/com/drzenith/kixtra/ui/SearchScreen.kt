package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(state: Screen.Search, actions: Actions) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val jobHolder = remember { arrayOfNulls<Job>(1) }

    fun search(immediate: Boolean) {
        val q = state.query.trim()
        jobHolder[0]?.cancel()
        if (q.length < 2) return
        jobHolder[0] = scope.launch {
            if (!immediate) delay(400)
            state.results.loading = true
            state.results.error = null
            try {
                state.results.data = KickApi.search(q)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.results.error = e.message ?: "Search failed"
            } finally {
                state.results.loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (state.query.isEmpty()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.back() }) { Text("←") }
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it; search(false) },
                placeholder = { Text("Search channels, streams, categories") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val r = state.results.data
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.results.loading && r == null) item { LoadingBox() }
            state.results.error?.let { err -> item { ErrorBox(err) { search(true) } } }
            if (r != null) {
                if (r.channels.isEmpty() && r.streams.isEmpty() && r.categories.isEmpty()) {
                    item { EmptyBox("No results") }
                }
                if (r.channels.isNotEmpty()) item { SectionTitle("Channels") }
                items(r.channels, key = { "c_" + it.slug }) { c ->
                    ChannelRow(c.avatar, c.username, if (c.verified) "Verified" else null, c.isLive) {
                        actions.openChannel(c.slug)
                    }
                }
                if (r.categories.isNotEmpty()) {
                    item { SectionTitle("Categories") }
                    item {
                        LazyRow {
                            items(r.categories, key = { "cat_" + it.slug }) { cat ->
                                Column(Modifier.width(120.dp)) {
                                    CategoryCard(cat) { actions.openCategory(cat.slug, cat.name, cat.id) }
                                }
                            }
                        }
                    }
                }
                if (r.streams.isNotEmpty()) item { SectionTitle("Live streams") }
                items(r.streams, key = { "s_" + it.slug }) { s ->
                    StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
                }
            }
        }
    }
}
