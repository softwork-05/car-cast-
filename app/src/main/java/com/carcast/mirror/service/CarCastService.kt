package com.carcast.mirror.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.carcast.mirror.CarCastApplication
import com.carcast.mirror.MainActivity
import com.carcast.mirror.R
import com.carcast.mirror.engine.NetworkInfoState
import com.carcast.mirror.engine.NetworkManager
import com.carcast.mirror.engine.airplay.AirPlayDebugLogger
import com.carcast.mirror.engine.airplay.AirPlayEngine
import com.carcast.mirror.engine.airplay.AirPlayStreamListener
import com.carcast.mirror.engine.discovery.CarCastDiscoveryAdvertiser
import com.carcast.mirror.engine.androidcast.AndroidCastListener
import com.carcast.mirror.engine.androidcast.AndroidCastServer
import com.carcast.mirror.engine.decoder.HardwareVideoDecoder
import com.carcast.mirror.engine.decoder.LowLatencyAudioTrack
import com.carcast.mirror.model.AspectRatioMode
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.ConnectionStatus
import com.carcast.mirror.model.DeviceType
import com.carcast.mirror.model.DisplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AndroidReceiverDiagnostics(
    val senderConnected: Boolean = false,
    val senderIp: String = "",
    val framesReceived: Long = 0L,
    val framesRendered: Long = 0L,
    val fps: Int = 0,
    val decoderInitialized: Boolean = false,
    val renderingStarted: Boolean = false,
    val streamWidth: Int = 1280,
    val streamHeight: Int = 720,
    val audioPacketsReceived: Long = 0L,
    val audioBytesReceived: Long = 0L,
    val audioActive: Boolean = false,
    val lastError: String? = null
)

class CarCastService : Service(), AirPlayStreamListener, AndroidCastListener {

