package com.meomeo.catdrive.lib

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.util.*

@SuppressLint("MissingPermission")
class BluetoothSerial() {
    private val mAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!
    private var mDevice: BluetoothDevice? = null

    private var mSocket: BluetoothSocket? = null
    private var mOutputStream: OutputStream? = null
    private var mInputStream: InputStream? = null
    private var mConnectionCoroutine: Job? = null

    private var mOnConnectedCallback: ((device: BluetoothDevice) -> Unit)? = null
    private var mOnConnectionFailedCallback: ((device: BluetoothDevice, reason: String) -> Unit)? = null
    private var mOnDisconnectedCallback: ((device: BluetoothDevice) -> Unit)? = null

    fun setOnConnectedCallback(callback: (device: BluetoothDevice) -> Unit) {
        mOnConnectedCallback = callback
    }

    fun setOnConnectionFailedCallback(callback: (device: BluetoothDevice, reason: String) -> Unit) {
        mOnConnectionFailedCallback = callback
    }

    fun setOnDisconnectedCallback(callback: (device: BluetoothDevice) -> Unit) {
        mOnDisconnectedCallback = callback
    }

    fun connect(address: String) {
        val device = mAdapter.getRemoteDevice(address)
        if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) return
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        Timber.d("connect")

        mAdapter.cancelDiscovery()
        closeConnection()

        Timber.d("mSocket: $mSocket, connected: ${mSocket?.isConnected}")
        mDevice = mAdapter.getRemoteDevice(device.address)
        if (mDevice == null || mDevice?.bondState != BluetoothDevice.BOND_BONDED) {
            return
        }

        //Standard SerialPortService ID
        val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        mSocket = mDevice!!.createRfcommSocketToServiceRecord(uuid)

        if (mSocket == null) {
            onConnectFailed(mDevice!!, "Unable to create socket")
            return
        } else {
            connectInBackground()
        }

        // TODO: implement when receiving is needed
        // https://stackoverflow.com/questions/13450406/how-to-receive-serial-data-using-android-bluetooth
        // beginListenForData()
    }

    fun isConnected(): Boolean {
        return (mDevice != null && mSocket != null && mSocket?.isConnected == true)
    }

    fun connectedDevice(): BluetoothDevice? {
        return if (!isConnected())
            null
        else
            mDevice
    }

    fun sendData(msg: String) {
        if (isConnected()) {
            try {
                Timber.v(msg)
                mOutputStream?.write(msg.toByteArray())
            } catch (e: Exception) {
                Timber.w("Unable to send data to device, closing connection, error: $e")
                closeConnection()
            }
        } else {
            if (mDevice != null && (mConnectionCoroutine == null || mConnectionCoroutine?.isActive == false)) {
                Timber.w("device not connected but did not notify")
                closeConnection()
            }
        }
    }

    fun keepConnectionAlive() {
        if (mDevice != null && mSocket != null && mSocket?.isConnected == false) {
            connectInBackground()
        }
    }

    private fun onConnected(device: BluetoothDevice) {
        Timber.i("connected")
        mOutputStream = mSocket!!.outputStream
        mInputStream = mSocket!!.inputStream
        mOnConnectedCallback?.let { it(device) }
    }

    private fun onConnectFailed(device: BluetoothDevice, error: String) {
        Timber.e(error)
        closeConnection()
        mOnConnectionFailedCallback?.let { it(device, error) }
    }

    private fun onDisconnected(device: BluetoothDevice) {
        Timber.w("disconnected")
        mOnDisconnectedCallback?.let { it(device) }
    }

    fun isBusyConnecting(): Boolean {
        if (mConnectionCoroutine == null)
            return false
        return mConnectionCoroutine!!.isActive
    }

    private fun connectInBackground() {
        Timber.w("Connecting in coroutine")
        if (mConnectionCoroutine?.isActive == true) {
            mConnectionCoroutine?.cancel()
        }

        val device = mDevice!!

        mConnectionCoroutine = GlobalScope.launch(Dispatchers.Main) {
            val worker = GlobalScope.async(Dispatchers.Default) {
                mSocket?.connect()
                return@async mSocket?.isConnected
            }

            try {
                val connected = worker.await()
                Timber.w("socket connection await finished")
                if (connected == true)
                    onConnected(device)
                else
                    onConnectFailed(device, "unknown")
            } catch (e: Exception) {
                onConnectFailed(device, e.toString())
            }
        }
    }

    fun closeConnection() {
        Timber.w("closing connection")
        if (mConnectionCoroutine != null && mConnectionCoroutine?.isActive == true) {
            mConnectionCoroutine!!.cancel()
        }
        mConnectionCoroutine = null

        if (isConnected()) {
            onDisconnected(mDevice!!)
            mOutputStream?.close()
            mInputStream?.close()
            mSocket?.close()
        }

        mSocket = null
        mOutputStream = null
        mInputStream = null
        mDevice = null
    }
}