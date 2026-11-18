package com.carcast.mirror.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carcast.mirror.engine.NetworkInfoState
import com.carcast.mirror.engine.airplay.AirPlayDebugLogger
import com.carcast.mirror.model.ConnectionStatus
import com.carcast.mirror.model.DisplayConfig
import com.carcast.mirror.ui.components.AutomotiveCard
import com.carcast.mirror.ui.components.GlowingStatusRing
import com.carcast.mirror.ui.components.InstructionStep
import com.carcast.mirror.ui.components.QRCodeBitmapGenerator
import com.carcast.mirror.ui.components.StepInstructionCard
import com.carcast.mirror.ui.theme.CardBorder
import com.carcast.mirror.ui.theme.CardBorderActive
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    status: ConnectionStatus,
    networkInfo: NetworkInfoState,
    displayConfig: DisplayConfig,
    onOpenSettings: () -> Unit,
    onOpenUpdates: () -> Unit,
    onEditDeviceName: () -> Unit,
    onSwitchToSender: () -> Unit,
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLogDialogOpen by remember { mutableStateOf(false) }
    var isPaymentDialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("carcast_prefs", android.content.Context.MODE_PRIVATE) }
    val hasUpdate = remember(status) { prefs.getBoolean("update_available", false) }
    val portalUrl = "http://${networkInfo.ipAddress}:8088"
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(networkInfo.ipAddress) {
        if (networkInfo.ipAddress != "0.0.0.0") {
            qrBitmap = QRCodeBitmapGenerator.generate(portalUrl, 200)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val isCompact = screenWidth < 580.dp
        val isShortHeight = screenHeight < 500.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 10.dp else 14.dp)
        ) {
            // --- 1. Top Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Branding: Car icon + App Name + Engine Badge
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(ElectricCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = if (isCompact && screenWidth < 380.dp) "CarCast" else "CarCast Mirror",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Engine Badge - reflects real server listening state
                    val isServerListening by remember { AirPlayDebugLogger.isServerListening }
                    val serverStatusText by remember { AirPlayDebugLogger.serverStatusText }
                    val badgeColor = if (isServerListening) NeonGreen else Color(0xFFFF5252)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF161B22))
                            .border(1.dp, if (isServerListening) CardBorderActive else CardBorder, RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(badgeColor, CircleShape)
                            )
                            Text(
                                text = if (isCompact) {
                                    if (isServerListening) serverStatusText else "OFFLINE"
                                } else {
                                    if (isServerListening) "AIRPLAY: $serverStatusText • READY" else "AIRPLAY SERVER OFFLINE ($serverStatusText)"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Top Actions: Mode Switch, Bug Logger, Update Button & Settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Mode Switcher (Phone Mode)
                    if (isCompact) {
                        // Compact Icon Button for small phone screens
                        IconButton(
                            onClick = onSwitchToSender,
                            modifier = Modifier
                                .size(36.dp)
                                .background(ElectricCyan.copy(alpha = 0.15f), CircleShape)
                                .border(1.dp, CardBorderActive, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = "Switch to Phone Mirror Mode",
                                tint = ElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        // Extended Pill with text for landscape / car displays
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF21262D))
                                .border(1.dp, CardBorderActive, RoundedCornerShape(20.dp))
                                .clickable { onSwitchToSender() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = "Switch to Phone Mirror Mode",
                                tint = ElectricCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Phone Mode",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Scan to Pay Button (Prominent QR / Payment action)
                    if (isCompact) {
                        IconButton(
                            onClick = { isPaymentDialogOpen = true },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF1E293B), CircleShape)
                                .border(1.dp, Color(0xFFFF9100), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Scan to Pay",
                                tint = Color(0xFFFF9100),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFFFF9100), RoundedCornerShape(20.dp))
                                .clickable { isPaymentDialogOpen = true }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Scan to Pay",
                                tint = Color(0xFFFF9100),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Scan to Pay",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Debug Log Viewer Button (Bug icon)
                    IconButton(
                        onClick = { isLogDialogOpen = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF161B22), CircleShape)
                            .border(1.dp, CardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "AirPlay Handshake Logs",
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Update Button
                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(
                            onClick = onOpenUpdates,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF161B22), CircleShape)
                                .border(1.dp, if (hasUpdate) NeonGreen else CardBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Check GitHub Updates",
                                tint = if (hasUpdate) NeonGreen else ElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (hasUpdate) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(NeonGreen, CircleShape)
                                    .border(1.dp, ObsidianBg, CircleShape)
                            )
                        }
                    }

                    // Settings Action
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF161B22), CircleShape)
                            .border(1.dp, CardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- 2. Main Content (Responsive: Column in Portrait, Row in Landscape) ---
            if (isPortrait) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Connection Status & Network Info Card (Portrait)
                    StatusAndNetworkCard(
                        status = status,
                        networkInfo = networkInfo,
                        displayConfig = displayConfig,
                        portalUrl = portalUrl,
                        qrBitmap = qrBitmap,
                        onEditDeviceName = onEditDeviceName,
                        onOpenPayment = { isPaymentDialogOpen = true }
                    )

                    // iPhone Card (Portrait)
                    StepInstructionCard(
                        platformTitle = "iPhone / iPad (AirPlay)",
                        platformIcon = Icons.Default.PhoneIphone,
                        badgeColor = Color(0xFF00E5FF),
                        steps = listOf(
                            InstructionStep(1, Icons.Default.Wifi, "Connect iPhone to '${networkInfo.ssid}'"),
                            InstructionStep(2, Icons.Default.TouchApp, "Swipe down to open Control Center"),
                            InstructionStep(3, Icons.Default.DirectionsCar, "Tap Screen Mirroring -> Select '${displayConfig.broadcastName}'")
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Android Card (Portrait)
                    StepInstructionCard(
                        platformTitle = "Android (CarCast Phone Mode)",
                        platformIcon = Icons.Default.PhoneAndroid,
                        badgeColor = NeonGreen,
                        steps = listOf(
                            InstructionStep(1, Icons.Default.PhoneAndroid, "Open CarCast on phone -> Tap 'Phone Mode'"),
                            InstructionStep(2, Icons.Default.Wifi, "Enter Car LCD IP: ${networkInfo.ipAddress}"),
                            InstructionStep(3, Icons.Default.Cast, "Tap Start Mirroring • 60 FPS live screen & touch")
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                // Landscape Mode (Widescreen Car LCD & Horizontal Phones)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Panel: Connection Status & Network Info Card
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                    ) {
                        StatusAndNetworkCard(
                            status = status,
                            networkInfo = networkInfo,
                            displayConfig = displayConfig,
                            portalUrl = portalUrl,
                            qrBitmap = qrBitmap,
                            onEditDeviceName = onEditDeviceName,
                            onOpenPayment = { isPaymentDialogOpen = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isShortHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        )
                    }

                    // Right Panel: Platform Instructions (iPhone & Android)
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .then(if (isShortHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StepInstructionCard(
                            platformTitle = "iPhone / iPad (AirPlay)",
                            platformIcon = Icons.Default.PhoneIphone,
                            badgeColor = Color(0xFF00E5FF),
                            steps = listOf(
                                InstructionStep(1, Icons.Default.Wifi, "Connect iPhone to '${networkInfo.ssid}'"),
                                InstructionStep(2, Icons.Default.TouchApp, "Swipe down to open Control Center"),
                                InstructionStep(3, Icons.Default.DirectionsCar, "Tap Screen Mirroring -> Select '${displayConfig.broadcastName}'")
                            ),
                            modifier = if (isShortHeight) Modifier.fillMaxWidth() else Modifier.weight(1f)
                        )

                        StepInstructionCard(
                            platformTitle = "Android (CarCast Phone Mode)",
                            platformIcon = Icons.Default.PhoneAndroid,
                            badgeColor = NeonGreen,
                            steps = listOf(
                                InstructionStep(1, Icons.Default.PhoneAndroid, "Open CarCast on phone -> Tap 'Phone Mode'"),
                                InstructionStep(2, Icons.Default.Wifi, "Enter Car LCD IP: ${networkInfo.ipAddress}"),
                                InstructionStep(3, Icons.Default.Cast, "Tap Start Mirroring • 60 FPS live screen & touch")
                            ),
                            modifier = if (isShortHeight) Modifier.fillMaxWidth() else Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Live Handshake Log Viewer Dialog
        if (isLogDialogOpen) {
            AlertDialog(
                onDismissRequest = { isLogDialogOpen = false },
                containerColor = Color(0xFF161B22),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .widthIn(max = 560.dp),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AirPlay Handshake Logs",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Clear",
                            color = ElectricCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { AirPlayDebugLogger.clear() }
                                .padding(4.dp)
                        )
                    }
                },
                text = {
                    val logs = AirPlayDebugLogger.logs
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "No log messages recorded yet.\nConnect an iPhone or trigger casting to see handshake output.",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            logs.forEach { logLine ->
                                val color = when {
                                    logLine.contains("ERROR") || logLine.contains("FAIL") || logLine.contains("EXCEPTION") || logLine.contains("FATAL") -> Color(0xFFFF5252)
                                    logLine.contains("SUCCESS") || logLine.contains("READY") || logLine.contains("ONLINE") -> NeonGreen
                                    logLine.contains("WARN") || logLine.contains("NOTICE") -> Color(0xFFFFD54F)
                                    logLine.contains("RTSP:METHOD") || logLine.contains("STREAM:START") -> ElectricCyan
                                    else -> TextPrimary
                                }
                                Text(
                                    text = logLine,
                                    color = color,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { isLogDialogOpen = false },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text(text = "Close", color = ObsidianBg, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Live Payment QR Dialog (NayaPay & Easypaisa)
        if (isPaymentDialogOpen) {
            PaymentDialog(onDismiss = { isPaymentDialogOpen = false })
        }
    }
}

@Composable
private fun StatusAndNetworkCard(
    status: ConnectionStatus,
    networkInfo: NetworkInfoState,
    displayConfig: DisplayConfig,
    portalUrl: String,
    qrBitmap: Bitmap?,
    onEditDeviceName: () -> Unit,
    onOpenPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    AutomotiveCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = CardBorderActive.copy(alpha = 0.6f),
        contentPadding = 14.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status Badge + Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlowingStatusRing(status = status, size = 42.dp)
                Column {
                    Text(
                        text = when (status) {
                            ConnectionStatus.IDLE -> "Ready to Connect"
                            ConnectionStatus.CONNECTING -> "Connecting..."
                            ConnectionStatus.STREAMING -> "Mirroring Active"
                            ConnectionStatus.DISCONNECTED -> "Device Disconnected"
                            ConnectionStatus.ERROR -> "Receiver Error"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = if (networkInfo.isConnected) "Receiver is broadcasting" else "Please connect to Wi-Fi/Hotspot",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary, fontSize = 12.sp)
                    )
                }
            }

            // Device Name & Wi-Fi Details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F141C))
                    .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Wi-Fi / Hotspot Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (networkInfo.isHotspot) Icons.Default.WifiTethering else Icons.Default.Wifi,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Network:",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = networkInfo.ssid,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Broadcast Name Row (with edit icon)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Device Name:",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { onEditDeviceName() }
                    ) {
                        Text(
                            text = displayConfig.broadcastName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = ElectricCyan,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // QR Code & Direct IP Fast-Pair
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // QR Code Graphic
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Android Cast QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                // IP and Quick Instruction
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "HOTSPOT COMPATIBLE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen
                        )
                    }
                    Text(
                        text = "Scan or browse on Android:",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = portalUrl,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Quick "Scan to Pay" Action Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF131B2A))
                    .border(1.dp, Color(0xFFFF9100).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable { onOpenPayment() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = Color(0xFFFF9100),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Scan to Pay (NayaPay & Easypaisa)",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Open QR ➔",
                    color = ElectricCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
