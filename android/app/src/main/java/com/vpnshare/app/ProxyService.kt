package com.vpnshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.vpnshare.app.ACTION_START"
        const val ACTION_STOP = "com.vpnshare.app.ACTION_STOP"
        const val CHANNEL_ID = "vpn_share_service_channel"
        const val NOTIFICATION_ID = 1001

        var isServiceRunning = false
            private set

        var activeConnections = 0
            private set
        var bytesUploaded = 0L
            private set
        var bytesDownloaded = 0L
            private set

        var onStateChangeListener: ((isRunning: Boolean) -> Unit)? = null
        var onStatsUpdateListener: ((conns: Int, tx: Long, rx: Long) -> Unit)? = null

        fun startService(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var httpProxyServer: HttpProxyServer? = null
    private var socks5ProxyServer: Socks5ProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxyServers()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startProxyServers()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startProxyServers() {
        if (isServiceRunning) return

        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification("VPN Sharing is Active", "Listening on port 8080 & 1080"))

        val statsCallback = { _: Int, _: Long, _: Long ->
            val totalConns = (httpProxyServer?.activeConnections?.get() ?: 0) + (socks5ProxyServer?.activeConnections?.get() ?: 0)
            val totalTx = (httpProxyServer?.totalBytesSent?.get() ?: 0) + (socks5ProxyServer?.totalBytesSent?.get() ?: 0)
            val totalRx = (httpProxyServer?.totalBytesReceived?.get() ?: 0) + (socks5ProxyServer?.totalBytesReceived?.get() ?: 0)

            activeConnections = totalConns.toInt()
            bytesUploaded = totalTx
            bytesDownloaded = totalRx

            onStatsUpdateListener?.invoke(activeConnections, bytesUploaded, bytesDownloaded)
        }

        httpProxyServer = HttpProxyServer(port = 8080, onStatsUpdated = statsCallback)
        socks5ProxyServer = Socks5ProxyServer(port = 1080, onStatsUpdated = statsCallback)

        httpProxyServer?.start()
        socks5ProxyServer?.start()

        isServiceRunning = true
        onStateChangeListener?.invoke(true)
    }

    private fun stopProxyServers() {
        httpProxyServer?.stop()
        socks5ProxyServer?.stop()
        httpProxyServer = null
        socks5ProxyServer = null

        releaseLocks()

        isServiceRunning = false
        activeConnections = 0
        onStateChangeListener?.invoke(false)
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VpnShare::WakeLock")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VpnShare::WifiLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Share Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for sharing phone VPN with PC"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pLaunch)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopProxyServers()
        super.onDestroy()
    }
}
