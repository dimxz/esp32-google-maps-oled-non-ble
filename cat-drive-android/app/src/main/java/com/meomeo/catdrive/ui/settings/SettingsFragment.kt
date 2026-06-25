package com.meomeo.catdrive.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.meomeo.catdrive.MainActivity
import com.meomeo.catdrive.R
import com.meomeo.catdrive.SHARED_PREFERENCES_FILE
import com.meomeo.catdrive.ui.ActivityViewModel
import com.meomeo.catdrive.utils.PermissionCheck
import com.meomeo.catdrive.utils.ServiceManager

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var mServiceEnableSwitch: SwitchPreference
    private lateinit var mAccessNotificationCheckbox: CheckBoxPreference
    private lateinit var mPostNotificationCheckbox: CheckBoxPreference
    private lateinit var mAccessLocationCheckbox: CheckBoxPreference
    // mAccessBluetoothCheckbox removed — no Bluetooth needed
    private lateinit var mConnectDeviceButton: Preference
    private lateinit var mDisplayBacklightSwitch: SwitchPreference
    private lateinit var mSpeedLimitEdit: EditTextPreference

    private lateinit var mSharedPref: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewModel = ViewModelProvider(requireActivity())[ActivityViewModel::class.java]

        viewModel.permissionUpdatedTimestamp.observe(viewLifecycleOwner) {
            refreshSettings()
        }

        // connectedDevice now carries the IP string in device.name / device.address
        viewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            mConnectDeviceButton.summary = if (device != null)
                "Connected to ${device.name}"   // device.name == host IP in WiFi version
            else
                "Tap to set ESP8266 address"
        }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as MainActivity

        mServiceEnableSwitch        = preferenceScreen.findPreference("enable_service")!!
        mAccessNotificationCheckbox = preferenceScreen.findPreference("access_notification")!!
        mPostNotificationCheckbox   = preferenceScreen.findPreference("post_notification")!!
        mAccessLocationCheckbox     = preferenceScreen.findPreference("access_location")!!
        mConnectDeviceButton        = preferenceScreen.findPreference("connect_device")!!
        mDisplayBacklightSwitch     = preferenceScreen.findPreference("device_backlight")!!
        mSpeedLimitEdit             = preferenceScreen.findPreference("speed_warning_limit")!!

        // Hide the Bluetooth permission row — not needed for WiFi
        preferenceScreen.findPreference<CheckBoxPreference>("access_bluetooth")?.isVisible = false

        mSharedPref = mainActivity.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        refreshSettings()

        mServiceEnableSwitch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) ServiceManager.startBroadcastService(mainActivity)
            else ServiceManager.stopBroadcastService(mainActivity)
            return@setOnPreferenceChangeListener true
        }

        mAccessNotificationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) PermissionCheck.requestNotificationAccessPermission(mainActivity)
            return@setOnPreferenceChangeListener false
        }

        mAccessLocationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) PermissionCheck.requestLocationAccessPermission(mainActivity)
            return@setOnPreferenceChangeListener false
        }

        // Open the WiFi connection dialog instead of the BT picker
        mConnectDeviceButton.setOnPreferenceClickListener {
            mainActivity.openDeviceSelectionActivity()
            return@setOnPreferenceClickListener false
        }

        mDisplayBacklightSwitch.setOnPreferenceChangeListener { _, newValue ->
            mSharedPref.edit().putString("display_backlight", if (newValue as Boolean) "on" else "off").apply()
            return@setOnPreferenceChangeListener true
        }

        mSpeedLimitEdit.setOnPreferenceChangeListener { _, newValue ->
            val limit = (newValue as String).toIntOrNull() ?: 60
            mSpeedLimitEdit.summary = "$limit km/h"
            mSharedPref.edit().putInt("speed_limit", limit).apply()
            return@setOnPreferenceChangeListener true
        }
    }

    private fun refreshSettings() {
        val context = requireContext()

        // Service can start as long as notification + location permissions are granted
        // (no Bluetooth required anymore)
        val canStart = PermissionCheck.checkNotificationsAccessPermission(context)
                    && PermissionCheck.checkLocationAccessPermission(context)
        mServiceEnableSwitch.isEnabled = canStart
        mServiceEnableSwitch.isChecked = ServiceManager.isBroadcastServiceRunningInBackground(activity as MainActivity)

        mDisplayBacklightSwitch.isChecked = mSharedPref.getString("display_backlight", "off") == "on"
        mSpeedLimitEdit.summary = mSharedPref.getInt("speed_limit", 60).toString() + " km/h"

        PermissionCheck.checkNotificationsAccessPermission(context).also {
            mAccessNotificationCheckbox.isEnabled = !it
            mAccessNotificationCheckbox.isChecked = it
        }
        PermissionCheck.checkNotificationPostingPermission(context).also {
            mPostNotificationCheckbox.isEnabled = !it
            mPostNotificationCheckbox.isChecked = it
        }
        PermissionCheck.checkLocationAccessPermission(context).also {
            mAccessLocationCheckbox.isEnabled = !it
            mAccessLocationCheckbox.isChecked = it
        }

        // Show last saved IP as the connect button subtitle
        val savedHost = mSharedPref.getString("last_device_address", null)
        if (savedHost != null) mConnectDeviceButton.summary = "Last: $savedHost  (tap to change)"
    }
}
