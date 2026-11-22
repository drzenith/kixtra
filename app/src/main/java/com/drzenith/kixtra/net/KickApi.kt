package com.drzenith.kixtra.net

import android.util.Log
import com.drzenith.kixtra.data.Category
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.ChannelHit
import com.drzenith.kixtra.data.LiveInfo
import com.drzenith.kixtra.data.Page
import com.drzenith.kixtra.data.SearchResults
import com.drzenith.kixtra.data.StreamItem
import com.drzenith.kixtra.data.Video
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Kick's undocumented endpoints, as used by kick.com, its app, yt-dlp, streamlink and other
 * open-source clients. Everything is parsed leniently.
 */
object KickApi {
    private const val TAG = "KickApi"
    private const val PLAYBACK_BODY =
        """{"video_player":{"player":{}},"video_session":{},"user_session":{"non_personalised_ads":true}}"""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Channels ----------

    suspend fun channel(slug: String): Channel {
        val o = Http.getJson("https://kick.com/api/v2/channels/${enc(slug.lowercase())}").obj()
            ?: throw ApiException(0, "Bad channel response")
        return parseChannel(o, slug)
    }

    private fun parseChannel(o: JsonObject, fallbackSlug: String): Channel {
        val user = o.o("user")
        val ls = o.o("livestream")
        val live = if (ls != null && ls.b("is_live") != false) {
            LiveInfo(
                id = ls.l("id") ?: 0,
                title = ls.s("session_title") ?: "",
                viewers = ls.l("viewer_count") ?: ls.l("viewers") ?: 0,
                category = ls.a("categories").objects().firstOrNull().s("name") ?: ls.o("category").s("name"),
                thumbnail = imageUrl(ls["thumbnail"]),
                startTime = ls.s("start_time") ?: ls.s("created_at"),
                playbackUrl = o.s("playback_url") ?: ls.s("playback_url"),
                vodId = ls.s("vod_id"),
            )
        } else null
        return Channel(
            id = o.l("id") ?: 0,
            slug = o.s("slug") ?: fallbackSlug,
            username = user.s("username") ?: o.s("slug") ?: fallbackSlug,
            avatar = user.s("profile_pic") ?: user.s("profilepic"),
            bio = user.s("bio"),
            followers = o.l("followers_count") ?: o.l("followersCount") ?: 0,
            verified = o.b("verified") ?: (o["verified"] is JsonObject),
            chatroomId = o.o("chatroom").l("id") ?: o.l("chatroom_id"),
            live = live,
        )
    }

    suspend fun videos(slug: String): List<Video> {
        val el = Http.getJson("https://kick.com/api/v2/channels/${enc(slug.lowercase())}/videos")
        val list = el.arr() ?: el.obj().a("data")
        return list.objects().map { v ->
            val inner = v.o("video")
            Video(
                livestreamId = v.l("id") ?: 0,
                uuid = inner.s("uuid") ?: v.s("uuid"),
                vodId = v.s("vod_id"),
                title = v.s("session_title") ?: inner.s("title") ?: v.s("title") ?: "Untitled",
                durationMs = v.l("duration") ?: 0,
                isLive = v.b("is_live") ?: false,
                thumbnail = imageUrl(v["thumbnail"]) ?: imageUrl(inner?.get("thumbnail")),
                startTime = v.s("start_time") ?: v.s("created_at"),
                views = inner.l("views") ?: v.l("views") ?: v.l("viewer_count") ?: 0,
                category = v.a("categories").objects().firstOrNull().s("name"),
                source = v.s("source"),
            )
        }
    }

    // ---------- Playback ----------

    /** POST /stream/{id}/playback: returns a fresh signed VOD (or live-DVR) playlist URL. */
    private suspend fun streamPlayback(vodId: String): String? = runCatching {
        Http.postJson("https://web.kick.com/api/v1/stream/${enc(vodId)}/playback", PLAYBACK_BODY)
            .obj().o("playback_url").s("vod")
    }.onFailure { Log.w(TAG, "stream playback failed for $vodId: $it") }.getOrNull()

    /** Resolves a playable m3u8 for a VOD, including VODs of streams that are still live. */
    suspend fun vodUrl(video: Video): String {
        var vodId = video.vodId
        var detailSource: String? = null
        if (vodId == null && video.uuid != null) {
            runCatching {
                val d = Http.getJson("https://kick.com/api/v1/video/${enc(video.uuid)}").obj()
                vodId = d.o("livestream").s("vod_id")
                detailSource = d.s("source") ?: d.s("playback_url")
            }.onFailure { Log.w(TAG, "video detail failed: $it") }
        }
        vodId?.let { id -> streamPlayback(id)?.let { return it } }
        detailSource?.let { return it }
        video.source?.let { return it }
        throw ApiException(0, "Couldn't get a playback URL for this video")
    }

    /** The growing VOD of a stream that is live right now ("watch from start"). */
    suspend fun liveDvrUrl(channel: Channel): String? {
        channel.live?.vodId?.let { id -> streamPlayback(id)?.let { return it } }
        val id = runCatching {
            Http.getJson("https://web.kick.com/api/v1/channels/${enc(channel.slug)}/livestream")
                .obj().o("data").o("livestream").s("id")
        }.getOrNull() ?: return null
        return streamPlayback(id)
    }

