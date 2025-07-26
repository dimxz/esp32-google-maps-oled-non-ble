package com.meomeo.catdrive.utils

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.meomeo.catdrive.MeowGoogleMapNotificationListener
import com.meomeo.catdrive.lib.Intents
import timber.log.Timber


class PermissionCheck {
    companion object {
        fun checkNotificationsAccessPermission(context: Context): Boolean {
            Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).also {
                return MeowGoogleMapNotificationListener::class.qualifiedName.toString() in it
            }
        }

        // TODO: From API 33 we must request permission for notification posting
        fun checkNotificationPostingPermission(context: Context): Boolean {
            return true
        }

        fun checkLocationAccessPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothConnectPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothAdminPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothScanPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothPermissions(context: Context): Boolean {
            return checkBluetoothConnectPermission(context)
                    && checkBluetoothAdminPermission(context)
                    && checkBluetoothScanPermission(context)
                    && checkBluetoothPermission(context)
        }

        fun allPermissionsGranted(context: Context): Boolean {
            return checkNotificationsAccessPermission(context)
                    && checkNotificationPostingPermission(context)
                    && checkLocationAccessPermission(context)
                    && checkBluetoothPermissions(context)
        }

        fun requestLocationAccessPermission(activity: AppCompatActivity) {
            if (checkLocationAccessPermission(activity)) return
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                100
            )
        }

        fun requestNotificationAccessPermission(activity: AppCompatActivity) {
            @Suppress("DEPRECATION") activity.startActivityForResult(
                Intent(Intents.OpenNotificationListenerSettings),
                0
            )
        }

        fun requestBluetoothAccessPermissions(activity: AppCompatActivity) {
            if (checkBluetoothPermissions(activity)) return
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
                100
            )
        }

        fun isBluetoothEnabled(context: Context): Boolean {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter!!
            return adapter.isEnabled
        }

        fun requestEnableBluetooth(activity: AppCompatActivity) {
            if (!checkBluetoothPermissions(activity)) {
                Timber.e("No bluetooth permission!!!")
                return
            }

            if (isBluetoothEnabled(activity.applicationContext))
                return

            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivityForResult(enableBtIntent, 102)
        }
    }
}
