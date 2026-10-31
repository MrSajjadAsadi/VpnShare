package com.vpnshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOpenHotspot: Button
    private lateinit var btnWifiDirect: Button
    private lateinit var layoutP2pInfo: LinearLayout
    private lateinit var tvP2pSsid: TextView
    private lateinit var tvP2pPassword: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvActiveConnections: TextView

    private var wifiDirectManager: WifiDirectManager? = null
    private var isP2pActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()
        initViews()
        setupListeners()
        initWifiDirect()
    }

    override fun onResume() {
        super.onResume()
        updateUiState(ProxyService.isServiceRunning)
        updateIpAddress()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnOpenHotspot = findViewById(R.id.btnOpenHotspot)
        btnWifiDirect = findViewById(R.id.btnWifiDirect)
        layoutP2pInfo = findViewById(R.id.layoutP2pInfo)
        tvP2pSsid = findViewById(R.id.tvP2pSsid)
        tvP2pPassword = findViewById(R.id.tvP2pPassword)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvDownload = findViewById(R.id.tvDownload)
        tvUpload = findViewById(R.id.tvUpload)
        tvActiveConnections = findViewById(R.id.tvActiveConnections)
    }

    private fun initWifiDirect() {
        wifiDirectManager = WifiDirectManager(this).apply {
            onGroupCreated = { ssid, pass ->
                runOnUiThread {
                    isP2pActive = true
                    layoutP2pInfo.visibility = View.VISIBLE
                    tvP2pSsid.text = ssid
                    tvP2pPassword.text = pass
                    btnWifiDirect.text = "توقف Wi-Fi Direct"
                    btnWifiDirect.setBackgroundColor(Color.parseColor("#DC2626"))
                    Toast.makeText(this@MainActivity, "نقطه اتصال Wi-Fi Direct فعال شد", Toast.LENGTH_SHORT).show()

                    // Automatically start proxy server if not already running
                    if (!ProxyService.isServiceRunning) {
                        ProxyService.startService(this@MainActivity)
                    }
                }
            }

            onError = { errMsg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            if (ProxyService.isServiceRunning) {
                ProxyService.stopService(this)
                Toast.makeText(this, "سرویس متوقف شد", Toast.LENGTH_SHORT).show()
            } else {
                ProxyService.startService(this)
                Toast.makeText(this, "سرویس فعال شد (پورت 8080 و 1080)", Toast.LENGTH_SHORT).show()
            }
        }

        btnWifiDirect.setOnClickListener {
            if (isP2pActive) {
                wifiDirectManager?.stopDirectHotspot()
                isP2pActive = false
                layoutP2pInfo.visibility = View.GONE
                btnWifiDirect.text = "ایجاد نقطه اتصال Wi-Fi Direct"
                btnWifiDirect.setBackgroundColor(Color.parseColor("#059669"))
            } else {
                tvP2pSsid.text = "در حال ایجاد..."
                tvP2pPassword.text = "-"
                layoutP2pInfo.visibility = View.VISIBLE
                wifiDirectManager?.startDirectHotspot()
            }
        }

        btnOpenHotspot.setOnClickListener {
            try {
                val intent = Intent("android.settings.TETHER_SETTINGS")
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "تنظیمات هات‌اسپات باز نشد", Toast.LENGTH_SHORT).show()
                }
            }
        }

        ProxyService.onStateChangeListener = { isRunning ->
            runOnUiThread {
                updateUiState(isRunning)
            }
        }

        ProxyService.onStatsUpdateListener = { conns, tx, rx ->
            runOnUiThread {
                tvActiveConnections.text = "اتصالات: $conns"
                tvUpload.text = "آپلود: ${NetworkUtils.formatBytes(tx)}"
                tvDownload.text = "دانلود: ${NetworkUtils.formatBytes(rx)}"
            }
        }
    }

    private fun updateUiState(isRunning: Boolean) {
        if (isRunning) {
            tvStatus.text = "سرویس فعال است (Active & Sharing)"
            tvStatus.setBackgroundColor(Color.parseColor("#065F46"))
            tvStatus.setTextColor(Color.parseColor("#34D399"))

            btnToggle.text = "توقف اشتراک گذاری"
            btnToggle.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            tvStatus.text = "سرویس غیرفعال است (Stopped)"
            tvStatus.setBackgroundColor(Color.parseColor("#334155"))
            tvStatus.setTextColor(Color.parseColor("#CBD5E1"))

            btnToggle.text = "شروع اشتراک VPN"
            btnToggle.setBackgroundColor(Color.parseColor("#2563EB"))
        }
        updateIpAddress()
    }

    private fun updateIpAddress() {
        val ip = NetworkUtils.getLocalIpAddress()
        tvIpAddress.text = ip
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        if (isP2pActive) {
            wifiDirectManager?.stopDirectHotspot()
        }
        super.onDestroy()
    }
}
