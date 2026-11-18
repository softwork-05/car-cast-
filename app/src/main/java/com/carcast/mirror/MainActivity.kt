package com.carcast.mirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.carcast.mirror.engine.AppMode
import com.carcast.mirror.engine.ModeManager
import com.carcast.mirror.engine.update.AutoUpdateWorker
import com.carcast.mirror.model.ConnectionStatus
import com.carcast.mirror.service.CarCastService
import com.carcast.mirror.ui.screens.HomeScreen
import com.carcast.mirror.ui.screens.MirroringScreen
import com.carcast.mirror.ui.screens.MobileSenderScreen
import com.carcast.mirror.ui.screens.SettingsDialog
import com.carcast.mirror.ui.screens.UpdateDialog
import com.carcast.mirror.ui.theme.CarCastMirrorTheme
import com.carcast.mirror.ui.theme.CardSurface
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var carCastService: CarCastService? = null
    private var isBound = false
    private val openUpdateDialogState = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? CarCastService.LocalBinder
            val service = localBinder?.getService()
            carCastService = service
            isBound = true
            service?.let { viewModel.bindService(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carCastService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (ignored: Exception) {}

        // Request Notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize central ModeManager
        ModeManager.init(this)

        // Schedule periodic update checks via WorkManager (every 24 hours)
        AutoUpdateWorker.schedulePeriodic(this)

        val prefs = getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_check_updates", true)) {
            AutoUpdateWorker.checkNow(this)
        }

        if (intent?.getBooleanExtra("OPEN_UPDATE_DIALOG", false) == true) {
            openUpdateDialogState.value = true
        }

        setContent {
            CarCastMirrorTheme {
                val appMode by ModeManager.currentMode.collectAsState()
                val status by viewModel.connectionStatus.collectAsState()
                val client by viewModel.connectedClient.collectAsState()
                val netInfo by viewModel.networkInfo.collectAsState()
                val config by viewModel.displayConfig.collectAsState()
                val isSettingsOpen by viewModel.isSettingsDialogOpen.collectAsState()

                var isUpdateDialogOpen by openUpdateDialogState
                var isEditNameDialogOpen by remember { mutableStateOf(false) }
                var tempDeviceName by remember { mutableStateOf(config.broadcastName) }

                val savedName = remember { prefs.getString("broadcast_name", "My Car LCD") ?: "My Car LCD" }
                LaunchedEffect(Unit) {
                    viewModel.initBroadcastName(savedName)
                }

                // Automatically manage receiver service based on AppMode (Do not force screen orientation)
                LaunchedEffect(appMode) {
                    if (appMode == AppMode.RECEIVER) {
                        startAndBindService()
                    } else {
                        // SENDER MODE: Unbind receiver service so ports and mDNS are free
                        unbindReceiverService()
                    }
                }

                // Manage full-screen immersion: hide system bars only during active video mirroring
                LaunchedEffect(status) {
                    if (status == ConnectionStatus.STREAMING) {
                        hideSystemBars()
                    } else {
                        showSystemBars()
                    }
                }

                if (appMode == AppMode.SENDER) {
                    // Mobile Phone Sender View (Casts phone screen & internal audio to Car LCD)
                    MobileSenderScreen(
                        onSwitchToReceiver = {
                            ModeManager.switchMode(this@MainActivity, AppMode.RECEIVER)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Car Head Unit Receiver View
                    Crossfade(targetState = status, label = "screen_transition") { currentStatus ->
                        if (currentStatus == ConnectionStatus.STREAMING && carCastService != null) {
                            MirroringScreen(
                                service = carCastService!!,
                                client = client,
                                config = config,
                                onUpdateConfig = { newConfig ->
                                    viewModel.updateConfig(newConfig, carCastService)
                                },
                                onDisconnect = {
                                    viewModel.disconnectStream(carCastService)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            HomeScreen(
                                status = currentStatus,
                                networkInfo = netInfo,
                                displayConfig = config,
                                onOpenSettings = {
                                    viewModel.isSettingsDialogOpen.value = true
                                },
                                onOpenUpdates = {
                                    isUpdateDialogOpen = true
                                },
                                onEditDeviceName = {
                                    tempDeviceName = config.broadcastName
                                    isEditNameDialogOpen = true
                                },
                                onSwitchToSender = {
                                    ModeManager.switchMode(this@MainActivity, AppMode.SENDER)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Settings Dialog
                if (isSettingsOpen) {
                    SettingsDialog(
                        config = config,
                        onSaveConfig = { updated ->
                            viewModel.updateConfig(updated, carCastService)
                        },
                        onCheckUpdate = {
                            viewModel.isSettingsDialogOpen.value = false
                            isUpdateDialogOpen = true
                        },
                        onDismiss = {
                            viewModel.isSettingsDialogOpen.value = false
                        }
                    )
                }

                // GitHub Auto-Update Dialog
                if (isUpdateDialogOpen) {
                    UpdateDialog(
                        gitHubRepo = config.gitHubRepo,
                        onDismiss = { isUpdateDialogOpen = false }
                    )
                }

                // Rename Device Dialog
                if (isEditNameDialogOpen) {
                    AlertDialog(
                        onDismissRequest = { isEditNameDialogOpen = false },
                        containerColor = CardSurface,
                        title = {
                            Text(text = "Change Car Broadcast Name", color = TextPrimary)
                        },
                        text = {
                            OutlinedTextField(
                                value = tempDeviceName,
                                onValueChange = { tempDeviceName = it },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = ElectricCyan
                                )
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val trimmed = tempDeviceName.trim()
                                    if (trimmed.isNotBlank()) {
                                        prefs.edit().putString("broadcast_name", trimmed).apply()
                                        viewModel.updateDeviceName(trimmed, carCastService)
                                    }
                                    isEditNameDialogOpen = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                            ) {
                                Text(text = "Save", color = ObsidianBg)
                            }
                        },
                        dismissButton = {
                            Button(
                                onClick = { isEditNameDialogOpen = false },
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianBg)
                            ) {
                                Text(text = "Cancel", color = TextSecondary)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val updateManager = com.carcast.mirror.engine.update.GitHubUpdateManager(this)
            updateManager.getPendingDownloadedApk()
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("OPEN_UPDATE_DIALOG", false) == true) {
            openUpdateDialogState.value = true
        }
    }

    private fun startAndBindService() {
        if (isBound) return
        try {
            val serviceIntent = Intent(this, CarCastService::class.java).apply {
                action = CarCastService.ACTION_START_RECEIVER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("CarCast", "Failed to start receiver service", e)
        }
    }

    private fun unbindReceiverService() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (ignored: Exception) {}
            isBound = false
            carCastService = null
        }
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
            }
        } catch (ignored: Exception) {}
    }

    private fun showSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindReceiverService()
    }
}
