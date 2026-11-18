package com.carcast.mirror.engine.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

class LowLatencyAudioTrack {

    companion object {
        private const val TAG = "CarCast_AudioTrack"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isMuted = false
    private var currentVolume = 1.0f

    fun init() {
        try {
            stop()
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = minBufferSize * 2

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

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

            audioTrack?.play()
            isPlaying = true
            Log.i(TAG, "LowLatencyAudioTrack initialized (44.1kHz Stereo PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun playAudio(pcmData: ByteArray, offset: Int, length: Int) {
        if (!isPlaying || isMuted || audioTrack == null) return
        try {
            audioTrack?.write(pcmData, offset, length)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio frame", e)
        }
    }

    fun setVolume(volume: Float) {
        this.currentVolume = volume.coerceIn(0f, 1f)
        try {
            if (!isMuted) {
                audioTrack?.setVolume(currentVolume)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume", e)
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
            Log.w(TAG, "Error toggling mute", e)
        }
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }
}
