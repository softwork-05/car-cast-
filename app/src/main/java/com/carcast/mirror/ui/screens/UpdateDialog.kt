package com.carcast.mirror.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.carcast.mirror.engine.update.GitHubUpdateManager
import com.carcast.mirror.engine.update.UpdateCheckResult
import com.carcast.mirror.engine.update.UpdateStatus
import com.carcast.mirror.ui.theme.CardBorderActive
import com.carcast.mirror.ui.theme.CardSurface
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ErrorRed
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(
    gitHubRepo: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { GitHubUpdateManager(context) }

    var updateStatus by remember { mutableStateOf(UpdateStatus.CHECKING) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    fun runUpdateCheck() {
        updateStatus = UpdateStatus.CHECKING
        errorMessage = null
        scope.launch {
            val result = updater.checkForUpdate(gitHubRepo)
            result.fold(
                onSuccess = { res ->
                    updateResult = res
                    downloadedFile = res.downloadedApkFile
                    updateStatus = res.status
                },
                onFailure = { err ->
                    errorMessage = GitHubUpdateManager.sanitizeErrorMessage(err.message)
                    updateStatus = UpdateStatus.CHECK_FAILED
                }
            )
        }
    }

    fun startDownload(targetDownloadUrl: String) {
        updateStatus = UpdateStatus.DOWNLOADING
        downloadProgress = 0
        errorMessage = null
        scope.launch {
            var urlToUse = targetDownloadUrl
            if (urlToUse.isBlank()) {
                val refresh = updater.checkForUpdate(gitHubRepo)
                urlToUse = refresh.getOrNull()?.downloadUrl ?: ""
            }

            if (urlToUse.isBlank()) {
                errorMessage = "Could not locate a valid APK download link on GitHub."
                updateStatus = UpdateStatus.DOWNLOAD_FAILED
                return@launch
            }

            val dlResult = updater.downloadApk(urlToUse) { p ->
                downloadProgress = p
            }
            dlResult.fold(
                onSuccess = { file ->
                    downloadedFile = file
                    updateStatus = UpdateStatus.READY_TO_INSTALL
                },
                onFailure = { err ->
                    errorMessage = GitHubUpdateManager.sanitizeErrorMessage(err.message)
                    updateStatus = UpdateStatus.DOWNLOAD_FAILED
                }
            )
        }
    }

    fun openInBrowser(url: String?) {
        try {
            val cleanUrl = url?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?: "https://github.com/${gitHubRepo.trim()}/releases/latest"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        runUpdateCheck()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CardSurface)
                .border(1.dp, CardBorderActive, RoundedCornerShape(20.dp))
                .padding(22.dp)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Software Updates",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onDismiss() }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                when (updateStatus) {
                    UpdateStatus.CHECKING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(vertical = 20.dp)
                        ) {
                            CircularProgressIndicator(
                                color = ElectricCyan,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Column {
                                Text(
                                    text = "Checking for updates...",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Connecting to https://github.com/${gitHubRepo.trim()}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    UpdateStatus.CHECK_FAILED -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, ErrorRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "Unable to Check for Updates",
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Text(
                                text = errorMessage ?: "Could not establish connection to the update server.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { runUpdateCheck() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", color = ElectricCyan, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { openInBrowser("https://github.com/${gitHubRepo.trim()}/releases") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2128)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open GitHub Releases", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    UpdateStatus.DOWNLOAD_FAILED -> {
                        val res = updateResult
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, ErrorRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "Update Download Failed",
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Text(
                                text = errorMessage ?: "The update could not be downloaded from the server.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    startDownload(res?.downloadUrl ?: "")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = ObsidianBg, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry Download", color = ObsidianBg, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val browserUrl = res?.downloadUrl?.takeIf { it.isNotBlank() }
                                        ?: "https://github.com/${gitHubRepo.trim()}/releases/latest"
                                    openInBrowser(browserUrl)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open in Browser", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    UpdateStatus.UP_TO_DATE -> {
                        val res = updateResult
                        val instName = res?.installedVersionName ?: updater.getInstalledVersionName()
                        val instCode = res?.installedVersionCode ?: updater.getInstalledVersionCode()
                        val latName = res?.latestVersionName ?: instName
                        val checkTime = GitHubUpdateManager.formatTimestamp(res?.checkTimestamp ?: System.currentTimeMillis())

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = NeonGreen,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(
                                        text = "You're Up to Date!",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        text = "CarCast Mirror is running the latest release.",
                                        color = NeonGreen,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            UpdateInfoRow(label = "Installed Version:", value = "v$instName (Build $instCode)")
                            UpdateInfoRow(label = "Latest Available:", value = "v$latName")
                            UpdateInfoRow(label = "Last Checked:", value = checkTime)
                            UpdateInfoRow(label = "Status:", value = "No update required", valueColor = NeonGreen)

                            Spacer(modifier = Modifier.height(6.dp))

                            Button(
                                onClick = { runUpdateCheck() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check for Updates", color = ElectricCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    UpdateStatus.UPDATE_AVAILABLE -> {
                        val res = updateResult!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, CardBorderActive, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "🎉 New Update Available",
                                color = NeonGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )

                            UpdateInfoRow(label = "Current Version:", value = "v${res.installedVersionName} (Build ${res.installedVersionCode})")
                            UpdateInfoRow(label = "New Version:", value = "v${res.latestVersionName} (Build ${res.latestVersionCode})", valueColor = NeonGreen)
                            UpdateInfoRow(label = "Download Size:", value = "${String.format("%.1f", res.apkSizeMb)} MB")

                            Text(
                                text = "Release Notes:\n${res.releaseNotes}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianBg, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Button(
                                onClick = {
                                    startDownload(res.downloadUrl)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, null, tint = ObsidianBg, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download Update", color = ObsidianBg, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    openInBrowser(res.downloadUrl)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderActive),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download via Browser", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    UpdateStatus.DOWNLOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, CardBorderActive, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Downloading update: $downloadProgress%", color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ElectricCyan,
                                trackColor = ObsidianBg
                            )
                            Text("Please wait while the update APK is downloaded and verified...", color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    UpdateStatus.READY_TO_INSTALL -> {
                        val file = downloadedFile ?: updater.getPendingDownloadedApk()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22))
                                .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.SystemUpdate, null, tint = NeonGreen, modifier = Modifier.size(26.dp))
                                Column {
                                    Text("Update Ready to Install", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("APK downloaded & verified successfully.", color = TextSecondary, fontSize = 11.sp)
                                }
                            }

                            Text(
                                text = "Tap the button below to launch the official Android Package Installer and complete the update.",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    if (file != null && file.exists()) {
                                        updater.installApk(file)
                                        onDismiss()
                                    } else {
                                        runUpdateCheck()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install Update Now", color = ObsidianBg, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Text("Close", color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateInfoRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(text = value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
