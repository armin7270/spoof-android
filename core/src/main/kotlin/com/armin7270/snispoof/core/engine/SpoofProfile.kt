package com.armin7270.snispoof.core.engine

import com.armin7270.snispoof.core.desync.DesyncMethod
import com.armin7270.snispoof.core.desync.DesyncParams
import com.armin7270.snispoof.core.packet.Ip4
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Scope in which a profile applies to an outgoing TCP:443 flow. */
enum class MatchScope(val id: String) {
    ALL_443("all443"),           // every TLS flow (patterniha's single-target model)
    CLOUDFLARE("cloudflare"),    // flows into Cloudflare ranges (default)
    IP_LIST("ip_list"),          // user-provided CIDR list
    HOSTNAME("hostname");        // resolved hostnames seen on our DNS

    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: CLOUDFLARE }
}

/**
 * One SNI-spoof profile — the Android equivalent of patterniha's config.json
 * (LISTEN_PORT / CONNECT_IP / CONNECT_PORT / FAKE_SNI) plus desync options.
 */
@Serializable
data class SpoofProfile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val matchScope: String = MatchScope.CLOUDFLARE.id,
    val ipList: List<String> = emptyList(),     // CIDRs for IP_LIST
    val hostSuffixes: List<String> = emptyList(), // for HOSTNAME
    // patterniha core fields
    val connectIp: String = "188.114.98.0",     // CONNECT_IP
    val connectPort: Int = 443,                 // CONNECT_PORT
    val fakeSni: String = "auth.vercel.com",    // FAKE_SNI
    val substitute: Boolean = false,            // connect to connectIp instead of original dst
    val desyncMethod: String = DesyncMethod.SPLIT_SNI.id,
    val splitN: Int = 5,
    val splitAt: Int = 2,
    val fragmentCount: Int = 4,
    val delayMs: Int = 25,
    val rebindPauseMs: Int = 20,
) {
    val match: MatchScope get() = MatchScope.fromId(matchScope)

    fun desyncParams(): DesyncParams = DesyncParams(
        method = DesyncMethod.fromId(desyncMethod),
        splitN = splitN,
        splitAt = splitAt,
        fragmentCount = fragmentCount,
        delayMs = delayMs,
        fakeSni = fakeSni,
        rebindPauseMs = rebindPauseMs,
    )

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun defaultProfiles(): List<SpoofProfile> = listOf(
            SpoofProfile(
                id = "cf-default",
                name = "Cloudflare · split at SNI",
                matchScope = MatchScope.CLOUDFLARE.id,
                connectIp = "188.114.98.0",
                fakeSni = "auth.vercel.com",
                substitute = false,
                desyncMethod = DesyncMethod.SPLIT_SNI.id,
            ),
            SpoofProfile(
                id = "cf-frag",
                name = "Cloudflare · split 5",
                matchScope = MatchScope.CLOUDFLARE.id,
                connectIp = "104.16.0.0",
                fakeSni = "www.speedtest.net",
                substitute = false,
                desyncMethod = DesyncMethod.SPLIT_N.id,
            ),
            SpoofProfile(
                id = "all-443",
                name = "All TLS · fragmented",
                matchScope = MatchScope.ALL_443.id,
                substitute = false,
                fakeSni = "auth.vercel.com",
                desyncMethod = DesyncMethod.MULTI_FRAG.id,
            ),
        )
    }
}

object ProfileCodec {
    fun encode(profiles: List<SpoofProfile>): String = profiles.joinToString("\n") { SpoofProfile.json.encodeToString(it) }

