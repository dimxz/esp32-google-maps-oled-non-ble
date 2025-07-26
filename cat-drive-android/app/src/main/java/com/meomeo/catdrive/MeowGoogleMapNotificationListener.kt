package com.meomeo.catdrive

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.meomeo.catdrive.lib.Intents
import com.meomeo.catdrive.lib.NavigationNotification
import com.meomeo.catdrive.service.NavigationListener

class MeowGoogleMapNotificationListener : NavigationListener() {
    private val mBinder = LocalBinder()

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): MeowGoogleMapNotificationListener = this@MeowGoogleMapNotificationListener
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bind by activity
        if (intent?.action == Intents.BindLocalService) {
            return mBinder
        }
        // Bind by OS
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intents.EnableServices) {
            enabled = true
        }
        if (intent?.action == Intents.DisableServices) {
            enabled = false
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.NavigationUpdate).apply {
                putExtra("navigation_data", navNotification.navigationData)
            }
        )
    }

    override fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            // Empty data (no extras)
            Intent(Intents.NavigationUpdate)
        )
    }
}