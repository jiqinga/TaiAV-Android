package com.taiav.browser

import android.content.Context
import android.webkit.CookieManager
import com.taiav.network.AdRules
import com.taiav.network.DirectRouter
import com.taiav.network.ResourceProbe
import com.taiav.network.TunnelServer
import com.taiav.browserdata.HlsQuality
import com.taiav.browserdata.HlsQualities
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Each native session owns its relay; changing WebView's proxy never breaks a playing session. */
class NativePlaybackNetwork(context: Context, store: BrowserStore, mediaUrl: String) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val rules = store.adRules()
    private val filtering = store.adFilterEnabled
    private val route = if (store.optimized) DirectRouter(store.routingConfig()) else null
    private val relay = TunnelServer { host, port ->
        if (filtering && rules.blocks("https://$host/")) throw java.io.IOException("广告资源已过滤")
        route?.connect(host, port) ?: ResourceProbe.systemConnect(host, port)
    }
    val proxyUrl = "http://127.0.0.1:${relay.port}"
    private val cookieManager = CookieManager.getInstance()
    val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port)))
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieManager.getCookie(url.toString()).orEmpty()
                .split(';').mapNotNull { Cookie.parse(url, it.trim() + "; Path=/") }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookieManager.setCookie(url.toString(), it.toString()) }
            }
        })
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!request.url.isHttps || (filtering && rules.blocks(request.url.toString()))) {
                throw java.io.IOException("媒体资源地址已过滤")
            }
            chain.proceed(request)
        }.build()

    // FFmpeg needs a PEM trust bundle rather than Android's hashed certificate directory.
    val caFile: File = File.createTempFile("player-ca-", ".pem", context.cacheDir)
    val cookieFile: File = File.createTempFile("player-cookies-", ".txt", context.cacheDir)

    init {
        try {
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }.trustManagers.filterIsInstance<X509TrustManager>()
            caFile.bufferedWriter().use { output ->
                managers.flatMap { it.acceptedIssuers.toList() }.forEach { certificate ->
                    output.appendLine("-----BEGIN CERTIFICATE-----")
                    output.appendLine(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded))
                    output.appendLine("-----END CERTIFICATE-----")
                }
            }
            check(caFile.length() > 0) { "系统证书读取未成功" }
            val host = java.net.URI(mediaUrl).host
            cookieFile.bufferedWriter().use { output ->
                output.appendLine("# Netscape HTTP Cookie File")
                cookieManager.getCookie(mediaUrl).orEmpty().split(';').forEach { value ->
                    val pair = value.trim().split('=', limit = 2)
                    if (pair.size == 2 && pair.none { it.contains('\n') || it.contains('\r') || it.contains('\t') }) {
                        // Host-only cookies: no static Cookie header forwarded to other CDN hosts.
                        output.appendLine("$host\tFALSE\t/\tTRUE\t0\t${pair[0]}\t${pair[1]}")
                    }
                }
            }
        } catch (error: Exception) { close(); throw error }
    }

    fun hlsQualities(url: String, referer: String, agent: String): List<HlsQuality> {
        if (!java.net.URI(url).path.orEmpty().endsWith(".m3u8", true)) return emptyList()
        return runCatching {
            client.newCall(Request.Builder().url(url).header("Referer", referer).header("User-Agent", agent).build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body ?: return@use emptyList()
                body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 256 * 1024) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > 256 * 1024) emptyList() else HlsQualities.parse(bytes.toString(Charsets.UTF_8), response.request.url.toString())
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing pooled TLS sockets can write a close_notify record. Keep that off the UI thread.
        cleanup.execute {
            client.dispatcher.cancelAll()
            runCatching { client.connectionPool.evictAll() }
            relay.close()
            caFile.delete()
            cookieFile.delete()
        }
    }

    companion object {
        private val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "player-network-close").apply { isDaemon = true } }
    }
}
