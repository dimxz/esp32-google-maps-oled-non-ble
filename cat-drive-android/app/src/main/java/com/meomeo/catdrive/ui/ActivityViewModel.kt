package com.meomeo.catdrive.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.meomeo.catdrive.lib.NavigationData

class ActivityViewModel : ViewModel() {
    val permissionUpdatedTimestamp = MutableLiveData<Long>().apply { value = 0 }
    val navigationData = MutableLiveData<NavigationData>().apply { value = NavigationData() }
    val speed = MutableLiveData<Int>().apply { value = 0 }
    // Was MutableLiveData<BluetoothDevice?> — now just the host IP string (or null = disconnected)
    val connectedDevice = MutableLiveData<String?>().apply { value = null }
    val serviceRunInBackground = MutableLiveData<Boolean>().apply { value = false }
}