    companion object {
        private const val TAG = "CarCast_Service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_RECEIVER = "com.carcast.mirror.ACTION_START"
        const val ACTION_STOP_RECEIVER = "com.carcast.mirror.ACTION_STOP"

        var instance: CarCastService? = null
            private set
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Engines
    private lateinit var networkManager: NetworkManager
    private var airplayEngine: AirPlayEngine? = null
    private var androidCastServer: AndroidCastServer? = null
    private var discoveryAdvertiser: CarCastDiscoveryAdvertiser? = null
    val videoDecoder = HardwareVideoDecoder()
    val audioTrack by lazy { LowLatencyAudioTrack(this) }

    // Observables
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedClient = MutableStateFlow<ConnectedClient?>(null)
    val connectedClient: StateFlow<ConnectedClient?> = _connectedClient.asStateFlow()

    private val _displayConfig = MutableStateFlow(DisplayConfig())
    val displayConfig: StateFlow<DisplayConfig> = _displayConfig.asStateFlow()

    private val _streamAspectRatio = MutableStateFlow(16f / 9f)
    val streamAspectRatio: StateFlow<Float> = _streamAspectRatio.asStateFlow()

    private val _androidDiagnostics = MutableStateFlow(AndroidReceiverDiagnostics())
    val androidDiagnostics: StateFlow<AndroidReceiverDiagnostics> = _androidDiagnostics.asStateFlow()

    private var lastDiagUpdate = 0L

    fun getNetworkState(): StateFlow<NetworkInfoState> = networkManager.networkState

    var activeSurface: Surface? = null
        set(value) {
            val old = field
            field = value
            val clientType = _connectedClient.value?.deviceType
            if (value != null) {
                if (clientType != DeviceType.ANDROID_CAST) {
                    airplayEngine?.setSurface(value)
                }
                if (clientType == DeviceType.ANDROID_CAST) {
                    val diag = _androidDiagnostics.value
                    videoDecoder.init(value, diag.streamWidth, diag.streamHeight)
                    _androidDiagnostics.value = _androidDiagnostics.value.copy(
                        decoderInitialized = videoDecoder.isReady
                    )
                    androidCastServer?.requestKeyframe()
                }
            } else {
                if (clientType != DeviceType.ANDROID_CAST) {
                    airplayEngine?.clearSurface(old)
                }
                if (clientType == DeviceType.ANDROID_CAST) {
                    videoDecoder.stop()
                    _androidDiagnostics.value = _androidDiagnostics.value.copy(
                        decoderInitialized = false,
                        renderingStarted = false
                    )
                }
            }
        }

    fun setAspectRatioMode(isFit: Boolean) {
        airplayEngine?.setAspectRatioMode(isFit)
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) {
        airplayEngine?.updateSurfaceSize(w, h)
    }

    inner class LocalBinder : Binder() {
        fun getService(): CarCastService = this@CarCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "CarCastService created")
        AirPlayDebugLogger.log("[SERVICE:LIFECYCLE] CarCastService created")

        try {
            val prefs = getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("broadcast_name", null)
            if (!savedName.isNullOrBlank()) {
                _displayConfig.value = _displayConfig.value.copy(broadcastName = savedName)
            }

            networkManager = NetworkManager(this)
            audioTrack.init()

            // Wire decoder resolution change listener
            videoDecoder.onResolutionChanged = { w, h ->
                if (w > 0 && h > 0) {
                    val aspect = w.toFloat() / h.toFloat()
                    _streamAspectRatio.value = aspect
                    _androidDiagnostics.value = _androidDiagnostics.value.copy(
                        streamWidth = w,
                        streamHeight = h
                    )
                    AirPlayDebugLogger.log("[DECODER:FORMAT] Stream format detected: ${w}x${h} (aspect: $aspect)")
                }
            }

            updateNotification("Starting AirPlay receiver...")

            // 1. Start native AirPlay backend immediately on 0.0.0.0
            startAirPlayReceiver()

            // 2. Start Android Cast server immediately on 0.0.0.0:8080 (independent of Wi-Fi state)
            try {
                androidCastServer = AndroidCastServer(this, 8080, this).apply {
                    start()
                }
                AirPlayDebugLogger.log("[SERVICE:ANDROID] Android Cast Server started on port 8080")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AndroidCastServer on port 8080", e)
                AirPlayDebugLogger.log("[SERVICE:ERROR] AndroidCastServer start error: ${e.message}")
            }

            // 2b. Start CarCast multi-platform discovery advertiser (Android LCD, Android TV, Windows PC)
            try {
                discoveryAdvertiser = CarCastDiscoveryAdvertiser(this).apply {
                    startAdvertising(_displayConfig.value.broadcastName)
                }
                AirPlayDebugLogger.log("[SERVICE:DISCOVERY] CarCast Discovery Advertiser started for '${_displayConfig.value.broadcastName}'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery advertiser", e)
                AirPlayDebugLogger.log("[SERVICE:ERROR] DiscoveryAdvertiser start error: ${e.message}")
            }

            // 3. Start network monitoring
            networkManager.startMonitoring()

            // 4. Monitor network state changes for logging & discovery
            serviceScope.launch {
                networkManager.networkState.collect { netState ->
                    Log.i(TAG, "Network state updated: IP=${netState.ipAddress}, isConnected=${netState.isConnected}")
                    AirPlayDebugLogger.log("[SERVICE:NET] IP=${netState.ipAddress}, SSID=${netState.ssid}, Hotspot=${netState.isHotspot}")

                    // If AirPlay engine hasn't started yet, attempt start
                    if (airplayEngine == null) {
                        startAirPlayReceiver()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in CarCastService onCreate", e)
            AirPlayDebugLogger.log("[SERVICE:ERROR] onCreate failed: ${e.message}")
            updateNotification("CarCast Service Error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECEIVER -> {
                stopSelf()
            }
            else -> {
                networkManager.refreshNetworkStatus()
                if (airplayEngine == null) {
                    startAirPlayReceiver()
                }
            }
        }
        return START_STICKY
    }

    fun updateBroadcastName(newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return

        getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("broadcast_name", cleanName)
            .apply()

        _displayConfig.value = _displayConfig.value.copy(broadcastName = cleanName)
        discoveryAdvertiser?.updateBroadcastName(cleanName)
        startAirPlayReceiver()
    }

    fun updateDisplayConfig(config: DisplayConfig) {
        _displayConfig.value = config
        audioTrack.setMuted(config.isAudioMuted)
        audioTrack.setVolume(config.audioVolume)
        airplayEngine?.setMuted(config.isAudioMuted)
        airplayEngine?.setAspectRatioMode(config.aspectRatioMode == AspectRatioMode.FIT)
    }

    fun updateStreamAspectRatio(aspect: Float) {
        if (aspect > 0f) {
            _streamAspectRatio.value = aspect
        }
    }

    fun dispatchTouchEvent(action: String, xRatio: Float, yRatio: Float) {
        val logMsg = "[TOUCH:SEND] action=$action x=${"%.3f".format(xRatio)} y=${"%.3f".format(yRatio)}"
        AirPlayDebugLogger.log(logMsg)
        Log.d(TAG, logMsg)
        androidCastServer?.sendTouchEvent(action, xRatio, yRatio)
    }

    private fun startAirPlayReceiver() {
        val devName = _displayConfig.value.broadcastName
        Log.i(TAG, "Starting native AirPlay receiver as '$devName'")
        AirPlayDebugLogger.log("[SERVICE:AIRPLAY] Starting receiver '$devName'...")

        airplayEngine?.stop()
        val engine = AirPlayEngine(this, this)
        airplayEngine = engine
        val success = engine.start(devName, 7000)

        activeSurface?.let { engine.setSurface(it) }

        if (success) {
            val port = engine.boundPort
            updateNotification("Ready for connections • AirPlay Port $port")
        } else {
            val status = AirPlayDebugLogger.serverStatusText.value
            updateNotification("AirPlay Offline • $status")
        }
    }

    fun disconnectActiveStream() {
        videoDecoder.stop()
        audioTrack.stop()
        _connectionStatus.value = ConnectionStatus.IDLE
        _connectedClient.value = null
        _androidDiagnostics.value = _androidDiagnostics.value.copy(
            senderConnected = false,
            decoderInitialized = false,
            renderingStarted = false,
            audioActive = false,
            audioPacketsReceived = 0L,
            audioBytesReceived = 0L
        )

        // Ensure AirPlay engine is listening
        if (airplayEngine == null) {
            startAirPlayReceiver()
        } else {
            val port = airplayEngine?.boundPort ?: 7000
            updateNotification("Ready for connections • AirPlay Port $port")
        }
    }

    // --- Stream Callbacks (AirPlay & Android) ---

    override fun onClientConnected(client: ConnectedClient) {
        serviceScope.launch {
            _connectedClient.value = client
            _connectionStatus.value = ConnectionStatus.CONNECTING
            updateNotification("Connecting to ${client.deviceName}...")

            if (client.deviceType == DeviceType.ANDROID_CAST) {
                _androidDiagnostics.value = _androidDiagnostics.value.copy(
                    senderConnected = true,
                    senderIp = client.ipAddress,
                    framesReceived = 0L,
                    renderingStarted = false,
                    lastError = null
                )
                AirPlayDebugLogger.log("[ANDROID:CONNECT] Sender connected from ${client.ipAddress}")
            }
        }
    }

    override fun onStreamStarted(client: ConnectedClient) {
        serviceScope.launch {
            _connectedClient.value = client
            _connectionStatus.value = ConnectionStatus.STREAMING
            updateNotification("Mirroring active: ${client.deviceName}")

            activeSurface?.let { surface ->
                if (client.deviceType == DeviceType.ANDROID_CAST) {
                    val diag = _androidDiagnostics.value
                    videoDecoder.init(surface, diag.streamWidth, diag.streamHeight)
                    _androidDiagnostics.value = _androidDiagnostics.value.copy(
                        decoderInitialized = videoDecoder.isReady
                    )
                    audioTrack.init()
                    androidCastServer?.requestKeyframe()
                } else {
                    airplayEngine?.setSurface(surface)
                }
            }
        }
    }

    override fun onStreamMeta(width: Int, height: Int) {
        serviceScope.launch {
            if (width > 0 && height > 0) {
                val aspect = width.toFloat() / height.toFloat()
                _streamAspectRatio.value = aspect
                _androidDiagnostics.value = _androidDiagnostics.value.copy(
                    streamWidth = width,
                    streamHeight = height
                )
                AirPlayDebugLogger.log("[ANDROID:META] Sender stream meta: ${width}x${height} (aspect: $aspect)")

                activeSurface?.let { surface ->
                    videoDecoder.init(surface, width, height)
                    _androidDiagnostics.value = _androidDiagnostics.value.copy(
                        decoderInitialized = videoDecoder.isReady
                    )
                    androidCastServer?.requestKeyframe()
                }
            }
        }
    }

    override fun onVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _streamAspectRatio.value = width.toFloat() / height.toFloat()
            AirPlayDebugLogger.log("[AIRPLAY:DIMENSIONS] Video dimensions: ${width}x${height} (aspect: ${_streamAspectRatio.value})")
        }
    }

    override fun onVideoFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        videoDecoder.decodeFrame(data, offset, length, ptsUs)
    }

