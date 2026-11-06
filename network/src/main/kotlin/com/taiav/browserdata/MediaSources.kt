package com.taiav.browserdata

import com.taiav.network.NetworkJournal

data class MediaSource(val url: String, val referer: String)

/** Page-local candidates only: signed query strings survive; blob URLs and fragments are excluded. */
class MediaSources {
    private var page = ""
    private var generation = 0L
    private val sources = linkedMapOf<String, MediaSource>()

    @Synchronized fun navigation(url: String?): Long {
        generation++
        page = url.orEmpty().substringBefore('#')
        sources.clear()
        return generation
    }

    @Synchronized fun token(): Long = generation

    @Synchronized fun observe(url: String, referer: String?, token: Long) {
        if (token != generation || SiteUrls.movieKey(page) == null || !playable(url)) return
        val from = referer?.takeIf { NetworkJournal.parsed(it) != null } ?: page
        // A top-level movie reference from another navigation is stale.
        if (SiteUrls.movieKey(from) != null && SiteUrls.movieKey(from) != SiteUrls.movieKey(page)) return
        val previous = sources.remove(url)
        sources[url] = MediaSource(url, previous?.referer ?: from)
        while (sources.size > 24) sources.remove(sources.keys.first())
    }

    @Synchronized fun snapshot(): List<MediaSource> = sources.values.toList().asReversed()

    companion object {
        fun playable(url: String): Boolean {
            val uri = NetworkJournal.parsed(url) ?: return false
            return Regex("\\.(m3u8|mpd|mp4|webm|mkv)$", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty())
        }
    }
}