    fun decode(text: String): List<SpoofProfile> {
        val out = ArrayList<SpoofProfile>()
        // profiles are separated by a blank line; tolerate single or batch imports
        val chunks = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.size == 1 && !chunks[0].startsWith("{")) return out
        for (chunk in chunks) {
            runCatching { out.add(SpoofProfile.json.decodeFromString<SpoofProfile>(chunk)) }
        }
        if (out.isEmpty()) {
            runCatching {
                val arr = SpoofProfile.json.decodeFromString<List<SpoofProfile>>(text)
                out.addAll(arr)
            }
        }
        return out
    }

    /** Import of the original patterniha config.json format. */
    fun fromPatternihaConfig(text: String): SpoofProfile? {
        return runCatching {
            val obj = SpoofProfile.json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                ?: return null
            fun str(key: String, def: String): String =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: def
            fun int(key: String, def: Int): Int =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: def
            SpoofProfile(
                id = "pat-" + System.currentTimeMillis(),
                name = "patterniha import",
                matchScope = MatchScope.ALL_443.id,
                connectIp = str("CONNECT_IP", "188.114.98.0"),
                connectPort = int("CONNECT_PORT", 443),
                fakeSni = str("FAKE_SNI", "auth.vercel.com"),
                desyncMethod = DesyncMethod.SPLIT_N.id,
            )
        }.getOrNull()
    }
}

/** De-duplication like the reference app's config import. */
fun dedupe(profiles: List<SpoofProfile>): List<SpoofProfile> {
    val seen = HashSet<String>()
    val out = ArrayList<SpoofProfile>()
    for (p in profiles) {
        val key = "${p.matchScope}|${p.connectIp}|${p.connectPort}|${p.fakeSni}|${p.desyncMethod}|${p.splitN}|${p.splitAt}|${p.fragmentCount}|${p.delayMs}"
        if (seen.add(key)) out.add(p)
    }
    return out
}

/** The well-known Cloudflare IPv4 ranges (published list). */
object CloudflareRanges {
    val CIDRS = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "172.64.0.0/13", "131.0.72.0/22",
    )

    private val parsed = CIDRS.map { cidr ->
        val (base, prefix) = cidr.split("/")
        Triple(Ip4.parse(base), prefix.toInt(), cidr)
    }

    fun contains(ip: Int): Boolean = parsed.any { (base, prefix, _) -> Ip4.inCidr(ip, base, prefix) }

    /** A few stable edge IPs used as CONNECT_IP candidates by the scanner/speed test. */
    val EDGE_CANDIDATES = listOf(
        "188.114.96.0", "188.114.97.0", "188.114.98.0", "188.114.99.0",
        "104.16.0.0", "104.17.0.0", "104.18.0.0", "104.19.0.0",
        "172.64.0.0", "162.158.0.0", "108.162.192.0", "141.101.64.0",
    )
}

/** Decides where a flow should be relayed and with which desync. */
class ProfileRouter(private var profiles: List<SpoofProfile>, private val allowAll443WhenNoneMatch: Boolean = false) {

    @Synchronized
    fun setProfiles(list: List<SpoofProfile>) { profiles = list }

    data class Decision(
        val targetIp: Int,
        val targetPort: Int,
        val desync: DesyncParams?,
        val profile: SpoofProfile?,
    )

    fun pick(dstIp: Int, dstPort: Int, hostnames: Set<String>): Decision {
        val active = synchronized(this) { profiles }
        for (p in active) {
            if (!p.enabled) continue
            val matched = when (p.match) {
                MatchScope.ALL_443 -> dstPort == 443
                MatchScope.CLOUDFLARE -> dstPort == 443 && CloudflareRanges.contains(dstIp)
                MatchScope.IP_LIST -> dstPort == 443 && p.ipList.any { cidr ->
                    runCatching {
                        val (base, prefix) = cidr.split("/")
                        Ip4.inCidr(dstIp, Ip4.parse(base), prefix.toInt())
                    }.getOrDefault(false)
                }
                MatchScope.HOSTNAME -> dstPort == 443 && hostnames.any { h ->
                    p.hostSuffixes.any { suffix -> h == suffix || h.endsWith(".$suffix") }
                }
            }
            if (!matched) continue
            val targetIp = if (p.substitute) runCatching { Ip4.parse(p.connectIp) }.getOrDefault(dstIp) else dstIp
            val targetPort = if (p.substitute) p.connectPort else dstPort
            return Decision(targetIp, targetPort, p.desyncParams(), p)
        }
        return Decision(dstIp, dstPort, null, null)
    }
}
