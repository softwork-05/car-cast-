package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carcast.mirror.engine.airplay.AirPlayDebugLogger

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun acquireMulticastLock() {
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "MulticastLock acquired")
                AirPlayDebugLogger.log("[MDNS:LOCK] Wi-Fi MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring MulticastLock", e)
            AirPlayDebugLogger.log("[MDNS:WARN] MulticastLock error: ${e.message}")
        }
    }

    fun register(
        services: List<MdnsService>,
        onRegistered: ((type: String, name: String, port: Int) -> Unit)? = null,
        onFailed: ((type: String, code: Int, errorMsg: String) -> Unit)? = null
    ) {
        services.forEach { service ->
            _registerWithRetry(service, retryCount = 1, onRegistered, onFailed)
        }
    }

    private fun _registerWithRetry(
        s: MdnsService,
        retryCount: Int,
        onRegistered: ((type: String, name: String, port: Int) -> Unit)?,
        onFailed: ((type: String, code: Int, errorMsg: String) -> Unit)?
    ) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            port = s.port
            s.txt.forEach { (k, v) ->
                try {
                    setAttribute(k, v)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set TXT attribute $k=$v: ${e.message}")
                }
            }
        }

        AirPlayDebugLogger.log("[MDNS:REGISTER] Registering ${s.type} '${s.name}' on port ${s.port} (${s.txt.size} TXT attrs)")

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(regInfo: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered successfully as: ${regInfo.serviceName} on port ${regInfo.port}")
                AirPlayDebugLogger.log("[MDNS:SUCCESS] ${s.type} registered as '${regInfo.serviceName}' (port ${regInfo.port})")
                onRegistered?.invoke(s.type, regInfo.serviceName, regInfo.port)
            }

            override fun onRegistrationFailed(failedInfo: NsdServiceInfo, code: Int) {
                val codeStr = when (code) {
                    NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE (3)"
                    NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT (4)"
                    NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR (0)"
                    else -> "ERROR_$code"
                }
                Log.e(TAG, "${s.type} registration failed: $codeStr")
                AirPlayDebugLogger.log("[MDNS:FAIL] ${s.type} registration failed: $codeStr")

                if (code == NsdManager.FAILURE_ALREADY_ACTIVE && retryCount > 0) {
                    AirPlayDebugLogger.log("[MDNS:RETRY] Retrying ${s.type} registration after unregister cleanup...")
                    mainHandler.postDelayed({
                        try {
                            nsdManager.unregisterService(this)
                        } catch (_: Exception) {}
                        registrations.remove(this)
                        _registerWithRetry(s, retryCount - 1, onRegistered, onFailed)
                    }, 500)
                } else {
                    onFailed?.invoke(s.type, code, codeStr)
                }
            }

            override fun onServiceUnregistered(regInfo: NsdServiceInfo) {
                Log.i(TAG, "${s.type} unregistered")
                AirPlayDebugLogger.log("[MDNS:UNREG] ${s.type} unregistered cleanly")
            }

            override fun onUnregistrationFailed(failedInfo: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
                AirPlayDebugLogger.log("[MDNS:WARN] ${s.type} unregister failed (code: $code)")
            }
        }

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrations += listener
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling registerService for ${s.type}", e)
            AirPlayDebugLogger.log("[MDNS:EXCEPTION] registerService failed: ${e.message}")
            onFailed?.invoke(s.type, -1, e.message ?: "Exception")
        }
    }

    fun release() {
        AirPlayDebugLogger.log("[MDNS:RELEASE] Unregistering all mDNS services (${registrations.size} active)")
        registrations.forEach {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.d(TAG, "Unregister ignored: ${e.message}")
            }
        }
        registrations.clear()
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}
