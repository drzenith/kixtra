package com.drzenith.kixtra.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.Video
import com.drzenith.kixtra.player.Playable
import com.drzenith.kixtra.player.PlayerController

/**
 * Navigation + playback actions shared by all screens.
 *
 * The player is an overlay above the screen stack: expanded (full player) or minimized
 * (floating mini player) while you keep browsing. Playing something new replaces it.
 */
class Actions(val nav: Navigator, val player: PlayerController, val store: Store) {
    /** True = full player on top; false = mini player (if something is loaded). */
    var playerExpanded by mutableStateOf(false)

    fun openChannel(slug: String) {
        playerExpanded = false
        nav.push(Screen.ChannelPage(slug))
    }

    fun openCategory(slug: String, name: String, id: Long) = nav.push(Screen.CategoryPage(slug, name, id))
    fun openSearch() = nav.push(Screen.Search())
    fun openSettings() = nav.push(Screen.Settings())

    fun openLive(slug: String) {
        player.playLive(slug)
        playerExpanded = true
    }

    fun playLive(channel: Channel) {
        player.play(Playable.Live(channel))
        playerExpanded = true
    }

    fun playVod(channel: Channel, video: Video) {
        player.play(Playable.Vod(channel, video))
        playerExpanded = true
    }

    fun playClip(clip: Clip) {
        val channel = Channel(
            id = clip.channelId,
            slug = clip.channelSlug,
            username = clip.channelName,
            avatar = clip.channelAvatar,
            bio = null,
            followers = 0,
            verified = false,
            chatroomId = null,
            live = null,
        )
        player.play(Playable.ClipItem(channel, clip))
        playerExpanded = true
    }

    /** Jump from a clip to the moment it came from in the full VOD. */
    fun continueClipInVod(clip: Clip) {
        player.playClipSource(clip)
        playerExpanded = true
    }

    /** Start the growing VOD of the current live stream from the beginning. */
    fun watchFromStart(channel: Channel) {
        player.play(Playable.Vod(channel, dvrVideo(channel) ?: return, dvr = true))
        playerExpanded = true
    }

    fun minimizePlayer() {
        playerExpanded = false
    }

    fun expandPlayer() {
        playerExpanded = true
    }

    /** Stop playback and remove the player entirely. */
    fun closePlayer() {
        playerExpanded = false
        player.stop()
    }

    /** New default language: save it and apply it to the home Live tab. */
    fun setDefaultLanguage(code: String) {
        store.defaultLanguage = code
        (nav.stack.firstOrNull() as? Screen.Home)?.let { home ->
            home.streamFilters.language = code
            home.streams.reset()
        }
    }

    fun toggleFollow(slug: String, username: String, avatar: String?) =
        store.toggleFollow(FollowedChannel(slug, username, avatar))

    /** Back from a regular screen. */
    fun back(): Boolean = nav.pop()

    companion object {
        /** A Video standing in for the still-growing VOD of a channel's current stream. */
        fun dvrVideo(channel: Channel): Video? {
            val live = channel.live ?: return null
            return Video(
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
        }
    }
}