    override fun onVideoData(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        videoDecoder.decodeFrame(data, offset, length, ptsUs)

        val count = _androidDiagnostics.value.framesReceived + 1
        val isRendering = videoDecoder.framesRendered > 0
        val now = System.currentTimeMillis()

        if (now - lastDiagUpdate >= 250 || count == 1L) {
            lastDiagUpdate = now
            _androidDiagnostics.value = _androidDiagnostics.value.copy(
                framesReceived = count,
                framesRendered = videoDecoder.framesRendered,
                fps = videoDecoder.fps,
                decoderInitialized = videoDecoder.isReady,
                renderingStarted = isRendering,
                lastError = videoDecoder.lastError
            )
            if (count == 1L) {
                AirPlayDebugLogger.log("[ANDROID:STREAM] First video frame received (${length} bytes). Rendering started!")
            }
        } else {
            _androidDiagnostics.value = _androidDiagnostics.value.copy(
                framesReceived = count,
                framesRendered = videoDecoder.framesRendered,
                fps = videoDecoder.fps,
                renderingStarted = isRendering
            )
        }
    }

    override fun onAudioFrame(data: ByteArray, offset: Int, length: Int) {
        if (!audioTrack.isAudioActive) {
            audioTrack.init()
        }
        audioTrack.playAudio(data, offset, length)
    }

