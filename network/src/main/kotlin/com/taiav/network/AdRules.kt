package com.taiav.network

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Immutable snapshot: request interception reads this from WebView worker threads. */
class AdRules(
    extraBlocked: Set<String> = emptySet(),
    private val allowed: Set<String> = emptySet(),
) {
    val blockedDomains: Set<String> = DEFAULT_DOMAINS + extraBlocked

    fun blocks(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https", "ws", "wss")) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (allowed.any { matchesHost(host, it) }) return false
        if (blockedDomains.any { matchesHost(host, it) }) return true
        // /acs/ai is a real site feature; only these observed advertising paths match.
        return matchesHost(host, "taiav.com") && SITE_AD_PATH.matches(uri.path.orEmpty())
    }

    companion object {
        val DEFAULT_DOMAINS = setOf(
            "jads.co", "juicyads.com", "adxadserv.com", "pemsrv.com",
            "imkirh.com", "mavrtracktor.com", "exoclick.com", "exosrv.com",
            "exdynsrv.com", "trafficjunky.net", "trafficjunky.com", "propellerads.com",
            "popads.net", "popcash.net", "doubleclick.net", "googlesyndication.com",
            "adsterra.com",
        )
        private val SITE_AD_PATH = Regex("^/acs/(?:desktop|phone)/[0-9]+/[0-9]+\\.js$", RegexOption.IGNORE_CASE)
        fun matchesHost(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

        fun parseDomains(value: String): Set<String> {
            val domains = value.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
            require(domains.size <= 128) { "最多填写 128 个域名" }
            return domains.map { raw ->
                val domain = runCatching {
                    IDN.toASCII(raw.removePrefix("*.").trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                }.getOrElse { throw IllegalArgumentException("请填写域名，例如 ads.example.com") }
                require(domain.length <= 253 && domain.contains('.') && domain.any { it in 'a'..'z' } &&
                    domain.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) {
                    "请填写域名，例如 ads.example.com；每行一个，无需 https:// 或路径"
                }
                domain
            }.toSet()
        }
    }
}
