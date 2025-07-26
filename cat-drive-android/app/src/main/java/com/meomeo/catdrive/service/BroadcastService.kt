package com.meomeo.catdrive.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.util.Size
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.meomeo.catdrive.MainActivity
import com.meomeo.catdrive.R
import com.meomeo.catdrive.SHARED_PREFERENCES_FILE
import com.meomeo.catdrive.lib.BitmapHelper
import com.meomeo.catdrive.lib.BluetoothSerial
import com.meomeo.catdrive.lib.Intents
import com.meomeo.catdrive.lib.NavigationData
import com.meomeo.catdrive.utils.PermissionCheck
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import kotlin.math.ceil


const val NOTIFICATION_ID = 1201

class BroadcastService : Service(), LocationListener {
    companion object {
        private var mSerial: BluetoothSerial? = null
        private var mRunInBackground: Boolean = false
        private var mNotificationBuilder: Notification.Builder? = null
        private var mPingTimer: Timer? = null
        private var mReconnectTimer: Timer? = null
        private var mFirstPing: Boolean = true
    }

    private var mLastNavigationData: NavigationData? = null

    val connectedDevice: BluetoothDevice?
        get() {
            return mSerial?.connectedDevice()
        }

    /**
     * RunInBackground != Running, because running means it will stop with the life cycle of activity
     */
    var runInBackground: Boolean
        get() {
            return mRunInBackground
        }
        private set(value) {
            if (mRunInBackground == value)
                return
            mRunInBackground = value
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.BackgroundServiceStatus).apply {
                    putExtra("service", this::class.java.simpleName)
                    putExtra("run_in_background", value)
                }
            )
        }

    private val mBinder = LocalBinder()

    private val navigationReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            mLastNavigationData = intent.getParcelableExtra("navigation_data") as NavigationData?
            sendToDevice(mLastNavigationData)
        }
    }

    fun sendToDevice(data: NavigationData?) {
        // TODO: Fix in hardware, currently hardware font does not support Vietnamese '?' tonal
        fun patchedVietnameseString(s: String?): String? {
            if (s == null)
                return null
            var out = s
            val strFrom = "ảẢẳẲẩẨẻẺểỂỉỈỏỎổỔởỞủỦửỬỷỶ"
            val strTo   = "ãÃẵẴẫẪẽẼễỄĩĨõÕỗỖỡỠũŨữỮỹỸ"
            for (i in strFrom.indices)
                out = out!!.replace(strFrom[i], strTo[i], false)
            return out
        }

        val json = JSONObject().apply {
            put("navigation", JSONObject().apply {
                put("next_road", patchedVietnameseString(data?.nextDirection?.nextRoad))
                put("next_road_sub", patchedVietnameseString(data?.nextDirection?.nextRoadAdditionalInfo))
                put("next_road_distance", data?.nextDirection?.distance)
                put("eta", data?.eta?.eta)
                put("ete", data?.eta?.ete)
                put("distance", data?.eta?.distance)
                if (data?.actionIcon?.bitmap != null)
                    put("icon", with(BitmapHelper()) {
                        toBase64(compressBitmap(data.actionIcon.bitmap!!, Size(32, 32)))
                    }
                    )
            })
        }
        sendToDevice(json)
    }

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): BroadcastService = this@BroadcastService
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bind by activity
        if (intent?.action == Intents.BindLocalService) {
            return mBinder
        }
        // Bind by OS
        return null
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("onStartCommand: $intent")

        if (intent?.action == Intents.EnableServices) {
            runInBackground = true
            startForeground(NOTIFICATION_ID, buildForegroundNotification())

            LocalBroadcastManager.getInstance(this)
                .registerReceiver(navigationReceiver, IntentFilter(Intents.NavigationUpdate))

            subscribeToLocationUpdates()
            startReconnectTimer()
        }

        if (intent?.action == Intents.DisableServices) {
            runInBackground = false
            mSerial?.closeConnection()
            unsubscribeFromLocationUpdates()

            LocalBroadcastManager.getInstance(this).unregisterReceiver(navigationReceiver)
            mNotificationBuilder = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopPingTimer()
            stopReconnectTimer()
        }

        if (intent?.action == Intents.ConnectDevice) {
            if (PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                val device = intent.getParcelableExtra<BluetoothDevice>("device")!!
                if (connectedDevice?.address != device.address) {
                    setupSerialConnection()
                    mSerial!!.connect(device)
                }
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun setupSerialConnection() {
        if (mSerial != null)
            return

        mSerial = BluetoothSerial()
        mSerial!!.setOnConnectedCallback {
            // Toast.makeText(this, "${it.name} connected!", Toast.LENGTH_SHORT).show()
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "connected")
                    putExtra("device_name", it.name)
                    putExtra("device_address", it.address)
                }
            )
            updateNotificationText("Connected to ${it.name}")
            stopReconnectTimer()
            sendPreferencesToDevice()
            startPingTimer()
        }
        mSerial!!.setOnConnectionFailedCallback { device, reason ->
            // Toast.makeText(this, "${device.name} failed!", Toast.LENGTH_SHORT).show()
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "failed")
                    putExtra("device_name", device.name)
                    putExtra("device_address", device.address)
                    putExtra("reason", reason)
                }
            )
        }
        mSerial!!.setOnDisconnectedCallback {
            // Toast.makeText(this, "${it.name} disconnected!", Toast.LENGTH_SHORT).show()
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "disconnected")
                    putExtra("device_name", it.name)
                    putExtra("device_address", it.address)
                }
            )
            updateNotificationText("No device connected")
            startReconnectTimer()
            stopPingTimer()
        }
    }

    fun connectToLastDevice() {
        if (mSerial != null && !mSerial!!.isConnected()) {
            // mSerial?.keepConnectionAlive()
            val sp = applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
            val name = sp.getString("last_device_name", null)
            val address = sp.getString("last_device_address", null)
            if (name != null && address != null) {
                Timber.i("trying connecting to $name address $address")
                mSerial!!.connect(address)
            }
        }
    }

    private fun subscribeToLocationUpdates() {
        if (PermissionCheck.checkLocationAccessPermission(applicationContext)) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
        }
    }

    private fun unsubscribeFromLocationUpdates() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(this)
    }

    private fun startPingTimer() {
        stopPingTimer()
        mPingTimer = Timer()
        mFirstPing = true
        mPingTimer!!.schedule(object : TimerTask() {
            override fun run() {
                Timber.d("Ping timer elapsed")
                if (mFirstPing) {
                    // resend prefs just in case the device did not received the prefs at first connection
                    sendPreferencesToDevice()
                    mFirstPing = false
                }
                else if (mLastNavigationData != null) {
                    sendToDevice(mLastNavigationData)
                }
            }
        }, 1000, 25000)
    }

    private fun stopPingTimer() {
        if (mPingTimer != null) {
            mPingTimer!!.cancel()
            mPingTimer = null
        }
    }

    private fun startReconnectTimer() {
        stopReconnectTimer()
        mReconnectTimer = Timer()
        mReconnectTimer!!.schedule(object : TimerTask() {
            override fun run() {
                Timber.d("reconnect timer elapsed")
                if (!PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                    Timber.w("No BT permissions, stop reconnect timer!")
                    stopReconnectTimer()
                    return
                }
                if (mSerial?.isConnected() == true) {
                    stopReconnectTimer()
                    return
                }

                setupSerialConnection()
                Timber.d("Trying to connect to last device...")
                if (mSerial?.isBusyConnecting() == true) {
                    Timber.w("Busy!")
                } else {
                    if (PermissionCheck.isBluetoothEnabled(applicationContext))
                        connectToLastDevice()
                }
            }
        }, 1000, 15000)
    }

    private fun stopReconnectTimer() {
        if (mReconnectTimer != null) {
            mReconnectTimer!!.cancel()
            mReconnectTimer = null
        }
    }

    private fun updateNotificationText(text: String) {
        if (mNotificationBuilder == null)
            return
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationBuilder!!.setContentText(text)
        notificationManager.notify(NOTIFICATION_ID, mNotificationBuilder!!.build())
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = createNotificationChannel(
            this::class.java.simpleName,
            this::class.java.simpleName
        )
        val builder: Notification.Builder = Notification.Builder(this, channelId)
        val intent = Intent(this, MainActivity::class.java)
        intent.action = System.currentTimeMillis().toString()
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mNotificationBuilder = builder.setContentTitle("CatDrive service is meow-ing")
            .setContentText("Méo!")
            .setSmallIcon(R.drawable.catface)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return mNotificationBuilder!!.build()
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
        return channelId
    }

    override fun onLocationChanged(location: Location) {
        val speed = ceil(location.speed * 3600f / 1000f).toInt()

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.GpsUpdate).apply {
                putExtra("speed", speed)
            }
        )

        val json: JSONObject = JSONObject().apply {
            put("speed", speed)
        }
        sendToDevice(json)
    }

    fun sendToDevice(jsonObject: JSONObject) {
        mSerial?.sendData(jsonObject.toString() + "\r\n")
    }

    fun sendPreferencesToDevice() {
        sendToDevice(JSONObject().apply {
            val sp = applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
            put("preferences", JSONObject().apply {
                put("display_backlight", sp.getString("display_backlight", "off") == "on")
                put("display_contrast", sp.getInt("display_contrast", 0))
                put("speed_limit", sp.getInt("speed_limit", 60))
            })
        })
    }
}