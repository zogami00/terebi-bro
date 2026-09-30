package com.terebibro.tv.mdns

import android.content.Context
import android.net.wifi.WifiManager
import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.util.SafeLog
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises `_http._tcp` for the control server as `<deviceName>.local`.
 *
 * The [WifiManager.MulticastLock] is held only while a registration is active.
 * Failure is never fatal: the IP address fallback is always shown and never
 * depends on mDNS.
 */
class MdnsAdvertiser(private val context: Context, private val config: ConfigStore) {

    private var jmDns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start(ip: String) {
        stop()
        try {
            acquireMulticastLock()
            val host = config.mdnsName
            val address = InetAddress.getByName(ip)
            val instance = JmDNS.create(address, "$host.local")
            // jmDNS 3.6.3 derives the SRV target from the JmDNS instance host
            // (the `$host.local` passed to create); ServiceInfo has no setServer.
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                host,
                config.controllerPort,
                "Terebi Bro controller"
            )
            instance.registerService(info)
            jmDns = instance
            SafeLog.i(TAG, "mDNS advertised as $host.local")
        } catch (e: Exception) {
            SafeLog.w(TAG, "mDNS advertisement failed: ${e.javaClass.simpleName}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        try {
            jmDns?.close()
        } catch (ignored: Exception) {
            // ignore
        }
        jmDns = null
        try {
            multicastLock?.release()
        } catch (ignored: Exception) {
            // ignore
        }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock
    }

    private companion object {
        const val TAG = "MdnsAdvertiser"
        const val SERVICE_TYPE = "_http._tcp.local."
        const val MULTICAST_LOCK_TAG = "terebi-mdns"
    }
}
