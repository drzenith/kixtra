package com.drzenith.kixtra.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drzenith.kixtra.App
import com.drzenith.kixtra.data.Category
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.data.Page
import com.drzenith.kixtra.data.SearchResults
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.data.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Simple async state holder so screens keep their data when you navigate back to them. */
class Loadable<T> {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** An endlessly scrolling list backed by a cursor-paged endpoint. */
class Paged<T>(private val keyOf: (T) -> String) {
    val items = mutableStateListOf<T>()
    private var cursor: String? = null
    var end by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var generation = 0

    fun reset() {
        generation++
        items.clear()
        cursor = null
        end = false
        loading = false
        error = null
    }

    fun loadMore(scope: CoroutineScope, fetch: suspend (String?) -> Page<T>) {
        if (loading || end) return
        loading = true
        error = null
        val gen = generation
        scope.launch {
            try {
                val page = fetch(cursor)
                if (gen != generation) return@launch
                val known = items.map(keyOf).toHashSet()
                items.addAll(page.items.filter { keyOf(it) !in known })
                cursor = page.next
                if (page.next == null || page.items.isEmpty()) end = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Couldn't load"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }
}

/** Filters for live stream lists. language "" = any. */
class StreamFilters {
    var language by mutableStateOf(App.instance.store.defaultLanguage)
    var sort by mutableStateOf("viewer_count_desc")
    var hideMature by mutableStateOf(App.instance.store.hideMature)
}

/** Filters for clip lists. */
class ClipFilters(sort: String = "view", time: String = "week") {
    var sort by mutableStateOf(sort)
    var time by mutableStateOf(time)
}

fun streamPaged() = Paged<StreamItem> { it.slug }
fun clipPaged() = Paged<Clip> { it.id }

/** Each back-stack entry is its own state object, so going back restores lists and scroll. */
sealed class Screen {
    class Home : Screen() {
        var tab by mutableIntStateOf(0)
        val follows = Loadable<List<Channel>>()
        val streams = streamPaged()
        val streamFilters = StreamFilters()
        val categories = Paged<Category> { it.slug }
        val clips = clipPaged()
        val clipFilters = ClipFilters()
        val followsList = LazyGridState()
        val streamsGrid = LazyGridState()
        val categoriesGrid = LazyGridState()
        val clipsGrid = LazyGridState()
    }

    class Search : Screen() {
        var query by mutableStateOf("")
        val results = Loadable<SearchResults>()
        /** 0 all, 1 channels, 2 live, 3 categories */
        var type by mutableIntStateOf(0)
        var liveOnly by mutableStateOf(false)
        var language by mutableStateOf("")
        val list = LazyListState()
    }

    class ChannelPage(val slug: String, val autoPlayUuid: String? = null) : Screen() {
        val channel = Loadable<Channel>()
        val videos = Loadable<List<Video>>()
        var tab by mutableIntStateOf(0)
        val clips = clipPaged()
        val clipFilters = ClipFilters(time = "all")
        var autoPlayed = false
        val grid = LazyGridState()
    }

    class CategoryPage(val slug: String, val name: String, var id: Long) : Screen() {
        var tab by mutableIntStateOf(0)
        val streams = streamPaged()
        val filters = StreamFilters()
        val clips = clipPaged()
        val clipFilters = ClipFilters()
        val grid = LazyGridState()
    }

    class Player : Screen()
    class Settings : Screen()
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home())
    val top: Screen get() = stack.last()

    fun push(s: Screen) {
        // Only one player entry at a time.
        if (s is Screen.Player) stack.removeAll { it is Screen.Player }
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
