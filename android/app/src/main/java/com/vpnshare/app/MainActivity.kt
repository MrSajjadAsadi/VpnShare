package com.vpnshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOpenHotspot: Button
    private lateinit var tvIpAddress: TextView
    private lateinit var tvHttpPort: TextView
    private lateinit var tvSocks5Port: TextView
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvActiveConnections: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()
        initViews()
        setupListeners()
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
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvHttpPort = findViewById(R.id.tvHttpPort)
        tvSocks5Port = findViewById(R.id.tvSocks5Port)
        tvDownload = findViewById(R.id.tvDownload)
        tvUpload = findViewById(R.id.tvUpload)
        tvActiveConnections = findViewById(R.id.tvActiveConnections)
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

        btnOpenHotspot.setOnClickListener {
            try {
                // Open Tethering settings directly
                val intent = Intent()
                intent.action = "android.settings.TETHER_SETTINGS"
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "لطفاً هات‌اسپات را از تنظیمات گوشی روشن کنید", Toast.LENGTH_SHORT).show()
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
                tvActiveConnections.text = conns.toString()
                tvUpload.text = NetworkUtils.formatBytes(tx)
                tvDownload.text = NetworkUtils.formatBytes(rx)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
