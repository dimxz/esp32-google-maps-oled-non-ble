package com.meomeo.catdrive.lib

import kotlinx.coroutines.*
import timber.log.Timber
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress

/**
 * WiFiSerial — drop-in replacement for BluetoothSerial.
 *
 * Connects to the ESP8266 TCP server over WiFi.
 * The ESP8266 creates a soft-AP named "ArvNav" (password "12345678").
 * Connect your Android phone to that network, then this class will
 * reach the ESP8266 at 192.168.4.1:8080.
 *
 * Usage is identical to the old BluetoothSerial class so BroadcastService
 * needs only minimal changes (see the comments there).
 */
class WiFiSerial {

    // ── Callbacks ────────────────────────────────────────────────────────────
    private var mOnConnectedCallback:       ((host: String) -> Unit)? = null
    private var mOnConnectionFailedCallback:((host: String, reason: String) -> Unit)? = null
    private var mOnDisconnectedCallback:    ((host: String) -> Unit)? = null

    fun setOnConnectedCallback       (cb: (host: String) -> Unit)                    { mOnConnectedCallback        = cb }
    fun setOnConnectionFailedCallback(cb: (host: String, reason: String) -> Unit)    { mOnConnectionFailedCallback = cb }
    fun setOnDisconnectedCallback    (cb: (host: String) -> Unit)                    { mOnDisconnectedCallback     = cb }

    // ── State ────────────────────────────────────────────────────────────────
    private var mHost: String?            = null
    private var mPort: Int                = 8080
    private var mSocket: Socket?          = null
    private var mOutputStream: OutputStream? = null
    private var mConnectionCoroutine: Job?   = null

    // ── Public API ───────────────────────────────────────────────────────────

    /** Connect to [host]:[port]. Call this from BroadcastService instead of connect(BluetoothDevice). */
    fun connect(host: String, port: Int = 8080) {
        Timber.d("WiFiSerial: connecting to $host:$port")
        closeConnection()
        mHost = host
        mPort = port
        connectInBackground()
    }

    fun isConnected(): Boolean =
        mSocket != null && mSocket?.isConnected == true && mSocket?.isClosed == false

    /** The currently connected host, or null. */
    fun connectedHost(): String? = if (isConnected()) mHost else null

    fun sendData(msg: String) {
        if (isConnected()) {
            try {
                Timber.v("WiFiSerial TX: $msg")
                mOutputStream?.write(msg.toByteArray(Charsets.UTF_8))
                mOutputStream?.flush()
            } catch (e: Exception) {
                Timber.w("WiFiSerial: send failed — $e")
                closeConnection()
            }
        } else {
            // Re-trigger connection if we have a host but lost the socket
            if (mHost != null && (mConnectionCoroutine == null || mConnectionCoroutine?.isActive == false)) {
                Timber.w("WiFiSerial: not connected, reconnecting")
                connectInBackground()
            }
        }
    }

    fun isBusyConnecting(): Boolean = mConnectionCoroutine?.isActive == true

    fun closeConnection() {
        Timber.w("WiFiSerial: closing connection")
        mConnectionCoroutine?.cancel()
        mConnectionCoroutine = null

        val host = mHost ?: ""
        if (isConnected()) mOnDisconnectedCallback?.invoke(host)

        try { mOutputStream?.close() } catch (_: Exception) {}
        try { mSocket?.close()       } catch (_: Exception) {}

        mSocket       = null
        mOutputStream = null
        mHost         = null
    }

    // ── Private ──────────────────────────────────────────────────────────────

    @OptIn(DelicateCoroutinesApi::class)
    private fun connectInBackground() {
        if (mConnectionCoroutine?.isActive == true) mConnectionCoroutine?.cancel()

        val host = mHost ?: return
        val port = mPort

        mConnectionCoroutine = GlobalScope.launch(Dispatchers.Main) {
            val result = GlobalScope.async(Dispatchers.IO) {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket
            }
            try {
                val socket = result.await()
                mSocket       = socket
                mOutputStream = socket.getOutputStream()
                Timber.i("WiFiSerial: connected to $host:$port")
                mOnConnectedCallback?.invoke(host)
            } catch (e: Exception) {
                Timber.e("WiFiSerial: connection failed — $e")
                mOnConnectionFailedCallback?.invoke(host, e.toString())
                mSocket       = null
                mOutputStream = null
            }
        }
    }
}
