package com.carcast.mirror.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.carcast.mirror.service.CarCastService
import com.carcast.mirror.service.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppMode {
    RECEIVER, // Car LCD Mode (AirPlay + Android Receiver Server)
    SENDER    // Mobile Phone Mode (MediaProjection + Screen & Audio Capture)
}

object ModeManager {
    private const val TAG = "CarCast_ModeManager"
    private const val PREFS_KEY_MODE = "app_mode"

    private val _currentMode = MutableStateFlow(AppMode.RECEIVER)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
        val savedModeStr = prefs.getString(PREFS_KEY_MODE, null)
        val initialMode = if (savedModeStr != null) {
            try {
                AppMode.valueOf(savedModeStr)
            } catch (_: Exception) {
                AppMode.RECEIVER
            }
        } else {
            if (prefs.getBoolean("is_sender_mode", false)) AppMode.SENDER else AppMode.RECEIVER
        }
        _currentMode.value = initialMode
        Log.i(TAG, "ModeManager initialized in mode: $initialMode")
    }

    @Synchronized
    fun switchMode(context: Context, newMode: AppMode) {
        val oldMode = _currentMode.value
        Log.i(TAG, "Switching mode: $oldMode -> $newMode")

        if (newMode == AppMode.SENDER) {
            // 1. Completely shutdown local Receiver mode (AirPlay native engine, AndroidCastServer, mDNS)
            try {
                val stopReceiverIntent = Intent(context, CarCastService::class.java).apply {
                    action = CarCastService.ACTION_STOP_RECEIVER
                }
                context.startService(stopReceiverIntent)
                context.stopService(stopReceiverIntent)
                Log.i(TAG, "[MODE:SWITCH] Successfully stopped local CarCastService (AirPlay + Android Receiver)")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping CarCastService", e)
            }
        } else {
            // 2. Completely shutdown local Sender mode (MediaProjection, AudioRecord, Video encoder)
            try {
                val stopSenderIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                context.startService(stopSenderIntent)
                context.stopService(stopSenderIntent)
                Log.i(TAG, "[MODE:SWITCH] Successfully stopped local ScreenCaptureService (Sender)")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping ScreenCaptureService", e)
            }

            // 3. Start local Receiver mode
            try {
                val startReceiverIntent = Intent(context, CarCastService::class.java).apply {
                    action = CarCastService.ACTION_START_RECEIVER
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startReceiverIntent)
                } else {
                    context.startService(startReceiverIntent)
                }
                Log.i(TAG, "[MODE:SWITCH] Successfully started local CarCastService (Receiver)")
            } catch (e: Exception) {
                Log.w(TAG, "Error starting CarCastService", e)
            }
        }

        _currentMode.value = newMode
        val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREFS_KEY_MODE, newMode.name)
            .putBoolean("is_sender_mode", newMode == AppMode.SENDER)
            .apply()
    }
}
