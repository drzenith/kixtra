package com.drzenith.kixtra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

private val TYPES = listOf("0" to "Everything", "1" to "Channels", "2" to "Live streams", "3" to "Categories")

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
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it; search(false) },
                placeholder = { Text("Search Kick") },
                singleLine = true,
                trailingIcon = if (state.query.isNotEmpty()) ({
                    IconButton(onClick = { state.query = ""; state.results.data = null }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        FilterRow {
            ChipMenu(TYPES[state.type].second, TYPES, state.type.toString()) { state.type = it.toInt() }
            if (state.type == 0 || state.type == 1) {
                ToggleChip("Live only", state.liveOnly) { state.liveOnly = !state.liveOnly }
            }
            if (state.type == 0 || state.type == 2) {
                LanguageChip(state.language) { state.language = it }
            }
        }

        val r = state.results.data
        val showChannels = state.type == 0 || state.type == 1
        val showStreams = state.type == 0 || state.type == 2
        val showCategories = state.type == 0 || state.type == 3
        val channels = if (!showChannels || r == null) emptyList() else r.channels.filter { !state.liveOnly || it.isLive }
        // Language is only filtered when Kick tells us a stream's language.
        val streams = if (!showStreams || r == null) emptyList() else r.streams.filter {
            state.language.isBlank() || it.language == null || it.language.equals(state.language, true)
        }
        val categories = if (!showCategories || r == null) emptyList() else r.categories

        LazyColumn(Modifier.fillMaxSize(), state = state.list) {
            if (state.results.loading && r == null) item { LoadingBox() }
            state.results.error?.let { err -> item { ErrorBox(err) { search(true) } } }
            if (r != null && channels.isEmpty() && streams.isEmpty() && categories.isEmpty()) {
                item { EmptyBox("No results") }
            }
            if (channels.isNotEmpty()) item { SectionTitle("Channels") }
            items(channels, key = { "c_" + it.slug }) { c ->
                ChannelRow(c.avatar, c.username, if (c.verified) "Verified" else null, c.isLive) {
                    actions.openChannel(c.slug)
                }
            }
            if (categories.isNotEmpty()) {
                item { SectionTitle("Categories") }
                item {
                    LazyRow {
                        items(categories, key = { "cat_" + it.slug }) { cat ->
                            Column(Modifier.width(120.dp)) {
                                CategoryCard(cat) { actions.openCategory(cat.slug, cat.name, cat.id) }
                            }
                        }
                    }
                }
            }
            if (streams.isNotEmpty()) item { SectionTitle("Live streams") }
            items(streams, key = { "s_" + it.slug }) { s ->
                StreamCard(s, onClick = { actions.openLive(s.slug) }, onChannel = { actions.openChannel(s.slug) })
            }
        }
    }
}
