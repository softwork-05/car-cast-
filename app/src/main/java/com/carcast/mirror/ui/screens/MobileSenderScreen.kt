package com.carcast.mirror.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.carcast.mirror.model.QualityProfile
import com.carcast.mirror.service.CarCastAccessibilityService
import com.carcast.mirror.service.ScreenCaptureService
import com.carcast.mirror.service.SenderStatus
import com.carcast.mirror.ui.theme.CardBorder
import com.carcast.mirror.ui.theme.CardBorderActive
import com.carcast.mirror.ui.theme.CardSurface
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ErrorRed
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

@Composable
fun MobileSenderScreen(
    onSwitchToReceiver: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val senderState by ScreenCaptureService.senderState.collectAsState()

    val prefs = remember { context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE) }
    val savedIp = remember { prefs.getString("last_target_ip", "192.168.43.1") ?: "192.168.43.1" }
    var carIp by remember { mutableStateOf(savedIp) }

    val savedProfileName = remember { prefs.getString("sender_quality_profile", QualityProfile.AUTO.name) ?: QualityProfile.AUTO.name }
    var selectedProfile by remember {
        mutableStateOf(
            try {
                QualityProfile.valueOf(savedProfileName)
            } catch (_: Exception) {
                QualityProfile.AUTO
            }
        )
    }

    val isBroadcasting = senderState.status == SenderStatus.STREAMING ||
            senderState.status == SenderStatus.CONNECTING ||
            senderState.status == SenderStatus.CONNECTED ||
            senderState.status == SenderStatus.ENCODING

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val cleanIp = carIp.trim()
            prefs.edit()
                .putString("last_target_ip", cleanIp)
                .putString("sender_quality_profile", selectedProfile.name)
                .apply()

            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_CAR_IP, cleanIp)
                putExtra(ScreenCaptureService.EXTRA_QUALITY_PROFILE, selectedProfile.name)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or denied, launch projection capture
        val projMgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projMgr.createScreenCaptureIntent())
    }

    fun startCaptureFlow() {
        val cleanIp = carIp.trim()
        if (cleanIp.isNotBlank()) {
            prefs.edit().putString("last_target_ip", cleanIp).apply()

            val hasAudioPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasAudioPerm) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                val projMgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(projMgr.createScreenCaptureIntent())
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(24.dp))
                .background(CardSurface)
                .border(1.dp, CardBorderActive, RoundedCornerShape(24.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Mode Switcher Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF21262D))
                        .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                        .clickable { onSwitchToReceiver() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "Switch to Car LCD Mode",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Switch to Car LCD Mode",
                        color = ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Icon & Header
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(ElectricCyan.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(30.dp)
                )
            }

            Text(
                text = "CarCast Phone Sender",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            // Connection Status Badge
            val statusColor = when (senderState.status) {
                SenderStatus.STREAMING -> if (senderState.framesSent > 0) NeonGreen else ElectricCyan
                SenderStatus.CONNECTING, SenderStatus.CONNECTED, SenderStatus.ENCODING -> ElectricCyan
                SenderStatus.FAILED -> ErrorRed
                SenderStatus.IDLE -> TextSecondary
            }
            val statusText = when (senderState.status) {
                SenderStatus.STREAMING -> if (senderState.framesSent > 0) "● STREAMING LIVE" else "STARTING STREAM..."
                SenderStatus.CONNECTING -> "CONNECTING TO CAR LCD..."
                SenderStatus.CONNECTED -> "CONNECTED • LAUNCHING ENCODER"
                SenderStatus.ENCODING -> "ENCODER ACTIVE • INITIALIZING"
                SenderStatus.FAILED -> "CONNECTION FAILED"
                SenderStatus.IDLE -> "READY TO MIRROR"
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .border(1.dp, statusColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }

            // Car IP Input Field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Car LCD Receiver IP Address:",
                    color = ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = carIp,
                    onValueChange = { carIp = it },
                    singleLine = true,
                    enabled = !isBroadcasting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Quality Profile Selector Chips
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Mirror Quality Profile:",
                    color = ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QualityProfile.values().forEach { profile ->
                        FilterChip(
                            selected = selectedProfile == profile,
                            onClick = {
                                if (!isBroadcasting) {
                                    selectedProfile = profile
                                    prefs.edit().putString("sender_quality_profile", profile.name).apply()
                                }
                            },
                            label = {
                                Text(
                                    text = when (profile) {
                                        QualityProfile.AUTO -> "Auto"
                                        QualityProfile.HD -> "720p HD"
                                        QualityProfile.FULL_HD -> "1080p FHD"
                                        QualityProfile.SMOOTH -> "60 FPS"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedProfile == profile) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan,
                                selectedLabelColor = ObsidianBg,
                                containerColor = Color(0xFF21262D),
                                labelColor = TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedProfile == profile,
                                borderColor = if (selectedProfile == profile) ElectricCyan else CardBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Live Diagnostics Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1117))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TRANSMISSION & STREAM DIAGNOSTICS",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                DiagnosticRow(
                    label = "Target Receiver:",
                    value = if (senderState.targetIp.isNotBlank()) "ws://${senderState.targetIp}:${senderState.targetPort}" else "Not connected",
                    valueColor = if (senderState.targetIp.isNotBlank()) ElectricCyan else TextSecondary
                )

                DiagnosticRow(
                    label = "Quality Profile:",
                    value = senderState.qualityProfileName,
                    valueColor = ElectricCyan
                )

                DiagnosticRow(
                    label = "Hardware Encoder:",
                    value = if (senderState.encoderActive) "${senderState.resolution} (H.264)" else "Idle",
                    valueColor = if (senderState.encoderActive) NeonGreen else TextSecondary
                )

                DiagnosticRow(
                    label = "Live Frame Rate:",
                    value = if (senderState.fps > 0) "${senderState.fps} FPS" else if (senderState.encoderActive) "30 FPS" else "0 FPS",
                    valueColor = if (senderState.fps > 0) NeonGreen else TextSecondary
                )

                DiagnosticRow(
                    label = "Live Bitrate:",
                    value = if (senderState.bitrateKbps > 0) "${senderState.bitrateKbps} kbps" else "0 kbps",
                    valueColor = if (senderState.bitrateKbps > 0) NeonGreen else TextSecondary
                )

                DiagnosticRow(
                    label = "Frames Sent / Dropped:",
                    value = "${senderState.framesSent} / ${senderState.droppedFrames}",
                    valueColor = if (senderState.droppedFrames == 0L) NeonGreen else TextSecondary
                )

                DiagnosticRow(
                    label = "Internal Audio Mirroring:",
                    value = if (senderState.audioCaptureActive) "Active • 44.1kHz PCM (Internal)" else "Muted / Inactive",
                    valueColor = if (senderState.audioCaptureActive) NeonGreen else TextSecondary
                )

                DiagnosticRow(
                    label = "Reverse Touch Control:",
                    value = if (senderState.reverseTouchActive || CarCastAccessibilityService.isRunning) "Active (Car LCD Control)" else "Disabled (Needs Permission)",
                    valueColor = if (senderState.reverseTouchActive || CarCastAccessibilityService.isRunning) NeonGreen else TextSecondary
                )
            }

            // Reverse Touch Permission Card
            val isA11yActive = CarCastAccessibilityService.isRunning || CarCastAccessibilityService.isServiceEnabled(context)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1117))
                    .border(1.dp, if (isA11yActive) NeonGreen.copy(alpha = 0.5f) else Color(0xFF30363D), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CAR SCREEN REVERSE TOUCH",
                        color = if (isA11yActive) NeonGreen else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isA11yActive) NeonGreen.copy(alpha = 0.15f) else Color(0xFF30363D))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isA11yActive) "ENABLED" else "DISABLED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isA11yActive) NeonGreen else TextSecondary
                        )
                    }
                }

                Text(
                    text = if (isA11yActive)
                        "Reverse Touch is active! Touching the Car LCD will directly control this phone (tap, swipe, scroll, drag, long press)."
                    else
                        "To control this phone from your Car LCD screen, enable 'CarCast Reverse Touch' in Android Accessibility settings.",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                if (!isA11yActive) {
                    Button(
                        onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enable Reverse Touch in Settings", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error Banner
            AnimatedVisibility(visible = senderState.errorMessage != null) {
                senderState.errorMessage?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ErrorRed.copy(alpha = 0.15f))
                            .border(1.dp, ErrorRed, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Error, null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                            Text(
                                text = err,
                                color = ErrorRed,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Button: Start / Stop Mirroring
            if (isBroadcasting) {
                Button(
                    onClick = {
                        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Stop, null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Mirroring",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            } else {
                Button(
                    onClick = { startCaptureFlow() },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Cast, null, tint = ObsidianBg)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Mirroring to Car LCD",
                        color = ObsidianBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
