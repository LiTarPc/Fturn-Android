package com.freeturn.app.data.config

import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.net.Inet4Address
import org.json.JSONArray
import org.json.JSONObject

/** The WireGuard library parses wg-quick files only; sing-box owns the tunnel. */
class WireGuardProfile private constructor(private val config: Config) {
    companion object {
        fun parse(raw: String): WireGuardProfile {
            require(raw.isNotBlank() && raw.length <= 256 * 1024) { "Пустой или слишком большой конфиг WireGuard" }
            val config = try { Config.parse(raw.byteInputStream()) } catch (e: BadConfigException) {
                // BadConfigException.text/cause can contain private keys.
                throw IllegalArgumentException("Некорректный WireGuard: ${e.section}/${e.location} (${e.reason})")
            }
            require(config.`interface`.addresses.any { it.address is Inet4Address }) { "WireGuard требует адрес IPv4 в Interface" }
            require(config.peers.isNotEmpty()) { "WireGuard требует хотя бы один Peer" }
            config.peers.forEach { peer ->
                require(peer.allowedIps.any { it.address is Inet4Address }) { "WireGuard требует AllowedIPs IPv4 для каждого Peer" }
            }
            return WireGuardProfile(config)
        }
    }

    fun singBoxConfig(localEndpoint: String, ruleSets: JSONArray = JSONArray()): String {
        val localPort = SingBoxConfig.localPort(localEndpoint)
        val peers = JSONArray()
        var bootstrap = false
        config.peers.forEachIndexed { index, peer ->
            val endpoint = if (index == 0) null else peer.endpoint.orElse(null)
            require(index == 0 || endpoint != null) { "Для дополнительных Peer нужен Endpoint" }
            val host = endpoint?.host?.removeSurrounding("[", "]") ?: "127.0.0.1"
            require(':' !in host) { "Endpoint WireGuard должен использовать IPv4" }
            if (host.any { it.isLetter() }) bootstrap = true
            val item = JSONObject().put("address", host).put("port", endpoint?.port ?: localPort)
                .put("public_key", peer.publicKey.toBase64())
                .put("allowed_ips", JSONArray(peer.allowedIps.filter { it.address is Inet4Address }.map { it.toString() }))
                .put("persistent_keepalive_interval", peer.persistentKeepalive.orElse(0))
            peer.preSharedKey.ifPresent { item.put("pre_shared_key", it.toBase64()) }
            peers.put(item)
        }
        val iface = config.`interface`
        val endpoint = JSONObject().put("type", "wireguard").put("tag", "proxy").put("system", false)
            .put("mtu", ClientConfig.WG_MTU)
            .put("address", JSONArray(iface.addresses.filter { it.address is Inet4Address }.map { it.toString() }))
            .put("private_key", iface.keyPair.privateKey.toBase64()).put("peers", peers)
        iface.listenPort.ifPresent { endpoint.put("listen_port", it) }
        if (bootstrap) endpoint.put("domain_resolver", JSONObject().put("server", "bootstrap").put("strategy", "ipv4_only"))
        val allowed = config.peers.flatMap { it.allowedIps }.filter { it.address is Inet4Address }.map { it.toString() }.distinct()
        val dns = iface.dnsServers.filterIsInstance<Inet4Address>().map { it.hostAddress!! }.ifEmpty { listOf("1.1.1.1") }
        return SingBoxConfig.build(endpoint, ruleSets, wireGuard = true, allowedIps = allowed, dnsServers = dns, bootstrapDns = bootstrap)
    }
}
