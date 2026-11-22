package com.drzenith.kixtra.chat

import android.util.Log
import com.drzenith.kixtra.net.Http
import com.drzenith.kixtra.net.KickApi
import com.drzenith.kixtra.net.a
import com.drzenith.kixtra.net.l
import com.drzenith.kixtra.net.o
import com.drzenith.kixtra.net.obj
import com.drzenith.kixtra.net.objects
import com.drzenith.kixtra.net.s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder

data class ChatMsg(
    val id: String,
    val user: String,
    val color: String?,
    val text: String,
    val time: Instant?,
    val badges: List<String>,
    /** For replayed chat: position in the VOD (ms). */
    val offsetMs: Long = 0,
) {
    companion object {
        fun parse(o: JsonObject): ChatMsg? {
            val id = o.s("id") ?: return null
            val sender = o.o("sender")
            val identity = sender.o("identity")
            return ChatMsg(
                id = id,
                user = sender.s("username") ?: "?",
                color = identity.s("color"),
                text = o.s("content") ?: "",
                time = parseKickTime(o.s("created_at")),
                badges = identity.a("badges").objects().mapNotNull { it.s("type") },
            )
        }
    }
}

/** Kick mixes "2026-08-21 12:00:00" (UTC) and ISO-8601 timestamps. */
fun parseKickTime(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrElse {
        runCatching { LocalDateTime.parse(value.replace(' ', 'T').substringBefore('+')).toInstant(ZoneOffset.UTC) }
            .getOrNull()
    }
}

private const val MAX_MESSAGES = 250

/** Common shape of live chat and VOD chat replay. */
interface ChatSource {
    val messages: StateFlow<List<ChatMsg>>
    val status: StateFlow<String>
    fun start()
    fun stop()
}

/** Live chat over Kick's public Pusher socket, with history polling as a fallback. */
class LiveChat(
    private val channelId: Long,
    private val chatroomId: Long,
    private val scope: CoroutineScope,
) : ChatSource {
    private val tag = "LiveChat"
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    override val messages: StateFlow<List<ChatMsg>> = _messages
    override val status = MutableStateFlow("Connecting…")

    private val seen = LinkedHashSet<String>()
    private var socket: WebSocket? = null
    @Volatile private var subscribed = false
    @Volatile private var stopped = false
    private var config: KickApi.PusherConfig? = null
    private var job: Job? = null

    override fun start() {
        job = scope.launch {
            runCatching { KickApi.chatHistory(channelId) }
                .onSuccess { list -> add(list.mapNotNull(ChatMsg::parse).sortedBy { it.time }) }
            config = KickApi.pusherConfig(channelId)
            connect()
            // Fallback: if the socket isn't working, poll recent history.
            delay(10_000)
            while (isActive && !stopped) {
                if (!subscribed) {
                    status.value = "Chat (polling)"
                    runCatching { KickApi.chatHistory(channelId) }
                        .onSuccess { list -> add(list.mapNotNull(ChatMsg::parse).sortedBy { it.time }) }
                }
                delay(3_000)
            }
        }
    }

    override fun stop() {
        stopped = true
        job?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    @Synchronized
    private fun add(batch: List<ChatMsg>) {
        val fresh = batch.filter { seen.add(it.id) }
        if (fresh.isEmpty()) return
        while (seen.size > 2000) seen.remove(seen.first())
        _messages.value = (_messages.value + fresh).takeLast(MAX_MESSAGES)
    }

    private fun connect() {
        if (stopped) return
        val cfg = config ?: KickApi.FALLBACK_PUSHER
        val url = "wss://ws-${cfg.cluster}.pusher.com/app/${cfg.key}?protocol=7&client=js&version=8.4.0&flash=false"
        val req = Request.Builder().url(url).header("Origin", "https://kick.com").header("User-Agent", Http.BROWSER_UA).build()
        socket = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "socket failure: $t")
                retry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
        })
    }

    private fun retry() {
        subscribed = false
        if (stopped) return
        status.value = "Reconnecting…"
        scope.launch {
            delay(3_000)
            connect()
        }
    }

    private fun handle(ws: WebSocket, text: String) {
        val o = runCatching { Http.json.parseToJsonElement(text).obj() }.getOrNull() ?: return
        when (o.s("event")) {
            "pusher:connection_established" -> ws.send(
                """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}"""
            )
            "pusher_internal:subscription_succeeded" -> {
                subscribed = true
                status.value = "Live chat"
            }
            "pusher:ping" -> ws.send("""{"event":"pusher:pong","data":{}}""")
            "pusher:error" -> {
                val data = o["data"]
                val code = runCatching {
                    (data.obj() ?: Http.json.parseToJsonElement(o.s("data") ?: "{}").obj()).l("code")
                }.getOrNull() ?: 0
                if (code in 4000..4099) {
                    // Kick rotated its Pusher app; ask again and reconnect.
                    scope.launch {
                        config = KickApi.pusherConfig(channelId)
                        ws.close(1000, null)
                    }
                }
            }
            "App\\Events\\ChatMessageEvent" -> {
                val data = o["data"].obj()
                    ?: runCatching { Http.json.parseToJsonElement(o.s("data") ?: "").obj() }.getOrNull()
                data?.let { ChatMsg.parse(it) }?.let { add(listOf(it)) }
            }
        }
    }
}

