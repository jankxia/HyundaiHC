package com.jankxia.hyundaic.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import android.annotation.TargetApi
import java.util.UUID

/**
 * 车机的一次 BLE 广播：[start] 之后到 [stop] 之间发同一份数据；要换数据就停掉这个、再开一个新的。
 * BLE 广播是安卓 5.0（API 21）起才有的接口，更早的系统（含 4.4）用不了，见 [support]。
 *
 * 广播参数：传统广播、可连接（因而可扫描）、低延迟档（约 100 ms 一次）、中档发射功率（约 −7 dBm）、不超时；
 * 广播数据只有挂在 [serviceDataUuid] 下的一段 Service Data，不带设备名和功率字段，扫描响应为空。
 * 可连接广播最前面的 Flags 由系统蓝牙栈自己加。用的是安卓 5.0 起的老接口 `startAdvertising`：
 * 新系统内部把它换算成同样的广播集参数（低延迟 = 间隔 160 × 0.625 ms），安卓 6.0 起只走这一条路径。
 *
 * **发送地址由系统蓝牙栈决定**（开了 LE 隐私就是随机地址），应用既改不了也读不到；要在真机上用别的设备扫描核对。
 *
 * 回调在主线程上（系统接口如此），里面不要阻塞。[stop] 可以在任意时刻、任意线程调用，可重复调用：
 * 还在启动中时先记下，等系统回了「已开始」再真正停掉（启动没完成就停，部分系统版本会漏停）。
 *
 * 权限：安卓 12 起要「附近的设备」里的广播权限（BLUETOOTH_ADVERTISE），更早是清单里的普通蓝牙权限；没有时报 onFailed。
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class BleBeacon(
    private val adapter: BluetoothAdapter,
    private val serviceDataUuid: UUID,
    payload: ByteArray,
    private val events: Events,
) {
    interface Events {
        /** 系统确认开始广播。 */
        fun onStarted()

        /** 没发出去。[permanent] 为 true 表示这台车机这样发不了（不支持、数据太长），重试没用。 */
        fun onFailed(message: String, permanent: Boolean)
    }

    /** 车机能不能发 BLE 广播。 */
    sealed class Support {
        object Available : Support() {
            override fun toString() = "支持"
        }

        class Unavailable(val reason: String) : Support() {
            override fun toString() = "不支持（$reason）"
        }
    }

    private enum class State { NEW, STARTING, STOP_PENDING, STARTED, STOPPED }

    private val data = payload.copyOf()
    private val lock = Any()
    private var state = State.NEW
    private var advertiser: BluetoothLeAdvertiser? = null

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            val previous = synchronized(lock) {
                val s = state
                if (s == State.STARTING) state = State.STARTED
                if (s == State.STOP_PENDING) state = State.STOPPED
                s
            }
            when (previous) {
                State.STARTING -> events.onStarted()
                // 启动中被要求停：现在才真正停掉。
                State.STOP_PENDING -> stopAdvertising()
                else -> Unit
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val previous = synchronized(lock) { state.also { state = State.STOPPED } }
            if (previous == State.STARTING) events.onFailed(describe(errorCode), permanent(errorCode))
        }
    }

    /** 开始广播。只能调用一次；结果经 [Events] 回来（开不了的情况可能在本方法里同步回调）。 */
    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，下面按失败上报。
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            check(state == State.NEW) { "start 只能调用一次" }
            state = State.STARTING
        }
        val le = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: RuntimeException) {
            null
        }
        if (le == null) return fail("系统没有提供广播接口（蓝牙没开，或车机不支持 BLE 广播）", permanent = false)
        synchronized(lock) { advertiser = le }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(serviceDataUuid), data)
            .build()
        try {
            le.startAdvertising(settings, advertiseData, callback)
        } catch (e: SecurityException) {
            fail("没有广播权限（安卓 12 起要「附近的设备」权限）：${describe(e)}", permanent = false)
        } catch (e: RuntimeException) {
            fail("开不了广播：${describe(e)}", permanent = false)
        }
    }

    /** 停止广播。可重复调用；之后不再回调。 */
    fun stop() {
        val started = synchronized(lock) {
            when (state) {
                // 还在启动中：等系统回了结果再停（见类注释）。
                State.STARTING -> {
                    state = State.STOP_PENDING
                    false
                }
                State.STARTED -> {
                    state = State.STOPPED
                    true
                }
                State.NEW -> {
                    state = State.STOPPED
                    false
                }
                State.STOP_PENDING, State.STOPPED -> false
            }
        }
        if (started) stopAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val le = synchronized(lock) { advertiser } ?: return
        try {
            le.stopAdvertising(callback)
        } catch (e: RuntimeException) {
            // 蓝牙已经关了、权限被收回等：广播本来也停了。
        }
    }

    private fun fail(message: String, permanent: Boolean) {
        val report = synchronized(lock) { (state == State.STARTING).also { state = State.STOPPED } }
        if (report) events.onFailed(message, permanent)
    }

    companion object {
        /**
         * 车机能不能发 BLE 广播：没有蓝牙、蓝牙没开、系统报告不支持多广播（`isMultipleAdvertisementSupported`）都算不能。
         * 系统在蓝牙关着时一律报不支持，所以先确认蓝牙开着再问。
         */
        fun support(adapter: BluetoothAdapter?): Support {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return Support.Unavailable("BLE 广播要安卓 5.0 起（这台是 ${Build.VERSION.RELEASE}）")
            }
            if (adapter == null) return Support.Unavailable("没有蓝牙")
            val on = try {
                adapter.isEnabled
            } catch (e: RuntimeException) {
                false
            }
            if (!on) return Support.Unavailable("蓝牙没开")
            val multiple = try {
                adapter.isMultipleAdvertisementSupported
            } catch (e: RuntimeException) {
                false
            }
            return if (multiple) Support.Available else Support.Unavailable("系统报告不支持 BLE 广播")
        }

        /** 这些失败重试也没用。 */
        private fun permanent(errorCode: Int): Boolean =
            errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ||
                errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE

        private fun describe(errorCode: Int): String = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据太长"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "系统的广播实例用完了（别的应用也在广播）"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已经在广播"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "系统蓝牙内部错误"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "车机不支持 BLE 广播"
            else -> "错误码 $errorCode"
        }

        private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
