package com.carcast.mirror.engine.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.carcast.mirror.MainActivity
import com.carcast.mirror.R
import java.io.File
import java.util.concurrent.TimeUnit

class AutoUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CarCast_AutoUpdate"
        const val UPDATE_CHANNEL_ID = "carcast_updates_channel"
        const val NOTIFICATION_ID_UPDATE_READY = 2001
        const val NOTIFICATION_ID_UPDATE_AVAILABLE = 2002
        private const val WORK_NAME_PERIODIC = "carcast_periodic_update_check"

        fun schedulePeriodic(context: Context) {
            val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
            val wifiOnly = prefs.getBoolean("download_wifi_only", true)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<AutoUpdateWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.i(TAG, "Scheduled periodic update check with WorkManager (24h interval, wifiOnly=$wifiOnly)")
        }

        fun checkNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
            Log.i(TAG, "Enqueued immediate one-time update check")
        }
    }

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
        val autoCheck = prefs.getBoolean("auto_check_updates", true)
        if (!autoCheck) {
            Log.i(TAG, "Automatic update check disabled in settings")
            return Result.success()
        }

        val autoDownload = prefs.getBoolean("auto_download_updates", false)
        val repo = prefs.getString("update_repo", GitHubUpdateManager.DEFAULT_REPO) ?: GitHubUpdateManager.DEFAULT_REPO

        val updateManager = GitHubUpdateManager(context)
        Log.i(TAG, "Running scheduled update check against repo: $repo")

        val checkResult = updateManager.checkForUpdate(repo)

        checkResult.onSuccess { update ->
            createNotificationChannel()

            when (update.status) {
                UpdateStatus.UP_TO_DATE -> {
                    Log.i(TAG, "App is completely up to date (v${update.installedVersionName})")
                    updateManager.clearUpdateNotifications()
                }

                UpdateStatus.READY_TO_INSTALL -> {
                    Log.i(TAG, "Downloaded update is ready for installation: v${update.latestVersionName}")
                    val apkFile = update.downloadedApkFile ?: File(context.cacheDir, "updates/CarCastMirror_update.apk")
                    if (apkFile.exists()) {
                        showUpdateReadyNotification(update.latestVersionName, apkFile, updateManager)
                    }
                }

                UpdateStatus.UPDATE_AVAILABLE -> {
                    Log.i(TAG, "Update available: v${update.latestVersionName} (code ${update.latestVersionCode})")
                    if (autoDownload) {
                        Log.i(TAG, "Auto-downloading update APK in background...")
                        val dlResult = updateManager.downloadApk(update.downloadUrl)
                        dlResult.onSuccess { apkFile ->
                            showUpdateReadyNotification(update.latestVersionName, apkFile, updateManager)
                        }.onFailure { err ->
                            Log.e(TAG, "Background APK download failed", err)
                            showUpdateAvailableNotification(update.latestVersionName)
                        }
                    } else {
                        showUpdateAvailableNotification(update.latestVersionName)
                    }
                }

                else -> {
                    Log.i(TAG, "Update check result status: ${update.status}")
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Update check failed", err)
        }

        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "CarCast Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for CarCast Mirror software updates"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showUpdateReadyNotification(
        version: String,
        apkFile: File,
        updateManager: GitHubUpdateManager
    ) {
        val installIntent = updateManager.getInstallIntent(apkFile)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_UPDATE_READY,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Update Ready to Install")
            .setContentText("CarCast Mirror v$version is downloaded. Tap to update.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("CarCast Mirror v$version has been downloaded and verified. Tap to install the latest version now.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_UPDATE_READY, notification)
    }

    private fun showUpdateAvailableNotification(version: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_UPDATE_DIALOG", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_UPDATE_AVAILABLE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CarCast Update Available")
            .setContentText("CarCast Mirror v$version is now available. Tap to view.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_UPDATE_AVAILABLE, notification)
    }
}
