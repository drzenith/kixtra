package com.drzenith.kixtra.net

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.drzenith.kixtra.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Runs fetch() inside a hidden kick.com WebView so Cloudflare sees a real browser. */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
object WebFetcher {
    private const val TAG = "KixtraWeb"
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    class Bridge {
        @JavascriptInterface
        fun done(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    private fun create(): WebView {
        val deferred = CompletableDeferred<Unit>()
        ready = deferred
        CookieManager.getInstance().setAcceptCookie(true)
        val wv = WebView(App.instance)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.blockNetworkImage = true
        wv.settings.mediaPlaybackRequiresUserGesture = true
        wv.settings.userAgentString = Http.BROWSER_UA
        wv.addJavascriptInterface(Bridge(), "KixtraBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript("document.title") { title ->
                    val t = title ?: ""
                    if (!t.contains("Just a moment", ignoreCase = true) &&
                        !t.contains("Attention Required", ignoreCase = true)
                    ) {
                        deferred.complete(Unit)
                    }
                }
            }
        }
        wv.loadUrl("https://kick.com/")
        return wv
    }

    private suspend fun ensureReady(): WebView {
        val wv = withContext(Dispatchers.Main) {
            webView ?: create().also { webView = it }
        }
        if (withTimeoutOrNull(30_000) { ready.await() } == null) {
            Log.w(TAG, "kick.com did not finish loading in time; trying anyway")
        }
        return wv
    }

    suspend fun fetch(url: String, method: String, body: String?): Pair<Int, String> {
        val wv = ensureReady()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()
        pending[id] = deferred
        val bodyPart = if (method == "POST") ", body: ${JSONObject.quote(body ?: "{}")}" else ""
        val js = """
            (function(){
              fetch(${JSONObject.quote(url)}, {
                method: ${JSONObject.quote(method)},
                credentials: 'include',
                headers: {'Accept': 'application/json', 'Content-Type': 'application/json'}$bodyPart
              }).then(function(r){ return r.text().then(function(t){ KixtraBridge.done(${JSONObject.quote(id)}, r.status, t); }); })
                .catch(function(e){ KixtraBridge.done(${JSONObject.quote(id)}, -1, String(e)); });
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) { wv.evaluateJavascript(js, null) }
        return withTimeoutOrNull(25_000) { deferred.await() } ?: run {
            pending.remove(id)
            -1 to "timeout"
        }
    }
}
