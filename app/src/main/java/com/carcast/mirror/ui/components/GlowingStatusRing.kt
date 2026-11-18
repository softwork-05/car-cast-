package com.carcast.mirror.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carcast.mirror.model.ConnectionStatus
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ElectricCyanGlow
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.NeonGreenGlow
import com.carcast.mirror.ui.theme.WarningAmber

@Composable
fun GlowingStatusRing(
    status: ConnectionStatus,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (solidColor, glowColor) = when (status) {
        ConnectionStatus.IDLE -> Pair(ElectricCyan, ElectricCyanGlow)
        ConnectionStatus.CONNECTING -> Pair(WarningAmber, WarningAmber.copy(alpha = 0.3f))
        ConnectionStatus.STREAMING -> Pair(NeonGreen, NeonGreenGlow)
        ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> Pair(Color.Gray, Color.DarkGray)
    }

    Box(
        modifier = modifier.size(size * 1.5f),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing aura
        Box(
            modifier = Modifier
                .size(size)
                .scale(pulseScale)
                .background(glowColor.copy(alpha = pulseAlpha), shape = CircleShape)
        )

        // Middle ring
        Box(
            modifier = Modifier
                .size(size * 0.9f)
                .border(2.dp, solidColor.copy(alpha = 0.6f), CircleShape)
        )

        // Core solid dot
        Box(
            modifier = Modifier
                .size(size * 0.45f)
                .background(solidColor, shape = CircleShape)
        )
    }
}
