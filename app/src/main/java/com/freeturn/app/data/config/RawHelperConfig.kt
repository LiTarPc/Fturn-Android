package com.freeturn.app.data.config

import com.freeturn.app.data.RuleDownloadTunnel
import org.json.JSONArray
import org.json.JSONObject

/** Explicit proxy clients only. Ordinary phone packets never enter this service. */
object RawHelperConfig {
    fun build(hotspot: HotspotConfig, download: RuleDownloadTunnel.Endpoint): String {
        val config = JSONObject(SingBoxConfig.build(JSONObject().put("type", "direct").put("tag", "proxy"), JSONArray())).apply {
            put("inbounds", JSONArray())
            getJSONObject("route").put("auto_detect_interface", false)
        }
        return hotspot.configure(RuleDownloadTunnel.configure(config.toString(), download))
    }
}
