package com.drzenith.kixtra.net

import android.util.Log
import com.drzenith.kixtra.data.Category
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.ChannelHit
import com.drzenith.kixtra.data.Clip
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
            lastLiveMs = o.a("previous_livestreams").objects().mapNotNull(::endTimeMs).maxOrNull(),
        )
    }

    /** End (or start) time of a past stream object, in epoch ms. */
    private fun endTimeMs(v: JsonObject): Long? {
        val start = com.drzenith.kixtra.chat.parseKickTime(v.s("start_time") ?: v.s("created_at"))?.toEpochMilli()
            ?: return null
        val dur = v.l("duration") ?: 0
        return start + dur
    }

    /** When a channel last went offline, from its newest VOD (for channels Kick gives no history for). */
    suspend fun lastLiveFromVideos(slug: String): Long? = runCatching {
        videos(slug).mapNotNull { v ->
            com.drzenith.kixtra.chat.parseKickTime(v.startTime)?.toEpochMilli()?.plus(v.durationMs)
        }.maxOrNull()
    }.getOrNull()

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
            language = ls.s("language") ?: ls.s("lang_iso"),
            mature = ls.b("is_mature") ?: false,
            startTime = ls.s("start_time") ?: ls.s("started_at") ?: ls.s("created_at"),
            livestreamId = ls.l("id") ?: 0,
        )
    }

    /** sort: "viewer_count_desc" or "featured". language: Kick language code or null for any. */
    suspend fun liveStreams(
        cursor: String?,
        categoryId: Long? = null,
        language: String? = null,
        sort: String = "viewer_count_desc",
    ): Page<StreamItem> {
        val q = buildString {
            append("limit=24&sort=${enc(sort)}")
            if (categoryId != null) append("&category_id=$categoryId")
            if (!language.isNullOrBlank()) append("&language=${enc(language)}")
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

    /** Language codes Kick streams can be tagged with (e.g. "en", "es"). */
    suspend fun languageCodes(): List<String> = runCatching {
        Http.getJson("https://web.kick.com/api/v1/livestreams/language-codes").obj().a("data")
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() } }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGUAGES

    val DEFAULT_LANGUAGES = listOf(
        "en", "es", "pt", "fr", "de", "it", "tr", "ar", "ru", "pl", "nl", "sv", "ja", "ko", "zh", "hi", "id", "vi", "th", "fil",
    )

    // ---------- Clips ----------

    private fun parseClip(o: JsonObject, fallbackChannel: Channel? = null): Clip? {
        val id = o.s("id") ?: return null
        val ch = o.o("channel") ?: o.o("creator")
        val slug = ch.s("slug") ?: fallbackChannel?.slug ?: return null
        return Clip(
            id = id,
            title = o.s("title") ?: "Clip",
            url = o.s("clip_url") ?: o.s("playback_url") ?: o.s("video_url"),
            thumbnail = o.s("thumbnail_url") ?: imageUrl(o["thumbnail"]),
            durationMs = ((o.s("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            views = o.l("views") ?: o.l("view_count") ?: o.l("views_count") ?: 0,
            createdAt = o.s("created_at"),
            category = o.o("category").s("name"),
            channelId = ch.l("id") ?: fallbackChannel?.id ?: 0,
            channelSlug = slug,
            channelName = ch.s("username") ?: ch.o("user").s("username") ?: fallbackChannel?.username ?: slug,
            channelAvatar = ch.s("profile_picture") ?: ch.s("profile_pic") ?: ch.s("profilepic")
                ?: ch.o("user").s("profile_pic") ?: fallbackChannel?.avatar,
        )
    }

    private fun clipPage(el: kotlinx.serialization.json.JsonElement, fallbackChannel: Channel? = null): Page<Clip> {
        val o = el.obj()
        val data = o.o("data")
        val list = o.a("clips") ?: data.a("clips") ?: o.a("data") ?: el.arr()
        val items = list.objects().mapNotNull { parseClip(it, fallbackChannel) }.distinctBy { it.id }
        val next = o.s("nextCursor") ?: o.s("next_cursor") ?: o.s("cursor") ?: data.s("cursor")
            ?: data.s("next_cursor") ?: o.o("pagination").s("next_cursor")
        return Page(items, next?.takeIf { items.isNotEmpty() })
    }

    /** sort: "view" or "date"; time: "day", "week", "month", "all". */
    suspend fun topClips(sort: String, time: String, cursor: String?): Page<Clip> {
        val c = if (cursor != null) "&cursor=${enc(cursor)}" else ""
        return clipPage(Http.getJson("https://kick.com/api/v2/clips?sort=$sort&time=$time$c"))
    }

    suspend fun categoryClips(slug: String, sort: String, time: String, cursor: String?): Page<Clip> {
        val c = if (cursor != null) "&cursor=${enc(cursor)}" else ""
        return clipPage(Http.getJson("https://kick.com/api/v2/categories/${enc(slug)}/clips?sort=$sort&time=$time$c"))
    }

    suspend fun channelClips(channel: Channel, sort: String, time: String, cursor: String?): Page<Clip> {
        val c = if (cursor != null) "&cursor=${enc(cursor)}" else ""
        // web.kick.com uses "views"; kick.com/api/v2 uses "view".
        val webSort = if (sort == "view") "views" else sort
        return runCatching {
            clipPage(Http.getJson("https://web.kick.com/api/v1/channels/${channel.id}/clips?sort=$webSort&time=$time$c"), channel)
        }.getOrElse {
            clipPage(Http.getJson("https://kick.com/api/v2/channels/${enc(channel.slug)}/clips?sort=$sort&time=$time$c"), channel)
        }
    }

    /** Fresh playable URL for a clip. */
    suspend fun clipUrl(clip: Clip): String {
        val o = runCatching { Http.getJson("https://kick.com/api/v2/clips/${enc(clip.id)}").obj() }.getOrNull()
        val c = o.o("clip") ?: o
        return c.s("clip_url") ?: c.s("playback_url") ?: c.s("video_url") ?: clip.url
            ?: throw ApiException(0, "Couldn't get this clip")
    }

    /** Where a clip came from: the VOD (or still-growing live VOD) and the clip's position in it. */
    data class ClipSource(val channel: Channel, val video: Video?, val offsetMs: Long, val dvr: Boolean)

    suspend fun clipSource(clip: Clip): ClipSource? {
        val d = runCatching {
            Http.getJson("https://web.kick.com/api/v1/clips/${enc(clip.id)}").obj().let { it.o("data") ?: it }
        }.getOrNull()
        val slug = d.o("channel").s("slug") ?: clip.channelSlug
        val channel = channel(slug)
        val startedAt = com.drzenith.kixtra.chat.parseKickTime(d.s("started_at"))?.toEpochMilli()
        val videoId = d.o("video").s("id")
        val rawOffset = d.o("video").l("offset")
        val vids = runCatching { videos(slug) }.getOrDefault(emptyList())
        fun startOf(v: Video) = com.drzenith.kixtra.chat.parseKickTime(v.startTime)?.toEpochMilli()

        val byId = videoId?.let { id ->
            vids.firstOrNull { it.uuid == id || it.vodId == id || it.livestreamId.toString() == id }
        }
        val byTime = if (startedAt == null) null else vids.firstOrNull { v ->
            val s = startOf(v) ?: return@firstOrNull false
            startedAt >= s && (v.isLive || v.durationMs == 0L || startedAt <= s + v.durationMs)
        }
        val video = byId ?: byTime
        if (video != null) {
            val start = startOf(video)
            val offset = when {
                byId != null && rawOffset != null ->
                    // Kick reports seconds; guard against it ever switching to ms.
                    if (video.durationMs > 0 && rawOffset * 1000 > video.durationMs + 60_000) rawOffset else rawOffset * 1000
                startedAt != null && start != null -> startedAt - start
                else -> 0L
            }
            return ClipSource(channel, video, offset.coerceAtLeast(0), dvr = false)
        }
        // Clip from the stream that is live right now but not in the VOD list yet.
        val live = channel.live
        val liveStart = com.drzenith.kixtra.chat.parseKickTime(live?.startTime)?.toEpochMilli()
        if (live != null && startedAt != null && liveStart != null && startedAt >= liveStart) {
            return ClipSource(channel, null, startedAt - liveStart, dvr = true)
        }
        return null
    }

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
