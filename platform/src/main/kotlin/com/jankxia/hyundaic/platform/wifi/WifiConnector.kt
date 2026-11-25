package com.jankxia.hyundaic.platform.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log

/**
 * 让车机（STA 角色）连上指定热点。
 *
 * 车机只是 WiFi 客户端，自己不开热点：手机先开个人热点，把名称、密码交给这里，车机主动去连。
 * 安卓 4.4 上 `addNetwork` / `enableNetwork` 对普通应用就可用，不需要系统签名，也不需要 root
 * （安卓 10 之后才收紧到需要系统权限，车机不是那个版本）。只需要 `ACCESS_WIFI_STATE` 与 `CHANGE_WIFI_STATE`。
 *
 * 连上之后车机与手机在同一局域网，投屏数据面就能通；认证本身走蓝牙，与这条链路无关。
 *
 * 所有方法都会阻塞（最长 [timeoutMs]），调用方不要放在主线程。
 *
 * 权限由调用方确认（manifest 里声明了 `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`；安卓 6 起读已配网络还要位置权限，
 * 开始无线连接时的 `WirelessPermissions` 会一并确认）。4.4 上这些是普通权限，装上就有。没权限时这里转成原因返回，不抛给界面。
 */
@SuppressLint("MissingPermission")
class WifiConnector(private val context: Context) {

    sealed class Result {
        /** 已连上并拿到地址。 */
        class Connected(val ssid: String, val ipAddress: String) : Result()

        /** 没连上，[reason] 是可以直接显示给车主的原因。 */
        class Failed(val reason: String) : Result()
    }

    /**
     * 连到 [ssid]（密码为空按开放网络处理）。已经配过且密码相同就复用记录，不同就删掉重配。
     * 返回前会把 WiFi 打开、断开当前连接、启用目标网络并轮询到拿到地址或超时。
     */
    fun connect(ssid: String, password: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val name = ssid.trim()
        if (name.isEmpty()) return Result.Failed("热点名称是空的")
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Result.Failed("这台车机没有 WiFi 服务")
        return try {
            connect(manager, name, password, timeoutMs)
        } catch (e: SecurityException) {
            Log.w(TAG, "没有改 WiFi 的权限", e)
            Result.Failed("没有改 WiFi 的权限：${e.message ?: "SecurityException"}")
        }
    }

    @Suppress("DEPRECATION")
    private fun connect(manager: WifiManager, ssid: String, password: String, timeoutMs: Long): Result {
        if (!manager.isWifiEnabled) {
            Log.i(TAG, "WiFi 没开，先打开")
            manager.isWifiEnabled = true
            val until = SystemClock.uptimeMillis() + WIFI_ENABLE_WAIT_MS
            while (!manager.isWifiEnabled && SystemClock.uptimeMillis() < until) Thread.sleep(200)
            if (!manager.isWifiEnabled) return Result.Failed("打不开 WiFi：车机可能把 WiFi 开关锁了")
        }

        val quoted = "\"" + ssid + "\""
        val existing = manager.configuredNetworks.orEmpty().firstOrNull { it.SSID == quoted }
        val netId = when {
            existing == null -> addNetwork(manager, ssid, password)
            // 同一热点、同一密码（含都是开放网络）：直接用原来的记录
            sameKey(existing, password) -> existing.networkId
            else -> {
                Log.i(TAG, "热点密码变了，删掉旧记录重配：$ssid")
                manager.removeNetwork(existing.networkId)
                addNetwork(manager, ssid, password)
            }
        }
        if (netId < 0) return Result.Failed("系统不接受这个热点配置：检查名称和密码（密码至少 8 位），或先在车机设置里删掉同名网络")

        manager.disconnect()
        if (!manager.enableNetwork(netId, true)) return Result.Failed("系统不让启用这个网络（id=$netId）")
        manager.reconnect()

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val info = manager.connectionInfo
            if (info != null && info.ssid == quoted) {
                val state = info.supplicantState
                if (state == SupplicantState.COMPLETED || info.ipAddress != 0) {
                    val ip = formatIp(info.ipAddress)
                    Log.i(TAG, "已连上热点 $ssid，地址 $ip")
                    return Result.Connected(ssid, ip)
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return Result.Failed(
            "20 秒内没连上：确认手机热点已打开、密码对、频段是 2.4 GHz（4.4 车机多半不支持 5 GHz）；" +
                "也可以到车机系统设置里手动连一次这个热点再回来",
        )
    }

    private fun addNetwork(manager: WifiManager, ssid: String, password: String): Int {
        val config = WifiConfiguration().apply {
            SSID = "\"" + ssid + "\""
            if (password.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"" + password + "\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        return manager.addNetwork(config)
    }

    @Suppress("DEPRECATION")
    private fun sameKey(config: WifiConfiguration, password: String): Boolean {
        val stored = config.preSharedKey ?: ""
        return if (password.isEmpty()) stored.isEmpty() else stored == "\"" + password + "\""
    }

    private fun formatIp(address: Int): String {
        if (address == 0) return "已连上（还没拿到地址）"
        return listOf(24, 16, 8, 0).joinToString(".") { shift -> ((address ushr shift) and 0xFF).toString() }
    }

    private companion object {
        const val TAG = "WifiConnector"
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val WIFI_ENABLE_WAIT_MS = 5_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
