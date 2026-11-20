package com.carcast.mirror

import com.carcast.mirror.engine.update.GitHubUpdateManager
import com.carcast.mirror.engine.update.UpdateCheckResult
import com.carcast.mirror.engine.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateManagerTest {

    // A lightweight mock helper to test logic methods that do not require Android Context
    private val updater = GitHubUpdateManagerHelper

    object GitHubUpdateManagerHelper {
        fun parseVersionCodeFromRelease(tagName: String, releaseBody: String): Long {
            val regex = Regex("""(?i)versioncode\s*[:=]\s*(\d+)""")
            val match = regex.find(releaseBody)
            if (match != null) {
                val code = match.groupValues[1].toLongOrNull()
                if (code != null && code > 0) return code
            }

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
    }

    @Test
    fun testParseVersionCodeFromRelease() {
        assertEquals(10L, updater.parseVersionCodeFromRelease("v1.0.10", ""))
        assertEquals(11L, updater.parseVersionCodeFromRelease("v1.0.11", ""))
        assertEquals(42L, updater.parseVersionCodeFromRelease("v1.0.0", "Release Notes\nversionCode: 42\nDetails"))
        assertEquals(99L, updater.parseVersionCodeFromRelease("v2.1.0", "versionCode = 99"))
        assertEquals(5L, updater.parseVersionCodeFromRelease("v5", ""))
    }

    @Test
    fun testSemanticVersionComparison() {
        assertTrue(updater.isSemanticVersionNewer("1.0.10", "1.0.11"))
        assertTrue(updater.isSemanticVersionNewer("v1.0.9", "v1.0.10"))
        assertTrue(updater.isSemanticVersionNewer("1.0.0", "2.0.0"))
        assertFalse(updater.isSemanticVersionNewer("1.0.10", "1.0.10"))
        assertFalse(updater.isSemanticVersionNewer("1.0.11", "1.0.10"))
        assertFalse(updater.isSemanticVersionNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun testIsUpdateAvailable() {
        // Higher versionCode available
        assertTrue(updater.isUpdateAvailable(10L, "1.0.10", 11L, "1.0.11"))

        // Same versionCode
        assertFalse(updater.isUpdateAvailable(10L, "1.0.10", 10L, "1.0.10"))

        // Already on higher versionCode
        assertFalse(updater.isUpdateAvailable(11L, "1.0.11", 10L, "1.0.10"))

        // Fallback to semver when codes are 0
        assertTrue(updater.isUpdateAvailable(0L, "1.0.10", 0L, "1.0.11"))
        assertFalse(updater.isUpdateAvailable(0L, "1.0.11", 0L, "1.0.10"))
    }

    @Test
    fun testSanitizeErrorMessageRemovesRawUrls() {
        val rawSasUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/1461278605/e9b0ca73-0afd-43a7-8112-8879911d7c1d?sp=r&sv=2018-11-09&sr=b&spr=https&se=2026-11-20T08%3A03%3A02Z&sig=abc"
        val sanitized = GitHubUpdateManager.sanitizeErrorMessage(rawSasUrl)

        assertFalse("Sanitized error must not contain raw URLs or SAS tokens", sanitized.contains("release-assets.githubusercontent.com"))
        assertFalse("Sanitized error must not contain sig query param", sanitized.contains("sig="))
        assertTrue("Sanitized error should mention update package issue", sanitized.contains("update package"))

        val blobNotFound = "The specified blob does not exist (BlobNotFound)"
        val sanitizedBlob = GitHubUpdateManager.sanitizeErrorMessage(blobNotFound)
        assertTrue(sanitizedBlob.contains("tap Retry to fetch the latest download link"))

        val error403 = "Server returned HTTP 403 Forbidden"
        val sanitized403 = GitHubUpdateManager.sanitizeErrorMessage(error403)
        assertTrue(sanitized403.contains("expired") || sanitized403.contains("denied"))

        val nullMsg = GitHubUpdateManager.sanitizeErrorMessage(null)
        assertTrue(nullMsg.contains("Could not connect"))
    }

    @Test
    fun testUpdateStatusProperties() {
        val availableResult = UpdateCheckResult(
            status = UpdateStatus.UPDATE_AVAILABLE,
            latestVersionName = "1.0.11",
            latestVersionCode = 11L,
            installedVersionName = "1.0.10",
            installedVersionCode = 10L,
            releaseNotes = "Fix download redirects",
            downloadUrl = "https://github.com/softwork-05/car-cast-/releases/download/v1.0.11/CarCastMirror.apk"
        )
        assertTrue(availableResult.isUpdateAvailable)

        val upToDateResult = availableResult.copy(status = UpdateStatus.UP_TO_DATE)
        assertFalse(upToDateResult.isUpdateAvailable)

        val readyResult = availableResult.copy(status = UpdateStatus.READY_TO_INSTALL)
        assertTrue(readyResult.isUpdateAvailable)

        val failedResult = availableResult.copy(status = UpdateStatus.DOWNLOAD_FAILED)
        assertFalse(failedResult.isUpdateAvailable)
    }
}
