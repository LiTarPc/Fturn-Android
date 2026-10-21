package com.freeturn.app.data.config

import com.freeturn.app.data.server.ServerOpts

object RawVpnConfig {
    fun validate(cfg: ClientConfig) {
        require(cfg.provider == Provider.VK) { "RAW requires the VK provider" }
        require(!cfg.isRawMode) { "RAW VPN requires a profile, not a custom command" }
        require(HostPort.isValid(cfg.serverAddress)) { "Invalid RAW server address" }
        val links = cfg.vkLink.split(',').map { it.trim() }
        require(links.isNotEmpty() && links.all { link -> runCatching {
            val uri = java.net.URI(link)
            uri.scheme == "https" && uri.host in setOf("vk.ru", "vk.com") &&
                uri.rawUserInfo == null && uri.port == -1 && uri.path.startsWith("/call/join/") &&
                uri.path.length > "/call/join/".length
        }.getOrDefault(false) }) { "RAW requires VK call links" }
        require(cfg.threads in 1..64 && cfg.threads.toLong() * links.size <= 64) { "RAW supports 1..64 total streams" }
        require(cfg.streamsPerCred in 1..64) { "Invalid streams per credential" }
        require(cfg.rawMtu in 576..1500) { "RAW MTU must be 576..1500 and match the server" }
        require(cfg.rawUplink in setOf("packet", "tcp-flow")) { "Invalid RAW uplink mode" }
        val parts = cfg.rawAddress.split('/')
        val octets = parts.first().split('.')
        require(parts.size == 2 && parts[1].toIntOrNull() in 1..30 && octets.size == 4 &&
            octets.all { it.toIntOrNull() in 0..255 && it == it.toIntOrNull().toString() } &&
            octets[0].toInt() in 1..223) { "RAW address must be an IPv4 CIDR (prefix 1..30)" }
    }

    fun args(cfg: ClientConfig, srv: ServerOpts, clientId: String, carrierDns: String?): List<String> {
        validate(cfg)
        require(srv.obfProfile in setOf("rtpopus", "rtpopus2", "rtpopus3") && ObfProfile.isValidKey(srv.obfKey)) {
            "RAW requires the server encryption profile and its 64-character shared key"
        }
        val args = com.freeturn.app.data.CoreArgs.client(cfg, srv, carrierDns, clientId)
            .map { if (it == "-link") "-links" else it }.toMutableList()
        // RAW defaults differ from the legacy core: preserve an explicit 12 too.
        if ("-streams-per-cred" !in args) args += listOf("-streams-per-cred", cfg.streamsPerCred.toString())
        return args + listOf("-raw-address", cfg.rawAddress,
            "-raw-mtu", cfg.rawMtu.toString(), "-raw-uplink", cfg.rawUplink)
    }
}