    // ---------- Browse ----------

    private fun parseStream(o: JsonObject): StreamItem? {
        val ls = o.o("livestream") ?: o
        val ch = ls.o("channel") ?: o.o("channel")
        val slug = ch.s("slug") ?: ls.s("slug") ?: return null
        val cat = ls.o("category") ?: ls.a("categories").objects().firstOrNull()
        return StreamItem(
            slug = slug,
            username = ch.s("username") ?: ch.o("user").s("username") ?: slug,
            title = ls.s("title") ?: ls.s("session_title") ?: "",
            viewers = ls.l("viewer_count") ?: ls.l("viewers") ?: 0,
            category = cat.s("name"),
            thumbnail = imageUrl(ls["thumbnail"]),
            avatar = ch.s("profile_picture") ?: ch.s("profile_pic") ?: ch.o("user").s("profile_pic"),
        )
    }

    suspend fun liveStreams(cursor: String?, categoryId: Long? = null): Page<StreamItem> {
        val q = buildString {
            append("limit=24&sort=viewer_count_desc")
            if (categoryId != null) append("&category_id=$categoryId")
            if (cursor != null) append("&after=${enc(cursor)}")
        }
        val o = Http.getJson("https://web.kick.com/api/v1/livestreams?$q").obj()
        val data = o.o("data") ?: o
        val items = data.a("livestreams").objects().mapNotNull(::parseStream)
        val next = data.o("pagination").s("next_cursor") ?: data.s("next_cursor")
        return Page(items, next?.takeIf { items.isNotEmpty() })
    }

    private fun parseCategory(o: JsonObject): Category? {
        val slug = o.s("slug") ?: return null
        return Category(
            id = o.l("id") ?: 0,
            name = o.s("name") ?: slug,
            slug = slug,
            viewers = o.l("viewers") ?: o.l("viewer_count") ?: 0,
            banner = imageUrl(o["banner"]) ?: imageUrl(o["thumbnail"]),
        )
    }

    suspend fun categories(page: Int): Page<Category> {
        val o = Http.getJson("https://kick.com/api/v1/subcategories?page=$page&limit=32").obj()
        val items = o.a("data").objects().mapNotNull(::parseCategory)
        val last = o.l("last_page") ?: Long.MAX_VALUE
        return Page(items, if (items.isNotEmpty() && page < last) (page + 1).toString() else null)
    }

    suspend fun category(slug: String): Category? =
        Http.getJson("https://kick.com/api/v1/subcategories/${enc(slug)}").obj()?.let(::parseCategory)

    // ---------- Search ----------

    suspend fun search(query: String): SearchResults {
        val q = enc(query.trim())
        val el = runCatching { Http.getJson("https://search.kick.com/api/v1/search?query=$q") }
            .getOrElse { Http.getJson("https://search.kick.com/api/v1/search/enriched?query=$q") }
        val data = el.obj().o("data") ?: el.obj()
        val channels = data.a("channels").objects().mapNotNull { c ->
            val slug = c.s("slug") ?: return@mapNotNull null
            ChannelHit(
                slug = slug,
                username = c.s("username") ?: slug,
                avatar = c.s("profile_picture") ?: c.s("profile_pic"),
                isLive = c.b("is_live") ?: false,
                verified = c.b("is_verified") ?: false,
            )
        }
        val streams = data.a("livestreams").objects().mapNotNull(::parseStream)
        val categories = data.a("categories").objects().mapNotNull(::parseCategory)
        return SearchResults(
            channels.distinctBy { it.slug },
            streams.distinctBy { it.slug },
            categories.distinctBy { it.slug },
        )
    }

    // ---------- Chat ----------

    data class PusherConfig(val key: String, val cluster: String)

    val FALLBACK_PUSHER = PusherConfig("34bf7a0ff419a2a775b9", "us2")

    suspend fun pusherConfig(channelId: Long): PusherConfig {
        val clientId = (1..16).joinToString("") { "%02x".format((0..255).random()) }
        val body = """{"client":{"id":"$clientId","type":"web"},"capabilities":{"accepted_providers":[{"provider":"pusher"}]}}"""
        return runCatching {
            val conns = Http.postJson(
                "https://web.kick.com/api/v1/realtime/channels/$channelId/chat/connection", body
            ).obj().o("data").a("connections").objects()
            val p = conns.first { it.s("provider") == "pusher" }.o("credentials")
            PusherConfig(p.s("app_key")!!, p.s("cluster")!!)
        }.getOrDefault(FALLBACK_PUSHER)
    }

    /** Recent live chat, or (with startTime) recorded chat around a point in a past stream. */
    suspend fun chatHistory(channelId: Long, startTime: String? = null): List<JsonObject> {
        val url = buildString {
            append("https://web.kick.com/api/v1/chat/$channelId/history")
            if (startTime != null) append("?start_time=${enc(startTime)}")
        }
        return Http.getJson(url).obj().o("data").a("messages").objects()
    }
}