    override fun onAudioData(data: ByteArray, offset: Int, length: Int) {
        if (!audioTrack.isAudioActive) {
            audioTrack.init()
        }
        audioTrack.playAudio(data, offset, length)

        val packets = audioTrack.packetsReceived.get()
        if (packets == 1L || packets % 50L == 0L) {
            _androidDiagnostics.value = _androidDiagnostics.value.copy(
                audioPacketsReceived = packets,
                audioBytesReceived = audioTrack.bytesReceived.get(),
                audioActive = true
            )
        }
    }

    override fun onClientDisconnected() {
        serviceScope.launch {
            videoDecoder.stop()
            audioTrack.stop()
            _connectionStatus.value = ConnectionStatus.IDLE
            _connectedClient.value = null
            _androidDiagnostics.value = _androidDiagnostics.value.copy(
                senderConnected = false,
                decoderInitialized = false,
                renderingStarted = false,
                audioActive = false,
                audioPacketsReceived = 0L,
                audioBytesReceived = 0L
            )
            val port = airplayEngine?.boundPort ?: 7000
            updateNotification("Ready for connections • AirPlay Port $port")
            AirPlayDebugLogger.log("[STREAM:DISCONNECT] Client disconnected")
        }
    }

    private fun updateNotification(contentText: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CarCastApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CarCast Receiver")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CarCastService destroying")
        AirPlayDebugLogger.log("[SERVICE:LIFECYCLE] CarCastService onDestroy")
        airplayEngine?.stop()
        airplayEngine = null
        discoveryAdvertiser?.stopAdvertising()
        discoveryAdvertiser = null
        androidCastServer?.stop()
        androidCastServer = null
        videoDecoder.stop()
        audioTrack.stop()
        networkManager.stopMonitoring()
        instance = null
    }
}
