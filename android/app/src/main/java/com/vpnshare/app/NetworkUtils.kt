package com.vpnshare.app

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the local IP address for Tethering / Hotspot / Wi-Fi.
     * Android Hotspots typically assign 192.168.43.1, or 192.168.x.x.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: AP / Hotspot / Tethering interfaces (ap0, wlan0, rndis0, usb0, p2p)
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("ap") || name.contains("rndis") || name.contains("p2p") || name.contains("wlan")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            // Typically hotspot IPs are 192.168.43.1 or 192.168.x.x or 10.x.x.x
                            if (!host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }

            // Priority 2: Any non-loopback IPv4
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Standard Android default hotspot IP
        return "192.168.43.1"
    }

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
