package com.drzenith.kixtra.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Kick's internal API sits behind Cloudflare. Plain OkHttp works on most hosts and networks,
 * but kick.com/api/v2 is sometimes blocked by TLS fingerprinting. When that happens we replay
 * the request inside a hidden WebView (real Chromium network stack + cookies) and remember
 * that host so later calls go straight there.
 */
object Http {
    private const val TAG = "KixtraHttp"
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/142.0.0.0 Mobile Safari/537.36"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val webViewHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun getJson(url: String): JsonElement = request(url, "GET", null)
    suspend fun postJson(url: String, body: String): JsonElement = request(url, "POST", body)

    private fun looksJson(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    private suspend fun request(url: String, method: String, body: String?): JsonElement {
        val host = url.toHttpUrl().host
        if (host !in webViewHosts) {
            val result = runCatching { okhttp(url, method, body) }
            val (code, text) = result.getOrElse { e ->
                Log.w(TAG, "okhttp failed for $url: $e")
                -1 to ""
            }
            if (code in 200..299 && looksJson(text)) return json.parseToJsonElement(text)
            if (code == 404) throw ApiException(404, "Not found")
            val blocked = code == -1 || code == 403 || code == 429 || code == 503 || !looksJson(text)
            if (!blocked) throw ApiException(code, "HTTP $code")
            Log.i(TAG, "Falling back to WebView for $host (HTTP $code)")
        }
        val (code, text) = WebFetcher.fetch(url, method, body)
        if (code in 200..299 && looksJson(text)) {
            webViewHosts.add(host)
            return json.parseToJsonElement(text)
        }
        throw ApiException(code, if (code == 404) "Not found" else "Kick request failed (HTTP $code)")
    }

    private suspend fun okhttp(url: String, method: String, body: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://kick.com")
                .header("Referer", "https://kick.com/")
            if (method == "POST") {
                builder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            }
            client.newCall(builder.build()).execute().use { resp ->
                resp.code to resp.body.string()
            }
        }
}
