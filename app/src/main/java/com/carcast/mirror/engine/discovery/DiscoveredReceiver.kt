package com.carcast.mirror.engine.discovery

/**
 * Platform type of the discovered CarCast receiver.
 */
enum class ReceiverPlatform(val label: String) {
    CAR_LCD("Car LCD"),
    ANDROID_TV("Android TV"),
    WINDOWS_PC("Windows PC"),
    UNKNOWN("CarCast Device")
}

/**
 * Represents a nearby CarCast receiver discovered via NSD/mDNS across any supported platform.
 */
data class DiscoveredReceiver(
    val id: String,                         // Stable persistent unique identifier (UUID)
    val name: String,                       // Device broadcast name (e.g., "My Car LCD", "Living Room TV", "Windows PC")
    val host: String,                       // Resolved IP address (e.g., "192.168.43.1")
    val port: Int = 8080,                   // WebSocket streaming port (default 8080)
    val httpPort: Int = 8088,               // HTTP pairing portal port (default 8088)
    val version: String = "",               // App version of the receiver
    val platform: ReceiverPlatform = ReceiverPlatform.CAR_LCD, // Device platform
    val isAutoDetected: Boolean = false,    // Whether this matches the remembered receiver ID or default name
    val lastSeenMs: Long = System.currentTimeMillis()
)
