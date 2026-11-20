package com.carcast.mirror.engine

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Detects device hardware platform: Android TV, Car Automotive Head Unit, or Mobile Phone/Tablet.
 */
object PlatformHelper {

    fun isAndroidTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
               pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }

    fun isAutomotive(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    fun getPlatformLabel(context: Context): String {
        return when {
            isAndroidTv(context) -> "Android TV"
            isAutomotive(context) -> "Car Head Unit"
            else -> "Mobile Device"
        }
    }
}
