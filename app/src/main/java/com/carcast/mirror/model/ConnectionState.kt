package com.carcast.mirror.model

enum class ConnectionStatus {
    IDLE,               // Ready to connect, advertising services
    CONNECTING,         // Handshake in progress
    STREAMING,          // Active mirroring
    DISCONNECTED,       // Device disconnected
    ERROR               // Network or decoder error
}

enum class DeviceType {
    IOS_AIRPLAY,
    ANDROID_CAST,
    UNKNOWN
}

data class ConnectedClient(
    val deviceName: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val resolution: String = "1920x1080",
    val fps: Int = 60,
    val connectedAtMillis: Long = System.currentTimeMillis()
)
