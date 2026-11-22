package com.arix.screenshare

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalises and validates the signaling-server address typed by the user.
 * Plain http:// (needed for offline LAN mode) is only accepted for local/private hosts, so a screen
 * stream's signaling can never accidentally travel unencrypted over the public internet.
 * Pure JVM code (no Android classes) so it is unit-testable.
 */
object ServerAddress {
    sealed class Result {
        data class Ok(val base: String) : Result()
        data class Bad(val message: String) : Result()
    }

    fun normalize(raw: String): Result {
        var s = raw.trim()
        if (s.isEmpty()) return Result.Bad("Enter the server address, e.g. http://192.168.1.20:8000")
        if (!s.contains("://")) {
            val host = s.substringBefore('/').substringBefore(':')
            s = (if (isPrivateHost(host)) "http://" else "https://") + s
        }
        val url = s.toHttpUrlOrNull() ?: return Result.Bad("That is not a valid server address.")
        if (url.scheme == "http" && !isPrivateHost(url.host)) {
            return Result.Bad("Plain http:// is only allowed for local-network addresses. Use https:// for internet servers.")
        }
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return Result.Ok("${url.scheme}://$host$port")
    }

    /** ws://… or wss://… URL for the sender role. */
    fun senderWsUrl(base: String, code: String, token: String): String =
        base.replaceFirst("http", "ws") + "/ws/$code/sender?token=$token"

    fun isPrivateHost(rawHost: String): Boolean {
        val h = rawHost.lowercase().trim('[', ']')
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.') && !h.contains(':')) return true            // single-label LAN name, e.g. "mylaptop"
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
        if (v4 != null) {
            val o = v4.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || o[0] == 127 ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 100 && o[1] in 64..127)                         // CGNAT range, also used by Tailscale
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }
}
