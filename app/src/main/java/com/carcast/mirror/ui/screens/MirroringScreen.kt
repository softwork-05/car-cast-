package com.carcast.mirror.ui.screens

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.carcast.mirror.model.AspectRatioMode
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.DeviceType
import com.carcast.mirror.model.DisplayConfig
import com.carcast.mirror.service.CarCastService
import com.carcast.mirror.ui.components.FloatingControlPill
import com.carcast.mirror.ui.theme.ErrorRed
import kotlinx.coroutines.delay

@Composable
fun MirroringScreen(
    service: CarCastService,
    client: ConnectedClient?,
    config: DisplayConfig,
    onUpdateConfig: (DisplayConfig) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showControls by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val streamAspect by service.streamAspectRatio.collectAsState()
    val androidDiag by service.androidDiagnostics.collectAsState()

    // Pass aspect ratio mode changes to service (for native OpenGL AirPlay pipeline)
    LaunchedEffect(config.aspectRatioMode) {
        service.setAspectRatioMode(config.aspectRatioMode == AspectRatioMode.FIT)
    }

    // Auto-hide floating pill after 4 seconds of inactivity
    LaunchedEffect(showControls, lastInteractionTime) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth.value
        val containerHeight = maxHeight.value
        val containerAspect = if (containerHeight > 0f) containerWidth / containerHeight else 1.777f

        // --- 1. Video Surface View (Hardware MediaCodec Target & Touch Dispatcher) ---
        // For AirPlay (iOS): Keep SurfaceView full-screen! OpenGL VideoPipeline handles authoritative FIT_CENTER.
        // For Android Cast: MediaCodec renders directly to Surface, so Compose handles aspect ratio sizing.
        val isAirPlay = (client?.deviceType == DeviceType.IOS_AIRPLAY)
        val surfaceModifier = if (isAirPlay) {
            Modifier.fillMaxSize()
        } else if (streamAspect > 0.05f) {
            when (config.aspectRatioMode) {
                AspectRatioMode.FIT -> {
                    // Fit Screen: show full uncropped content with black letterbox/pillarbox
                    Modifier
                        .aspectRatio(streamAspect, matchHeightConstraintsFirst = (streamAspect < containerAspect))
                        .fillMaxSize()
                }
                AspectRatioMode.FILL -> {
                    // Fill Screen: center crop to fill widescreen without stretching or distortion
                    Modifier
                        .aspectRatio(streamAspect, matchHeightConstraintsFirst = (streamAspect > containerAspect))
                }
            }
        } else {
            Modifier.fillMaxSize()
        }

        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            service.activeSurface = holder.surface
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            service.activeSurface = holder.surface
                            service.onSurfaceSizeChanged(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            service.activeSurface = null
                        }
                    })

                    // Reverse Touch Listener: Dispatch touches back to phone
                    setOnTouchListener { v, event ->
                        val w = v.width.toFloat()
                        val h = v.height.toFloat()
                        if (w > 0 && h > 0) {
                            var xRatio = (event.x / w).coerceIn(0f, 1f)
                            var yRatio = (event.y / h).coerceIn(0f, 1f)

                            // Correct for manual screen rotation if configured
                            when (config.rotationDegrees) {
                                90 -> {
                                    val oldX = xRatio
                                    xRatio = yRatio
                                    yRatio = 1f - oldX
                                }
                                180 -> {
                                    xRatio = 1f - xRatio
                                    yRatio = 1f - yRatio
                                }
                                270 -> {
                                    val oldX = xRatio
                                    xRatio = 1f - yRatio
                                    yRatio = oldX
                                }
                            }

                            val actionStr = when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    showControls = true
                                    lastInteractionTime = System.currentTimeMillis()
                                    "down"
                                }
                                MotionEvent.ACTION_MOVE -> "move"
                                MotionEvent.ACTION_UP -> "up"
                                MotionEvent.ACTION_CANCEL -> "cancel"
                                else -> null
                            }

                            if (actionStr != null && config.isTouchControlEnabled) {
                                if (client?.deviceType == DeviceType.ANDROID_CAST) {
                                    service.dispatchTouchEvent(actionStr, xRatio, yRatio)
                                }
                            }
                        }
                        true
                    }
                }
            },
            modifier = surfaceModifier.graphicsLayer {
                rotationZ = config.rotationDegrees.toFloat()
            }
        )

        // --- 2. Floating Auto-Hiding Control Pill (Top Center Edge) ---
        val extraInfo = if (client?.deviceType == DeviceType.ANDROID_CAST) {
            if (androidDiag.framesReceived > 0) {
                "${androidDiag.streamWidth}x${androidDiag.streamHeight} • ${androidDiag.fps} FPS"
            } else {
                "Waiting for video frames..."
            }
        } else null

        FloatingControlPill(
            visible = showControls,
            aspectRatioMode = config.aspectRatioMode,
            isMuted = config.isAudioMuted,
            isTouchEnabled = config.isTouchControlEnabled,
            isTouchSupported = (client?.deviceType != DeviceType.IOS_AIRPLAY),
            clientName = client?.deviceName ?: "Connected Device",
            extraInfo = extraInfo,
            onToggleAspectRatio = {
                val nextMode = if (config.aspectRatioMode == AspectRatioMode.FIT) AspectRatioMode.FILL else AspectRatioMode.FIT
                onUpdateConfig(config.copy(aspectRatioMode = nextMode))
                lastInteractionTime = System.currentTimeMillis()
            },
            onRotateScreen = {
                val nextRotation = (config.rotationDegrees + 90) % 360
                onUpdateConfig(config.copy(rotationDegrees = nextRotation))
                lastInteractionTime = System.currentTimeMillis()
            },
            onToggleMute = {
                onUpdateConfig(config.copy(isAudioMuted = !config.isAudioMuted))
                lastInteractionTime = System.currentTimeMillis()
            },
            onToggleTouch = {
                onUpdateConfig(config.copy(isTouchControlEnabled = !config.isTouchControlEnabled))
                lastInteractionTime = System.currentTimeMillis()
            },
            onDisconnect = onDisconnect,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        )

        // Error Banner if decoder experienced error
        if (androidDiag.lastError != null && showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ErrorRed.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Decoder Error: ${androidDiag.lastError}",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}
