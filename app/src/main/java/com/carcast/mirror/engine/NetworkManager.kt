package com.carcast.mirror.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInfoState(
    val isConnected: Boolean = false,
    val isHotspot: Boolean = false,
    val ssid: String = "Not Connected",
    val ipAddress: String = "0.0.0.0",
    val portAirPlay: Int = 7000,
    val portAndroidCast: Int = 8080
)

class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "CarCast_NetMgr"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _networkState = MutableStateFlow(NetworkInfoState())
    val networkState: StateFlow<NetworkInfoState> = _networkState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkStatus()
        }

        override fun onLost(network: Network) {
            refreshNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refreshNetworkStatus()
        }
    }

    fun startMonitoring() {
        acquireLocks()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
        refreshNetworkStatus()
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
        releaseLocks()
    }

    fun refreshNetworkStatus() {
        val ip = getLocalIpAddress()
        val isHotspot = isHotspotActive(ip)
        val ssid = getWifiSSID(isHotspot)

        _networkState.value = NetworkInfoState(
            isConnected = ip != "0.0.0.0",
            isHotspot = isHotspot,
            ssid = ssid,
            ipAddress = ip
        )
        Log.i(TAG, "Network updated: IP=$ip, SSID=$ssid, isHotspot=$isHotspot")
    }

    private fun getLocalIpAddress(): String {
        try {
            var fallbackIp: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        // Prefer standard Wi-Fi (wlan), hotspot (ap/softap), or ethernet (eth) interfaces
                        if (iface.name.contains("wlan") || iface.name.contains("ap") || iface.name.contains("eth")) {
                            return hostAddress
                        }
                        if (fallbackIp == null && !hostAddress.startsWith("127.")) {
                            fallbackIp = hostAddress
                        }
                    }
                }
            }
            return fallbackIp ?: "0.0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving IP address", e)
        }
        return "0.0.0.0"
    }

    private fun isHotspotActive(ip: String): Boolean {
        // Standard Android mobile hotspot commonly uses 192.168.43.1 or 192.168.50.1
        return ip.startsWith("192.168.43.") || ip.startsWith("192.168.50.")
    }

    private fun getWifiSSID(isHotspot: Boolean): String {
        if (isHotspot) return "Car Wi-Fi Hotspot"
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: ""
            if (ssid.isEmpty() || ssid == "<unknown ssid>") "Connected (Wi-Fi)" else ssid
        } catch (e: Exception) {
            "Connected (Local Network)"
        }
    }

    private fun acquireLocks() {
        try {
            // Multicast Lock is required for receiving mDNS / Bonjour packets from iOS devices
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("CarCast_MulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL,
                    "CarCast_WifiLock"
                ).apply {
                    acquire()
                }
            }
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CarCast:Wakelock"
                ).apply {
                    acquire(10 * 60 * 1000L /* 10 minutes fallback */)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null

            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null

            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }
}
