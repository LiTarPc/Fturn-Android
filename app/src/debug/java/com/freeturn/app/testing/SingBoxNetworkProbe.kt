package com.freeturn.app.testing

/** Runs as shell through app_process, so the VPN owner's exclusion cannot hide a broken TUN. */
object SingBoxNetworkProbe {
    @JvmStatic fun main(args: Array<String>) {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("198.18.0.1", 19081), 10000)
            socket.soTimeout = 10000
            val payload = "FTURN_SB1144_OK".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(payload)
            val received = ByteArray(payload.size)
            java.io.DataInputStream(socket.getInputStream()).readFully(received)
            check(received.contentEquals(payload))
            println(String(received, Charsets.UTF_8))
        }
    }
}
