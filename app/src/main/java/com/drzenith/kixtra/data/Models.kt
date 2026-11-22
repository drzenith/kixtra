package com.drzenith.kixtra.data

data class Channel(
    val id: Long,
    val slug: String,
    val username: String,
    val avatar: String?,
    val bio: String?,
    val followers: Long,
    val verified: Boolean,
    val chatroomId: Long?,
    val live: LiveInfo?,
    /** When the channel was last live (epoch ms), if Kick tells us. */
    val lastLiveMs: Long? = null,
)

data class LiveInfo(
    val id: Long,
    val title: String,
    val viewers: Long,
    val category: String?,
    val thumbnail: String?,
    val startTime: String?,
    val playbackUrl: String?,
    val vodId: String?,
)

/** A live stream in a list (browse, search, category). */
data class StreamItem(
    val slug: String,
    val username: String,
    val title: String,
    val viewers: Long,
    val category: String?,
    val thumbnail: String?,
    val avatar: String?,
    val language: String? = null,
    val mature: Boolean = false,
    /** When the stream started (for the uptime badge). */
    val startTime: String? = null,
)

data class Clip(
    val id: String,
    val title: String,
    val url: String?,
    val thumbnail: String?,
    val durationMs: Long,
    val views: Long,
    val createdAt: String?,
    val category: String?,
    val channelId: Long,
    val channelSlug: String,
    val channelName: String,
    val channelAvatar: String?,
)

data class Category(
    val id: Long,
    val name: String,
    val slug: String,
    val viewers: Long,
    val banner: String?,
)

data class ChannelHit(
    val slug: String,
    val username: String,
    val avatar: String?,
    val isLive: Boolean,
    val verified: Boolean,
)

data class SearchResults(
    val channels: List<ChannelHit>,
    val streams: List<StreamItem>,
    val categories: List<Category>,
)

data class Video(
    val livestreamId: Long,
    val uuid: String?,
    val vodId: String?,
    val title: String,
    val durationMs: Long,
    val isLive: Boolean,
    val thumbnail: String?,
    val startTime: String?,
    val views: Long,
    val category: String?,
    val source: String?,
) {
    /** Stable key for resume positions. */
    val key: String get() = uuid ?: vodId ?: livestreamId.toString()
}

data class Page<T>(val items: List<T>, val next: String?)

data class FollowedChannel(val slug: String, val username: String, val avatar: String?)
