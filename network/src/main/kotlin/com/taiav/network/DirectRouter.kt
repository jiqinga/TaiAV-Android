package com.taiav.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class RoutingConfig(
    val fixedAddresses: List<String> = listOf("104.21.42.69", "172.67.158.125"),
    val dohEndpoint: String? = null,
)

object AddressPolicy {
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            if (bytes[0] == 0 || bytes[0] >= 224) return false
            if (bytes[0] == 100 && bytes[1] in 64..127) return false
            if (bytes[0] == 198 && bytes[1] in 18..19) return false
        } else if (bytes[0] and 0xFE == 0xFC) return false
        return true
    }

    fun ipv4(text: String): InetAddress {
        val parts = text.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) { "请填写有效的 IPv4 地址" }
        return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()).also {
            require(publicAddress(it)) { "请使用公网 IPv4 地址" }
        }
    }

    fun endpoint(text: String): String? {
        if (text.isBlank()) return null
        val uri = URI(text.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "DoH 地址需要使用 HTTPS"
        }
        return uri.toASCIIString()
    }
}

class DirectRouter(config: RoutingConfig = RoutingConfig()) {
    data class Route(val host: String, val address: String, val source: String)
    private data class Cached(val addresses: List<InetAddress>, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, Cached>()
    @Volatile private var settings = config
    @Volatile var lastRoute: Route? = null
        private set

    init { validate(config) }

    fun configure(config: RoutingConfig) {
        validate(config)
        settings = config
        dnsCache.clear()
    }

    private fun validate(config: RoutingConfig) {
        require(config.fixedAddresses.isNotEmpty()) { "至少保留一个站点备用地址" }
        config.fixedAddresses.forEach(AddressPolicy::ipv4)
        config.dohEndpoint?.let(AddressPolicy::endpoint)
    }

    fun connect(host: String, port: Int): Socket {
        require(port == 443) { "仅连接 HTTPS 端口" }
        val snapshot = settings
        var lastError: Exception? = null
        fun attempt(addresses: List<InetAddress>, source: String): Socket? {
            addresses.filter(AddressPolicy::publicAddress).sortedBy { if (it is Inet4Address) 0 else 1 }.forEach { address ->
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(address, port), 3500)
                    socket.tcpNoDelay = true
                    socket.soTimeout = 120_000
                    lastRoute = Route(host, address.hostAddress.orEmpty(), source)
                    return socket
                } catch (error: IOException) {
                    lastError = error
                    socket.close()
                }
            }
            return null
        }
        if (host == "taiav.com" || host == "www.taiav.com") {
            attempt(snapshot.fixedAddresses.map(AddressPolicy::ipv4), "备用 IP")?.let { return it }
        }
        val endpoint = snapshot.dohEndpoint
        if (endpoint != null) {
            try { attempt(doh(host, endpoint), "DoH")?.let { return it } }
            catch (error: Exception) { lastError = error }
        }
        try { attempt(InetAddress.getAllByName(host).toList(), "系统 DNS")?.let { return it } }
        catch (error: IOException) { lastError = error }
        throw IOException("连接 $host 失败，请检查网络或备用地址", lastError)
    }

    private fun doh(host: String, endpoint: String): List<InetAddress> {
        val key = "$endpoint|$host"
        val now = System.nanoTime()
        dnsCache[key]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val query = DnsWire.query(host)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query.bytes)
        val uri = URI(endpoint + (if ('?' in endpoint) "&" else "?") + "dns=" + encoded)
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 3500
            connection.readTimeout = 4500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/dns-message")
            require(connection.responseCode == 200) { "DoH HTTP ${connection.responseCode}" }
            require(connection.contentType?.substringBefore(';')?.trim() == "application/dns-message") { "DoH 响应格式不正确" }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_535) { "DoH 响应过大" }
                    output.write(buffer, 0, count)
                }
            }
            val result = DnsWire.answer(query, output.toByteArray())
            val addresses = result.addresses.filter(AddressPolicy::publicAddress)
            require(addresses.isNotEmpty()) { "DoH 返回的地址不适用" }
            dnsCache[key] = Cached(addresses, System.nanoTime() + result.ttlSeconds * 1_000_000_000L)
            return addresses
        } finally { connection.disconnect() }
    }
}
