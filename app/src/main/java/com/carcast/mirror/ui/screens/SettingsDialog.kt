package com.carcast.mirror.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.window.Dialog
import com.carcast.mirror.engine.update.AutoUpdateWorker
import com.carcast.mirror.model.AspectRatioMode
import com.carcast.mirror.model.AudioOutputRoute
import com.carcast.mirror.model.DisplayConfig
import com.carcast.mirror.model.QualityProfile
import com.carcast.mirror.model.StreamResolution
import com.carcast.mirror.ui.theme.CardBorder
import com.carcast.mirror.ui.theme.CardBorderActive
import com.carcast.mirror.ui.theme.CardSurface
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

@Composable
fun SettingsDialog(
    config: DisplayConfig,
    onSaveConfig: (DisplayConfig) -> Unit,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE) }

    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    var selectedAspect by remember { mutableStateOf(config.aspectRatioMode) }
    var selectedQuality by remember { mutableStateOf(config.qualityProfile) }
    var showSidePanels by remember { mutableStateOf(config.showPaymentSidePanels) }
    var selectedAudioRoute by remember { mutableStateOf(config.audioOutputRoute) }
    var audioVol by remember { mutableFloatStateOf(config.audioVolume) }
    var autoBoot by remember { mutableStateOf(config.autoStartOnBoot) }
    var touchControl by remember { mutableStateOf(config.isTouchControlEnabled) }
    var devName by remember { mutableStateOf(config.broadcastName) }
    var gitRepo by remember { mutableStateOf(config.gitHubRepo) }

    // Update settings
    var autoCheckUpdates by remember { mutableStateOf(prefs.getBoolean("auto_check_updates", true)) }
    var autoDownloadUpdates by remember { mutableStateOf(prefs.getBoolean("auto_download_updates", false)) }
    var downloadWifiOnly by remember { mutableStateOf(prefs.getBoolean("download_wifi_only", true)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CardSurface)
                .border(1.dp, CardBorderActive, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CarCast Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onDismiss() }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Broadcast Device Name
                Text(
                    text = "Car LCD Broadcast Name (Shown on iPhone & Android):",
                    style = MaterialTheme.typography.labelLarge.copy(color = ElectricCyan)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = devName,
                    onValueChange = { devName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Screen Fit Mode (FIT vs FILL)
                Text(
                    text = "Car LCD Screen Fit Mode:",
                    style = MaterialTheme.typography.labelLarge.copy(color = ElectricCyan)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAspect = AspectRatioMode.FIT }
                    ) {
                        RadioButton(
                            selected = selectedAspect == AspectRatioMode.FIT,
                            onClick = { selectedAspect = AspectRatioMode.FIT },
                            colors = RadioButtonDefaults.colors(selectedColor = ElectricCyan)
                        )
                        Column {
                            Text("Fit Screen (Default)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Entire phone screen visible, original aspect ratio, centered with sidebars, no distortion.", color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAspect = AspectRatioMode.FILL }
                    ) {
                        RadioButton(
                            selected = selectedAspect == AspectRatioMode.FILL,
                            onClick = { selectedAspect = AspectRatioMode.FILL },
                            colors = RadioButtonDefaults.colors(selectedColor = ElectricCyan)
                        )
                        Column {
                            Text("Fill Screen (Center Crop)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Fills entire car widescreen LCD without stretching, centered crop.", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quality Profiles
                Text(
                    text = "Android Streaming Quality Profile:",
                    style = MaterialTheme.typography.labelLarge.copy(color = ElectricCyan)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QualityProfile.values().forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedQuality = profile }
                        ) {
                            RadioButton(
                                selected = selectedQuality == profile,
                                onClick = { selectedQuality = profile },
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricCyan)
                            )
                            Column {
                                Text(profile.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(profile.description, color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Audio Volume Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Car Speaker Volume:", color = TextPrimary, fontSize = 14.sp)
                    Text("${(audioVol * 100).toInt()}%", color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = audioVol,
                    onValueChange = { audioVol = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = Color(0xFF21262D)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Reverse Touch Control Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Reverse Touch Control from Car LCD", color = TextPrimary, fontSize = 14.sp)
                        Text("Touch the Car LCD to control connected Android phone", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = touchControl,
                        onCheckedChange = { touchControl = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Auto Start on Boot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Launch on Car Ignition (Boot)", color = TextPrimary, fontSize = 14.sp)
                    Switch(
                        checked = autoBoot,
                        onCheckedChange = { autoBoot = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Show Payment QR Side Panels Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Show Payment QR Side Panels", color = TextPrimary, fontSize = 14.sp)
                        Text(
                            "When phone is mirrored in portrait on widescreen Car LCD, displays NayaPay & Easypaisa QR codes in unused side areas (Parked only).",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = showSidePanels,
                        onCheckedChange = { showSidePanels = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Audio Output Routing
                Text(
                    text = "Audio Output Route:",
                    style = MaterialTheme.typography.labelLarge.copy(color = ElectricCyan)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioOutputRoute.values().forEach { route ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAudioRoute = route }
                        ) {
                            RadioButton(
                                selected = selectedAudioRoute == route,
                                onClick = { selectedAudioRoute = route },
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricCyan)
                            )
                            Column {
                                Text(route.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(route.description, color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- DEDICATED APP UPDATES SECTION ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D1117))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                            Text("APP UPDATES", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("v$appVersion (Installed)", color = TextSecondary, fontSize = 11.sp)
                    }

                    // GitHub Repo Input & Check Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = gitRepo,
                            onValueChange = { gitRepo = it },
                            singleLine = true,
                            label = { Text("GitHub Repo (owner/repo)", fontSize = 10.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = CardBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = onCheckUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                        ) {
                            Icon(Icons.Default.CloudDownload, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Now", color = ElectricCyan, fontSize = 12.sp)
                        }
                    }

                    // Auto-check updates toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Automatically Check for Updates", color = TextPrimary, fontSize = 13.sp)
                            Text("Background check every 24 hours & on app launch", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoCheckUpdates,
                            onCheckedChange = { autoCheckUpdates = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                        )
                    }

                    // Auto-download updates toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Download Updates in Background", color = TextPrimary, fontSize = 13.sp)
                            Text("Pre-download new versions for one-tap install", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoDownloadUpdates,
                            onCheckedChange = { autoDownloadUpdates = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                        )
                    }

                    // Wi-Fi only toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Download Over Wi-Fi Only", color = TextPrimary, fontSize = 13.sp)
                        Switch(
                            checked = downloadWifiOnly,
                            onCheckedChange = { downloadWifiOnly = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Text(text = "Cancel", color = TextSecondary)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            prefs.edit()
                                .putBoolean("auto_check_updates", autoCheckUpdates)
                                .putBoolean("auto_download_updates", autoDownloadUpdates)
                                .putBoolean("download_wifi_only", downloadWifiOnly)
                                .putString("update_repo", gitRepo.ifEmpty { "softwork-05/car-cast-" })
                                .apply()

                            if (autoCheckUpdates) {
                                AutoUpdateWorker.schedulePeriodic(context)
                            }

                            onSaveConfig(
                                config.copy(
                                    broadcastName = devName.ifEmpty { "My Car LCD" },
                                    gitHubRepo = gitRepo.ifEmpty { "softwork-05/car-cast-" },
                                    isTouchControlEnabled = touchControl,
                                    aspectRatioMode = selectedAspect,
                                    qualityProfile = selectedQuality,
                                    showPaymentSidePanels = showSidePanels,
                                    audioOutputRoute = selectedAudioRoute,
                                    audioVolume = audioVol,
                                    autoStartOnBoot = autoBoot
                                )
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text(text = "Save Settings", color = ObsidianBg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
