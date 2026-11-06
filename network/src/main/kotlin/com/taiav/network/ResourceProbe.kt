package com.taiav.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ResourceProbeResult(val status: Int, val tls: String, val address: String, val elapsedMs: Long, val method: String)

object ResourceProbe {
    /** Routing wraps transport failures; retain their actionable category. */
    fun failureDescription(error: Throwable): String {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && visited.size < 16 && visited.add(current)) {
            val category = when (current) {
                is java.net.UnknownHostException -> "DNS 解析失败"
                is java.net.SocketTimeoutException -> "连接或响应超时"
                is javax.net.ssl.SSLException -> "TLS / 证书验证失败"
                is java.net.ConnectException -> "TCP 连接失败"
                else -> null
            }
            if (category != null) return "$category（${current.javaClass.simpleName}）"
            current = current.cause
        }
        return "连接检测失败（${error.javaClass.simpleName}）"
    }

    fun systemConnect(host: String, port: Int): Socket {
        require(port == 443)
        val addresses = InetAddress.getAllByName(host).filter(AddressPolicy::publicAddress)
        require(addresses.isNotEmpty()) { "域名未返回公网地址" }
        var last: Exception? = null
        addresses.take(3).forEach { address ->
            val socket = Socket()
            try { socket.connect(InetSocketAddress(address, port), 2500); return socket }
            catch (error: Exception) { socket.close(); last = error }
        }
        throw java.io.IOException("连接失败", last)
    }

    fun check(url: String, connector: (String, Int) -> Socket, cookie: String? = null,
        referer: String = "https://taiav.com/", userAgent: String = "TaiAV-Network-Check/0.3"): ResourceProbeResult {
        val uri = NetworkJournal.parsed(url) ?: error("诊断仅支持标准 HTTPS 地址")
        val started = System.nanoTime()
        fun request(method: String): ResourceProbeResult {
            connector(uri.host, 443).use { socket ->
                socket.soTimeout = 5000
                val address = socket.inetAddress.hostAddress.orEmpty()
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, uri.host, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 5000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.getOutputStream().apply { write(header(url, method, cookie, referer, userAgent).toByteArray(Charsets.US_ASCII)); flush() }
                    val status = TunnelServer.readHeader(tls.getInputStream()).lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("HTTP 响应异常")
                    return ResourceProbeResult(status, tls.session.protocol, address, (System.nanoTime() - started) / 1_000_000, method)
                }
            }
        }
        val head = request("HEAD")
        return if (head.status in listOf(405, 501)) request("GET") else head
    }

    internal fun header(url: String, method: String, cookie: String?, referer: String, userAgent: String): String {
        val uri = NetworkJournal.parsed(url) ?: error("资源地址格式异常")
        require(method in listOf("HEAD", "GET"))
        fun clean(value: String): String {
            require(value.length <= 8192 && !value.any { it == '\r' || it == '\n' }) { "请求头格式异常" }
            return value
        }
        val ascii = java.net.URI(uri.toASCIIString())
        val target = ascii.rawPath.orEmpty().ifEmpty { "/" } + (ascii.rawQuery?.let { "?$it" } ?: "")
        return buildString {
            append("$method $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n")
            append("User-Agent: ${clean(userAgent)}\r\nReferer: ${clean(referer)}\r\nAccept: */*\r\n")
            if (method == "GET") append("Range: bytes=0-0\r\n")
            if (!cookie.isNullOrEmpty()) append("Cookie: ${clean(cookie)}\r\n")
            append("\r\n")
        }
    }
}
