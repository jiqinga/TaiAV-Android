package com.taiav.network

import java.net.URI
import java.util.Locale

enum class ResourceKind(val label: String) { PAGE("页面"), IMAGE("图片"), VIDEO("视频"), OTHER("其他资源") }
enum class ResourceState { REQUESTED, BLOCKED, FAILED }
data class ResourceEvent(val host: String, val url: String, val kind: ResourceKind, val state: ResourceState,
    val detail: String, val count: Int, val time: Long)
data class ResourceTarget(val url: String, val kind: ResourceKind)

/** Bounded process-local observations. Display URLs omit credentials, query tokens and fragments. */
class NetworkJournal {
    private val events = linkedMapOf<String, ResourceEvent>()
    private val targets = linkedMapOf<String, ResourceTarget>()

    @Synchronized fun record(url: String, kind: ResourceKind = classify(url), state: ResourceState, detail: String) {
        val uri = parsed(url) ?: return
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        val display = "https://$host${uri.rawPath.orEmpty().take(500).ifEmpty { "/" }}"
        val safeDetail = detail.replace(Regex("[\\r\\n\\t]"), " ").take(180)
        val key = "$host|$kind|$state|$safeDetail"
        val previous = events.remove(key)
        events[key] = ResourceEvent(host, display, kind, state, safeDetail, (previous?.count ?: 0) + 1, System.currentTimeMillis())
        while (events.size > 200) events.remove(events.keys.first())
        if (state != ResourceState.BLOCKED && kind != ResourceKind.OTHER) {
            val targetKey = "$kind|$host"
            targets.remove(targetKey)
            targets[targetKey] = ResourceTarget(url, kind)
            while (targets.size > 32) targets.remove(targets.keys.first())
        }
    }
    @Synchronized fun entries(state: ResourceState? = null): List<ResourceEvent> = events.values.toList().asReversed().filter { state == null || it.state == state }
    @Synchronized fun clearBlocked() { events.entries.removeAll { it.value.state == ResourceState.BLOCKED } }
    @Synchronized fun clearFailures() { events.entries.removeAll { it.value.state == ResourceState.FAILED } }
    @Synchronized fun samples(): List<ResourceTarget> = listOf(ResourceKind.IMAGE, ResourceKind.VIDEO).flatMap { kind ->
        targets.values.toList().asReversed().filter { it.kind == kind }.take(2)
    }

    companion object {
        fun parsed(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
            url.length <= 8192 && it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.port in listOf(-1, 443)
        }
        fun classify(url: String, destination: String? = null, mainFrame: Boolean = false): ResourceKind {
            if (mainFrame || destination == "document") return ResourceKind.PAGE
            val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            if (destination == "image" || Regex("\\.(?:jpg|jpeg|png|gif|webp|avif|svg|ico)$").containsMatchIn(path)) return ResourceKind.IMAGE
            if (destination in listOf("video", "audio") || Regex("\\.(?:m3u8|mpd|ts|m4s|mp4|webm|aac|m4a)$").containsMatchIn(path)) return ResourceKind.VIDEO
            return ResourceKind.OTHER
        }
    }
}
