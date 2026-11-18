package com.carcast.mirror.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carcast.mirror.model.AspectRatioMode
import com.carcast.mirror.ui.theme.CardBorderActive
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ErrorRed
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

@Composable
fun FloatingControlPill(
    visible: Boolean,
    aspectRatioMode: AspectRatioMode,
    isMuted: Boolean,
    isTouchEnabled: Boolean,
    isTouchSupported: Boolean = true,
    clientName: String,
    extraInfo: String? = null,
    onToggleAspectRatio: () -> Unit,
    onRotateScreen: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleTouch: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xE6161B22))
                .border(1.dp, CardBorderActive, RoundedCornerShape(30.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Device Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF21262D))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = clientName,
                    fontSize = 12.sp,
                    color = ElectricCyan
                )
            }

            if (!extraInfo.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF21262D))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = extraInfo,
                        fontSize = 11.sp,
                        color = NeonGreen
                    )
                }
            }

            // Reverse Touch Control Toggle (Only active if touch is supported by the client protocol)
            if (isTouchSupported) {
                PillIconButton(
                    icon = Icons.Default.TouchApp,
                    tooltip = if (isTouchEnabled) "Touch: ON" else "Touch: OFF",
                    tint = if (isTouchEnabled) NeonGreen else TextSecondary,
                    onClick = onToggleTouch
                )
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF21262D))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Display Only",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }

            // Aspect Ratio Toggle
            PillIconButton(
                icon = if (aspectRatioMode == AspectRatioMode.FIT) Icons.Default.FitScreen else Icons.Default.AspectRatio,
                tooltip = if (aspectRatioMode == AspectRatioMode.FIT) "Fit" else "Fill",
                onClick = onToggleAspectRatio
            )

            // Rotate Screen (0 -> 90 -> 180 -> 270)
            PillIconButton(
                icon = Icons.AutoMirrored.Filled.RotateRight,
                tooltip = "Rotate",
                onClick = onRotateScreen
            )

            // Audio Mute Toggle
            PillIconButton(
                icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                tooltip = if (isMuted) "Unmute" else "Mute",
                tint = if (isMuted) ErrorRed else TextPrimary,
                onClick = onToggleMute
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Disconnect Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(ErrorRed.copy(alpha = 0.2f))
                    .clickable { onDisconnect() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Disconnect",
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PillIconButton(
    icon: ImageVector,
    tooltip: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0xFF21262D))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tooltip,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}
