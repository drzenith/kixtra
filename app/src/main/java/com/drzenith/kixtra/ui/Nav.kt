package com.drzenith.kixtra.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drzenith.kixtra.data.Category
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.SearchResults
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.data.Video

/** Simple async state holder so screens keep their data when you navigate back to them. */
class Loadable<T> {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Each back-stack entry is its own state object, so going back restores lists and scroll. */
sealed class Screen {
    class Home : Screen() {
        var tab by mutableIntStateOf(0)
        val follows = Loadable<List<Channel>>()
        val streams = mutableStateListOf<StreamItem>()
        var streamsCursor by mutableStateOf<String?>(null)
        var streamsEnd by mutableStateOf(false)
        var streamsLoading by mutableStateOf(false)
        var streamsError by mutableStateOf<String?>(null)
        val categories = mutableStateListOf<Category>()
        var categoriesPage by mutableStateOf<String?>("1")
        var categoriesLoading by mutableStateOf(false)
        var categoriesError by mutableStateOf<String?>(null)
        val followsList = LazyListState()
        val streamsList = LazyListState()
        val categoriesGrid = LazyGridState()
    }

    class Search : Screen() {
        var query by mutableStateOf("")
        val results = Loadable<SearchResults>()
    }

    class ChannelPage(val slug: String, val autoPlayUuid: String? = null) : Screen() {
        val channel = Loadable<Channel>()
        val videos = Loadable<List<Video>>()
        var autoPlayed = false
        val list = LazyListState()
    }

    class CategoryPage(val slug: String, val name: String, var id: Long) : Screen() {
        val streams = mutableStateListOf<StreamItem>()
        var cursor by mutableStateOf<String?>(null)
        var end by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val list = LazyListState()
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
