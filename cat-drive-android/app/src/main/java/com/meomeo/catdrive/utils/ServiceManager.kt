package com.meomeo.catdrive.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.meomeo.catdrive.MeowGoogleMapNotificationListener
import com.meomeo.catdrive.lib.Intents
import com.meomeo.catdrive.service.BroadcastService
import com.meomeo.catdrive.service.DEFAULT_ESP_HOST
import com.meomeo.catdrive.service.DEFAULT_ESP_PORT
import com.meomeo.catdrive.ui.ActivityViewModel
import timber.log.Timber

class ServiceManager {
    companion object {

        fun startBroadcastService(activity: AppCompatActivity) {
            Timber.i("start services")
            activity.startService(
                Intent(activity, BroadcastService::class.java).apply { action = Intents.EnableServices }
            )
            activity.startService(
                Intent(activity, MeowGoogleMapNotificationListener::class.java).apply { action = Intents.EnableServices }
            )
        }

        /**
         * Tell BroadcastService to open a TCP connection to [host]:[port].
         * Called from MainActivity after the user picks a host in DeviceSelectionActivity.
         */
        fun requestConnectDevice(activity: AppCompatActivity, host: String, port: Int = DEFAULT_ESP_PORT) {
            activity.startService(
                Intent(activity, BroadcastService::class.java).apply {
                    action = Intents.ConnectDevice
                    putExtra("host", host)
                    putExtra("port", port)
                }
            )
        }

        fun stopBroadcastService(activity: AppCompatActivity) {
            Timber.i("stop services")
            activity.startService(
                Intent(activity, BroadcastService::class.java).apply { action = Intents.DisableServices }
            )
            activity.startService(
                Intent(activity, MeowGoogleMapNotificationListener::class.java).apply { action = Intents.DisableServices }
            )
        }

        @Suppress("DEPRECATION")
        private fun <T> isServiceRunningInBackground(activity: AppCompatActivity, service: Class<T>): Boolean {
            val running = (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == service.name }
            val viewModel = ViewModelProvider(activity)[ActivityViewModel::class.java]
            return running && viewModel.serviceRunInBackground.value == true
        }

        fun isBroadcastServiceRunningInBackground(activity: AppCompatActivity): Boolean {
            return isServiceRunningInBackground(activity, BroadcastService::class.java)
        }
    }
}
