package com.freeturn.app.domain.proxy

enum class DnsFallback { CARRIER_UNAVAILABLE, DOH }

/** One alert per transition kind during a logical connection, including core retries. */
class DnsFallbackAlerts {
    private val seen = mutableSetOf<DnsFallback>()

    @Synchronized fun mark(fallback: DnsFallback): Boolean = seen.add(fallback)
    @Synchronized fun reset() = seen.clear()
}
