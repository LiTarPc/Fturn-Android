package com.freeturn.app.data.share

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.RawVpnConfig
import com.freeturn.app.data.config.TunnelTransport
import org.json.JSONObject
import java.util.Base64

object RawShareLink {
    fun parse(link: String): FreeturnLink {
        require(link.length <= 16384) { "RAW link too large" }
        val o = JSONObject(String(Base64.getUrlDecoder().decode(link.substringAfter("://")), Charsets.UTF_8))
        val allowed = setOf("v", "server", "vk", "key", "mtu", "streams", "streamsPerCred", "mode", "transport", "obfProfile", "uplink")
        require(o.keys().asSequence().all { it in allowed }) { "Unknown RAW link field" }
        require(o.getString("mode") in setOf("full", "tunnel")) { "Invalid RAW route mode" }
        val v = o.getInt("v")
        require(v in 1..4) { "Unsupported RAW link version" }
        require(v >= 2 || o.optInt("streamsPerCred") == 0) { "Invalid RAW v1 fields" }
        require(v >= 3 || (o.optString("transport").isEmpty() && o.optString("obfProfile").isEmpty())) { "Invalid RAW version fields" }
        require(v >= 4 || o.optString("uplink").isEmpty()) { "Invalid RAW uplink version" }
        val transport = if (v >= 3) o.getString("transport") else "tcp"
        val profile = if (v >= 3) o.getString("obfProfile") else "rtpopus2"
        val uplink = if (v >= 4) o.getString("uplink") else "packet"
        val spc = if (v >= 2) o.getInt("streamsPerCred") else 5
        require(transport in setOf("tcp", "udp")) { "Invalid RAW transport" }
        require(profile in setOf("rtpopus", "rtpopus2", "rtpopus3")) { "Invalid RAW encryption profile" }
        require(ObfProfile.isValidKey(o.getString("key"))) { "Invalid RAW shared key" }
        val cfg = ClientConfig(serverAddress = o.getString("server"), vkLink = o.getString("vk"),
            threads = o.getInt("streams"), streamsPerCred = spc, rawMtu = o.getInt("mtu"), rawUplink = uplink,
            tunnelTransport = TunnelTransport.RAW)
        RawVpnConfig.validate(cfg)
        return FreeturnLink(provider = "vk", peer = cfg.serverAddress, links = cfg.vkLink,
            transport = transport, obfProfile = profile, obfKey = o.getString("key"),
            n = cfg.threads, streamsPerCred = spc, rawVpn = true, rawMtu = cfg.rawMtu, rawUplink = uplink)
    }
}
