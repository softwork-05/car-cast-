package com.carcast.mirror.engine.airplay

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AirPlayDebugLogger {
    val logs = mutableStateListOf<String>()
    val isServerListening = mutableStateOf(false)
    val serverStatusText = mutableStateOf("STARTING")

    private val mainHandler = Handler(Looper.getMainLooper())

    fun log(msg: String) {
        // SimpleDateFormat is not thread-safe. Format only inside the main-thread callback.
        android.util.Log.i("CarCast_AirPlayRTSP", msg)
        mainHandler.post {
            val entry = "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}] $msg"
            if (logs.size >= 200) logs.removeAt(0)
            logs.add(entry)
        }
    }

    fun setServerListening(listening: Boolean, status: String = if (listening) "ACTIVE" else "OFFLINE") {
        mainHandler.post {
            isServerListening.value = listening
            serverStatusText.value = status
        }
    }

    fun clear() {
        mainHandler.post {
            logs.clear()
        }
    }
}
