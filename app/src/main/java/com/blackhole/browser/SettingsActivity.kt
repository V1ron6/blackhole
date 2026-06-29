package com.blackhole.browser

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blackhole.browser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        // Populate current values
        when (settings.securityMode) {
            SecurityMode.STRICT -> binding.radioStrict.isChecked = true
            SecurityMode.CTF -> binding.radioCtf.isChecked = true
        }
        binding.switchProxy.isChecked = settings.proxyEnabled
        binding.inputProxyScheme.setText(settings.proxyScheme)
        binding.inputProxyHost.setText(settings.proxyHost)
        binding.inputProxyPort.setText(settings.proxyPort)

        if (!ProxyManager.isSupported()) {
            binding.switchProxy.isEnabled = false
            Toast.makeText(
                this,
                "Proxy override isn't supported by this device's WebView build.",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnSaveSettings.setOnClickListener { saveAndApply() }

        binding.btnPresetTor.setOnClickListener {
            binding.inputProxyScheme.setText(Settings.DEFAULT_PROXY_SCHEME)
            binding.inputProxyHost.setText(Settings.DEFAULT_PROXY_HOST)
            binding.inputProxyPort.setText(Settings.DEFAULT_PROXY_PORT)
            binding.switchProxy.isChecked = true
            Toast.makeText(this, "Tor/Orbot preset loaded - tap Save to apply", Toast.LENGTH_SHORT).show()
        }

        binding.btnPresetBurp.setOnClickListener {
            binding.inputProxyScheme.setText("http")
            binding.inputProxyHost.setText("127.0.0.1")
            binding.inputProxyPort.setText("8080")
            binding.switchProxy.isChecked = true
            Toast.makeText(this, "Burp preset loaded - tap Save to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndApply() {
        settings.securityMode =
            if (binding.radioCtf.isChecked) SecurityMode.CTF else SecurityMode.STRICT

        settings.proxyEnabled = binding.switchProxy.isChecked
        settings.proxyScheme = binding.inputProxyScheme.text.toString().ifBlank {
            Settings.DEFAULT_PROXY_SCHEME
        }
        settings.proxyHost = binding.inputProxyHost.text.toString().ifBlank {
            Settings.DEFAULT_PROXY_HOST
        }
        settings.proxyPort = binding.inputProxyPort.text.toString().ifBlank {
            Settings.DEFAULT_PROXY_PORT
        }

        ProxyManager.applyFromSettings(settings) {
            runOnUiThread {
                Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show()
            }
        }
        // New tabs pick up the security-mode change automatically (read live
        // from Settings). Existing tabs keep their current WebSettings until
        // reloaded/recreated - that's intentional, not a bug: a setting that
        // could change mid-load on an open tab is a worse security property.
        finish()
    }
}
