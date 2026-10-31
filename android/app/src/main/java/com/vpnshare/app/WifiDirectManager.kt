package com.vpnshare.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

class WifiDirectManager(private val context: Context) {
    private val tag = "WifiDirectManager"
    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    var onGroupCreated: ((ssid: String, passphrase: String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        try {
            channel = p2pManager?.initialize(context, context.mainLooper, null)
        } catch (e: Exception) {
            Log.e(tag, "Failed to init p2p: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDirectHotspot() {
        if (p2pManager == null || channel == null) {
            onError?.invoke("Wi-Fi Direct در این دستگاه پشتیبانی نمی‌شود")
            return
        }

        // Remove old group first to avoid 'busy' state
        p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createGroupInternal()
            }
            override fun onFailure(reason: Int) {
                createGroupInternal()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupInternal() {
        p2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(tag, "Wi-Fi Direct Group created successfully")
                // Wait briefly for Android to generate SSID and Password
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fetchGroupInfo()
                }, 600)
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.BUSY -> "شبکه وای‌فای مشغول است (BUSY)"
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct پشتیبانی نمی‌شود"
                    else -> "کد خطا: $reason"
                }
                onError?.invoke("خطا در ایجاد Wi-Fi Direct: $reasonStr")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupInfo() {
        p2pManager?.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            if (group != null) {
                val ssid = group.networkName ?: "DIRECT-VpnShare"
                val pass = group.passphrase ?: "بدون پسورد"
                Log.i(tag, "Group Info: SSID=$ssid, Pass=$pass")
                onGroupCreated?.invoke(ssid, pass)
            } else {
                onError?.invoke("اطلاعات گروه وای‌فای دریافت نشد")
            }
        }
    }

    fun stopDirectHotspot() {
        try {
            p2pManager?.removeGroup(channel, null)
        } catch (_: Exception) {}
    }
}