/** Replays a past stream's chat in sync with the VOD position (works at any speed). */
class ReplayChat(
    private val channelId: Long,
    private val start: Instant,
    private val scope: CoroutineScope,
    private val positionMs: () -> Long,
) : ChatSource {
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    override val messages: StateFlow<List<ChatMsg>> = _messages
    override val status = MutableStateFlow("Chat replay")

    private val buffer = LinkedHashMap<String, ChatMsg>()
    private val fetched = HashSet<Long>()
    private var lastBucket: Long? = null
    private var job: Job? = null
    private var backfillJob: Job? = null
    private val bucketMs = 5_000L
    private val formatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    override fun start() {
        // Runs on the main dispatcher (the player must be read on the main thread).
        job = scope.launch {
            while (isActive) {
                val pos = positionMs().coerceAtLeast(0)
                val bucket = pos / bucketMs
                val last = lastBucket
                if (bucket != last) {
                    if (last == null || bucket < last || bucket > last + 2) {
                        // Seek (or first load): start over and pull in earlier chat to fill the window.
                        buffer.clear()
                        fetched.clear()
                        backfillJob?.cancel()
                        backfillJob = launch { backfill(pos) }
                    }
                    lastBucket = bucket
                    for (b in bucket..bucket + 2) {
                        if (fetched.add(b)) launch { fetch(b) }
                    }
                }
                buffer.values.removeAll { it.offsetMs < pos - 900_000 }
                if (buffer.size > 800) {
                    val keep = buffer.values.sortedBy { it.offsetMs }.takeLast(600).map { it.id }.toHashSet()
                    buffer.keys.retainAll(keep)
                }
                _messages.value = buffer.values.filter { it.offsetMs <= pos }
                    .sortedBy { it.offsetMs }.takeLast(150)
                delay(400)
            }
        }
    }

    /** Messages before [pos]: look further back until there's enough to fill the chat. */
    private suspend fun backfill(pos: Long) {
        for (back in listOf(30_000L, 120_000L, 300_000L)) {
            if (buffer.values.count { it.offsetMs <= pos } >= 40) break
            val at = (pos - back).coerceAtLeast(0)
            fetchAt(at)
            if (at == 0L) break
        }
    }

    private suspend fun fetch(bucket: Long) {
        if (!fetchAt(bucket * bucketMs)) fetched.remove(bucket)
    }

    /** Loads messages from [offsetMs] into the stream onwards. Returns false on failure. */
    private suspend fun fetchAt(offsetMs: Long): Boolean {
        val time = formatter.format(start.plusMillis(offsetMs))
        val list = runCatching { KickApi.chatHistory(channelId, time) }.getOrElse {
            status.value = "Chat replay unavailable"
            return false
        }
        status.value = "Chat replay"
        list.mapNotNull(ChatMsg::parse).forEach { m ->
            val t = m.time ?: return@forEach
            val offset = t.toEpochMilli() - start.toEpochMilli()
            if (offset >= 0) buffer[m.id] = m.copy(offsetMs = offset)
        }
        return true
    }

    override fun stop() {
        job?.cancel()
    }
}
