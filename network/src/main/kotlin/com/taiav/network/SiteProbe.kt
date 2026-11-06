package com.taiav.network

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProbeResult(val httpStatus: Int, val tlsProtocol: String, val elapsedMs: Long)

object SiteProbe {
    fun check(port: Int, host: String = "taiav.com"): ProbeResult {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            require(TunnelServer.readHeader(socket.getInputStream()).startsWith("HTTP/1.1 200")) { "本地连接建立失败" }
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 443, true) as SSLSocket).use { secure ->
                secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                secure.startHandshake()
                secure.getOutputStream().apply {
                    write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: TaiAV-Connection-Check/0.1\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                val header = TunnelServer.readHeader(secure.getInputStream())
                val status = header.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                return ProbeResult(status, secure.session.protocol, (System.nanoTime() - started) / 1_000_000)
            }
        }
    }
}

fun main() {
    val router = DirectRouter()
    TunnelServer(router::connect).use { server ->
        val result = SiteProbe.check(server.port)
        println("status=${result.httpStatus} tls=${result.tlsProtocol} elapsed_ms=${result.elapsedMs}")
        println("route=${router.lastRoute}")
        check(result.httpStatus == 200) { "首页请求未通过" }
    }
}
