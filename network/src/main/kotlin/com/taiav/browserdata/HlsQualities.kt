package com.taiav.browserdata

import com.taiav.network.NetworkJournal

data class HlsQuality(val bitrate: Long, val height: Int) {
    val label: String get() = if (height > 0) "${height}p · ${bitrate / 1000} kbps" else "${bitrate / 1000} kbps"
}

object HlsQualities {
    fun parse(manifest: String, baseUrl: String): List<HlsQuality> {
        if (manifest.length > 256 * 1024 || !manifest.trimStart().startsWith("#EXTM3U")) return emptyList()
        val base = NetworkJournal.parsed(baseUrl) ?: return emptyList()
        var attributes: String? = null
        val values = mutableListOf<HlsQuality>()
        manifest.lineSequence().take(4000).map(String::trim).forEach { line ->
            if (line.startsWith("#EXT-X-STREAM-INF:")) attributes = line.substringAfter(':')
            else if (line.isNotEmpty() && !line.startsWith('#')) {
                val attrs = attributes
                attributes = null
                if (attrs != null && NetworkJournal.parsed(runCatching { base.resolve(line).toString() }.getOrDefault("")) != null) {
                    val bitrate = Regex("(?:^|,)BANDWIDTH=(\\d+)(?:,|$)").find(attrs)?.groupValues?.get(1)?.toLongOrNull()
                    val height = Regex("(?:^|,)RESOLUTION=\\d+x(\\d+)(?:,|$)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (bitrate != null && bitrate in 1..1_000_000_000L && height in 0..16384) values += HlsQuality(bitrate, height)
                }
            }
        }
        return values.distinctBy { it.bitrate }.sortedByDescending { it.bitrate }.take(32)
    }
}
