package com.carcast.mirror.engine.airplay

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.DeviceType
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.MdnsService
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Production AirPlay Engine integrating native UxPlay backend with real FairPlay,
 * RTSP/RAOP protocol handling, MediaCodec H.264/H.265 video decoding, and mDNS discovery.
 */
class AirPlayEngine(
    private val context: Context,
    private val listener: AirPlayStreamListener
) : RaopCallbackHandler, LogListener {

    companion object {
        private const val TAG = "CarCast_AirPlayEngine"
        private const val DEFAULT_AIRPLAY_PORT = 7000
    }

    private var nativeHandle = 0L
    private val nsdManager = NsdServiceManager(context)
    val videoRenderer = VideoRenderer(context)
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    @Volatile
    private var isRunning = false
    @Volatile
    private var currentClientName = "iPhone"
    @Volatile
    var boundPort = 0
        private set

    fun start(deviceName: String, requestedPort: Int = DEFAULT_AIRPLAY_PORT): Boolean {
        if (isRunning) return true
        Log.i(TAG, "Starting AirPlayEngine as '$deviceName' on requested port $requestedPort")
        AirPlayDebugLogger.log("[AIRPLAY:START] Starting native AirPlay receiver as '$deviceName' (requested port $requestedPort)")
        AirPlayDebugLogger.setServerListening(false, "STARTING")

        // 1. Check native library load status
        if (!NativeBridge.isLoaded) {
            val err = NativeBridge.loadError
            val errMsg = err?.message ?: "UnsatisfiedLinkError"
            Log.e(TAG, "Native libraries not loaded: $errMsg", err)
            AirPlayDebugLogger.log("[AIRPLAY:FATAL] Native library load failed: ${err?.javaClass?.simpleName}: $errMsg")
            AirPlayDebugLogger.setServerListening(false, "LIB LOAD FAILED")
            return false
        }

        try {
            // 2. Configure audio defaults for native Oboe/OpenSL
            val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44100
            val framesPerBurst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            NativeBridge.nativeSetDefaultStreamValues(sampleRate, framesPerBurst)

            // 3. Prepare MAC address and PEM key file
            val hwAddr = getHwAddr()
            val macStr = hwAddr.joinToString(":") { "%02X".format(it) }
            val keyFile = context.filesDir.resolve("airplay.pem").absolutePath
            AirPlayDebugLogger.log("[AIRPLAY:CONFIG] Device ID: $macStr, Key: airplay.pem")

            // 4. Initialize native RAOP/UxPlay engine
            AirPlayDebugLogger.log("[AIRPLAY:INIT] Calling nativeInit for '$deviceName'...")
            nativeHandle = NativeBridge.nativeInit(
                callback = this,
                hwAddr = hwAddr,
                name = deviceName,
                keyFile = keyFile,
                nohold = false,
                requirePin = false
            )

            if (nativeHandle == 0L) {
                Log.e(TAG, "nativeInit failed; handle is 0")
                AirPlayDebugLogger.log("[AIRPLAY:ERROR] nativeInit returned 0 (failed to allocate server context)")
                AirPlayDebugLogger.setServerListening(false, "NATIVE INIT FAILED (0)")
                return false
            }
            AirPlayDebugLogger.log("[AIRPLAY:INIT] Native engine initialized (handle: 0x${java.lang.Long.toHexString(nativeHandle)})")

            audioRenderer.attachEngine(nativeHandle)

            // 5. Configure display and video codec capabilities
            val (reqW, reqH) = Pair(1920, 1080)
            val maxFps = 60
            val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, null)

            videoRenderer.enforceSdr = true
            videoRenderer.keyAllowFrameDrop = true
            videoRenderer.scheduledOutputBufferRelease = true

            NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
            NativeBridge.nativeSetCodecs(nativeHandle, alac = true, aac = true)
            NativeBridge.nativeSetHlsEnabled(nativeHandle, false)
            NativeBridge.nativeSetLang(nativeHandle, "en")
            NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_MIRROR, true)
            NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_AUDIO, true)
            NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
            NativeBridge.nativeSetPlist(nativeHandle, "overscanned", 0)

            videoRenderer.setResolution(reqW, reqH)
            NativeBridge.nativeSetDisplaySize(nativeHandle, reqW, reqH, maxFps)

            // 6. Start native listening socket with automatic port conflict fallback
            val portsToTry = mutableListOf(requestedPort)
            if (requestedPort != 7001) portsToTry.add(7001)
            if (requestedPort != 7002) portsToTry.add(7002)
            portsToTry.add(0) // dynamic port fallback

            var actualPort = -1
            for (candidatePort in portsToTry) {
                AirPlayDebugLogger.log("[AIRPLAY:PORT] Attempting socket bind on port $candidatePort...")
                val res = NativeBridge.nativeStart(nativeHandle, candidatePort)
                if (res > 0) {
                    actualPort = res
                    break
                } else {
                    AirPlayDebugLogger.log("[AIRPLAY:PORT] Port $candidatePort bind failed (code: $res, likely port conflict)")
                }
            }

            if (actualPort <= 0) {
                Log.e(TAG, "All port bind attempts failed")
                AirPlayDebugLogger.log("[AIRPLAY:FATAL] All port bind attempts failed. Sockets in use by another app.")
                AirPlayDebugLogger.setServerListening(false, "PORT BIND FAILED")
                stop()
                return false
            }

            boundPort = actualPort
            if (actualPort != requestedPort) {
                AirPlayDebugLogger.log("[AIRPLAY:NOTICE] Requested port $requestedPort was occupied (upstream receiver?). Successfully bound to port $actualPort!")
            } else {
                AirPlayDebugLogger.log("[AIRPLAY:SUCCESS] Native socket bound and listening on port $actualPort")
            }

            // 7. Register mDNS services using native TXT records via NsdManager
            nsdManager.acquireMulticastLock()
            val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
            val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
            val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
            val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: deviceName

            AirPlayDebugLogger.log("[AIRPLAY:MDNS] RAOP Name: '$raopName', AirPlay Name: '$resolvedName'")
            AirPlayDebugLogger.log("[AIRPLAY:MDNS] AirPlay TXT record count: ${airplayTxt.size}")

            val services = listOf(
                MdnsService(raopName, "_raop._tcp", actualPort, raopTxt),
                MdnsService(resolvedName, "_airplay._tcp", actualPort, airplayTxt)
            )

            nsdManager.register(
                services = services,
                onRegistered = { type, regName, port ->
                    if (type.contains("airplay")) {
                        AirPlayDebugLogger.setServerListening(true, "PORT $port OK")
                        AirPlayDebugLogger.log("[AIRPLAY:READY] AirPlay receiver '$regName' is now DISCOVERABLE on port $port in iOS Screen Mirroring!")
                    }
                },
                onFailed = { type, _, errorMsg ->
                    if (type.contains("airplay")) {
                        AirPlayDebugLogger.log("[AIRPLAY:WARN] mDNS discovery registration issue: $errorMsg")
                    }
                }
            )

            isRunning = true
            AirPlayDebugLogger.setServerListening(true, "PORT $actualPort OK")
            Log.i(TAG, "AirPlay native engine running on port $actualPort, mDNS advertised as '$resolvedName'")
            AirPlayDebugLogger.log("[AIRPLAY:ONLINE] Receiver online: '$resolvedName' on port $actualPort")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Exception starting native AirPlayEngine", e)
            AirPlayDebugLogger.log("[AIRPLAY:EXCEPTION] ${e.javaClass.simpleName}: ${e.message}")
            AirPlayDebugLogger.setServerListening(false, "ERROR: ${e.javaClass.simpleName}")
            stop()
            return false
        }
    }

    fun stop() {
        if (!isRunning && nativeHandle == 0L) return
        Log.i(TAG, "Stopping AirPlayEngine")
        AirPlayDebugLogger.log("[AIRPLAY:STOP] Stopping native AirPlay engine")
        AirPlayDebugLogger.setServerListening(false, "STOPPED")

        try {
            nsdManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing nsdManager", e)
        }

        try {
            videoRenderer.stopSession()
            videoRenderer.release()
        } catch (_: Exception) {}

        try {
            audioRenderer.stop()
            audioRenderer.detachEngine()
        } catch (_: Exception) {}

        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            try {
                NativeBridge.nativeStop(handle)
                NativeBridge.nativeDestroy(handle)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping/destroying native handle", e)
            }
        }

        isRunning = false
        boundPort = 0
    }

    fun setSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearSurface(surface: Surface? = null) {
        videoRenderer.clearSurface(surface)
    }

    fun setAspectRatioMode(isFit: Boolean) {
        videoRenderer.setAspectRatioMode(isFit)
    }

    fun updateSurfaceSize(w: Int, h: Int) {
        videoRenderer.updateSurfaceSize(w, h)
    }

    fun setMuted(muted: Boolean) {
        audioRenderer.setClientGain(if (muted) 0f else 1f)
    }

    // --- RaopCallbackHandler Callbacks ---

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        Log.i(TAG, "AirPlay video size: src=${srcW}x${srcH}, target=${w}x${h}")
        AirPlayDebugLogger.log("[VIDEO:SIZE] Source ${srcW.roundToInt()}x${srcH.roundToInt()} -> Output ${w.roundToInt()}x${h.roundToInt()}")
        val intW = w.roundToInt()
        val intH = h.roundToInt()
        videoRenderer.setResolution(intW, intH)
        listener.onVideoDimensions(intW, intH)
    }

    override fun onMirrorRunning(running: Boolean) {
        Log.i(TAG, "AirPlay mirror running state: $running")
        AirPlayDebugLogger.log("[MIRROR:STATE] Running = $running")
        if (running) {
            videoRenderer.startSession()
            listener.onStreamStarted(
                ConnectedClient(
                    deviceName = currentClientName,
                    deviceType = DeviceType.IOS_AIRPLAY,
                    ipAddress = ""
                )
            )
        } else {
            videoRenderer.stopSession()
            listener.onClientDisconnected()
        }
    }

    override fun onConnectionInit() {
        Log.i(TAG, "AirPlay client connected (connection init)")
        AirPlayDebugLogger.log("[CONNECTION:INIT] Client connected")
        listener.onClientConnected(
            ConnectedClient(
                deviceName = currentClientName,
                deviceType = DeviceType.IOS_AIRPLAY,
                ipAddress = ""
            )
        )
    }

    override fun onConnectionDestroy() {
        Log.i(TAG, "AirPlay client connection destroyed")
        AirPlayDebugLogger.log("[CONNECTION:DESTROY] Client disconnected")
        videoRenderer.stopSession()
        audioRenderer.stop()
        listener.onClientDisconnected()
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "AirPlay connection reset: $reason")
        AirPlayDebugLogger.log("[CONNECTION:RESET] Reason: $reason")
        videoRenderer.stopSession()
        audioRenderer.stop()
        listener.onClientDisconnected()
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Log.i(TAG, "AirPlay audio format: ct=$ct, spf=$spf, usingScreen=$usingScreen")
        audioRenderer.setFormat(ct, spf)
        audioRenderer.start()
    }

    override fun onAudioTeardown() {
        Log.i(TAG, "AirPlay audio teardown")
        audioRenderer.stop()
    }

    override fun onClientVolume(db: Float) {
        val gain = if (db <= -144f) 0f else (10.0.pow(db.toDouble() / 20.0)).toFloat().coerceIn(0f, 1f)
        audioRenderer.setClientGain(gain)
    }

    override fun onClientVolumeQuery(): Float = 0.0f

    override fun onDisplayPin(pin: String) {
        Log.i(TAG, "Display PIN requested: $pin")
        AirPlayDebugLogger.log("[PIN:PROMPT] PIN: $pin")
    }

    override fun onMetadata(data: ByteArray) {}
    override fun onCoverArt(data: ByteArray) {}
    override fun onProgress(start: Long, curr: Long, end: Long) {}
    override fun onDacpId(dacpId: String, activeRemote: String) {}
    override fun onVideoPlay(location: String, startPositionSeconds: Float) {}
    override fun onVideoScrub(positionSeconds: Float) {}
    override fun onVideoRate(rate: Float) {}
    override fun onVideoStop() {}
    override fun onVideoSessionPoll() {}

    override fun onLog(msg: String) {
        AirPlayDebugLogger.log("[NATIVE] $msg")
    }

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (mac != null && mac.size == 6 && mac.any { it != 0.toByte() }) {
                        return mac
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get hardware MAC", e)
        }

        val prefs = context.getSharedPreferences("carcast_airplay_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("airplay_mac", null)
        if (saved != null) {
            val parts = saved.split(":")
            if (parts.size == 6) {
                return ByteArray(6) { parts[it].toInt(16).toByte() }
            }
        }

        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        val formatted = mac.joinToString(":") { "%02X".format(it) }
        prefs.edit().putString("airplay_mac", formatted).apply()
        return mac
    }
}
