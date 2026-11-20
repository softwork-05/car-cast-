package com.carcast.mirror.engine.discovery

import android.content.Context
import java.util.UUID

/**
 * Manages persistent stable device identifiers and remembered receiver pairings.
 * This guarantees that a receiver is recognized across network changes, dynamic IP
 * reassignments, and reboots without requiring manual IP re-entry.
 */
object DeviceIdHelper {
    private const val PREFS_NAME = "carcast_prefs"
    private const val KEY_RECEIVER_STABLE_ID = "receiver_stable_id"
    private const val KEY_REMEMBERED_RECEIVER_ID = "remembered_receiver_id"
    private const val KEY_REMEMBERED_RECEIVER_NAME = "remembered_receiver_name"
    private const val KEY_LAST_TARGET_IP = "last_target_ip"

    /**
     * Gets or creates a permanent unique identifier for this device when acting as a receiver.
     */
    @Synchronized
    fun getOrCreateStableReceiverId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_RECEIVER_STABLE_ID, null)
        if (id.isNullOrBlank()) {
            id = "cc-" + UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(KEY_RECEIVER_STABLE_ID, id).apply()
        }
        return id
    }

    /**
     * Gets the stable ID of the receiver previously selected or remembered by the sender.
     */
    fun getRememberedReceiverId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_REMEMBERED_RECEIVER_ID, null)
    }

    /**
     * Gets the name of the receiver previously selected or remembered by the sender.
     */
    fun getRememberedReceiverName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_REMEMBERED_RECEIVER_NAME, null)
    }

    /**
     * Remembers a chosen receiver by its stable ID, friendly name, and latest IP.
     */
    fun saveRememberedReceiver(context: Context, id: String, name: String, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_REMEMBERED_RECEIVER_ID, id)
            .putString(KEY_REMEMBERED_RECEIVER_NAME, name)
            .putString(KEY_LAST_TARGET_IP, ip.trim())
            .apply()
    }

    /**
     * Clears the remembered receiver.
     */
    fun clearRememberedReceiver(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_REMEMBERED_RECEIVER_ID)
            .remove(KEY_REMEMBERED_RECEIVER_NAME)
            .apply()
    }

    /**
     * Matches a discovered receiver against remembered preferences.
     * Priority:
     * 1. Match by stable receiver identifier.
     * 2. Match by exact remembered name.
     * 3. Match by default "My Car LCD" if no custom receiver was previously explicitly saved.
     */
    fun matchesRememberedOrPreferred(
        receiver: DiscoveredReceiver,
        rememberedId: String?,
        rememberedName: String?
    ): Boolean {
        if (!rememberedId.isNullOrBlank() && receiver.id.equals(rememberedId, ignoreCase = true)) {
            return true
        }
        if (!rememberedName.isNullOrBlank() && receiver.name.equals(rememberedName, ignoreCase = true)) {
            return true
        }
        if (rememberedId.isNullOrBlank() && rememberedName.isNullOrBlank()) {
            if (receiver.name.equals("My Car LCD", ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
