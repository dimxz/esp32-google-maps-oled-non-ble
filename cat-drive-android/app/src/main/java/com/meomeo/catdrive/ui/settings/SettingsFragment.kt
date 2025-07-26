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
    private lateinit var mAccessBluetoothCheckbox: CheckBoxPreference
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

        viewModel.connectedDevice.observe(viewLifecycleOwner) {
            if (PermissionCheck.checkBluetoothConnectPermission(requireContext()))
                mConnectDeviceButton.summary = if (it !== null) "Connected to ${it.name}" else "No device connected"
            else
                mConnectDeviceButton.summary = "Please check bluetooth permissions!"
        }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as MainActivity

        mServiceEnableSwitch = preferenceScreen.findPreference("enable_service")!!
        mAccessNotificationCheckbox = preferenceScreen.findPreference("access_notification")!!
        mPostNotificationCheckbox = preferenceScreen.findPreference("post_notification")!!
        mAccessLocationCheckbox = preferenceScreen.findPreference("access_location")!!
        mAccessBluetoothCheckbox = preferenceScreen.findPreference("access_bluetooth")!!
        mConnectDeviceButton = preferenceScreen.findPreference("connect_device")!!
        mDisplayBacklightSwitch = preferenceScreen.findPreference("device_backlight")!!
        mSpeedLimitEdit = preferenceScreen.findPreference("speed_warning_limit")!!

        mSharedPref = mainActivity.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        refreshSettings()

        mServiceEnableSwitch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean)
                ServiceManager.startBroadcastService(mainActivity)
            else
                ServiceManager.stopBroadcastService(mainActivity)
            return@setOnPreferenceChangeListener true
        }

        mAccessNotificationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean)
                PermissionCheck.requestNotificationAccessPermission(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mAccessLocationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean)
                PermissionCheck.requestLocationAccessPermission(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mAccessBluetoothCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean)
                PermissionCheck.requestBluetoothAccessPermissions(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mConnectDeviceButton.setOnPreferenceClickListener { _ ->
            (activity as MainActivity).openDeviceSelectionActivity()
            return@setOnPreferenceClickListener false
        }

        mDisplayBacklightSwitch.setOnPreferenceChangeListener { _, newValue ->
            with(mSharedPref.edit()) {
                putString("display_backlight", if (newValue as Boolean) "on" else "off")
                apply()
            }
            return@setOnPreferenceChangeListener true
        }

        mSpeedLimitEdit.setOnPreferenceChangeListener { _, newValue ->
            mSpeedLimitEdit.summary = (newValue as String) + " km/h"
            with(mSharedPref.edit()) {
                putInt("speed_limit", (newValue as String).toInt())
                apply()
            }
            return@setOnPreferenceChangeListener true
        }
    }

    private fun refreshSettings() {
        val context = requireContext()

        mServiceEnableSwitch.isEnabled = PermissionCheck.allPermissionsGranted(context)
        mServiceEnableSwitch.isChecked = ServiceManager.isBroadcastServiceRunningInBackground(activity as MainActivity)

        mDisplayBacklightSwitch.isChecked = mSharedPref.getString("display_backlight", "off") == "on"
        mSpeedLimitEdit.summary = mSharedPref.getInt("speed_limit", 0).toString() + " km/h"

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
        PermissionCheck.checkBluetoothPermissions(context).also {
            mAccessBluetoothCheckbox.isEnabled = !it
            mAccessBluetoothCheckbox.isChecked = it
        }
    }
}