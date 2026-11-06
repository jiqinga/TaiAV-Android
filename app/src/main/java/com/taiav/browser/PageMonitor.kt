package com.taiav.browser

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.taiav.browserdata.PlaybackEntry
import com.taiav.browserdata.PlaybackPolicy
import com.taiav.browserdata.SiteUrls
import com.taiav.network.NetworkJournal
import com.taiav.network.ResourceKind
import com.taiav.network.ResourceState
import org.json.JSONObject

/** Origin-scoped page observations; no unrestricted addJavascriptInterface bridge. */
class PageMonitor(
    private val context: Context,
    private val webView: WebView,
    private val store: BrowserStore,
    private val journal: NetworkJournal,
    private val isBlocked: (String) -> Boolean,
    private val mediaResource: (String) -> Unit,
    private val canPrompt: () -> Boolean,
) {
    private val script = context.assets.open("media-monitor.js").bufferedReader().use { it.readText() }
    private val handler = Handler(Looper.getMainLooper())
    private var scriptHandler: ScriptHandler? = null
    private var page: String? = null
    private var prompted = false
    private var ready = false
    private var resumeEntry: PlaybackEntry? = null
    private var lastSavedPosition: Double? = null
    private var lastSavedDuration: Double? = null
    private var lastSavedEnded = false
    private var dialog: AlertDialog? = null
    private var stopped = false
    private var nativeActive = false
    private val origins = setOf("https://taiav.com", "https://*.taiav.com")
    private val tick = object : Runnable {
        override fun run() { if (!stopped) { capture(); handler.postDelayed(this, 5000) } }
    }

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "TaiavPageEvents", origins) { _, message, source, mainFrame, _ ->
                if (mainFrame && SiteUrls.isSite(source.toString())) accept(runCatching { message.data }.getOrNull())
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
        }
    }

    fun navigation(url: String?) {
        dialog?.dismiss(); dialog = null
        page = url; prompted = false; ready = false
        resumeEntry = url?.let(store::playbackFor)
        lastSavedPosition = null; lastSavedDuration = null; lastSavedEnded = false
    }

    fun committed(url: String?) {
        if (url != null && SiteUrls.isSite(url)) webView.evaluateJavascript(script, null)
    }

    private fun accept(raw: String?) {
        if (raw == null || raw.length > 16384) return
        runCatching {
            val value = JSONObject(raw)
            val sourcePage = value.optString("page")
            if (!SiteUrls.isSite(sourcePage) || sourcePage.substringBefore('#') != webView.url?.substringBefore('#')) return
            when (value.optString("kind")) {
                "ready" -> { ready = true; maybePrompt() }
                "progress" -> {
                    if (nativeActive) return
                    ready = true
                    maybePrompt()
                    if (value.optBoolean("active") && SiteUrls.movieKey(sourcePage) != null) {
                        val position = value.getDouble("position")
                        val duration = value.getDouble("duration")
                        val ended = value.optBoolean("ended")
                        if (!PlaybackPolicy.valid(position, duration)) return
                        if (lastSavedPosition?.let { kotlin.math.abs(position - it) < 0.5 } == true &&
                            duration == lastSavedDuration && ended == lastSavedEnded) return
                        val title = value.optString("title", "TaiAV 视频").replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "TaiAV 视频" }
                        store.savePlayback(PlaybackEntry(title, sourcePage, position, duration), ended)
                        lastSavedPosition = position; lastSavedDuration = duration; lastSavedEnded = ended
                    }
                }
                "resource" -> {
                    val url = value.optString("url")
                    if (NetworkJournal.parsed(url) == null) return
                    // A filtered advertisement is a deliberate block, not a network failure.
                    if (isBlocked(url)) return
                    mediaResource(url)
                    val resource = value.optString("resource")
                    val kind = if (resource == "video") ResourceKind.VIDEO else NetworkJournal.classify(url, resource)
                    val failed = value.optBoolean("failed")
                    journal.record(url, kind, if (failed) ResourceState.FAILED else ResourceState.REQUESTED,
                        if (failed) "页面资源加载失败" else "页面资源观察")
                }
            }
        }
    }

    fun maybePrompt() {
        if (nativeActive || !ready || prompted || !canPrompt()) return
        val url = page ?: return
        val entry = resumeEntry ?: return
        if (store.playbackFor(url) == null) return
        prompted = true
        webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.pause()", null)
        dialog = AlertDialog.Builder(context).setTitle("继续观看？")
            .setMessage("上次看到 ${PlaybackPolicy.time(entry.position)} / ${PlaybackPolicy.time(entry.duration)}")
            .setPositiveButton("继续观看") { _, _ ->
                webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.resume(${entry.position})", null)
                android.widget.Toast.makeText(context, "已恢复进度，点击播放器即可继续", android.widget.Toast.LENGTH_SHORT).show()
            }.setNeutralButton("从头播放") { _, _ ->
                store.removePlayback(url)
                webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.resume(0)", null)
            }.setNegativeButton("稍后", null).show()
    }

    fun capture() {
        if (nativeActive || !SiteUrls.isSite(webView.url.orEmpty())) return
        webView.evaluateJavascript("window.__taiavMedia?window.__taiavMedia.snapshot():null") { raw ->
            if (!stopped && raw != "null") accept(raw)
        }
    }

    fun suspendForNative() { nativeActive = true; dialog?.dismiss(); dialog = null }

    fun returnFromNative(position: Double?) {
        // Prevent an old webpage snapshot from replacing the native session's position.
        prompted = true; resumeEntry = null
        if (position != null && position.isFinite()) {
            webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.resume(${position.coerceAtLeast(0.0)})") {
                nativeActive = false
            }
        } else nativeActive = false
    }

    fun start() { stopped = false; handler.removeCallbacks(tick); handler.postDelayed(tick, 5000) }
    fun pause() { capture(); handler.removeCallbacks(tick) }
    fun close() {
        stopped = true; handler.removeCallbacksAndMessages(null); dialog?.dismiss()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) scriptHandler?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "TaiavPageEvents")
    }
}
