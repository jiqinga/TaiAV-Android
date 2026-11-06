package com.taiav.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid input was accepted" } }

private fun dnsChecks() {
    val query = DnsWire.query("taiav.com")
    val response = ByteArrayOutputStream()
    DataOutputStream(response).use { out ->
        out.writeShort(query.id); out.writeShort(0x8180); out.writeShort(1); out.writeShort(1); out.writeInt(0)
        out.write(query.bytes, 12, query.bytes.size - 12)
        out.writeShort(0xC00C); out.writeShort(1); out.writeShort(1); out.writeInt(120); out.writeShort(4)
        out.write(byteArrayOf(104, 21, 42, 69))
    }
    val packet = response.toByteArray()
    val answer = DnsWire.answer(query, packet)
    check(answer.addresses.single().hostAddress == "104.21.42.69" && answer.ttlSeconds == 120L)
    rejects { DnsWire.answer(query, packet.copyOf(packet.size - 1)) }
    rejects { DnsWire.answer(query.copy(id = query.id xor 1), packet) }
    val loop = packet.copyOf().apply { this[12] = 0xC0.toByte(); this[13] = 12 }
    rejects { DnsWire.answer(query, loop) }
    val mismatch = packet.copyOf().apply { this[13] = 'b'.code.toByte() }
    rejects { DnsWire.answer(query, mismatch) }
    println("PASS DNS compressed records, IDs, truncated packets, cycles, and question matching")
}

private fun validationChecks() {
    check(Authority.parse("taiav.com:443") == Authority("taiav.com", 443))
    check(Authority.parse("[2606:4700::1111]:443").port == 443)
    listOf("taiav.com:80", "taiav.com:443/path", "user@taiav.com:443", "bad host:443", "taiav.com:443\r\n", ":443").forEach { value -> rejects { Authority.parse(value) } }
    listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.0.1", "198.18.0.1", "100.64.0.1", "0.0.0.0", "224.0.0.1").forEach { value -> rejects { AddressPolicy.ipv4(value) } }
    check(AddressPolicy.ipv4("104.21.42.69").hostAddress == "104.21.42.69")
    check(!AddressPolicy.publicAddress(InetAddress.getByName("fc00::1")))
    rejects { AddressPolicy.endpoint("http://dns.example/dns-query") }
    rejects { AddressPolicy.endpoint("https://user:pass@dns.example/query") }
    rejects { TunnelServer.readHeader(ByteArrayInputStream(ByteArray(17000) { 'x'.code.toByte() })) }
    println("PASS CONNECT authority, HTTPS ports, local-address rejection, and header bounds")
}

private fun tunnelChecks() {
    // A binary stream larger than both relay buffers, with the TLS-like bytes pipelined
    // immediately after CONNECT. The upstream waits for EOF before responding.
    val payload = ByteArray(100_000) { (it % 251).toByte() }
    val reply = payload.reversedArray()
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { origin ->
        val received = CompletableFuture<ByteArray>()
        val originThread = Thread {
            origin.accept().use { peer ->
                peer.soTimeout = 5000
                val bytes = ByteArrayOutputStream()
                peer.getInputStream().copyTo(bytes)
                received.complete(bytes.toByteArray())
                peer.getOutputStream().write(reply)
                peer.getOutputStream().flush()
            }
        }.apply { isDaemon = true; start() }
        val tunnel = TunnelServer { host, port ->
            check(host == "fixture.example" && port == 443)
            Socket("127.0.0.1", origin.localPort)
        }
        val boundPort = tunnel.port
        tunnel.use {
            Socket("127.0.0.1", tunnel.port).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().apply {
                    write("CONNECT fixture.example:443 HTTP/1.1\r\nHost: fixture.example\r\n\r\n".toByteArray())
                    write(payload); flush()
                }
                check(TunnelServer.readHeader(client.getInputStream()).startsWith("HTTP/1.1 200"))
                client.shutdownOutput()
                val actual = ByteArrayOutputStream()
                client.getInputStream().copyTo(actual)
                check(actual.toByteArray().contentEquals(reply)) { "The download stream changed" }
                check(received.get(5, TimeUnit.SECONDS).contentEquals(payload)) { "The upload stream changed" }
            }
        }
        originThread.join(1000)
        // On Linux, a blocked accept can keep the listening descriptor alive
        // briefly after close() returns. Check bounded shutdown, closing every
        // probe socket, rather than assuming Windows' immediate refusal.
        val shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runCatching { Socket("127.0.0.1", boundPort).use { } }.isSuccess) {
            check(System.nanoTime() < shutdownDeadline) { "Tunnel still accepts connections after shutdown" }
            Thread.sleep(10)
        }
    }
    TunnelServer { _, _ -> error("Rejected requests must not reach connector") }.use { tunnel ->
        Socket("127.0.0.1", tunnel.port).use { client ->
            client.soTimeout = 3000
            client.getOutputStream().apply { write("CONNECT fixture.example:80 HTTP/1.1\r\n\r\n".toByteArray()); flush() }
            check(TunnelServer.readHeader(client.getInputStream()).startsWith("HTTP/1.1 502"))
        }
    }
    println("PASS pipelined binary payload, bidirectional relay, half-close, rejection, and shutdown")
}

fun main() { mediaSourceChecks(); featureChecks(); resourceProbeChecks(); adRulesChecks(); dnsChecks(); validationChecks(); tunnelChecks(); println("ALL NETWORK CHECKS PASSED") }
