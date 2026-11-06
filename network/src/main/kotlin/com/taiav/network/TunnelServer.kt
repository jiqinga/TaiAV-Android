package com.taiav.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class Authority(val host: String, val port: Int) {
    companion object {
        fun parse(value: String): Authority {
            require(value.length <= 260 && !value.any { it.isWhitespace() || it in "/\\@?#%" }) { "目标地址格式异常" }
            val host: String
            val port: String
            if (value.startsWith('[')) {
                val end = value.indexOf(']')
                require(end > 1 && value.getOrNull(end + 1) == ':')
                host = value.substring(1, end).lowercase()
                require(host.matches(Regex("[0-9a-f:]+")))
                port = value.substring(end + 2)
            } else {
                require(value.count { it == ':' } == 1)
                host = value.substringBefore(':').lowercase().trimEnd('.')
                require(host.length in 1..253 && host.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9_-]+")) })
                port = value.substringAfter(':')
            }
            require(port == "443") { "仅支持 HTTPS 端口" }
            return Authority(host, 443)
        }
    }
}

/** A loopback CONNECT relay. TLS bytes stay untouched; WebView verifies the real server. */
class TunnelServer(private val connector: (String, Int) -> Socket) : Closeable {
    private val listener = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private fun pool(name: String) = ThreadPoolExecutor(
        0, 32, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, name).apply { isDaemon = true } },
    )
    private val handlers = pool("taiav-connect")
    private val uploads = pool("taiav-upload")
    val port: Int get() = listener.localPort
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                try {
                    val client = listener.accept()
                    sockets += client
                    try { handlers.execute { handle(client) } }
                    catch (_: RejectedExecutionException) { sockets -= client; client.close() }
                } catch (_: IOException) { if (!closed) close() }
            }
        }, "taiav-listen").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var established = false
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val header = readHeader(client.getInputStream())
            val first = header.lineSequence().first().split(' ')
            require(first.size == 3 && first[0] == "CONNECT" && first[2] in listOf("HTTP/1.1", "HTTP/1.0"))
            val target = Authority.parse(first[1])
            val remote = connector(target.host, target.port)
            upstream = remote
            sockets += remote
            if (closed) return
            client.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            established = true
            client.soTimeout = 120_000
            uploads.execute {
                try { client.getInputStream().copyTo(remote.getOutputStream(), 32 * 1024) }
                catch (_: IOException) { /* Peer closure is normal, especially for cancelled media ranges. */ }
                finally { runCatching { remote.shutdownOutput() } }
            }
            remote.getInputStream().copyTo(client.getOutputStream(), 32 * 1024)
            runCatching { client.shutdownOutput() }
        } catch (_: Exception) {
            if (!established) runCatching {
                client.getOutputStream().apply {
                    write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
            }
        } finally {
            upstream?.let { sockets -= it; runCatching { it.close() } }
            sockets -= client
            runCatching { client.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        handlers.shutdownNow()
        uploads.shutdownNow()
    }

    companion object {
        fun readHeader(input: InputStream): String {
            val output = ByteArrayOutputStream()
            var tail = 0
            while (output.size() < 16_384) {
                val value = input.read()
                if (value < 0) throw IOException("连接提前结束")
                output.write(value)
                tail = (tail shl 8) or value
                if (tail == 0x0D0A0D0A) return output.toString("US-ASCII")
            }
            throw IOException("请求头过长")
        }
    }
}
