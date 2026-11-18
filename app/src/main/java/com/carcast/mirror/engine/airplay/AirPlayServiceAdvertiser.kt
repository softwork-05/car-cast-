package com.carcast.mirror.engine.airplay

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.InetAddress
import java.util.HashMap
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class AirPlayServiceAdvertiser(private val context: Context? = null) {

    companion object {
        private const val TAG = "CarCast_AirPlaymDNS"
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        private const val RAOP_SERVICE_TYPE = "_raop._tcp.local."
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
    }

    private var jmdns: JmDNS? = null
    private var airplayService: ServiceInfo? = null
    private var raopService: ServiceInfo? = null

    @Volatile
    private var isAdvertising = false
    @Volatile
    private var currentAdvertisedName: String? = null
    @Volatile
    private var currentAdvertisedIp: String? = null

    val deviceMac: String = "A4:C1:38:E1:B9:20"

    fun startAdvertising(localIp: String, deviceName: String) {
        if (localIp == "0.0.0.0") return
        if (isAdvertising && currentAdvertisedName == deviceName && currentAdvertisedIp == localIp) {
            return
        }

        Thread {
            try {
                stopAdvertising()
                Thread.sleep(150) // Brief pause to allow previous sockets & multicast state to clear

                AirPlayDebugLogger.log("[mDNS:START] Initializing JmDNS on $localIp (Host: CarCast-LCD)")
                val bindingAddress = InetAddress.getByName(localIp)
                val hostName = "CarCast-LCD"
                jmdns = JmDNS.create(bindingAddress, hostName)

                val macFormatted = deviceMac
                val crypto = AirPlayCrypto.instance

                // 1. Register AirPlay Video Mirroring Service
                // features: 0x527FFEE6,0x0 (Standard non-MFi profile matching UxPlay for iOS 17 compatibility)
                val airplayProps = HashMap<String, String>().apply {
                    put("deviceid", macFormatted)
                    put("features", "0x527FFEE6,0x0")
                    put("model", "AppleTV3,2")
                    put("srcvers", "220.68")
                    put("flags", "0x4")
                    put("vv", "2")
                    put("pk", crypto.publicKeyHex)
                    put("pi", "b08ab08a-02c0-4820-9426-0e1371800a72")
                    put("acl", "0")
                }

                airplayService = ServiceInfo.create(
                    AIRPLAY_SERVICE_TYPE,
                    deviceName,
                    AIRPLAY_PORT,
                    0,
                    0,
                    airplayProps
                )
                jmdns?.registerService(airplayService)
                AirPlayDebugLogger.log("[mDNS:AIRPLAY] Registered _airplay._tcp '$deviceName' on $localIp:$AIRPLAY_PORT")

                // 2. Register RAOP Audio Service (Remote Audio Output Protocol)
                val raopProps = HashMap<String, String>().apply {
                    put("ch", "2")
                    put("cn", "0,1,2,3")
                    put("da", "true")
                    put("et", "0,3,5")
                    put("md", "0,1,2")
                    put("pw", "false")
                    put("sr", "44100")
                    put("ss", "16")
                    put("tp", "UDP")
                    put("vn", "65537")
                    put("vs", "220.68")
                    put("am", "AppleTV3,2")
                    put("sf", "0x4")
                }

                val cleanMac = deviceMac.replace(":", "")
                val raopName = "$cleanMac@$deviceName"

                raopService = ServiceInfo.create(
                    RAOP_SERVICE_TYPE,
                    raopName,
                    RAOP_PORT,
                    0,
                    0,
                    raopProps
                )
                jmdns?.registerService(raopService)
                AirPlayDebugLogger.log("[mDNS:RAOP] Registered _raop._tcp '$raopName' on $localIp:$RAOP_PORT")

                currentAdvertisedName = deviceName
                currentAdvertisedIp = localIp
                isAdvertising = true
                Log.i(TAG, "AirPlay uniquely registered as '$deviceName' ($macFormatted) on $localIp")
                AirPlayDebugLogger.log("[mDNS:SUCCESS] Broadcast live as '$deviceName' on $localIp (Ports: AirPlay $AIRPLAY_PORT, RAOP $RAOP_PORT)")
            } catch (e: Exception) {
                AirPlayDebugLogger.log("[mDNS:ERROR] Failed to start mDNS: ${e.javaClass.simpleName} - ${e.message}")
                Log.e(TAG, "Failed to start AirPlay mDNS advertisement", e)
            }
        }.start()
    }

    fun stopAdvertising() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing JmDNS", e)
        } finally {
            jmdns = null
            airplayService = null
            raopService = null
            isAdvertising = false
            currentAdvertisedName = null
            currentAdvertisedIp = null
        }
    }
}
