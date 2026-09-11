package com.armin7270.snispoof.core.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class ProxyProtocol(val id: String) {
    VLESS("vless"), TROJAN("trojan"), VMESS("vmess");
    companion object { fun fromId(id: String): ProxyProtocol = entries.firstOrNull { it.id == id } ?: VLESS }
}

enum class ProxyNetwork(val id: String) { TCP("tcp"), WS("ws");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: TCP }
}

/** One imported proxy config — the UAC-style "config" the tunnel speaks. */
@Serializable
data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: String = ProxyProtocol.VLESS.id,
    val uuid: String = "",
    val password: String = "",
    val address: String = "",
    val port: Int = 443,
    val tls: Boolean = true,
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val network: String = ProxyNetwork.TCP.id,
    val alpn: String = "http/1.1",
    val allowInsecure: Boolean = true,
) {
    val proto: ProxyProtocol get() = ProxyProtocol.fromId(protocol)
    val net: ProxyNetwork get() = ProxyNetwork.fromId(network)
    val tlsSni: String get() = sni.ifBlank { host.ifBlank { address } }
    val wsHost: String get() = host.ifBlank { address }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(list: List<ProxyConfig>): String =
            list.joinToString("\n") { json.encodeToString(it) }
        fun decode(text: String): List<ProxyConfig> {
            val out = ArrayList<ProxyConfig>()
            text.split("\n").map { it.trim() }.filter { it.startsWith("{") }.forEach { line ->
                runCatching { out.add(json.decodeFromString<ProxyConfig>(line)) }
            }
            if (out.isEmpty()) runCatching { out.addAll(json.decodeFromString<List<ProxyConfig>>(text)) }
            return out
        }
    }
}

/** Parses share-link configs: vless://, trojan://, vmess:// (base64 json). */
object ProxyConfigParser {

    fun parseAll(text: String): List<ProxyConfig> {
        val out = ArrayList<ProxyConfig>()
        val uriRegex = Regex("(vless|trojan|vmess)://[^\\s\"'<>]+")
        for (m in uriRegex.findAll(text)) {
            runCatching { parseOne(m.value) }.getOrNull()?.let { out.add(it) }
        }
        // raw base64 bundles (subscription exports)
        runCatching {
            val decoded = String(com.armin7270.snispoof.core.util.Base64.decode(text), Charsets.UTF_8)
            for (m in uriRegex.findAll(decoded)) {
                runCatching { parseOne(m.value) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out.distinctBy { "${it.protocol}|${it.address}|${it.port}|${it.uuid}|${it.password}|${it.path}" }
    }

    fun parseOne(uri: String): ProxyConfig? {
        val clean = uri.trim().removeSuffix("#")
        return when {
            clean.startsWith("vmess://") -> parseVmess(clean)
            clean.startsWith("vless://") || clean.startsWith("trojan://") -> parseUserPassUri(clean)
            else -> null
        }
    }

    private fun parseUserPassUri(uri: String): ProxyConfig? {
        val scheme = uri.substringBefore("://")
        val rest = uri.substringAfter("://")
        val hashPart = rest.substringAfter("#", "")
        val mainPart = rest.substringBefore("#")
        val queryPart = mainPart.substringAfter("?", "")
        val authority = mainPart.substringBefore("?")
        val userinfo = authority.substringBefore("@")
        val hostPort = authority.substringAfter("@", "")
        if (!hostPort.contains(':')) return null
        val addr = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').substringBefore('/').toIntOrNull() ?: return null
        val q = queryPart.split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null
            else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val name = java.net.URLDecoder.decode(hashPart.ifBlank { "$addr:$port" }, "UTF-8")
            .take(64).ifBlank { "$addr:$port" }
        val type = q["type"] ?: "tcp"
        val security = q["security"] ?: "tls"
        val id = userinfo
        return ProxyConfig(
            id = "cfg-" + sha16(uri),
            name = name,
            protocol = scheme,
            uuid = if (scheme == "vless") id else "",
            password = if (scheme == "trojan") id else "",
            address = addr,
            port = port,
            tls = security == "tls" || security == "reality",
            sni = q["sni"] ?: "",
            host = q["host"] ?: "",
            path = q["path"] ?: "",
            network = if (type == "ws") "ws" else "tcp",
            alpn = q["alpn"]?.split(',')?.firstOrNull()?.ifBlank { "http/1.1" } ?: "http/1.1",
            allowInsecure = q["allowInsecure"] == "1" || q["allowInsecure"] == "true",
        )
    }

    private fun parseVmess(uri: String): ProxyConfig? {
        val b64 = uri.removePrefix("vmess://")
        val decoded = String(com.armin7270.snispoof.core.util.Base64.decode(b64), Charsets.UTF_8)
        val obj = ProxyConfig.json.parseToJsonElement(decoded) as? kotlinx.serialization.json.JsonObject
            ?: return null
        fun str(key: String): String =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val addr = str("add")
        val port = str("port").toIntOrNull() ?: return null
        val net = str("net")
        val tls = str("tls") == "tls"
        val name = str("ps").ifBlank { "$addr:$port" }.take(64)
        val host = when {
            str("host").isNotBlank() -> str("host")
            net == "ws" -> str("path").substringBefore('/').takeIf { it.contains('.') } ?: ""
            else -> ""
        }
        val path = if (net == "ws") {
            val p = str("path")
            if (p.contains("/")) "/" + p.substringAfter('/') else p.ifBlank { "/" }
        } else ""
        return ProxyConfig(
            id = "cfg-" + sha16(uri),
            name = name,
            protocol = "vmess",
            uuid = str("id"),
            address = addr,
            port = port,
            tls = tls,
            sni = str("sni"),
            host = host,
            path = path,
            network = if (net == "ws") "ws" else "tcp",
            alpn = "http/1.1",
            allowInsecure = true,
        )
    }

    internal fun sha16(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
}
