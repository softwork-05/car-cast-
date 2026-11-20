package com.carcast.mirror.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carcast.mirror.engine.airplay.AirPlayDebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active Network Service Discovery (NSD/mDNS) in Sender Mode.
 * Discovers nearby CarCastMirror receivers across Android LCD, Android TV,
 * and Windows PC, dynamically resolving their IP addresses and tracking stable IDs.
 */
class CarCastDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "CarCast_DiscoveryMgr"
        const val CARCAST_SERVICE_TYPE = "_carcast._tcp."
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    private val activeReceivers = ConcurrentHashMap<String, DiscoveredReceiver>()
    private val _discoveredReceivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val discoveredReceivers: StateFlow<List<DiscoveredReceiver>> = _discoveredReceivers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var carcastDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var airplayDiscoveryListener: NsdManager.DiscoveryListener? = null

    init {
        startResolveWorker()
    }

    private fun startResolveWorker() {
        scope.launch {
            for (serviceInfo in resolveChannel) {
                resolveSingleService(serviceInfo)
            }
        }
    }

    /**
     * Starts scanning for nearby CarCast, Android TV, and Windows PC receiver devices.
     */
    @Synchronized
    fun startDiscovery() {
        if (_isScanning.value) return
        _isScanning.value = true

        acquireMulticastLock()

        // 1. Scan for dedicated _carcast._tcp services (Car LCD, Android TV, Windows PC)
        carcastDiscoveryListener = createDiscoveryListener("CarCastReceiver", CARCAST_SERVICE_TYPE)
        try {
            nsdManager.discoverServices(CARCAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, carcastDiscoveryListener)
            Log.i(TAG, "Started discovery for $CARCAST_SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed discovering $CARCAST_SERVICE_TYPE", e)
        }

        // 2. Scan for _airplay._tcp services as complementary discovery
        airplayDiscoveryListener = createDiscoveryListener("AirPlayReceiver", AIRPLAY_SERVICE_TYPE)
        try {
            nsdManager.discoverServices(AIRPLAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, airplayDiscoveryListener)
            Log.i(TAG, "Started discovery for $AIRPLAY_SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed discovering $AIRPLAY_SERVICE_TYPE", e)
        }
    }

    /**
     * Stops NSD scanning and releases Wi-Fi multicast lock.
     */
    @Synchronized
    fun stopDiscovery() {
        _isScanning.value = false

        carcastDiscoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.d(TAG, "Stop discovery ignored: ${e.message}")
            }
        }
        carcastDiscoveryListener = null

        airplayDiscoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.d(TAG, "Stop discovery ignored: ${e.message}")
            }
        }
        airplayDiscoveryListener = null

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    /**
     * Clears current list and restarts discovery.
     */
    fun refresh() {
        activeReceivers.clear()
        _discoveredReceivers.value = emptyList()
        stopDiscovery()
        mainHandler.postDelayed({
            startDiscovery()
        }, 300)
    }

    fun release() {
        stopDiscovery()
        scope.cancel()
    }

    private fun createDiscoveryListener(tag: String, serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "[$tag] Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "[$tag] Found service: ${service.serviceName} (${service.serviceType})")
                resolveChannel.trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "[$tag] Lost service: ${service.serviceName}")
                val key = service.serviceName
                if (activeReceivers.remove(key) != null) {
                    updateFlowList()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "[$tag] Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[$tag] Start discovery failed: code $errorCode")
                _isScanning.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[$tag] Stop discovery failed: code $errorCode")
            }
        }
    }

    /**
     * Resolves a single NSD service sequentially to prevent Android concurrent resolve crashes.
     */
    private suspend fun resolveSingleService(serviceInfo: NsdServiceInfo) {
        val lock = Object()
        var completed = false

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: code $errorCode")
                synchronized(lock) {
                    completed = true
                    lock.notifyAll()
                }
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                Log.i(TAG, "Resolved service: ${resolved.serviceName} at ${resolved.host?.hostAddress}:${resolved.port}")
                handleResolvedService(resolved)
                synchronized(lock) {
                    completed = true
                    lock.notifyAll()
                }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
            synchronized(lock) {
                if (!completed) {
                    lock.wait(3000) // 3 second timeout per service
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception resolving service ${serviceInfo.serviceName}", e)
        }
    }

    private fun handleResolvedService(resolved: NsdServiceInfo) {
        val hostAddress = resolved.host?.hostAddress ?: return
        if (hostAddress.isBlank() || hostAddress == "0.0.0.0") return

        val serviceName = resolved.serviceName.trim()
        val isCarCastType = resolved.serviceType.contains("carcast", ignoreCase = true)

        // Read attributes from TXT record
        var stableId: String? = null
        var wsPort = 8080
        var httpPort = 8088
        var version = ""
        var platformStr: String? = null

        try {
            val attributes = resolved.attributes
            attributes["id"]?.let { stableId = String(it) }
            attributes["wsPort"]?.let { wsPort = String(it).toIntOrNull() ?: 8080 }
            attributes["httpPort"]?.let { httpPort = String(it).toIntOrNull() ?: 8088 }
            attributes["version"]?.let { version = String(it) }
            attributes["platform"]?.let { platformStr = String(it) }
        } catch (_: Exception) {}

        // Fallback stable ID if not provided in TXT record
        if (stableId.isNullOrBlank()) {
            stableId = "cc-name-" + serviceName.lowercase().replace(" ", "-")
        }

        // Determine platform type
        val platform = when {
            platformStr?.contains("TV", ignoreCase = true) == true || serviceName.contains("TV", ignoreCase = true) -> ReceiverPlatform.ANDROID_TV
            platformStr?.contains("WINDOWS", ignoreCase = true) == true || serviceName.contains("PC", ignoreCase = true) || serviceName.contains("Windows", ignoreCase = true) -> ReceiverPlatform.WINDOWS_PC
            else -> ReceiverPlatform.CAR_LCD
        }

        val rememberedId = DeviceIdHelper.getRememberedReceiverId(context)
        val rememberedName = DeviceIdHelper.getRememberedReceiverName(context)

        val candidate = DiscoveredReceiver(
            id = stableId!!,
            name = serviceName,
            host = hostAddress,
            port = if (isCarCastType) resolved.port else wsPort,
            httpPort = httpPort,
            version = version,
            platform = platform,
            isAutoDetected = false,
            lastSeenMs = System.currentTimeMillis()
        )

        val isAuto = DeviceIdHelper.matchesRememberedOrPreferred(candidate, rememberedId, rememberedName)
        val finalReceiver = candidate.copy(isAutoDetected = isAuto)

        // Key by stableId so that if its IP changes, the entry updates cleanly!
        activeReceivers[finalReceiver.id] = finalReceiver
        updateFlowList()
    }

    private fun updateFlowList() {
        val list = activeReceivers.values
            .sortedWith(compareByDescending<DiscoveredReceiver> { it.isAutoDetected }.thenBy { it.name })
        _discoveredReceivers.value = list
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("carcast_discovery_multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring MulticastLock", e)
        }
    }
}
