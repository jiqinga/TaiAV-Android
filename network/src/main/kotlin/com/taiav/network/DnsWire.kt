package com.taiav.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.IDN
import java.net.InetAddress
import java.security.SecureRandom

/** RFC 1035 wire format, used by RFC 8484 DoH. No Android or HTTP dependency. */
object DnsWire {
    data class Query(val id: Int, val bytes: ByteArray, val host: String)
    data class Answer(val addresses: List<InetAddress>, val ttlSeconds: Long)

    fun query(host: String): Query {
        val ascii = IDN.toASCII(host.trimEnd('.')).lowercase()
        require(ascii.length in 1..253) { "域名长度不正确" }
        val id = SecureRandom().nextInt(65536)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeShort(id)
            output.writeShort(0x0100)
            output.writeShort(1)
            repeat(3) { output.writeShort(0) }
            ascii.split('.').forEach { label ->
                require(label.length in 1..63 && label.matches(Regex("[a-z0-9_-]+"))) { "域名格式不正确" }
                output.writeByte(label.length)
                output.writeBytes(label)
            }
            output.writeByte(0)
            output.writeShort(1) // A; keep the first version usable on IPv4-only networks.
            output.writeShort(1)
        }
        return Query(id, bytes.toByteArray(), ascii)
    }

    fun answer(query: Query, bytes: ByteArray): Answer {
        fun requireRange(offset: Int, size: Int) {
            require(offset >= 0 && size >= 0 && offset <= bytes.size - size) { "DNS 响应不完整" }
        }
        fun u16(offset: Int): Int {
            requireRange(offset, 2)
            return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        }
        fun name(start: Int): Pair<String, Int> {
            var cursor = start
            var end = -1
            val labels = mutableListOf<String>()
            val visited = HashSet<Int>()
            while (true) {
                require(visited.add(cursor) && visited.size <= 128) { "DNS 压缩指针异常" }
                requireRange(cursor, 1)
                val length = bytes[cursor].toInt() and 255
                if (length == 0) return labels.joinToString(".").lowercase() to (if (end >= 0) end else cursor + 1)
                if (length and 0xC0 == 0xC0) {
                    val pointer = u16(cursor) and 0x3FFF
                    if (end < 0) end = cursor + 2
                    cursor = pointer
                } else {
                    require(length in 1..63) { "DNS 标签异常" }
                    requireRange(cursor + 1, length)
                    labels += String(bytes, cursor + 1, length, Charsets.US_ASCII)
                    cursor += length + 1
                }
            }
        }

        requireRange(0, 12)
        require(u16(0) == query.id) { "DNS 请求编号不匹配" }
        val flags = u16(2)
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0200 == 0) { "DNS 响应标记异常" }
        require(flags and 15 == 0) { "DNS 返回错误 ${flags and 15}" }
        require(u16(4) == 1) { "DNS 问题数异常" }
        val (questionName, questionEnd) = name(12)
        require(questionName == query.host && u16(questionEnd) == 1 && u16(questionEnd + 2) == 1) { "DNS 问题不匹配" }
        var cursor = questionEnd + 4
        val aliases = mutableMapOf<String, String>()
        val records = mutableListOf<Triple<String, InetAddress, Long>>()
        repeat(u16(6)) {
            val (owner, next) = name(cursor)
            cursor = next
            val type = u16(cursor)
            val clazz = u16(cursor + 2)
            val ttl = (u16(cursor + 4).toLong() shl 16) or u16(cursor + 6).toLong()
            val size = u16(cursor + 8)
            cursor += 10
            requireRange(cursor, size)
            if (clazz == 1 && type == 5) aliases[owner] = name(cursor).first
            if (clazz == 1 && type == 1 && size == 4) {
                records += Triple(owner, InetAddress.getByAddress(bytes.copyOfRange(cursor, cursor + size)), ttl)
            }
            cursor += size
        }
        val allowedOwners = mutableSetOf(query.host)
        var current = query.host
        repeat(16) { aliases[current]?.let { current = it; allowedOwners += it } }
        val selected = records.filter { it.first in allowedOwners }
        require(selected.isNotEmpty()) { "DNS 未返回 IPv4 地址" }
        return Answer(selected.map { it.second }.distinct(), selected.minOf { it.third }.coerceIn(1, 300))
    }
}
