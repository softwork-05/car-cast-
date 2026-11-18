package com.carcast.mirror

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class CarCastApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "carcast_receiver_channel"
        lateinit var instance: CarCastApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.carcast.mirror.engine.airplay.AirPlayCrypto.initialize(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
