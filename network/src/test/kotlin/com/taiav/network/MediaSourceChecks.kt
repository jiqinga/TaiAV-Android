package com.taiav.network

import com.taiav.browserdata.MediaSources
import com.taiav.browserdata.HlsQualities
import com.taiav.browserdata.LocalPlaylist

fun mediaSourceChecks() {
    val sources = MediaSources()
    val first = sources.navigation("https://taiav.com/cn/movie/fixture")
    val signed = "https://cdn.example/master.m3u8?token=a%2Fb&expires=123"
    sources.observe(signed, "https://taiav.com/cn/movie/fixture", first)
    check(sources.snapshot().single().url == signed)
    listOf("blob:https://taiav.com/id", "http://cdn.example/a.mp4", "https://cdn.example/a.ts",
        "https://cdn.example/a.m4s", "https://user:secret@cdn.example/a.mp4").forEach { value ->
        sources.observe(value, null, first)
    }
    check(sources.snapshot().size == 1)
    sources.observe(signed, null, first)
    check(sources.snapshot().single().referer == "https://taiav.com/cn/movie/fixture")
    val second = sources.navigation("https://taiav.com/cn/movie/second")
    sources.observe(signed, null, first)
    sources.observe(signed, "https://taiav.com/cn/movie/fixture", second)
    check(sources.snapshot().isEmpty())
    val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="audio"
        low/index.m3u8?token=a%2Fb
        #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080,AUDIO="audio"
        https://cdn.example/high.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9999999,RESOLUTION=3840x2160
        http://cdn.example/plain.m3u8
    """.trimIndent()
    val qualities = HlsQualities.parse(master, "https://cdn.example/master.m3u8")
    check(qualities.map { it.height } == listOf(1080, 360))
    check(qualities.map { it.bitrate } == listOf(3200000L, 800000L))
    check(HlsQualities.parse("#EXTM3U\n#EXTINF:10\nsegment.ts", signed).isEmpty())
    check(HlsQualities.parse("x".repeat(256 * 1024 + 1), signed).isEmpty())
    println("PASS HLS quality discovery: master variants, bitrate ordering, audio rendition preservation and bounded input")
    val observed = mutableListOf<String>()
    val offlineMaster = LocalPlaylist.rewrite(master.substringBefore("#EXT-X-STREAM-INF:BANDWIDTH=9999999"),
        "https://cdn.example/path/master.m3u8?root=secret") { url -> observed += url; "http://127.0.0.1:12345/session/${observed.size}" }
    check(observed == listOf("https://cdn.example/path/audio.m3u8", "https://cdn.example/path/low/index.m3u8?token=a%2Fb", "https://cdn.example/high.m3u8"))
    check(!offlineMaster.contains("https://"))
    check(offlineMaster.contains("URI=\"http://127.0.0.1:12345/session/1\""))
    val segmentList = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key?id=signed%2Fkey\"\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"1024@0\"\n#EXTINF:4,\nseg.ts?token=signed%2Fsegment\n#EXT-X-ENDLIST"
    val offlineSegments = LocalPlaylist.rewrite(segmentList, "https://cdn.example/video/index.m3u8") { "http://127.0.0.1/cached/${java.net.URLEncoder.encode(it, "UTF-8")}" }
    check(offlineSegments.contains("BYTERANGE=\"1024@0\""))
    check(!offlineSegments.contains("https://"))
    check(runCatching { LocalPlaylist.rewrite("#EXTM3U\nhttp://outside.example/segment.ts", signed) { it } }.isFailure)
    println("PASS offline HLS rewriting: relative signed segments, audio renditions, AES keys, byte ranges and non-HTTPS rejection")
    repeat(30) { sources.observe("https://cdn.example/$it.mp4", null, second) }
    check(sources.snapshot().size == 24)
    check(sources.snapshot().first().url.endsWith("29.mp4"))
    val home = sources.navigation("https://taiav.com/cn")
    sources.observe(signed, null, home)
    check(sources.snapshot().isEmpty())
    println("PASS native media sources: signed URLs, stale navigation, segment rejection, deduplication and bounded storage")
}
