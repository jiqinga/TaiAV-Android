package com.taiav.network

fun adRulesChecks() {
    val rules = AdRules()
    listOf("https://poweredby.jads.co/js/jads.js", "https://static.adxadserv.com/js/adb.js",
        "https://a.pemsrv.com/popunder1000.js", "https://s.pemsrv.com/v1/link.php",
        "https://go.imkirh.com/smartpop/123", "wss://a.pemsrv.com/socket",
        "https://JADS.CO./ad", "https://taiav.com/acs/phone/2/2.js?cache=1",
        "https://www.taiav.com/acs/desktop/1/1.js",
        "https://taiav.com/%61cs/phone/2/2.js").forEach { check(rules.blocks(it)) { "Missed ad: $it" } }
    listOf("https://taiav.com/cn", "https://taiav.com/cn/movie/123", "https://taiav.com/acs/ai",
        "https://taiav.com/api/movie?id=ads", "https://taiav.com/previewvideo?id=123",
        "https://taiav.com/js/clappr.min.js", "https://taiav.com/js/uikit.js",
        "https://img.storyofthepast.xyz/videos/poster.jpg", "https://cdn.example/video.m3u8",
        "https://cdn.example/segment.ts", "https://cdn.example/ads-in-title.mp4",
        "https://notjads.co/image.jpg", "https://jads.co.example/video",
        "https://jads.co@cdn.example/video", "blob:https://taiav.com/123", "data:text/plain,test",
        "about:blank", "", "https://[").forEach { check(!rules.blocks(it)) { "False positive: $it" } }
    val custom = AdRules(AdRules.parseDomains("Ads.Example.com\n*.banner.example.com"),
        AdRules.parseDomains("player.ads.example.com\npoweredby.jads.co"))
    check(custom.blocks("https://cdn.ads.example.com/image"))
    check(!custom.blocks("https://player.ads.example.com/video.m3u8"))
    check(!custom.blocks("https://sub.poweredby.jads.co/file"))
    check(custom.blocks("https://other.jads.co/file"))
    check(AdRules.parseDomains("").isEmpty())
    check(AdRules.parseDomains("EXAMPLE.com. example.com").size == 1)
    listOf("https://example.com", "example.com/path", "example.com:443", "127.0.0.1",
        "-bad.example", "example..com", "localhost", "x@ads.example", "a ".repeat(129)).forEach {
        check(runCatching { AdRules.parseDomains(it) }.isFailure) { "Invalid rule accepted: $it" }
    }
    println("PASS ad domains/subdomains, exact site paths, media preservation, allowlist priority, and input validation")
}
