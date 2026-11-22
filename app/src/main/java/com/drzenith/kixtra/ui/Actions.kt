package com.drzenith.kixtra.ui

import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.Video
import com.drzenith.kixtra.player.Playable
import com.drzenith.kixtra.player.PlayerController

/** Navigation + playback actions shared by all screens. */
class Actions(val nav: Navigator, val player: PlayerController, val store: Store) {
    fun openChannel(slug: String) = nav.push(Screen.ChannelPage(slug))
    fun openCategory(slug: String, name: String, id: Long) = nav.push(Screen.CategoryPage(slug, name, id))
    fun openSearch() = nav.push(Screen.Search())
    fun openSettings() = nav.push(Screen.Settings())

    fun openLive(slug: String) {
        player.playLive(slug)
        nav.push(Screen.Player())
    }

    fun playLive(channel: Channel) {
        player.play(Playable.Live(channel))
        nav.push(Screen.Player())
    }

    fun playVod(channel: Channel, video: Video) {
        player.play(Playable.Vod(channel, video))
        nav.push(Screen.Player())
    }

    /** Start the growing VOD of the current live stream from the beginning. */
    fun watchFromStart(channel: Channel) {
        val live = channel.live ?: return
        val video = Video(
            livestreamId = live.id,
            uuid = null,
            vodId = live.vodId,
            title = live.title,
            durationMs = 0,
            isLive = true,
            thumbnail = live.thumbnail,
            startTime = live.startTime,
            views = 0,
            category = live.category,
            source = null,
        )
        player.play(Playable.Vod(channel, video, dvr = true))
        if (nav.top !is Screen.Player) nav.push(Screen.Player())
    }

    fun toggleFollow(slug: String, username: String, avatar: String?) =
        store.toggleFollow(FollowedChannel(slug, username, avatar))

    fun back(): Boolean {
        if (nav.top is Screen.Player) player.stop()
        return nav.pop()
    }
}
