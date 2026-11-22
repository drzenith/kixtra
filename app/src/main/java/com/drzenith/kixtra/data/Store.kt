package com.drzenith.kixtra.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Settings, local follows and resume positions, all in SharedPreferences. */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kixtra", Context.MODE_PRIVATE)

    // ---- Settings ----
    var backgroundAudio: Boolean
        get() = prefs.getBoolean("bg_audio", true)
        set(v) = prefs.edit { putBoolean("bg_audio", v) }

    var pipEnabled: Boolean
        get() = prefs.getBoolean("pip", true)
        set(v) = prefs.edit { putBoolean("pip", v) }

    var chatEnabled: Boolean
        get() = prefs.getBoolean("chat", true)
        set(v) = prefs.edit { putBoolean("chat", v) }

    /** Remembered VOD speed (Xtra-style: the last speed you picked sticks). */
    var vodSpeed: Float
        get() = prefs.getFloat("vod_speed", 1f)
        set(v) = prefs.edit { putFloat("vod_speed", v) }

    /** Preferred max video height; 0 = Auto, -1 = audio only. */
    var preferredQuality: Int
        get() = prefs.getInt("quality", 0)
        set(v) = prefs.edit { putInt("quality", v) }

    /** Waydroid/emulators often have broken hardware decoders; auto-enabled after a decode error. */
    var softwareDecoding: Boolean
        get() = prefs.getBoolean("sw_decode", false)
        set(v) = prefs.edit { putBoolean("sw_decode", v) }

    /** Default stream language code ("" = any). */
    var defaultLanguage: String
        get() = prefs.getString("language", "") ?: ""
        set(v) = prefs.edit { putString("language", v) }

    var hideMature: Boolean
        get() = prefs.getBoolean("hide_mature", false)
        set(v) = prefs.edit { putBoolean("hide_mature", v) }

    /** Width of the side chat panel (landscape / wide windows), in dp. */
    var chatWidthDp: Float
        get() = prefs.getFloat("chat_width", 240f)
        set(v) = prefs.edit { putFloat("chat_width", v) }

    // ---- Pop-out chat overlay (position as fractions of the video, size in dp) ----
    var overlayX: Float
        get() = prefs.getFloat("ov_x", 0.02f)
        set(v) = prefs.edit { putFloat("ov_x", v) }
    var overlayY: Float
        get() = prefs.getFloat("ov_y", 0.15f)
        set(v) = prefs.edit { putFloat("ov_y", v) }
    var overlayW: Float
        get() = prefs.getFloat("ov_w", 260f)
        set(v) = prefs.edit { putFloat("ov_w", v) }
    var overlayH: Float
        get() = prefs.getFloat("ov_h", 220f)
        set(v) = prefs.edit { putFloat("ov_h", v) }
    /** 0 = no background, 1 = light, 2 = dark. */
    var overlayBg: Int
        get() = prefs.getInt("ov_bg", 1)
        set(v) = prefs.edit { putInt("ov_bg", v) }

    /** Live channels play their VOD at the live point (rewindable) instead of the plain live stream. */
    var rewindableLive: Boolean
        get() = prefs.getBoolean("rewindable_live", true)
        set(v) = prefs.edit { putBoolean("rewindable_live", v) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notif", false)
        set(v) = prefs.edit { putBoolean("asked_notif", v) }

    // ---- Last time a followed channel was seen live (epoch ms) ----
    fun lastLive(slug: String): Long? = prefs.getLong("lastlive_${slug.lowercase()}", 0L).takeIf { it > 0 }
    fun saveLastLive(slug: String, ms: Long) {
        if (ms > (lastLive(slug) ?: 0L)) prefs.edit { putLong("lastlive_${slug.lowercase()}", ms) }
    }

    // ---- Resume positions ----
    fun position(key: String): Long = prefs.getLong("pos_$key", 0L)
    fun savePosition(key: String, ms: Long) = prefs.edit { putLong("pos_$key", ms) }

    /** Saved spot for a VOD (falls back to positions saved by older versions under the old key). */
    fun positionFor(video: Video): Long =
        position(video.progressKey).takeIf { it > 0 } ?: position(video.key)

    /** Whether you were at LIVE when you last left this stream. */
    fun wasLive(key: String): Boolean = prefs.getBoolean("live_$key", false)
    fun saveWasLive(key: String, live: Boolean) = prefs.edit { putBoolean("live_$key", live) }

    // ---- Follows ----
    private val _follows = MutableStateFlow(loadFollows())
    val follows: StateFlow<List<FollowedChannel>> = _follows

    private fun loadFollows(): List<FollowedChannel> = runCatching {
        val arr = JSONArray(prefs.getString("follows", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FollowedChannel(
                slug = o.getString("slug"),
                username = o.optString("username", o.getString("slug")),
                avatar = o.optString("avatar").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    private fun saveFollows(list: List<FollowedChannel>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("slug", it.slug).put("username", it.username).put("avatar", it.avatar ?: ""))
        }
        prefs.edit { putString("follows", arr.toString()) }
        _follows.value = list
    }

    fun isFollowed(slug: String) = _follows.value.any { it.slug.equals(slug, true) }

    fun toggleFollow(channel: FollowedChannel) {
        val cur = _follows.value
        saveFollows(
            if (cur.any { it.slug.equals(channel.slug, true) }) cur.filterNot { it.slug.equals(channel.slug, true) }
            else cur + channel
        )
    }
}
