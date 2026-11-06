package com.taiav.network

import com.taiav.browserdata.BackupCodec
import com.taiav.browserdata.BrowserBackup
import com.taiav.browserdata.SavedPage
import com.taiav.browserdata.PlaybackEntry
import com.taiav.browserdata.PlaybackPolicy
import com.taiav.browserdata.SiteUrls
import org.json.JSONObject

private fun featureRejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid feature data was accepted" } }

fun featureChecks() {
    val page = SavedPage("测试收藏", "https://taiav.com/cn/movie/test123", 123L)
    val backup = BrowserBackup(listOf(page), true, setOf("ads.example.com"), setOf("player.example.com"))
    val text = BackupCodec.encode(backup, 1)
    check(BackupCodec.decode(text) == backup)
    check(!text.contains("cookie", true) && !text.contains("playback", true))
    listOf("{}", "[]", text + " trailing", text.replace("taiav-backup", "other"),
        text.replace("\"version\": 1", "\"version\": 1.2"), text.replace("https://taiav.com/cn/movie/test123", "https://evil.example/movie/1"),
        text.replace("ads.example.com", "https://ads.example.com"), text.replace("ads.example.com", "ads.example.com other.example.com"),
        text.replace("\"enabled\": true", "\"enabled\": \"true\""), "x".repeat(BackupCodec.MAX_BYTES + 1)).forEach {
        featureRejects { BackupCodec.decode(it) }
    }
    val next = backup.copy(bookmarks = listOf(page.copy(title = "另一个标题"), page.copy(url = "https://taiav.com/cn/movie/next")), filterEnabled = false,
        blocked = setOf("new.example.com"))
    val merged = BackupCodec.merge(backup, next)
    check(merged.bookmarks.size == 2 && merged.bookmarks[0].title == page.title && !merged.filterEnabled)
    check(merged.blocked == setOf("ads.example.com", "new.example.com"))
    featureRejects { BackupCodec.merge(backup.copy(blocked = (0..127).map { "a$it.example.com" }.toSet()), next) }
    val invalid = JSONObject(text).put("bookmarks", org.json.JSONArray().put(JSONObject().put("title", "x").put("url", "https://taiav.com/").put("time", "not a time")))
    featureRejects { BackupCodec.decode(invalid.toString()) }
    println("PASS backup round trip, strict validation, size bounds, merge and duplicate policy")

    check(SiteUrls.movieKey("https://taiav.com/en/movie/abc?source=1") == SiteUrls.movieKey("https://www.taiav.com/cn/movie/abc"))
    check(SiteUrls.movieKey("https://taiav.com/cn/search?q=movie") == null)
    check(!SiteUrls.isSite("https://taiav.com.evil/movie/abc"))
    check(!SiteUrls.isSite("https://user@taiav.com/movie/abc"))
    val progress = PlaybackEntry("movie", page.url, 50.0, 120.0, 1)
    val existing = listOf(progress)
    check(PlaybackPolicy.merge(existing, progress.copy(position = 0.0), false) == existing)
    check(PlaybackPolicy.merge(existing, progress.copy(position = Double.NaN), false) == existing)
    check(PlaybackPolicy.merge(existing, progress.copy(duration = Double.POSITIVE_INFINITY), false) == existing)
    check(PlaybackPolicy.merge(existing, progress.copy(position = 119.0), false).isEmpty())
    check(PlaybackPolicy.merge(existing, progress.copy(position = 60.0), true).isEmpty())
    check(PlaybackPolicy.merge(existing, progress.copy(position = 60.0), false).single().position == 60.0)
    val hundred = (1..100).map { progress.copy(url = "https://taiav.com/movie/m$it") }
    check(PlaybackPolicy.merge(hundred, progress, false).size == 100)
    check(PlaybackPolicy.time(3661.9) == "1:01:01")
    println("PASS playback identity, initial-state preservation, completion, finite bounds and record limits")

    val journal = NetworkJournal()
    val threads = (1..4).map { Thread { repeat(100) { journal.record("https://ads.example/a?token=secret#fragment", state = ResourceState.BLOCKED, detail = "广告域名") } }.apply { start() } }
    threads.forEach { it.join() }
    check(journal.entries(ResourceState.BLOCKED).single().count == 400)
    check(!journal.entries().single().url.contains("secret") && !journal.entries().single().url.contains('#'))
    journal.record("https://img.example/poster.jpg", state = ResourceState.REQUESTED, detail = "网页请求")
    journal.record("https://media.example/a.m3u8?token=x", state = ResourceState.FAILED, detail = "HTTP 403")
    check(journal.samples().map { it.kind }.toSet() == setOf(ResourceKind.IMAGE, ResourceKind.VIDEO))
    check(NetworkJournal.classify("https://cdn.example/video/poster.jpg?x=1") == ResourceKind.IMAGE)
    check(NetworkJournal.classify("https://cdn.example/hls/part.m4s") == ResourceKind.VIDEO)
    check(NetworkJournal.classify("https://taiav.com/", mainFrame = true) == ResourceKind.PAGE)
    check(NetworkJournal.parsed("http://127.0.0.1/a") == null)
    journal.clearBlocked()
    check(journal.entries(ResourceState.BLOCKED).isEmpty() && journal.entries(ResourceState.FAILED).size == 1)
    repeat(250) { journal.record("https://h$it.example/a", state = ResourceState.FAILED, detail = "连接失败") }
    check(journal.entries().size == 200)
    println("PASS concurrent request logs, aggregation, redacted URLs, resource categories and bounded storage")

    val header = ResourceProbe.header("https://media.example/hls/master.m3u8?token=secret#hidden", "GET", "session=abc", "https://taiav.com/", "fixture")
    check(header.startsWith("GET /hls/master.m3u8?token=secret HTTP/1.1\r\n"))
    check(header.contains("Range: bytes=0-0\r\n") && header.contains("Cookie: session=abc\r\n") && !header.contains("hidden"))
    check(ResourceProbe.header("https://media.example?token=x", "HEAD", null, "https://taiav.com/", "fixture").startsWith("HEAD /?token=x HTTP/1.1"))
    featureRejects { ResourceProbe.header("http://media.example/", "HEAD", null, "https://taiav.com/", "fixture") }
    featureRejects { ResourceProbe.header("https://media.example/", "POST", null, "https://taiav.com/", "fixture") }
    featureRejects { ResourceProbe.header("https://media.example/", "HEAD", "x\r\nInjected: 1", "https://taiav.com/", "fixture") }
    println("PASS diagnostic HTTP paths, signed queries, bounded GET and header validation")
}
