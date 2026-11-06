package com.taiav.browserdata

import java.net.URI
import java.util.Locale
import com.taiav.network.AdRules
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class SavedPage(val title: String, val url: String, val visitedAt: Long = System.currentTimeMillis())
data class PlaybackEntry(val title: String, val url: String, val position: Double, val duration: Double, val updatedAt: Long = System.currentTimeMillis())
data class BrowserBackup(val bookmarks: List<SavedPage>, val filterEnabled: Boolean, val blocked: Set<String>, val allowed: Set<String>)

object SiteUrls {
    fun isSite(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return value.length <= 4096 && uri.scheme == "https" && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && (host == "taiav.com" || host.endsWith(".taiav.com"))
    }
    fun movieKey(value: String): String? {
        if (!isSite(value)) return null
        val path = URI(value).path.orEmpty()
        val match = Regex("^/(?:cn/|en/|tc/)?movie/([A-Za-z0-9_-]{1,100})/?$").matchEntire(path) ?: return null
        return match.groupValues[1]
    }
}

object PlaybackPolicy {
    const val LIMIT = 100
    fun valid(position: Double, duration: Double): Boolean = position.isFinite() && duration.isFinite() &&
        duration in 10.0..216000.0 && position in 0.0..(duration + 1)
    fun finished(position: Double, duration: Double, ended: Boolean): Boolean = ended || position >= duration - 5
    fun resumable(entry: PlaybackEntry): Boolean = SiteUrls.movieKey(entry.url) != null &&
        valid(entry.position, entry.duration) && entry.position >= 5 && !finished(entry.position, entry.duration, false)
    fun merge(existing: List<PlaybackEntry>, next: PlaybackEntry, ended: Boolean): List<PlaybackEntry> {
        val key = SiteUrls.movieKey(next.url) ?: return existing
        if (!valid(next.position, next.duration)) return existing
        val other = existing.filterNot { SiteUrls.movieKey(it.url) == key }
        if (finished(next.position, next.duration, ended)) return other
        if (next.position < 5) return existing
        return (listOf(next) + other).take(LIMIT)
    }
    fun time(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        return if (total >= 3600) "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
    }
}

object BackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_BOOKMARKS = 1000
    fun encode(backup: BrowserBackup, now: Long = System.currentTimeMillis()): String {
        val pages = JSONArray()
        backup.bookmarks.forEach { pages.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.visitedAt)) }
        val text = JSONObject().put("format", "taiav-backup").put("version", 1).put("createdAt", now)
            .put("bookmarks", pages).put("rules", JSONObject().put("enabled", backup.filterEnabled)
                .put("blocked", JSONArray(backup.blocked.toList())).put("allowed", JSONArray(backup.allowed.toList()))).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份内容超过 2 MB，请先整理收藏" }
        return text
    }
    fun decode(text: String): BrowserBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件超过 2 MB" }
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: error("请选择 TaiAV JSON 备份文件")
        require(tokener.nextClean() == '\u0000') { "备份文件包含多余内容" }
        require(root.opt("format") == "taiav-backup" && root.opt("version") is Number && root.getInt("version") == 1 && root.getDouble("version") == 1.0) { "备份格式或版本不受支持" }
        val pages = root.getJSONArray("bookmarks")
        require(pages.length() <= MAX_BOOKMARKS) { "备份收藏超过 1000 条" }
        val bookmarks = (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val title = page.get("title") as? String ?: error("收藏标题格式异常")
            val url = page.get("url") as? String ?: error("收藏网址格式异常")
            require(title.length in 1..160 && title.isNotBlank() && !title.any { it.code < 32 }) { "收藏标题格式异常" }
            require(SiteUrls.isSite(url) && url.length <= 2048) { "备份中包含非本站网址或过长网址" }
            val time = if (page.has("time")) {
                require(page.get("time") is Number) { "收藏时间格式异常" }
                page.getLong("time").also { require(it >= 0) { "收藏时间格式异常" } }
            } else 0L
            SavedPage(title, url, time)
        }.distinctBy { it.url }
        val rules = root.getJSONObject("rules")
        val enabled = rules.get("enabled") as? Boolean ?: error("广告开关格式异常")
        fun domains(key: String): Set<String> {
            val array = rules.getJSONArray(key)
            require(array.length() <= 128) { "域名规则超过 128 条" }
            val values = (0 until array.length()).map {
                val value = array.get(it) as? String ?: error("域名规则格式异常")
                require(value.isNotBlank() && !value.any { c -> c.isWhitespace() || c in ",;" }) { "每条规则应为一个域名" }
                value
            }
            return AdRules.parseDomains(values.joinToString("\n"))
        }
        return BrowserBackup(bookmarks, enabled, domains("blocked"), domains("allowed"))
    }
    fun merge(local: BrowserBackup, incoming: BrowserBackup): BrowserBackup {
        val pages = (local.bookmarks + incoming.bookmarks).distinctBy { it.url }
        require(pages.size <= MAX_BOOKMARKS) { "合并后收藏超过 1000 条，请选择替换或先整理收藏" }
        val blocked = local.blocked + incoming.blocked
        val allowed = local.allowed + incoming.allowed
        require(blocked.size <= 128 && allowed.size <= 128) { "合并后的域名规则超过 128 条" }
        return BrowserBackup(pages, incoming.filterEnabled, blocked, allowed)
    }
}
