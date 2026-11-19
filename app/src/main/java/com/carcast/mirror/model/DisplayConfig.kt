package com.carcast.mirror.model

enum class AspectRatioMode {
    FIT,    // Fit Screen: Preserves phone aspect ratio with black bars (No distortion, complete screen)
    FILL    // Fill Screen: Fills full Car LCD without distortion (Center crop, no stretch)
}

enum class StreamResolution(val width: Int, val height: Int, val label: String) {
    RES_720P(1280, 720, "720p HD (High Performance)"),
    RES_1080P(1920, 1080, "1080p Full HD (Crisp Details)")
}

enum class QualityProfile(
    val displayName: String,
    val baseDim: Int,
    val targetFps: Int,
    val defaultBitrate: Int,
    val description: String
) {
    AUTO("Auto Quality", 1080, 30, 8_000_000, "Automatically adapts between 1080p FHD and 720p HD"),
    HD("HD (720p @ 30 FPS)", 720, 30, 4_500_000, "Standard 720p HD with low latency"),
    FULL_HD("Full HD (1080p @ 30 FPS)", 1080, 30, 8_500_000, "Crisp 1080p text and razor-sharp video"),
    SMOOTH("Smooth (720p @ 60 FPS)", 720, 60, 6_000_000, "High 60 FPS frame rate for fast UI motion")
}

enum class AudioOutputRoute(val displayName: String, val description: String) {
    CAR_LCD_ONLY("Car LCD Only (Preferred)", "Plays sound through car speakers and mutes phone speakers"),
    PHONE_ONLY("Phone Only", "Plays sound on phone only; no audio sent to car"),
    BOTH_DEVICES("Both Devices (Optional)", "Plays sound simultaneously on Car LCD and phone speakers")
}

data class DisplayConfig(
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val resolution: StreamResolution = StreamResolution.RES_1080P,
    val qualityProfile: QualityProfile = QualityProfile.AUTO,
    val rotationDegrees: Int = 0,
    val isAudioMuted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val autoStartOnBoot: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val isTouchControlEnabled: Boolean = true,
    val showPaymentSidePanels: Boolean = false,
    val audioOutputRoute: AudioOutputRoute = AudioOutputRoute.CAR_LCD_ONLY,
    val gitHubRepo: String = "softwork-05/car-cast-",
    val broadcastName: String = "My Car LCD"
)
