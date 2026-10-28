package com.youraicqa.app

import java.net.IDN
import java.net.InetAddress
import java.net.URL

object PluginNetGuard {

    const val MAX_REDIRECTS = 5

    fun parseHttpUrl(raw: String): URL? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        return try {
            val url = URL(trimmed)
            val proto = url.protocol?.lowercase()
            if (proto != "http" && proto != "https") return null
            if (url.host.isNullOrBlank()) return null
            url
        } catch (_: Exception) { null }
    }

    // Exact host or any subdomain of an allowlisted domain; "*" allows everything
    fun isHostAllowed(host: String, allowlist: Set<String>): Boolean {
        if (allowlist.isEmpty()) return false
        val normalizedHost = normalizeHost(host) ?: return false
        if (allowlist.any { it.trim() == "*" }) return true
        for (raw in allowlist) {
            val domain = normalizeHost(raw) ?: continue
            if (normalizedHost == domain || normalizedHost.endsWith(".$domain")) return true
        }
        return false
    }

    private fun normalizeHost(host: String): String? {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return null
        return try { IDN.toASCII(h) } catch (_: Exception) { h }
    }

    // Checked on the resolved IP, not the hostname, to stop SSRF into the local network
    fun isBlockedAddress(address: InetAddress): Boolean {
        return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            address.isAnyLocalAddress ||
            isCloudMetadataAddress(address)
    }

    // Cloud instance metadata endpoints
    private fun isCloudMetadataAddress(address: InetAddress): Boolean {
        val host = address.hostAddress ?: return false
        return host == "169.254.169.254" || host.equals("fd00:ec2::254", ignoreCase = true)
    }
}
