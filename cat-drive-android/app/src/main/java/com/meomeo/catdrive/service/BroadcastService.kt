package com.meomeo.catdrive.service

import android.annotation.SuppressLint
import android.app.*
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
import com.meomeo.catdrive.lib.Intents
import com.meomeo.catdrive.lib.NavigationData
import com.meomeo.catdrive.lib.WiFiSerial          // ← NEW: was BluetoothSerial
import com.meomeo.catdrive.utils.PermissionCheck
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import kotlin.math.ceil

const val NOTIFICATION_ID = 1201

// ── Default ESP8266 soft-AP address ──────────────────────────────────────────
// When the ESP8266 runs as an Access Point it always gets 192.168.4.1.
// Change this only if you configured a different address on the firmware side.
const val DEFAULT_ESP_HOST = "192.168.4.1"
const val DEFAULT_ESP_PORT = 8080

class BroadcastService : Service(), LocationListener {
    companion object {
        private var mSerial: WiFiSerial? = null          // ← was BluetoothSerial
        private var mRunInBackground: Boolean = false
        private var mNotificationBuilder: Notification.Builder? = null
        private var mPingTimer: Timer? = null
        private var mReconnectTimer: Timer? = null
        private var mFirstPing: Boolean = true
    }

    private var mLastNavigationData: NavigationData? = null

    // connectedDevice is now a host string (IP address)
    val connectedHost: String?
        get() = mSerial?.connectedHost()

    var runInBackground: Boolean
        get() = mRunInBackground
        private set(value) {
            if (mRunInBackground == value) return
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

    // ── Send navigation data ──────────────────────────────────────────────────
    fun sendToDevice(data: NavigationData?) {
        fun patchedVietnameseString(s: String?): String? {
            if (s == null) return null
            var out = s
            val from = "ảẢẳẲẩẨẻẺểỂỉỈỏỎổỔởỞủỦửỬỷỶ"
            val to   = "ãÃẵẴẫẪẽẼễỄĩĨõÕỗỖỡỠũŨữỮỹỸ"
            for (i in from.indices) out = out!!.replace(from[i], to[i], false)
            return out
        }

        val json = JSONObject().apply {
            put("navigation", JSONObject().apply {
                put("next_road",          patchedVietnameseString(data?.nextDirection?.nextRoad))
                put("next_road_sub",      patchedVietnameseString(data?.nextDirection?.nextRoadAdditionalInfo))
                put("next_road_distance", data?.nextDirection?.distance)
                put("eta",      data?.eta?.eta)
                put("ete",      data?.eta?.ete)
                put("distance", data?.eta?.distance)
                if (data?.actionIcon?.bitmap != null)
                    put("icon", with(BitmapHelper()) {
                        toBase64(compressBitmap(data.actionIcon.bitmap!!, Size(32, 32)))
                    })
            })
        }
        sendToDevice(json)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BroadcastService = this@BroadcastService
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == Intents.BindLocalService) return mBinder
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

        // ConnectDevice now uses host/port instead of a BluetoothDevice
        if (intent?.action == Intents.ConnectDevice) {
            val host = intent.getStringExtra("host") ?: DEFAULT_ESP_HOST
            val port = intent.getIntExtra("port", DEFAULT_ESP_PORT)
            setupSerialConnection()
            mSerial?.connect(host, port)
        }

        return START_STICKY
    }

    private fun setupSerialConnection() {
        if (mSerial != null) return

        mSerial = WiFiSerial()

        mSerial!!.setOnConnectedCallback { host ->
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "connected")
                    putExtra("device_name", host)
                    putExtra("device_address", host)
                }
            )
            updateNotificationText("Connected to $host")
            stopReconnectTimer()
            sendPreferencesToDevice()
            startPingTimer()
        }

        mSerial!!.setOnConnectionFailedCallback { host, reason ->
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "failed")
                    putExtra("device_name", host)
                    putExtra("device_address", host)
                    putExtra("reason", reason)
                }
            )
        }

        mSerial!!.setOnDisconnectedCallback { host ->
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.ConnectionUpdate).apply {
                    putExtra("status", "disconnected")
                    putExtra("device_name", host)
                    putExtra("device_address", host)
                }
            )
            updateNotificationText("No device connected")
            startReconnectTimer()
            stopPingTimer()
        }
    }

    /** Re-connect to the last saved host/port. */
    fun connectToLastDevice() {
        if (mSerial != null && !mSerial!!.isConnected()) {
            val sp = applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
            val host = sp.getString("last_device_address", DEFAULT_ESP_HOST) ?: DEFAULT_ESP_HOST
            val port = sp.getInt("last_device_port", DEFAULT_ESP_PORT)
            Timber.i("Trying to connect to $host:$port")
            mSerial!!.connect(host, port)
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
                if (mFirstPing) {
                    sendPreferencesToDevice()
                    mFirstPing = false
                } else if (mLastNavigationData != null) {
                    sendToDevice(mLastNavigationData)
                }
            }
        }, 1000, 25000)
    }

    private fun stopPingTimer() { mPingTimer?.cancel(); mPingTimer = null }

    private fun startReconnectTimer() {
        stopReconnectTimer()
        mReconnectTimer = Timer()
        mReconnectTimer!!.schedule(object : TimerTask() {
            override fun run() {
                if (mSerial?.isConnected() == true) { stopReconnectTimer(); return }
                setupSerialConnection()
                if (mSerial?.isBusyConnecting() == true) {
                    Timber.w("Busy connecting, skipping reconnect tick")
                } else {
                    connectToLastDevice()
                }
            }
        }, 1000, 15000)
    }

    private fun stopReconnectTimer() { mReconnectTimer?.cancel(); mReconnectTimer = null }

    private fun updateNotificationText(text: String) {
        if (mNotificationBuilder == null) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationBuilder!!.setContentText(text)
        nm.notify(NOTIFICATION_ID, mNotificationBuilder!!.build())
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = createNotificationChannel(this::class.java.simpleName, this::class.java.simpleName)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = System.currentTimeMillis().toString()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mNotificationBuilder = Notification.Builder(this, channelId)
            .setContentTitle("ArvNav service is running")
            .setContentText("Waiting for connection…")
            .setSmallIcon(R.drawable.catface)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return mNotificationBuilder!!.build()
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            lightColor = Color.BLUE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        return channelId
    }

    override fun onLocationChanged(location: Location) {
        val speed = ceil(location.speed * 3600f / 1000f).toInt()
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.GpsUpdate).apply { putExtra("speed", speed) }
        )
        sendToDevice(JSONObject().apply { put("speed", speed) })
    }

    fun sendToDevice(jsonObject: JSONObject) {
        // Append \r\n so the ESP8266 line parser recognises end-of-message
        mSerial?.sendData(jsonObject.toString() + "\r\n")
    }

    fun sendPreferencesToDevice() {
        val sp = applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
        sendToDevice(JSONObject().apply {
            put("preferences", JSONObject().apply {
                put("display_backlight", sp.getString("display_backlight", "off") == "on")
                put("display_contrast",  sp.getInt("display_contrast", 0))
                put("speed_limit",       sp.getInt("speed_limit", 60))
            })
        })
    }
}
