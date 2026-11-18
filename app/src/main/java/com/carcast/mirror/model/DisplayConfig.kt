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
    AUTO("Auto Quality", 1080, 30, 7_000_000, "Automatically adapts to device and network"),
    HD("HD (720p @ 30 FPS)", 720, 30, 3_500_000, "Standard 720p HD with low latency"),
    FULL_HD("Full HD (1080p @ 30 FPS)", 1080, 30, 7_500_000, "Crisp 1080p text and sharp video"),
    SMOOTH("Smooth (720p @ 60 FPS)", 720, 60, 5_500_000, "High frame rate for fast UI motion")
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
    val gitHubRepo: String = "softwork-05/car-cast-",
    val broadcastName: String = "My Car LCD"
)
