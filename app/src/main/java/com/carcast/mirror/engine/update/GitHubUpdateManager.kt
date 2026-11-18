package com.carcast.mirror.engine.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UpdateStatus {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    READY_TO_INSTALL,
    DOWNLOADING,
    CHECK_FAILED
}

data class UpdateCheckResult(
    val status: UpdateStatus,
    val latestVersionName: String,
    val latestVersionCode: Long,
    val installedVersionName: String,
    val installedVersionCode: Long,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeMb: Double = 0.0,
    val checkTimestamp: Long = System.currentTimeMillis(),
    val downloadedApkFile: File? = null,
    val errorMessage: String? = null
) {
    val isUpdateAvailable: Boolean
        get() = status == UpdateStatus.UPDATE_AVAILABLE || status == UpdateStatus.READY_TO_INSTALL

    // Backward compatibility helpers
    val latestVersion: String get() = latestVersionName
    val currentVersion: String get() = installedVersionName
}

class GitHubUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "CarCast_Updater"
        const val DEFAULT_REPO = "softwork-05/car-cast-"

        fun formatTimestamp(timestampMs: Long): String {
            if (timestampMs <= 0L) return "Never"
            return try {
                val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                sdf.format(Date(timestampMs))
            } catch (_: Exception) {
                Date(timestampMs).toString()
            }
        }
    }

    fun getInstalledVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun getInstalledVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun getArchiveVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * Inspects the cache directory for any previously downloaded update APK.
     * If the APK has already been installed (downloadedCode <= installedCode),
     * it automatically deletes the obsolete file, clears stale notifications,
     * and resets the update_available preference.
     * Returns the verified File only if it represents an uninstalled newer version.
     */
    fun getPendingDownloadedApk(): File? {
        val updatesDir = File(context.cacheDir, "updates")
        val apkFile = File(updatesDir, "CarCastMirror_update.apk")
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return null
        }

        val installedCode = getInstalledVersionCode()
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (archiveInfo == null) {
            apkFile.delete()
            return null
        }

        val downloadedCode = getArchiveVersionCode(archiveInfo)

        if (downloadedCode <= installedCode) {
            Log.i(TAG, "Cleaning up obsolete downloaded APK (downloadedCode: $downloadedCode <= installedCode: $installedCode)")
            apkFile.delete()
            clearUpdateNotifications()
            val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("update_available", false).apply()
            return null
        }

        return apkFile
    }

    fun clearUpdateNotifications() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(AutoUpdateWorker.NOTIFICATION_ID_UPDATE_READY)
            nm.cancel(AutoUpdateWorker.NOTIFICATION_ID_UPDATE_AVAILABLE)
        } catch (_: Exception) {}
    }

    fun parseVersionCodeFromRelease(tagName: String, releaseBody: String): Long {
        // 1. Check if release body explicitly defines "versionCode: 123" or "versionCode = 123"
        val regex = Regex("""(?i)versioncode\s*[:=]\s*(\d+)""")
        val match = regex.find(releaseBody)
        if (match != null) {
            val code = match.groupValues[1].toLongOrNull()
            if (code != null && code > 0) return code
        }

        // 2. Parse tag name (e.g., "v1.0.5", "1.0.6", "v5")
        val clean = tagName.trim().removePrefix("v").removePrefix("V")
        clean.toLongOrNull()?.let { return it }

        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.isNotEmpty()) {
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            if (major == 1 && minor == 0 && patch > 0) {
                return patch.toLong()
            }
            return (major * 10000 + minor * 100 + patch).toLong()
        }
        return 0L
    }

    fun isSemanticVersionNewer(current: String, latest: String): Boolean {
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")
        val cleanLatest = latest.trim().removePrefix("v").removePrefix("V")

        val currParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun isUpdateAvailable(
        installedCode: Long,
        installedName: String,
        latestCode: Long,
        latestName: String
    ): Boolean {
        if (latestCode > 0 && installedCode > 0) {
            if (latestCode > installedCode) return true
            if (latestCode <= installedCode) return false
        }
        return isSemanticVersionNewer(installedName, latestName)
    }

    suspend fun checkForUpdate(
        repoPath: String = DEFAULT_REPO,
        currentVersionNameOverride: String? = null
    ): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            val installedVersionName = currentVersionNameOverride ?: getInstalledVersionName()
            val installedVersionCode = getInstalledVersionCode()

            // First check if an existing downloaded APK is pending installation
            val pendingApk = getPendingDownloadedApk()

            val cleanRepo = repoPath.trim().ifEmpty { DEFAULT_REPO }
            val apiUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "CarCastMirror-App")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext Result.failure(
                    Exception("GitHub API returned HTTP $responseCode")
                )
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V").trim()
            val body = json.optString("body", "No release notes provided.")
            val assets = json.optJSONArray("assets")

            var apkDownloadUrl = ""
            var apkSizeBytes = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkSizeBytes = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkDownloadUrl.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No .apk asset found in the latest GitHub release")
                )
            }

            val latestVersionCode = parseVersionCodeFromRelease(tagName, body)
            val sizeMb = apkSizeBytes / (1024.0 * 1024.0)
            val now = System.currentTimeMillis()

            val hasUpdate = isUpdateAvailable(
                installedCode = installedVersionCode,
                installedName = installedVersionName,
                latestCode = latestVersionCode,
                latestName = tagName
            )

            val status = when {
                pendingApk != null -> UpdateStatus.READY_TO_INSTALL
                hasUpdate -> UpdateStatus.UPDATE_AVAILABLE
                else -> UpdateStatus.UP_TO_DATE
            }

            // Persist check time & update state
            val prefs = context.getSharedPreferences("carcast_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_update_check_time", now)
                .putBoolean("update_available", hasUpdate || pendingApk != null)
                .putString("latest_available_version", tagName)
                .putLong("latest_available_code", latestVersionCode)
                .putString("update_release_notes", body)
                .apply()

            Log.i(
                TAG,
                "Update check finished: status=$status, installed=v$installedVersionName (code $installedVersionCode), " +
                        "latest=v$tagName (code $latestVersionCode), pendingApk=${pendingApk != null}"
            )

            Result.success(
                UpdateCheckResult(
                    status = status,
                    latestVersionName = tagName,
                    latestVersionCode = latestVersionCode,
                    installedVersionName = installedVersionName,
                    installedVersionCode = installedVersionCode,
                    releaseNotes = body,
                    downloadUrl = apkDownloadUrl,
                    apkSizeMb = sizeMb,
                    checkTimestamp = now,
                    downloadedApkFile = pendingApk
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CarCastMirror-App")
            }

            val totalSize = connection.contentLength
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outputFile = File(updatesDir, "CarCastMirror_update.apk")
            if (outputFile.exists()) {
                outputFile.delete()
            }

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            val progress = ((totalRead * 100) / totalSize).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            if (!verifyApk(outputFile)) {
                outputFile.delete()
                return@withContext Result.failure(Exception("Downloaded APK failed package integrity verification"))
            }

            val sha256 = calculateSha256(outputFile)
            Log.i(TAG, "Downloaded APK verified. SHA-256: $sha256, Size: ${outputFile.length()} bytes")

            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> {
        val result = downloadApk(downloadUrl, onProgress)
        result.onSuccess { apkFile ->
            installApk(apkFile)
        }
        return result
    }

    fun verifyApk(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK file does not exist or is empty")
            return false
        }
        try {
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (pkgInfo == null) {
                Log.e(TAG, "Package archive could not be parsed as a valid Android APK")
                return false
            }
            if (pkgInfo.packageName != context.packageName) {
                Log.e(TAG, "APK package name mismatch: ${pkgInfo.packageName} vs expected ${context.packageName}")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying APK", e)
            return false
        }
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getInstallIntent(apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val intent = getInstallIntent(apkFile)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }
}
