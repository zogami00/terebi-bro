package com.terebibro.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Finds the active LAN IPv4 address and its prefix length, used both to bind
 * the control server and to reject peers outside the local subnet.
 */
class LocalNetwork(context: Context) {

    data class Info(val ip: String, val prefixLength: Int, val network: String)

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun current(): Info? {
        val cm = connectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network) ?: return null
        val linkProperties = cm.getLinkProperties(network) ?: return null

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "none"
        }

        val address = linkProperties.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return null

        val ip = address.address.hostAddress ?: return null
        return Info(ip = ip, prefixLength = address.prefixLength, network = networkType)
    }
}
