package com.taiav.browserdata

import com.taiav.network.NetworkJournal
import java.net.URI

/** HLS URIs always return through the session's cache reader, including audio, maps and encryption keys. */
object LocalPlaylist {
    fun rewrite(text: String, baseUrl: String, local: (String) -> String): String {
        val base = requireNotNull(NetworkJournal.parsed(baseUrl))
        require(text.trimStart().startsWith("#EXTM3U"))
        require(text.length <= 1024 * 1024)
        fun uri(value: String): String {
            if (value.startsWith("data:")) return value
            val absolute = base.resolve(URI(value)).toString()
            require(NetworkJournal.parsed(absolute) != null) { "媒体列表地址格式需要检查" }
            return local(absolute)
        }
        return text.lineSequence().joinToString("\n") { line ->
            val value = line.trim()
            when {
                value.isBlank() -> value
                !value.startsWith('#') -> uri(value)
                else -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${uri(it.groupValues[1])}\"" }
            }
        }
    }
}
