@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.taiav.browserdata.LocalPlaylist
import com.taiav.browserdata.HlsQualities
import com.taiav.browserdata.HlsQuality
import com.taiav.network.NetworkJournal
import com.taiav.network.TunnelServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loopback-only per-session server. Offline mode has a cache-only DataSource with no network upstream. */
class CacheMediaServer(private val factory: DataSource.Factory, private val offline: Boolean,
    private val contentLength: (String) -> Long = { -1L }) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    private val urls = ConcurrentHashMap<String, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = Executors.newFixedThreadPool(4) { task -> Thread(task, "player-cache-client").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile var failedRequests = 0
        private set
    val port get() = listener.localPort

    init {
        Thread({
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                if (sockets.size >= 16) { socket.close(); continue }
                sockets.add(socket)
                runCatching { clients.execute { serve(socket) } }.onFailure { sockets.remove(socket); socket.close() }
            }
        }, "player-cache-accept").apply { isDaemon = true; start() }
    }

    fun url(original: String): String {
        require(NetworkJournal.parsed(original) != null)
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(original.toByteArray()).joinToString("") { "%02x".format(it) }
        require(urls.size < 100_000 || urls.containsKey(id)) { "媒体列表资源过多" }
        urls[id] = original
        val extension = URI(original).path.orEmpty().substringAfterLast('.').takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bin"
        return "http://127.0.0.1:$port/$token/$id/media.$extension"
    }

    fun qualities(original: String): List<HlsQuality> = runCatching {
        if (!URI(original).path.orEmpty().endsWith(".m3u8", true)) return@runCatching emptyList()
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(original).build())
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (bytes.size() <= 256 * 1024) {
                val count = source.read(buffer, 0, buffer.size); if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            HlsQualities.parse(bytes.toString("UTF-8"), original)
        } finally { source.close() }
    }.getOrDefault(emptyList())

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val lines = TunnelServer.readHeader(socket.getInputStream()).split("\r\n")
            val request = lines.first().split(' ')
            require(request.size == 3 && request[0] in listOf("GET", "HEAD"))
            val path = URI(request[1]).path.split('/')
            require(path.size == 4 && path[1] == token)
            val original = requireNotNull(urls[path[2]])
            val rangeText = lines.drop(1).firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
            val range = rangeText?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            require(rangeText == null || range != null)
            val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = range?.groupValues?.get(2)?.toLongOrNull()
            require(start >= 0 && (end == null || end >= start))
            val manifest = URI(original).path.orEmpty().endsWith(".m3u8", true)
            val source = factory.createDataSource()
            try {
                val openStart = if (manifest) 0 else start
                val length = source.open(DataSpec.Builder().setUri(original).setPosition(openStart)
                    .setLength(if (!manifest && end != null) end - start + 1 else C.LENGTH_UNSET.toLong()).build())
                val output = socket.getOutputStream()
                if (manifest) {
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (bytes.size() <= 1024 * 1024) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    require(bytes.size() <= 1024 * 1024)
                    val rewritten = LocalPlaylist.rewrite(bytes.toString("UTF-8"), source.uri?.toString() ?: original, ::url).toByteArray()
                    require(start < rewritten.size)
                    val last = end?.coerceAtMost(rewritten.size.toLong()-1) ?: rewritten.size.toLong()-1
                    val count = last-start+1
                    val partial = range != null
                    val headers = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: $count\r\nAccept-Ranges: bytes\r\n" +
                        (if (partial) "Content-Range: bytes $start-$last/${rewritten.size}\r\n" else "") + "Connection: close\r\n\r\n"
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    if (request[0] != "HEAD") output.write(rewritten, start.toInt(), count.toInt())
                } else {
                    val rangeHeader = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                    val upstreamTotal = rangeHeader?.substringAfterLast('/')?.toLongOrNull()
                    val total = upstreamTotal ?: contentLength(original).takeIf { it >= 0 } ?: if (length >= 0 && end == null) start + length else null
                    val partial = range != null && length > 0 && total != null
                    val headers = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: application/octet-stream\r\n" +
                        (if (length >= 0) "Content-Length: $length\r\n" else "") +
                        (if (partial) "Content-Range: bytes $start-${start+length-1}/$total\r\n" else "") + "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    if (request[0] != "HEAD") {
                        val buffer = ByteArray(32 * 1024)
                        while (!closed) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
                    }
                }
                output.flush()
            } finally { source.close() }
        } catch (_: Exception) {
            if (!closed) failedRequests++
            runCatching { socket.getOutputStream().write("HTTP/1.1 ${if (offline) "503 Offline Cache Miss" else "502 Media Error"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
        } finally { sockets.remove(socket); runCatching { socket.close() } }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        clients.shutdownNow(); urls.clear()
    }
}
