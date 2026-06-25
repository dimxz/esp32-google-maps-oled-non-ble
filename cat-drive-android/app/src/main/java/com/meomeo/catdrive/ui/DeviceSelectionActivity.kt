package com.meomeo.catdrive.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.meomeo.catdrive.R
import com.meomeo.catdrive.SHARED_PREFERENCES_FILE
import com.meomeo.catdrive.service.DEFAULT_ESP_HOST
import com.meomeo.catdrive.service.DEFAULT_ESP_PORT

/**
 * Replaces DeviceSelectionActivity (Bluetooth picker).
 *
 * Shows a simple form:
 *   ┌──────────────────────────────────┐
 *   │  ESP8266 IP address              │
 *   │  [ 192.168.4.1              ]    │
 *   │  Port                            │
 *   │  [ 8080                     ]    │
 *   │  ℹ️  Connect your phone to the   │
 *   │     "ArvNav" WiFi network first. │
 *   │                                  │
 *   │         [ Connect ]              │
 *   └──────────────────────────────────┘
 *
 * On "Connect" it saves the values to SharedPreferences and returns
 * RESULT_OK with host/port extras — matching what MainActivity expects.
 */
class DeviceSelectionActivity : AppCompatActivity() {

    private lateinit var mHostInput: EditText
    private lateinit var mPortInput: EditText
    private lateinit var mConnectButton: Button
    private lateinit var mHintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Connect to ESP8266"
        }

        // ── Build layout programmatically (no XML needed) ──────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val sp = getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
        val savedHost = sp.getString("last_device_address", DEFAULT_ESP_HOST) ?: DEFAULT_ESP_HOST
        val savedPort = sp.getInt("last_device_port", DEFAULT_ESP_PORT)

        // IP label + field
        root.addView(TextView(this).apply { text = "ESP8266 IP address" })
        mHostInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedHost)
            hint = DEFAULT_ESP_HOST
        }
        root.addView(mHostInput)

        // Port label + field
        root.addView(TextView(this).apply {
            text = "Port"
            setPadding(0, 32, 0, 0)
        })
        mPortInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedPort.toString())
            hint = DEFAULT_ESP_PORT.toString()
        }
        root.addView(mPortInput)

        // Hint
        mHintText = TextView(this).apply {
            text = "ℹ️  Connect your phone to the \"ArvNav\" WiFi network before tapping Connect."
            setPadding(0, 48, 0, 48)
            alpha = 0.7f
        }
        root.addView(mHintText)

        // Connect button
        mConnectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { onConnectClicked() }
        }
        root.addView(mConnectButton)

        setContentView(root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun onConnectClicked() {
        val host = mHostInput.text.toString().trim().ifEmpty { DEFAULT_ESP_HOST }
        val port = mPortInput.text.toString().trim().toIntOrNull() ?: DEFAULT_ESP_PORT

        // Basic validation
        if (!isValidIp(host) && host != "localhost") {
            Toast.makeText(this, "Enter a valid IP address (e.g. 192.168.4.1)", Toast.LENGTH_SHORT).show()
            return
        }
        if (port !in 1..65535) {
            Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        // Save for next time
        getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE).edit().apply {
            putString("last_device_address", host)
            putString("last_device_name",    host)   // keep key for compatibility
            putInt   ("last_device_port",    port)
            apply()
        }

        setResult(RESULT_OK, Intent().apply {
            putExtra("host", host)
            putExtra("port", port)
        })
        finish()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
