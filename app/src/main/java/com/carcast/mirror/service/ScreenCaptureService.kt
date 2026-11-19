package com.carcast.mirror.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.carcast.mirror.CarCastApplication
import com.carcast.mirror.MainActivity
import com.carcast.mirror.model.AudioOutputRoute
import com.carcast.mirror.model.QualityProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class SenderStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ENCODING,
    STREAMING,
    FAILED
}

data class SenderState(
    val status: SenderStatus = SenderStatus.IDLE,
    val targetIp: String = "",
    val targetPort: Int = 8080,
    val permissionGranted: Boolean = false,
    val encoderActive: Boolean = false,
    val resolution: String = "",
    val qualityProfileName: String = "Auto Quality",
    val fps: Int = 0,
    val bitrateKbps: Long = 0L,
    val framesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val bytesSent: Long = 0L,
    val reverseTouchActive: Boolean = false,
    val audioCaptureActive: Boolean = false,
    val audioRoute: String = "Car LCD Only",
    val audioPacketsSent: Long = 0L,
    val audioBytesSent: Long = 0L,
    val errorMessage: String? = null
)

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "CarCast_Sender"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.carcast.mirror.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.carcast.mirror.ACTION_STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CAR_IP = "extra_car_ip"
        const val EXTRA_QUALITY_PROFILE = "extra_quality_profile"
        const val EXTRA_AUDIO_ROUTE = "extra_audio_route"

        private val _senderState = MutableStateFlow(SenderState())
        val senderState: StateFlow<SenderState> = _senderState.asStateFlow()

        val isStreaming: Boolean
            get() = _senderState.value.status == SenderStatus.STREAMING
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var wsClient: WebSocketClient? = null
    private val isRunning = AtomicBoolean(false)
    private val isReconfiguring = AtomicBoolean(false)
    private var cachedSpsPps: ByteArray? = null

    // Audio capture & Phone Speaker Suppression
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val isAudioActive = AtomicBoolean(false)
    private var activeAudioRoute: AudioOutputRoute = AudioOutputRoute.CAR_LCD_ONLY
    private var savedStreamVolume: Int = -1
    private var isPhoneSpeakerMutedByUs: Boolean = false

    // Quality Profile & Adaptive scaling
    private var activeProfile: QualityProfile = QualityProfile.AUTO
    private var autoDowngraded = false

    // Display & Orientation tracking
    private var displayListener: DisplayManager.DisplayListener? = null
    private var currentScreenW = 1080
    private var currentScreenH = 1920
    private var currentEncWidth = 720
    private var currentEncHeight = 1280
    private var currentDpi = DisplayMetrics.DENSITY_DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                val rawIp = intent.getStringExtra(EXTRA_CAR_IP) ?: "192.168.43.1"
                val profileName = intent.getStringExtra(EXTRA_QUALITY_PROFILE)
                activeProfile = if (profileName != null) {
                    try { QualityProfile.valueOf(profileName) } catch (_: Exception) { QualityProfile.AUTO }
                } else {
                    val prefs = getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
                    val saved = prefs.getString("quality_profile", QualityProfile.AUTO.name)
                    try { QualityProfile.valueOf(saved ?: QualityProfile.AUTO.name) } catch (_: Exception) { QualityProfile.AUTO }
                }
                val audioRouteName = intent.getStringExtra(EXTRA_AUDIO_ROUTE)
                activeAudioRoute = if (audioRouteName != null) {
                    try { AudioOutputRoute.valueOf(audioRouteName) } catch (_: Exception) { AudioOutputRoute.CAR_LCD_ONLY }
                } else {
                    val prefs = getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
                    val saved = prefs.getString("sender_audio_route", AudioOutputRoute.CAR_LCD_ONLY.name)
                    try { AudioOutputRoute.valueOf(saved ?: AudioOutputRoute.CAR_LCD_ONLY.name) } catch (_: Exception) { AudioOutputRoute.CAR_LCD_ONLY }
                }
                autoDowngraded = false

                if (resultCode != 0 && resultData != null) {
                    startForegroundNotification()
                    startStreaming(resultCode, resultData, rawIp)
                } else {
                    _senderState.value = _senderState.value.copy(
                        status = SenderStatus.FAILED,
                        errorMessage = "Screen capture permission was denied"
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun parseHostAndPort(rawInput: String): Pair<String, Int> {
        var clean = rawInput.trim()
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        var port = 8080
        if (clean.contains(":")) {
            val parts = clean.split(":")
            clean = parts[0].trim()
            port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
        }
        return Pair(clean, port)
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, rawIp: String) {
        val (cleanHost, targetPort) = parseHostAndPort(rawIp)
        if (cleanHost.isBlank()) {
            _senderState.value = _senderState.value.copy(
                status = SenderStatus.FAILED,
                errorMessage = "Invalid IP address. Please enter a valid Car LCD IP."
            )
            return
        }

        _senderState.value = SenderState(
            status = SenderStatus.CONNECTING,
            targetIp = cleanHost,
            targetPort = targetPort,
            permissionGranted = true,
            qualityProfileName = activeProfile.displayName,
            audioRoute = activeAudioRoute.displayName,
            reverseTouchActive = CarCastAccessibilityService.isRunning
        )

        try {
            val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projMgr.getMediaProjection(resultCode, resultData)?.apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        stopStreaming()
                    }
                }, null)
            }

            if (mediaProjection == null) {
                _senderState.value = _senderState.value.copy(
                    status = SenderStatus.FAILED,
                    errorMessage = "Failed to obtain MediaProjection token"
                )
                stopStreaming()
                return
            }

            // Connect WebSocket to Car LCD
            val wsUri = URI("ws://$cleanHost:$targetPort")
            Log.i(TAG, "Connecting WebSocket to Car LCD at $wsUri...")

            wsClient = object : WebSocketClient(wsUri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "Connected to Car LCD WebSocket: $wsUri")
                    Handler(Looper.getMainLooper()).post {
                        _senderState.value = _senderState.value.copy(
                            status = SenderStatus.CONNECTED,
                            errorMessage = null,
                            reverseTouchActive = CarCastAccessibilityService.isRunning
                        )
                        initCodecAndCapture()
                        startAudioCapture()
                        registerDisplayListener()
                    }
                }

                override fun onMessage(message: String?) {
                    if (message == null) return
                    if (message.contains("request_keyframe")) {
                        requestKeyframeFromCodec()
                    } else if (message.contains("\"type\":\"touch\"")) {
                        handleIncomingTouchEvent(message)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.i(TAG, "Car LCD WebSocket closed: code=$code, reason=$reason, remote=$remote")
                    if (isRunning.get()) {
                        _senderState.value = _senderState.value.copy(
                            status = SenderStatus.FAILED,
                            errorMessage = if (remote) "Car LCD closed connection: $reason" else "Disconnected from Car LCD"
                        )
                    }
                    stopStreaming()
                }

                override fun onError(ex: Exception?) {
                    val errMsg = ex?.message ?: "Cannot connect to Car LCD on $cleanHost:$targetPort"
                    Log.e(TAG, "Car LCD WebSocket error: $errMsg", ex)
                    _senderState.value = _senderState.value.copy(
                        status = SenderStatus.FAILED,
                        errorMessage = "Connection error: $errMsg. Ensure CarCast is open on Car LCD."
                    )
                    stopStreaming()
                }
            }.apply {
                connectionLostTimeout = 10
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture stream", e)
            _senderState.value = _senderState.value.copy(
                status = SenderStatus.FAILED,
                errorMessage = "Startup error: ${e.message}"
            )
            stopStreaming()
        }
    }

    private fun startAudioCapture() {
        if (activeAudioRoute == AudioOutputRoute.PHONE_ONLY) {
            Log.i(TAG, "Audio output set to PHONE_ONLY. Skipping internal audio capture.")
            _senderState.value = _senderState.value.copy(
                audioCaptureActive = false,
                audioRoute = activeAudioRoute.displayName
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Internal AudioPlaybackCapture requires Android 10+ (API 29+)")
            return
        }

        val projection = mediaProjection ?: return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; internal audio capture skipped")
            return
        }

        // When CAR_LCD_ONLY is selected, suppress local phone speaker playback
        if (activeAudioRoute == AudioOutputRoute.CAR_LCD_ONLY) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                try {
                    savedStreamVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    }
                    isPhoneSpeakerMutedByUs = true
                    Log.i(TAG, "Suppressed phone speaker audio (saved original volume: $savedStreamVolume)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not adjust phone speaker volume: ${e.message}")
                }
            }
        }

        try {
            val audioConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuf * 2, 4096)

            val format = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(audioConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            isAudioActive.set(true)
            _senderState.value = _senderState.value.copy(
                audioCaptureActive = true,
                audioRoute = activeAudioRoute.displayName
            )
            Log.i(TAG, "Internal AudioPlaybackCapture active (44.1kHz Stereo 16-bit PCM, route=${activeAudioRoute.displayName})")

            audioThread = Thread({
                val chunk = ByteArray(2048) // ~23ms chunks for low latency
                var packetsSent = 0L
                var bytesSent = 0L
                var lastDiag = System.currentTimeMillis()

                while (isRunning.get() && !isReconfiguring.get() && isAudioActive.get()) {
                    val read = try {
                        record.read(chunk, 0, chunk.size)
                    } catch (e: Exception) {
                        break
                    }
                    if (read > 0) {
                        val ws = wsClient
                        if (ws != null && ws.isOpen) {
                            // Tag with "AU" prefix (2 bytes) + audio PCM payload
                            val packet = ByteArray(2 + read)
                            packet[0] = 'A'.code.toByte()
                            packet[1] = 'U'.code.toByte()
                            System.arraycopy(chunk, 0, packet, 2, read)
                            try {
                                ws.send(packet)
                                packetsSent++
                                bytesSent += packet.size
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastDiag >= 1000) {
                        lastDiag = now
                        _senderState.value = _senderState.value.copy(
                            audioPacketsSent = packetsSent,
                            audioBytesSent = bytesSent,
                            audioCaptureActive = true
                        )
                    }
                }
            }, "CarCast_AudioCapture").apply { start() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start AudioPlaybackCapture: ${e.message}")
            _senderState.value = _senderState.value.copy(audioCaptureActive = false)
        }
    }

    private fun stopAudioCapture() {
        isAudioActive.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread = null

        // Restore phone speaker volume if we muted it
        if (isPhoneSpeakerMutedByUs && savedStreamVolume >= 0) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamVolume, 0)
                    Log.i(TAG, "Restored phone speaker volume to $savedStreamVolume")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore phone speaker volume: ${e.message}")
                }
            }
            isPhoneSpeakerMutedByUs = false
            savedStreamVolume = -1
        }

        _senderState.value = _senderState.value.copy(audioCaptureActive = false)
    }

    private fun handleIncomingTouchEvent(message: String) {
        try {
            val actionMatch = Regex(""""action"\s*:\s*"(\w+)"""").find(message)
            val xMatch = Regex(""""x"\s*:\s*([0-9.]+)""").find(message)
            val yMatch = Regex(""""y"\s*:\s*([0-9.]+)""").find(message)

            val action = actionMatch?.groupValues?.getOrNull(1) ?: return
            val xRatio = xMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return
            val yRatio = yMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return

            val a11y = CarCastAccessibilityService.instance
            if (a11y != null) {
                a11y.performTouchGesture(action, xRatio, yRatio, currentScreenW, currentScreenH)
                Log.d(TAG, "[TOUCH:RECV] Dispatched action=$action x=$xRatio y=$yRatio to AccessibilityService")
            } else {
                Log.w(TAG, "[TOUCH:RECV] Touch received but CarCastAccessibilityService is not enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse incoming touch JSON: ${e.message}")
        }
    }

    private fun requestKeyframeFromCodec() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Requested instantaneous sync keyframe from hardware encoder")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync keyframe: ${e.message}")
        }
    }

    private fun selectEffectiveBaseDim(requestedProfile: QualityProfile, screenW: Int, screenH: Int): Pair<Int, Int> {
        val minScreenDim = minOf(screenW, screenH)
        return when (requestedProfile) {
            QualityProfile.HD -> Pair(720, 30)
            QualityProfile.FULL_HD -> Pair(1080, 30)
            QualityProfile.SMOOTH -> Pair(720, 60)
            QualityProfile.AUTO -> {
                if (autoDowngraded) {
                    Pair(720, 30)
                } else if (minScreenDim >= 720) {
                    Pair(1080, 30)
                } else {
                    Pair(720, 30)
                }
            }
        }
    }

    private fun isEncoderSizeSupported(targetBase: Int): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetBase, 1920)
            codecList.findEncoderForFormat(format) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun calculateEncodingDimensions(screenW: Int, screenH: Int, baseDim: Int): Pair<Int, Int> {
        val maxLongDim = if (baseDim >= 1080) 1920 else 1280
        val maxShortDim = baseDim

        val isPortrait = screenW <= screenH
        val longScreen = if (isPortrait) screenH else screenW
        val shortScreen = if (isPortrait) screenW else screenH

        val scale = minOf(
            maxLongDim.toDouble() / longScreen,
            maxShortDim.toDouble() / shortScreen,
            1.0
        )

        val scaledW = (screenW * scale).roundToInt()
        val scaledH = (screenH * scale).roundToInt()

        // Align to 16-pixel macroblock boundaries required by hardware AVC encoders
        val encWidth = ((scaledW + 15) / 16) * 16
        val encHeight = ((scaledH + 15) / 16) * 16

        return Pair(encWidth, encHeight)
    }

    private fun createAndConfigureEncoder(encWidth: Int, encHeight: Int, targetFps: Int, targetBitrate: Int): Pair<MediaCodec, android.view.Surface> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codec = MediaCodec.createEncoderByType(mime)

        val configs = listOf(
            // Tier 1: User requested profile & bitrate with repeat frame
            {
                MediaFormat.createVideoFormat(mime, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
            },
            // Tier 2: 30 FPS, 3.5 Mbps standard baseline without KEY_LOW_LATENCY (Samsung/MTK compatibility)
            {
                MediaFormat.createVideoFormat(mime, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            },
            // Tier 3: 30 FPS, 2.5 Mbps safe baseline fallback
            {
                MediaFormat.createVideoFormat(mime, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
            }
        )

        var lastException: Exception? = null
        for ((idx, formatBuilder) in configs.withIndex()) {
            try {
                val format = formatBuilder()
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec.createInputSurface()
                codec.start()
                Log.i(TAG, "Hardware encoder successfully started using Tier ${idx + 1} profile (${encWidth}x${encHeight} @ $targetFps FPS, ${targetBitrate / 1000} kbps)")
                return Pair(codec, inputSurface)
            } catch (e: Exception) {
                Log.w(TAG, "Encoder config Tier ${idx + 1} failed: ${e.message}, trying fallback...", e)
                lastException = e
                try { codec.reset() } catch (_: Exception) {}
            }
        }
        codec.release()
        throw lastException ?: IllegalStateException("Failed to configure MediaCodec encoder on all tiers")
    }

    private fun initCodecAndCapture() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (defaultDisplay != null) {
            defaultDisplay.getRealMetrics(metrics)
        } else {
            metrics.setTo(resources.displayMetrics)
        }

        currentScreenW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        currentScreenH = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        currentDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT

        val (baseDim, targetFps) = selectEffectiveBaseDim(activeProfile, currentScreenW, currentScreenH)
        val targetBitrate = when (activeProfile) {
            QualityProfile.FULL_HD -> 8_500_000
            QualityProfile.HD -> 4_500_000
            QualityProfile.SMOOTH -> 6_000_000
            QualityProfile.AUTO -> if (baseDim >= 1080 && !autoDowngraded) 8_000_000 else 4_500_000
        }

        val (encWidth, encHeight) = calculateEncodingDimensions(currentScreenW, currentScreenH, baseDim)
        currentEncWidth = encWidth
        currentEncHeight = encHeight

        val resStr = "${encWidth}x${encHeight}"
        Log.i(TAG, "Initializing screen capture encoder: profile=${activeProfile.displayName}, physical=${currentScreenW}x${currentScreenH}, encoding=$resStr @ $targetFps FPS, dpi=$currentDpi")

        try {
            val (codec, inputSurface) = createAndConfigureEncoder(encWidth, encHeight, targetFps, targetBitrate)
            mediaCodec = codec

            val mainHandler = Handler(Looper.getMainLooper())
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "CarCast_MirrorDisplay",
                encWidth,
                encHeight,
                currentDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                mainHandler
            )

            isRunning.set(true)
            isReconfiguring.set(false)
            _senderState.value = _senderState.value.copy(
                status = SenderStatus.ENCODING,
                encoderActive = true,
                resolution = resStr,
                qualityProfileName = if (autoDowngraded) "Auto (720p Fallback)" else activeProfile.displayName,
                reverseTouchActive = CarCastAccessibilityService.isRunning
            )

            // Send metadata packet to receiver
            val metaJson = """{"type":"meta","width":$encWidth,"height":$encHeight,"fps":$targetFps}"""
            try {
                wsClient?.send(metaJson)
                Log.i(TAG, "Sent stream metadata to Car LCD: $metaJson")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send meta json: ${e.message}")
            }

            startEncoderDrainLoop()
            requestKeyframeFromCodec()
            Log.i(TAG, "Hardware Screen Capture encoder active ($resStr @ $targetFps FPS)")
        } catch (e: Exception) {
            val err = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Failed to start MediaCodec encoder: $err\n${e.stackTraceToString()}", e)
            _senderState.value = _senderState.value.copy(
                status = SenderStatus.FAILED,
                errorMessage = "Encoder initialization error: $err"
            )
            stopStreaming()
        }
    }

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    checkAndHandleDisplayOrientationChange()
                }
            }
        }
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        displayListener?.let {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
    }

    private fun checkAndHandleDisplayOrientationChange(force: Boolean = false) {
        if (!isRunning.get() || isReconfiguring.get()) return

        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay.getRealMetrics(metrics)

        val newScreenW = if (metrics.widthPixels > 0) metrics.widthPixels else return
        val newScreenH = if (metrics.heightPixels > 0) metrics.heightPixels else return
        val newDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT

        val (baseDim, targetFps) = selectEffectiveBaseDim(activeProfile, newScreenW, newScreenH)
        val targetBitrate = when (activeProfile) {
            QualityProfile.FULL_HD -> 8_500_000
            QualityProfile.HD -> 4_500_000
            QualityProfile.SMOOTH -> 6_000_000
            QualityProfile.AUTO -> if (baseDim >= 1080 && !autoDowngraded) 8_000_000 else 4_500_000
        }
        val (newEncW, newEncH) = calculateEncodingDimensions(newScreenW, newScreenH, baseDim)

        if (force || newEncW != currentEncWidth || newEncH != currentEncHeight) {
            Log.i(TAG, "[ROTATION/RESCALE] Screen ${currentScreenW}x${currentScreenH} -> ${newScreenW}x${newScreenH}, encoding ${currentEncWidth}x${currentEncHeight} -> ${newEncW}x${newEncH}")
            reconfigureEncoderForNewDimensions(newScreenW, newScreenH, newEncW, newEncH, newDpi, targetFps, targetBitrate)
        }
    }

    private fun reconfigureEncoderForNewDimensions(
        newScreenW: Int,
        newScreenH: Int,
        newEncW: Int,
        newEncH: Int,
        newDpi: Int,
        targetFps: Int,
        targetBitrate: Int
    ) {
        if (isReconfiguring.getAndSet(true)) return

        Log.i(TAG, "[RECONFIGURE] Reconfiguring encoder for ${newEncW}x${newEncH} @ $targetFps FPS...")
        try {
            // 1. Stop and release old codec
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping old encoder during reconfiguration: ${e.message}")
            }
            mediaCodec = null

            // 2. Configure and start new codec
            val (newCodec, newInputSurface) = createAndConfigureEncoder(newEncW, newEncH, targetFps, targetBitrate)
            mediaCodec = newCodec

            // 3. Resize VirtualDisplay and set new surface
            val vd = virtualDisplay
            val mainHandler = Handler(Looper.getMainLooper())
            if (vd != null) {
                try {
                    vd.resize(newEncW, newEncH, newDpi)
                    vd.surface = newInputSurface
                } catch (e: Exception) {
                    Log.w(TAG, "VirtualDisplay resize failed, recreating: ${e.message}")
                    try { vd.release() } catch (_: Exception) {}
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "CarCast_MirrorDisplay",
                        newEncW,
                        newEncH,
                        newDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newInputSurface,
                        null,
                        mainHandler
                    )
                }
            } else {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "CarCast_MirrorDisplay",
                    newEncW,
                    newEncH,
                    newDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newInputSurface,
                    null,
                    mainHandler
                )
            }

            // 4. Update dimension state and reset cached SPS/PPS
            currentScreenW = newScreenW
            currentScreenH = newScreenH
            currentEncWidth = newEncW
            currentEncHeight = newEncH
            currentDpi = newDpi
            cachedSpsPps = null

            val resStr = "${newEncW}x${newEncH}"
            _senderState.value = _senderState.value.copy(
                resolution = resStr,
                qualityProfileName = if (autoDowngraded) "Auto (720p Fallback)" else activeProfile.displayName,
                reverseTouchActive = CarCastAccessibilityService.isRunning
            )

            // 5. Send updated metadata to Car LCD
            val metaJson = """{"type":"meta","width":$newEncW,"height":$newEncH,"fps":$targetFps}"""
            try {
                wsClient?.send(metaJson)
                Log.i(TAG, "[RECONFIGURE] Transmitted updated metadata to Car LCD: $metaJson")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send reconfiguration meta json: ${e.message}")
            }

            // 6. Resume drain loop and request immediate IDR keyframe
            isReconfiguring.set(false)
            startEncoderDrainLoop()
            requestKeyframeFromCodec()
            Log.i(TAG, "[RECONFIGURE] Reconfiguration completed successfully to $resStr")
        } catch (e: Exception) {
            isReconfiguring.set(false)
            Log.e(TAG, "[RECONFIGURE] Failed to reconfigure encoder", e)
        }
    }

    private fun startEncoderDrainLoop() {
        Thread({
            val codec = mediaCodec ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()
            var framesCount = _senderState.value.framesSent
            var bytesCount = _senderState.value.bytesSent
            var droppedCount = _senderState.value.droppedFrames
            var lastStateUpdate = System.currentTimeMillis()
            var lastFramesCount = framesCount
            var lastBytesCount = bytesCount
            var lastDroppedCount = droppedCount

            while (isRunning.get() && !isReconfiguring.get()) {
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: Exception) {
                    break
                }

                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            cachedSpsPps = chunk.clone()
                            Log.d(TAG, "Cached SPS/PPS config bytes (${chunk.size} bytes)")
                        }

                        val ws = wsClient
                        if (ws != null && ws.isOpen) {
                            // Low-latency queue management:
                            // If WebSocket has buffered data backing up, drop non-keyframe to prevent latency accumulation!
                            val isCongested = ws.hasBufferedData()
                            if (isCongested && !isKey && !isConfig) {
                                droppedCount++
                            } else {
                                val payloadToSend = if (isKey && cachedSpsPps != null && !isConfigData(chunk, 0, chunk.size)) {
                                    val combined = ByteArray(cachedSpsPps!!.size + chunk.size)
                                    System.arraycopy(cachedSpsPps!!, 0, combined, 0, cachedSpsPps!!.size)
                                    System.arraycopy(chunk, 0, combined, cachedSpsPps!!.size, chunk.size)
                                    combined
                                } else {
                                    chunk
                                }

                                ws.send(payloadToSend)
                                framesCount++
                                bytesCount += payloadToSend.size
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastStateUpdate >= 1000) {
                                val elapsedSec = (now - lastStateUpdate) / 1000f
                                val currentFps = ((framesCount - lastFramesCount) / elapsedSec).roundToInt().coerceAtLeast(0)
                                val currentBitrate = (((bytesCount - lastBytesCount) * 8) / (elapsedSec * 1000)).toLong().coerceAtLeast(0)
                                lastFramesCount = framesCount
                                lastBytesCount = bytesCount
                                lastStateUpdate = now

                                // Auto Quality fallback trigger
                                val recentDrops = droppedCount - lastDroppedCount
                                lastDroppedCount = droppedCount
                                if (activeProfile == QualityProfile.AUTO && !autoDowngraded && recentDrops > 20 && currentEncWidth > 720) {
                                    Log.w(TAG, "[AUTO:QUALITY] Heavy queue congestion ($recentDrops dropped frames). Stepping down to 720p HD...")
                                    autoDowngraded = true
                                    Handler(Looper.getMainLooper()).post {
                                        checkAndHandleDisplayOrientationChange(force = true)
                                    }
                                }

                                _senderState.value = _senderState.value.copy(
                                    status = SenderStatus.STREAMING,
                                    framesSent = framesCount,
                                    bytesSent = bytesCount,
                                    droppedFrames = droppedCount,
                                    fps = currentFps,
                                    bitrateKbps = currentBitrate,
                                    qualityProfileName = if (autoDowngraded) "Auto (720p Fallback)" else activeProfile.displayName,
                                    reverseTouchActive = CarCastAccessibilityService.isRunning,
                                    audioCaptureActive = isAudioActive.get()
                                )
                            }
                        }
                    }
                    try {
                        codec.releaseOutputBuffer(outputIndex, false)
                    } catch (ignored: Exception) {}
                }
            }
        }, "CarCast_EncoderDrain").start()
    }

    private fun isConfigData(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length > 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
            val nalType = (data[offset + 4].toInt() and 0x1F)
            return nalType == 7 || nalType == 8
        }
        return false
    }

    private fun startForegroundNotification() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CarCastApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CarCast Screen Mirroring")
            .setContentText("Streaming screen & audio to Car LCD...")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopStreaming() {
        val wasRunning = isRunning.getAndSet(false)
        unregisterDisplayListener()
        stopAudioCapture()
        try { virtualDisplay?.release() } catch (ignored: Exception) {}
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (ignored: Exception) {}
        try { mediaProjection?.stop() } catch (ignored: Exception) {}
        try { wsClient?.close() } catch (ignored: Exception) {}
        virtualDisplay = null
        mediaCodec = null
        mediaProjection = null
        wsClient = null

        if (wasRunning) {
            _senderState.value = _senderState.value.copy(
                status = SenderStatus.IDLE,
                encoderActive = false,
                fps = 0,
                bitrateKbps = 0L,
                audioCaptureActive = false
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
