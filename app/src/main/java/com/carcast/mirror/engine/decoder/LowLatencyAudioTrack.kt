package com.carcast.mirror.engine.decoder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LowLatencyAudioTrack(private val context: Context? = null) {

    companion object {
        private const val TAG = "CarCast_AudioTrack"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_QUEUE_CAPACITY = 16 // ~180ms max buffer to eliminate lag
        private const val TARGET_JITTER_DEPTH = 6 // ~70ms target jitter buffer
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private var isMuted = false
    private var currentVolume = 1.0f

    // Audio Focus
    private var audioFocusRequest: Any? = null // AudioFocusRequest on API 26+

    // Queue & Playback Thread
    private val audioQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE_CAPACITY)
    private var playbackThread: Thread? = null

    // Statistics
    val packetsReceived = AtomicLong(0L)
    val bytesReceived = AtomicLong(0L)
    val packetsPlayed = AtomicLong(0L)

    val isAudioActive: Boolean
        get() = isPlaying.get() && audioTrack != null

    fun init() {
        try {
            stop()

            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            // Use 3x minBufferSize to prevent underruns on varied Android hardware
            val bufferSize = maxOf(minBufferSize * 3, 8192)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            // Request AudioFocus for Automotive Head Units
            requestAudioFocus(attributes)

            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize (state=${audioTrack?.state})")
                return
            }

            audioTrack?.play()
            isPlaying.set(true)
            setVolume(currentVolume)
            setMuted(isMuted)

            // Start dedicated background playback thread
            startPlaybackThread()

            Log.i(TAG, "LowLatencyAudioTrack initialized and playing (44.1kHz Stereo PCM, bufferSize=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    private fun requestAudioFocus(attributes: AudioAttributes) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "AudioFocus changed: $focusChange")
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                audioTrack?.setVolume(0f)
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                if (!isMuted) audioTrack?.setVolume(currentVolume)
                            }
                        }
                    }
                    .build()
                audioManager.requestAudioFocus(focusReq)
                audioFocusRequest = focusReq
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire AudioFocus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequest as? AudioFocusRequest)?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning AudioFocus: ${e.message}")
        }
        audioFocusRequest = null
    }

    private fun startPlaybackThread() {
        audioQueue.clear()
        playbackThread = Thread({
            Log.i(TAG, "Audio playback worker thread started")
            while (isPlaying.get()) {
                try {
                    val chunk = audioQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    val track = audioTrack ?: break

                    if (!isMuted && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        var offset = 0
                        var remaining = chunk.size
                        while (remaining > 0 && isPlaying.get()) {
                            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                track.write(chunk, offset, remaining, AudioTrack.WRITE_BLOCKING)
                            } else {
                                track.write(chunk, offset, remaining)
                            }
                            if (written > 0) {
                                offset += written
                                remaining -= written
                                bytesReceived.addAndGet(written.toLong())
                            } else {
                                break
                            }
                        }
                        packetsPlayed.incrementAndGet()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error writing audio chunk to AudioTrack: ${e.message}")
                }
            }
            Log.i(TAG, "Audio playback worker thread exited")
        }, "CarCast_AudioPlaybackWorker").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun playAudio(pcmData: ByteArray, offset: Int, length: Int) {
        if (!isPlaying.get() || isMuted || audioTrack == null || length <= 0) return

        packetsReceived.incrementAndGet()

        // Low latency jitter management:
        // If queue exceeds target depth, drop the oldest frame to prevent accumulation of audio lag
        while (audioQueue.size >= TARGET_JITTER_DEPTH) {
            audioQueue.poll()
        }

        val chunk = ByteArray(length)
        System.arraycopy(pcmData, offset, chunk, 0, length)
        audioQueue.offer(chunk)
    }

    fun setVolume(volume: Float) {
        this.currentVolume = volume.coerceIn(0f, 1f)
        try {
            if (!isMuted) {
                audioTrack?.setVolume(currentVolume)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume: ${e.message}")
        }
    }

    fun setMuted(muted: Boolean) {
        this.isMuted = muted
        try {
            if (muted) {
                audioTrack?.setVolume(0f)
            } else {
                audioTrack?.setVolume(currentVolume)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error toggling mute: ${e.message}")
        }
    }

    fun stop() {
        isPlaying.set(false)
        playbackThread?.interrupt()
        try {
            playbackThread?.join(300)
        } catch (_: Exception) {}
        playbackThread = null
        audioQueue.clear()

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }
        abandonAudioFocus()
    }
}
