package com.drzenith.kixtra.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.data.FollowedChannel
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.Video
import com.drzenith.kixtra.data.liveVideo
import com.drzenith.kixtra.player.Playable
import com.drzenith.kixtra.player.PlayerController
import com.drzenith.kixtra.player.PlayerSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Navigation + playback actions shared by all screens.
 *
 * The player is an overlay above the screen stack: expanded (full player) or minimized
 * (floating mini player) while you keep browsing. Playing something new replaces it.
 */
class Actions(val nav: Navigator, val player: PlayerController, val store: Store) {
    /** True = full player on top; false = mini player (if something is loaded). */
    var playerExpanded by mutableStateOf(false)

    /** Drag/animation state for minimizing the expanded player. */
    val sheet = PlayerSheet().also { sheet ->
        sheet.onCollapsed = { playerExpanded = false }
        // Hand-off: wait until the newly shown video view has drawn a frame (with a safety cap).
        sheet.awaitFirstFrame = {
            withTimeoutOrNull(500) { player.firstFrame.first() }
            delay(16)
        }
    }

    fun openChannel(slug: String) {
        playerExpanded = false
        nav.push(Screen.ChannelPage(slug))
    }

    fun openCategory(slug: String, name: String, id: Long) = nav.push(Screen.CategoryPage(slug, name, id))
    fun openSearch() = nav.push(Screen.Search())
    fun openSettings() = nav.push(Screen.Settings())

    fun openLive(slug: String) {
        player.playLive(slug)
        showPlayer()
    }

    fun playLive(channel: Channel) {
        player.playChannel(channel)
        showPlayer()
    }

    fun playVod(channel: Channel, video: Video) {
        player.play(Playable.Vod(channel, video))
        showPlayer()
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
        showPlayer()
    }

    /** Jump from a clip to the moment it came from in the full VOD. */
    fun continueClipInVod(clip: Clip) {
        player.playClipSource(clip)
        showPlayer()
    }

    /** Start the growing VOD of the current live stream from the beginning. */
    fun watchFromStart(channel: Channel) {
        player.play(Playable.Vod(channel, channel.liveVideo() ?: return, dvr = true), startAtMs = 0L)
        showPlayer()
    }

    private fun showPlayer() {
        sheet.reset()
        playerExpanded = true
    }

    /** Animated: the player glides down into the mini player. */
    fun minimizePlayer() {
        if (playerExpanded) sheet.collapse()
    }

    /** From the mini player: grows back out of the mini player's spot. */
    fun expandPlayer() {
        sheet.expandFromMini { playerExpanded = true }
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
}
