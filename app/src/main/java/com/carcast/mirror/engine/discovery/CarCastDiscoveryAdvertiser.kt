package com.carcast.mirror.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carcast.mirror.engine.PlatformHelper
import com.carcast.mirror.engine.airplay.AirPlayDebugLogger

/**
 * Publishes the CarCastMirror Receiver DNS-SD / mDNS service over Android NSD.
 * Enables nearby phones in Sender Mode to automatically discover "My Car LCD" or Android TV,
 * retrieve its current IP, and pair using its persistent stable device ID.
 */
class CarCastDiscoveryAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "CarCast_DiscoveryAdv"
        const val SERVICE_TYPE = "_carcast._tcp."
        const val DEFAULT_WS_PORT = 8080
        const val DEFAULT_HTTP_PORT = 8088
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var isAdvertising = false
    @Volatile
    private var currentAdvertisedName: String? = null

    fun startAdvertising(broadcastName: String) {
        if (isAdvertising && currentAdvertisedName == broadcastName) {
            return
        }
        stopAdvertising()

        acquireMulticastLock()

        val stableId = DeviceIdHelper.getOrCreateStableReceiverId(context)
        currentAdvertisedName = broadcastName
        isAdvertising = true

        val isTv = PlatformHelper.isAndroidTv(context)
        val platformStr = if (isTv) ReceiverPlatform.ANDROID_TV.name else ReceiverPlatform.CAR_LCD.name

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = broadcastName
            serviceType = SERVICE_TYPE
            port = DEFAULT_WS_PORT

            // TXT records with stable ID, platform type and connectivity endpoints
            try {
                setAttribute("id", stableId)
                setAttribute("name", broadcastName)
                setAttribute("platform", platformStr)
                setAttribute("type", "receiver")
                setAttribute("wsPort", DEFAULT_WS_PORT.toString())
                setAttribute("httpPort", DEFAULT_HTTP_PORT.toString())
                setAttribute("model", if (isTv) "CarCast-AndroidTV" else "CarCast-LCD")
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting NSD attributes: ${e.message}")
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(regInfo: NsdServiceInfo) {
                Log.i(TAG, "CarCast Receiver NSD registered: '${regInfo.serviceName}' (port ${regInfo.port}, platform $platformStr)")
                AirPlayDebugLogger.log("[DISCOVERY:ADVERTISER] Registered CarCast NSD '${regInfo.serviceName}' on port ${regInfo.port} ($platformStr, ID: $stableId)")
            }

            override fun onRegistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "CarCast Receiver NSD registration failed: code $errorCode")
                AirPlayDebugLogger.log("[DISCOVERY:ADVERTISER:ERR] Registration failed: code $errorCode")
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    mainHandler.postDelayed({
                        try {
                            nsdManager.unregisterService(this)
                        } catch (_: Exception) {}
                        if (isAdvertising) {
                            startAdvertising(broadcastName)
                        }
                    }, 500)
                }
            }

            override fun onServiceUnregistered(regInfo: NsdServiceInfo) {
                Log.i(TAG, "CarCast Receiver NSD unregistered: '${regInfo.serviceName}'")
                AirPlayDebugLogger.log("[DISCOVERY:ADVERTISER] Unregistered CarCast NSD cleanly")
            }

            override fun onUnregistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "CarCast Receiver NSD unregistration failed: code $errorCode")
            }
        }

        registrationListener = listener

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.i(TAG, "Calling registerService for CarCast Receiver '$broadcastName'...")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering CarCast Receiver NSD", e)
            AirPlayDebugLogger.log("[DISCOVERY:ADVERTISER:ERR] Exception during registerService: ${e.message}")
        }
    }

    fun updateBroadcastName(newName: String) {
        if (newName.isNotBlank() && newName != currentAdvertisedName) {
            Log.i(TAG, "Updating CarCast advertised receiver name: $newName")
            startAdvertising(newName)
        }
    }

    fun stopAdvertising() {
        isAdvertising = false
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.d(TAG, "Unregister ignored: ${e.message}")
            }
        }
        registrationListener = null
        currentAdvertisedName = null

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("carcast_advertiser_multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring MulticastLock", e)
        }
    }
}
