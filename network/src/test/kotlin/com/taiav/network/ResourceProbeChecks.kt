package com.taiav.network

import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Real loopback TLS with a temporary test CA, never an Android emulator or remote site. */
fun resourceProbeChecks() {
    listOf(
        java.net.UnknownHostException("fixture") to "DNS 解析失败",
        java.net.SocketTimeoutException("fixture") to "连接或响应超时",
        java.net.ConnectException("fixture") to "TCP 连接失败",
        javax.net.ssl.SSLHandshakeException("fixture") to "TLS / 证书验证失败",
    ).forEach { (cause, category) ->
        val failure = runCatching {
            ResourceProbe.check("https://fixture.example/check", { _, _ -> throw java.io.IOException("route failed", cause) })
        }.exceptionOrNull() ?: error("A failed route was accepted")
        check(ResourceProbe.failureDescription(failure).startsWith(category))
    }
    val a = java.io.IOException("a"); val b = java.io.IOException("b", a); a.initCause(b)
    check(ResourceProbe.failureDescription(a) == "连接检测失败（IOException）")
    println("PASS wrapped route DNS, timeout, TCP and TLS categories; cyclic causes terminate")
    val temporary = Files.createTempDirectory("taiav-resource-probe-")
    val password = "fixture-only-password".toCharArray()
    val originalContext = SSLContext.getDefault()
    try {
        val keyStoreFile = temporary.resolve("fixture.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(), "-storepass", String(password),
            "-dname", "CN=fixture.example", "-ext", "SAN=DNS:fixture.example", "-validity", "2", "-noprompt")
            .redirectErrorStream(true).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "Fixture certificate generation timed out" }
        check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        val keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStoreFile).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) })
        (serverContext.serverSocketFactory.createServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            server.soTimeout = 10000
            val requests = mutableListOf<String>()
            val finished = CompletableFuture<Unit>()
            val thread = Thread {
                try {
                    repeat(3) { index ->
                        (server.accept() as SSLSocket).use { peer ->
                            peer.soTimeout = 5000
                            try {
                                peer.startHandshake()
                                val header = TunnelServer.readHeader(peer.getInputStream())
                                requests += header
                                peer.getOutputStream().apply {
                                    val status = if (index == 0) "405 Method Not Allowed" else "206 Partial Content"
                                    write("HTTP/1.1 $status\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray()); flush()
                                }
                            } catch (error: java.io.IOException) { if (index < 2) throw error }
                        }
                    }
                    finished.complete(Unit)
                } catch (error: Throwable) { finished.completeExceptionally(error) }
            }.apply { isDaemon = true; start() }
            val connector = { _: String, _: Int -> Socket("127.0.0.1", server.localPort) }
            val result = ResourceProbe.check("https://fixture.example/hls/chunk.ts?token=fixture", connector, "session=fixture")
            check(result.status == 206 && result.method == "GET" && result.tls.startsWith("TLS"))
            check(requests.size == 2 && requests[0].startsWith("HEAD /hls/chunk.ts?token=fixture HTTP/1.1"))
            check(requests[1].contains("Range: bytes=0-0\r\n") && requests[1].contains("Cookie: session=fixture\r\n"))
            check(runCatching { ResourceProbe.check("https://wrong.example/video", connector) }.isFailure) { "TLS hostname mismatch was accepted" }
            finished.get(10, TimeUnit.SECONDS); thread.join(1000)
        }
    } finally {
        SSLContext.setDefault(originalContext)
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    println("PASS real TLS resource probe, HEAD-to-range fallback, signed query preservation and hostname validation")
}
