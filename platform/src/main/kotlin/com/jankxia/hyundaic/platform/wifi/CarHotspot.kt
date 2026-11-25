package com.jankxia.hyundaic.platform.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 手机开热点、车机连它：车主填热点名称、密码、频段，HyundaiHC 不开任何热点，只把车机当前连着的这个网络
 * 原样写进给手机的连接串。toString 不含名称和密码。
 *
 * 为什么是这个方向：帕里斯帝这台安卓 4.4 车机没有开热点的能力（系统连本地热点都是 8.0 才有），
 * 只能当 WiFi 客户端连别人开的热点；手机开个人热点、车机连上，是唯一可行的无线数据面。
 */
class CarHotspotSettings(val networkName: String, val passphrase: String, val band: WifiBand) {
    override fun toString() = "CarHotspotSettings(${band.label}，网络名与密码不显示)"
}

/**
 * 把「车机已经连着的手机热点」交给无线连接用，不开本地热点。用法同 [LocalHotspotHandle]。
 *
 * 车机是 WiFi 客户端，本身读不到也开不了热点，所以：
 * - 热点由手机开、车机自己连（首页的「让车机连上」用 [WifiConnector] 代劳，车主也可以在系统设置里手动连）。
 *   [start] 后先看车机现在连着的 WiFi：名称对得上填的那个就当它，名称对不上就明说不连；
 * - 名称、密码用车主填的（密码只用于写进连接串，车机侧已经由系统记住了）；BSSID 读系统回报的当前连接
 *   （[WifiManager.getConnectionInfo]），读不到用占位值；
 * - 频率优先用当前连接的实际频率（安卓 5.0 起读得到），读不到按车主选的频段推测
 *   （[LocalHotspotRules.estimateFrequency]）：同一频段内信道报错了手机还能自己找到（2026-11 实车）。
 * - 网络是手机开着的，[stop] 不断开它；开着期间每 [WATCH_MS] 看一次地址还在不在，断了报 [LocalHotspotEvents.onLost]。
 *
 * 老版本里「车机自己开热点」的做法（找开着的热点网卡）只在真能开热点的车机上还有意义，作为兜底保留。
 * 线程：自己的工作线程；[LocalHotspotEvents] 在这条线程上回调。
 */
