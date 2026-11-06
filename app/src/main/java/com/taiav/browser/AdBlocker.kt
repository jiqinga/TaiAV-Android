package com.taiav.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.taiav.network.AdRules
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import com.taiav.network.NetworkJournal
import com.taiav.network.ResourceState

/** Local rules only; no remote filtering service and no TLS interception. */
class AdBlocker(context: Context, store: BrowserStore, private val journal: NetworkJournal) {
    private val template = context.assets.open("ad-filter.js").bufferedReader().use { it.readText() }
    private val requests = AtomicInteger()
    private val popups = AtomicInteger()
    @Volatile var enabled = store.adFilterEnabled
        private set
    @Volatile private var rules = store.adRules()

    fun configure(store: BrowserStore) {
        rules = store.adRules()
        enabled = store.adFilterEnabled
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!blocks(request.url.toString())) return null
        // Return an empty response before WebView starts this request. Never fetch it ourselves.
        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    fun blocks(url: String, source: String = "资源请求"): Boolean {
        if (!wouldBlock(url)) return false
        requests.incrementAndGet()
        journal.record(url, state = ResourceState.BLOCKED, detail = source)
        return true
    }

    fun wouldBlock(url: String): Boolean = enabled && rules.blocks(url)

    fun blockPopup(): Boolean {
        if (!enabled) return false
        popups.incrementAndGet()
        return true
    }

    fun status(): String = if (enabled) "已开启 · 本次启动已拦截 ${requests.get()} 次请求 / ${popups.get()} 个弹窗"
        else "已关闭 · 点击开启广告过滤"

    fun documentScript(store: BrowserStore): String {
        val config = JSONObject().put("blocked", JSONArray(rules.blockedDomains.toList()))
            .put("allowed", JSONArray(AdRules.parseDomains(store.allowedAdDomains).toList()))
        return template.replace("__TAIAV_AD_RULES__", config.toString())
    }
}
