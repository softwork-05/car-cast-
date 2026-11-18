package com.carcast.mirror.engine.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class HardwareVideoDecoder {

    companion object {
        private const val TAG = "CarCast_VideoDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        private const val TIMEOUT_US = 10_000L
    }

    private var mediaCodec: MediaCodec? = null
    private val isConfigured = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    var currentWidth = 1280; private set
    var currentHeight = 720; private set

    var framesDecoded: Long = 0L; private set
    var framesRendered: Long = 0L; private set
    var fps: Int = 0; private set
    var lastError: String? = null; private set

    private var lastFpsTimestamp = 0L
    private var framesSinceLastFps = 0

    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    private var cachedSpsPps: ByteArray? = null

    val isReady: Boolean
        get() = isRunning.get() && mediaCodec != null

    fun init(surface: Surface, width: Int = 1280, height: Int = 720) {
        if (!surface.isValid) {
            Log.w(TAG, "Cannot init decoder: provided Surface is not valid")
            return
        }

        currentWidth = width
        currentHeight = height
        try {
            stop()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time low-latency priority

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                try {
                    configure(format, surface, null, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Decoder low-latency config failed: ${e.message}, retrying standard config...")
                    val safeFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    configure(safeFormat, surface, null, 0)
                }
                start()
            }
            isConfigured.set(true)
            isRunning.set(true)
            framesDecoded = 0L
            framesRendered = 0L
            lastError = null
            Log.i(TAG, "Hardware H.264 MediaCodec decoder started (${width}x${height} @ 60fps low-latency)")

            // If we cached SPS/PPS from previous frames before surface was attached, feed it now!
            cachedSpsPps?.let { sps ->
                queueConfigBuffer(sps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec decoder", e)
            lastError = "Init failed: ${e.message}"
            isConfigured.set(false)
        }
    }

    private fun queueConfigBuffer(config: ByteArray) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(config)
                codec.queueInputBuffer(inputIndex, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                Log.i(TAG, "Fed cached SPS/PPS config to newly initialized decoder (${config.size} bytes)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to feed cached config buffer: ${e.message}")
        }
    }

    fun decodeFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        // Cache SPS/PPS if observed in stream
        if (isConfigData(data, offset, length)) {
            val copy = ByteArray(length)
            System.arraycopy(data, offset, copy, 0, length)
            cachedSpsPps = copy
        }

        if (!isRunning.get() || mediaCodec == null) {
            return
        }

        try {
            val codec = mediaCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, offset, length)

                val flags = if (isConfigData(data, offset, length)) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else 0

                codec.queueInputBuffer(inputIndex, 0, length, ptsUs, flags)
                framesDecoded++
            }

            // Drain decoded frames to surface
            drainOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding H.264 video frame: ${e.message}")
            lastError = "Decode error: ${e.message}"
        }
    }

    private fun drainOutput() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "Decoder output format changed: ${w}x${h}")
                    currentWidth = w
                    currentHeight = h
                    onResolutionChanged?.invoke(w, h)
                } else if (outputIndex >= 0) {
                    // Render directly to surface (render = true)
                    codec.releaseOutputBuffer(outputIndex, true)
                    framesRendered++
                    framesSinceLastFps++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTimestamp >= 1000) {
                        fps = framesSinceLastFps
                        framesSinceLastFps = 0
                        lastFpsTimestamp = now
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "drainOutput exception: ${e.message}")
        }
    }

    private fun isConfigData(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length > 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
            val nalType = (data[offset + 4].toInt() and 0x1F)
            return nalType == 7 || nalType == 8
        }
        return false
    }

    fun stop() {
        isRunning.set(false)
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaCodec", e)
        } finally {
            mediaCodec = null
            isConfigured.set(false)
        }
    }
}
