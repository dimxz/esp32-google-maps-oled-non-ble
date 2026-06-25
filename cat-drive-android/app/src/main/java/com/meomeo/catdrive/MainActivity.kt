package com.meomeo.catdrive

import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.meomeo.catdrive.databinding.ActivityMainBinding
import com.meomeo.catdrive.lib.Intents
import com.meomeo.catdrive.lib.NavigationData
import com.meomeo.catdrive.service.BroadcastService
import com.meomeo.catdrive.service.DEFAULT_ESP_HOST
import com.meomeo.catdrive.service.DEFAULT_ESP_PORT
import com.meomeo.catdrive.ui.ActivityViewModel
import com.meomeo.catdrive.ui.DeviceSelectionActivity
import com.meomeo.catdrive.utils.PermissionCheck
import com.meomeo.catdrive.utils.ServiceManager
import timber.log.Timber

const val SHARED_PREFERENCES_FILE = "${BuildConfig.APPLICATION_ID}.preferences"

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private lateinit var mViewModel: ActivityViewModel
    private var mNavigationService: MeowGoogleMapNotificationListener? = null
    private var mNavigationServiceBound = false
    private var mBroadcastService: BroadcastService? = null
    private var mBroadcastServiceBound = false
    private lateinit var mSharedPref: SharedPreferences

    // ── Service connections ───────────────────────────────────────────────────

    private val navigationConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service !is MeowGoogleMapNotificationListener.LocalBinder) return
            mNavigationService = service.getService()
            mNavigationServiceBound = true
            Timber.d("$name connected")
            mViewModel.navigationData.postValue(mNavigationService!!.lastNavigationData)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mNavigationService = null
            mNavigationServiceBound = false
            Timber.d("$name disconnected")
            mViewModel.navigationData.postValue(NavigationData())
        }
    }

    private val broadcastConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service !is BroadcastService.LocalBinder) return
            mBroadcastService = service.getService()
            mBroadcastServiceBound = true
            Timber.d("$name connected")
            mViewModel.serviceRunInBackground.postValue(mBroadcastService!!.runInBackground)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mBroadcastService = null
            mNavigationServiceBound = false
            Timber.d("$name disconnected")
            mViewModel.navigationData.postValue(NavigationData())
            mViewModel.serviceRunInBackground.postValue(false)
        }
    }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private val navigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val data = intent.getParcelableExtra("navigation_data") as NavigationData?
            mViewModel.navigationData.postValue(data)
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intents.ConnectionUpdate -> {
                    val host = intent.getStringExtra("device_name")
                    val status = intent.getStringExtra("status")
                    // connectedDevice is now String? (host IP), null = disconnected
                    mViewModel.connectedDevice.postValue(if (status == "connected") host else null)
                    if (status == "connected") {
                        mSharedPref.edit()
                            .putString("last_device_name",    host)
                            .putString("last_device_address", host)
                            .apply()
                        sendLastNavigationDataToDevice()
                    }
                }
                Intents.GpsUpdate ->
                    mViewModel.speed.postValue(intent.getIntExtra("speed", 0))
                Intents.BackgroundServiceStatus ->
                    mViewModel.serviceRunInBackground.postValue(
                        intent.getBooleanExtra("run_in_background", false)
                    )
            }
        }
    }

    private val sharedPreferenceListener = OnSharedPreferenceChangeListener { _, _ ->
        mBroadcastService?.sendPreferencesToDevice()
    }

    // ── Device selection result ───────────────────────────────────────────────

    /** Launched when the user taps "Connect device" in Settings. */
    fun openDeviceSelectionActivity() {
        mDeviceSelectionRequest.launch(
            Intent(applicationContext, DeviceSelectionActivity::class.java)
        )
    }

    private val mDeviceSelectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val host = result.data?.getStringExtra("host") ?: DEFAULT_ESP_HOST
            val port = result.data?.getIntExtra("port", DEFAULT_ESP_PORT) ?: DEFAULT_ESP_PORT
            ServiceManager.requestConnectDevice(this, host, port)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(object : Timber.DebugTree() {
            override fun createStackElementTag(element: StackTraceElement) =
                super.createStackElementTag(element) + ":${element.lineNumber} ${element.methodName}"
        })

        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        val navView: BottomNavigationView = mBinding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_home, R.id.navigation_settings)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        mViewModel = ViewModelProvider(this)[ActivityViewModel::class.java]
        mSharedPref = getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        checkPermissions()
    }

    override fun onStart() {
        super.onStart()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(navigationReceiver, IntentFilter(Intents.NavigationUpdate))
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(Intents.GpsUpdate)
            addAction(Intents.ConnectionUpdate)
            addAction(Intents.BackgroundServiceStatus)
        })

        Intent(this, MeowGoogleMapNotificationListener::class.java).also {
            it.action = Intents.BindLocalService
            bindService(it, navigationConnection, Context.BIND_AUTO_CREATE)
        }
        Intent(this, BroadcastService::class.java).also {
            it.action = Intents.BindLocalService
            bindService(it, broadcastConnection, Context.BIND_AUTO_CREATE)
        }

        mSharedPref.registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(navigationReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        mSharedPref.unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener)

        if (mNavigationServiceBound) { unbindService(navigationConnection); mNavigationServiceBound = false; mNavigationService = null }
        if (mBroadcastServiceBound)  { unbindService(broadcastConnection);  mBroadcastServiceBound  = false; mBroadcastService  = null }

        super.onStop()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun checkPermissions() {
        if (!PermissionCheck.allPermissionsGranted(this))
            Toast.makeText(this, "Some permissions are not granted, see Settings page", Toast.LENGTH_LONG).show()
    }

    private fun sendLastNavigationDataToDevice() {
        mBroadcastService?.sendToDevice(mNavigationService?.lastNavigationData)
    }
}