class CarHotspot(
    context: Context,
    private val settings: CarHotspotSettings,
    private val events: LocalHotspotEvents,
    private val log: (String) -> Unit,
) : LocalHotspotHandle {
    private val app: Context = context.applicationContext ?: context
    private val wifi: WifiManager? = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val thread = HandlerThread("HyundaiHC-CarHotspot").also { it.start() }
    private val handler = Handler(thread.looper)

    // 以下只在工作线程上访问。
    private var stopped = false
    private var deadline = 0L
    private var ready: LocalHotspot? = null
    private var mismatchLogged = false
    private val find = Runnable { find() }
    private val watch = Runnable { watch() }

    override fun start() {
        handler.post {
            deadline = now() + FIND_WAIT_MS
            log("用手机开的热点（$settings）：看车机现在连着的 WiFi")
            find()
        }
    }

    override fun stop() {
        handler.post {
            if (stopped) return@post
            stopped = true
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    private fun find() {
        if (stopped || ready != null) return
        // 车机是客户端：它现在连着的那个热点就是要用的网络。刚连上时地址可能还没到手，等一会儿。
        stationNetwork()?.let { network ->
            readyFromStation(network)
            return
        }
        if (now() < deadline) {
            handler.postDelayed(find, POLL_MS)
            return
        }
        // 走到这里说明车机没连（或连的不是填的）这个热点。兜底：真能开热点的车机按老办法找那个热点网卡。
        if (findHostingInterface()) return
        stopped = true
        events.onFailed(
            "车机没连上手机热点：先在车机系统设置里连上「${settings.networkName}」" +
                "（或用首页的「让车机连上」），再开始无线连接（等了 ${FIND_WAIT_MS / 1000} 秒）",
        )
    }

    /** 车机当前连着的 WiFi；名称和填的对不上时返回 null（只记一次日志，每 500 毫秒一次太吵）。 */
    @Suppress("DEPRECATION")
    private fun stationNetwork(): StationNetwork? = try {
        val info = wifi?.connectionInfo
        val rawIp = info?.ipAddress ?: 0
        if (info == null || info.networkId == -1 || rawIp == 0) {
            null
        } else {
            val ssid = LocalHotspotRules.networkName(info.ssid) ?: ""
            val wanted = settings.networkName.trim()
            if (ssid.isEmpty() || (wanted.isNotEmpty() && !ssid.equals(wanted, ignoreCase = true))) {
                if (!mismatchLogged) {
                    mismatchLogged = true
                    log("车机连着的 WiFi 是「$ssid」，不是填的「$wanted」：连对热点再开始")
                }
                null
            } else {
                val ip = ipText(rawIp)
                val interfaceName = InterfaceAddresses.ipv4Snapshot().entries.firstOrNull { ip in it.value }?.key ?: "wlan0"
                StationNetwork(ssid, info.bssid, stationFrequency(info), ip, interfaceName)
            }
        }
    } catch (e: RuntimeException) {
        log("读不到车机当前连着的 WiFi：${e.javaClass.simpleName}：${e.message}")
        null
    }

    /** 车机已经连在手机热点上：把当前连接写成 [LocalHotspot] 交给连接用。 */
    private fun readyFromStation(network: StationNetwork) {
        val (bssid, macSource) = MacText.normalize(network.bssid)?.let { it to HotspotMacSource.REPORTED }
            ?: (MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER)
        val (frequency, frequencySource) = if (network.frequencyMhz > 0) {
            network.frequencyMhz to HotspotFrequencySource.REPORTED
        } else {
            LocalHotspotRules.estimateFrequency(HotspotBandHint(settings.band, 0))
        }
        val hotspot = LocalHotspot(
            network.ip,
            network.ssid,
            bssid,
            settings.passphrase,
            frequency,
            network.interfaceName,
            macSource,
            frequencySource,
            HotspotSecurity.OK,
        )
        ready = hotspot
        log("车机已连着手机热点：$hotspot；频率：${frequencySource.label}；BSSID：${macSource.label}")
        if (macSource == HotspotMacSource.PLACEHOLDER) log("系统没给出当前连接的 BSSID，连接串里用占位值：手机按名称也能找到这个热点")
        events.onReady(hotspot)
        handler.postDelayed(watch, WATCH_MS)
    }

    /**
     * 兜底：车机真能开热点时，车主在设置里打开它，这里找那个开着的热点网卡。
     * 找不到返回 false（由调用方报失败）。
     */
    private fun findHostingInterface(): Boolean {
        val picked = LocalHotspotRules.pickInterface(emptyMap(), InterfaceAddresses.ipv4Snapshot(), stationIpv4())
        if (picked == null) return false
        val (iface, ip) = picked
        val hardware = InterfaceAddresses.hardwareAddress(iface)
        val linkLocal = InterfaceAddresses.linkLocalMac(iface)
        val (bssid, macSource) = LocalHotspotRules.pickBssid(null, hardware, null, linkLocal)
        log(
            "这台车机自己能开热点：网卡 $iface（$ip）：硬件地址 ${MacText.mask(MacText.of(hardware) ?: "读不到")}，" +
                "由 IPv6 链路本地地址反推 ${MacText.mask(linkLocal ?: "推不出")}；BSSID 用 ${macSource.label}",
        )
        InterfaceAddresses.crossCheck(MacText.of(hardware), linkLocal)?.let(log)
        val (frequency, frequencySource) = LocalHotspotRules.estimateFrequency(HotspotBandHint(settings.band, 0))
        val hotspot = LocalHotspot(
            ip, settings.networkName, bssid, settings.passphrase, frequency, iface, macSource, frequencySource, HotspotSecurity.OK,
        )
        ready = hotspot
        log("车机自带热点就绪：$hotspot；频率：${frequencySource.label}")
        events.onReady(hotspot)
        handler.postDelayed(watch, WATCH_MS)
        return true
    }

    private fun watch() {
        val current = ready ?: return
        if (stopped) return
        val addresses = InterfaceAddresses.ipv4Snapshot()[current.interfaceName].orEmpty()
        if (current.localIp !in addresses) {
            stopped = true
            events.onLost("车机连着的热点断了（网卡 ${current.interfaceName} 上没有地址了）")
            return
        }
        handler.postDelayed(watch, WATCH_MS)
    }

    /** 车机作为终端正连着的 WiFi 的频率，安卓 5.0 以前读不到返回 0。 */
    @Suppress("DEPRECATION")
    private fun stationFrequency(info: android.net.wifi.WifiInfo): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency.coerceAtLeast(0) else 0
    } catch (e: RuntimeException) {
        0
    }

    /** 车机作为终端连着的 WiFi 上的 IPv4，没连为 null：选热点网卡时排除它。 */
    @Suppress("DEPRECATION")
    private fun stationIpv4(): String? = try {
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else ipText(ip)
    } catch (e: RuntimeException) {
        null
    }

    private fun ipText(address: Int): String =
        "${address and 0xFF}.${address ushr 8 and 0xFF}.${address ushr 16 and 0xFF}.${address ushr 24 and 0xFF}"

    private fun now() = SystemClock.elapsedRealtime()

    /** 车机当前连着的 WiFi 的信息，字段都来自系统回报。 */
    private class StationNetwork(
        val ssid: String,
        val bssid: String?,
        val frequencyMhz: Int,
        val ip: String,
        val interfaceName: String,
    )

    private companion object {
        /** 车机刚连上热点时地址可能还没到手，最多等这么久。 */
        const val FIND_WAIT_MS = 10_000L
        const val POLL_MS = 500L
        const val WATCH_MS = 2_000L
    }
}
