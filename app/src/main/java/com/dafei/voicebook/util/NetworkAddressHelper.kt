package com.dafei.voicebook.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddressHelper {
    const val DEFAULT_UPLOAD_PORT = 8765

    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress }
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("169.254.") }
        } catch (_: Exception) {
            null
        }
    }

    fun buildServerUrl(port: Int = DEFAULT_UPLOAD_PORT): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:$port"
    }
}
