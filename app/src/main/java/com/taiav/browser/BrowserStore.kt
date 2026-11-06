package com.taiav.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.taiav.network.AddressPolicy
import com.taiav.network.RoutingConfig
import com.taiav.network.AdRules
import com.taiav.browserdata.BackupCodec
import com.taiav.browserdata.BrowserBackup
import com.taiav.browserdata.PlaybackEntry
import com.taiav.browserdata.PlaybackPolicy
import com.taiav.browserdata.SiteUrls

typealias PageEntry = com.taiav.browserdata.SavedPage

class BrowserStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)

    var playerEngine: String
        get() = preferences.getString("player_engine", "media3").orEmpty().takeIf { it in listOf("media3", "mpv", "web") } ?: "media3"
        set(value) { require(value in listOf("media3", "mpv", "web")); preferences.edit().putString("player_engine", value).apply() }

    var preloadSeconds: Int
        get() = preferences.getInt("preload_seconds", 60).takeIf { it in listOf(15, 30, 60, 120) } ?: 60
        set(value) { require(value in listOf(15, 30, 60, 120)); preferences.edit().putInt("preload_seconds", value).apply() }

    var playbackCacheMb: Int
        get() = preferences.getInt("playback_cache_mb", 256).takeIf { it in listOf(64, 256, 512, 1024) } ?: 256
        set(value) { require(value in listOf(64, 256, 512, 1024)); preferences.edit().putInt("playback_cache_mb", value).apply() }

    var downloadConcurrency: Int
        get() = preferences.getInt("download_concurrency", 6).takeIf { it in DOWNLOAD_CONCURRENCY } ?: 6
        set(value) { require(value in DOWNLOAD_CONCURRENCY); preferences.edit().putInt("download_concurrency", value).apply() }

    var optimized: Boolean
        get() = preferences.getBoolean("optimized", true)
        set(value) { preferences.edit().putBoolean("optimized", value).apply() }

    var fixedAddresses: String
        get() = preferences.getString("fixed_addresses", "104.21.42.69\n172.67.158.125").orEmpty()
        set(value) { preferences.edit().putString("fixed_addresses", value).apply() }

    var dohEndpoint: String
        get() = preferences.getString("doh_endpoint", "").orEmpty()
        set(value) { preferences.edit().putString("doh_endpoint", value).apply() }

    var adFilterEnabled: Boolean
        get() = preferences.getBoolean("ad_filter_enabled", true)
        set(value) { preferences.edit().putBoolean("ad_filter_enabled", value).apply() }

    var blockedAdDomains: String
        get() = preferences.getString("blocked_ad_domains", "").orEmpty()
        set(value) { preferences.edit().putString("blocked_ad_domains", value).apply() }

    var allowedAdDomains: String
        get() = preferences.getString("allowed_ad_domains", "").orEmpty()
        set(value) { preferences.edit().putString("allowed_ad_domains", value).apply() }

    fun adRules() = AdRules(AdRules.parseDomains(blockedAdDomains), AdRules.parseDomains(allowedAdDomains))

    fun routingConfig(): RoutingConfig = RoutingConfig(
        fixedAddresses = parseAddresses(fixedAddresses),
        dohEndpoint = AddressPolicy.endpoint(dohEndpoint),
    )

    fun bookmarks(): List<PageEntry> = read("bookmarks")
    fun history(): List<PageEntry> = read("history")
    fun bookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    fun toggleBookmark(entry: PageEntry): Boolean {
        val old = bookmarks()
        val adding = old.none { it.url == entry.url }
        require(!adding || old.size < BackupCodec.MAX_BOOKMARKS) { "收藏已达 1000 条，请先导出或整理收藏" }
        write("bookmarks", if (adding) listOf(entry) + old else old.filterNot { it.url == entry.url })
        return adding
    }

    fun record(entry: PageEntry) = write("history", (listOf(entry) + history().filterNot { it.url == entry.url }).take(100))
    fun removeBookmark(url: String) = write("bookmarks", bookmarks().filterNot { it.url == url })
    fun clearHistory() { preferences.edit().remove("history").apply() }

    fun playback(): List<PlaybackEntry> = runCatching {
        val array = JSONArray(preferences.getString("playback", "[]"))
        (0 until array.length().coerceAtMost(PlaybackPolicy.LIMIT)).map { i ->
            val item = array.getJSONObject(i)
            PlaybackEntry(item.getString("title"), item.getString("url"), item.getDouble("position"),
                item.getDouble("duration"), item.optLong("time"))
        }.filter(PlaybackPolicy::resumable)
    }.getOrDefault(emptyList())

    fun playbackFor(url: String): PlaybackEntry? = SiteUrls.movieKey(url)?.let { key -> playback().firstOrNull { SiteUrls.movieKey(it.url) == key } }
    fun savePlayback(entry: PlaybackEntry, ended: Boolean = false) = writePlayback(PlaybackPolicy.merge(playback(), entry, ended))
    fun removePlayback(url: String) { writePlayback(playback().filterNot { SiteUrls.movieKey(it.url) == SiteUrls.movieKey(url) }) }
    fun clearPlayback() { preferences.edit().remove("playback").apply() }
    private fun writePlayback(entries: List<PlaybackEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url).put("position", it.position)
            .put("duration", it.duration).put("time", it.updatedAt)) }
        preferences.edit().putString("playback", array.toString()).apply()
    }

    fun backup(): BrowserBackup = BrowserBackup(bookmarks(), adFilterEnabled,
        AdRules.parseDomains(blockedAdDomains), AdRules.parseDomains(allowedAdDomains))

    fun importBackup(incoming: BrowserBackup, merge: Boolean): BrowserBackup {
        val next = if (merge) BackupCodec.merge(backup(), incoming) else incoming
        // Validate before the single preference transaction; malformed imports leave all data intact.
        BackupCodec.decode(BackupCodec.encode(next))
        val pages = JSONArray()
        next.bookmarks.forEach { pages.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.visitedAt)) }
        check(preferences.edit().putString("bookmarks", pages.toString()).putBoolean("ad_filter_enabled", next.filterEnabled)
            .putString("blocked_ad_domains", next.blocked.joinToString("\n"))
            .putString("allowed_ad_domains", next.allowed.joinToString("\n")).commit()) { "保存备份数据未成功，请重试" }
        return next
    }

    fun allowDomain(domain: String) {
        val values = AdRules.parseDomains(allowedAdDomains) + AdRules.parseDomains(domain)
        require(values.size <= 128) { "放行域名已达 128 条，请先整理规则" }
        allowedAdDomains = values.joinToString("\n")
    }

    private fun read(key: String): List<PageEntry> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PageEntry(item.getString("title"), item.getString("url"), item.optLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, entries: List<PageEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("title", entry.title).put("url", entry.url).put("time", entry.visitedAt))
        }
        preferences.edit().putString(key, array.toString()).apply()
    }

    companion object {
        val DOWNLOAD_CONCURRENCY = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

        fun parseAddresses(value: String): List<String> {
            val values = value.trim().split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }.distinct()
            require(values.size in 1..8) { "请填写 1 至 8 个备用地址，每行一个" }
            return values.map { AddressPolicy.ipv4(it).hostAddress.orEmpty() }
        }
    }
}
