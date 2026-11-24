package com.jankxia.hyundaic.wireless

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import com.jankxia.hyundaic.core.common.identity.HostIdentity
import com.jankxia.hyundaic.core.nearby.BleAdvertisement
import com.jankxia.hyundaic.platform.bluetooth.BeaconGattServer
import com.jankxia.hyundaic.platform.bluetooth.BleBeacon
import com.jankxia.hyundaic.wireless.ReconnectBeaconMachine.Action

/**
 * 一次无线连接里的 BLE 广播和 GATT 服务端：按 [ReconnectBeaconMachine] 给出的动作开关平台对象
 * （[BleBeacon]、[BeaconGattServer]、定时），平台的结果交回状态机。
 *
 * 线程：全部方法都在调度线程（[handler]）上调用；平台回调在系统线程上，先投回 [handler]，
 * 并且只认当前那个平台对象的回调（换过的、关掉的不再理）。
 *
 * 用法：会话的蓝牙控制监听开好后 [onListenerReady]；手机经蓝牙连上、会话结束、兜底收尾时 [stop]（可重复）。
 */
internal class ReconnectBeacon(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val host: HostIdentity,
    private val options: Options,
    private val sequence: () -> Int,
    private val handler: Handler,
    private val onStatus: (BeaconStatus) -> Unit,
    private val log: (String) -> Unit,
) {
    /** 进入这次连接时的设置快照。 */
    class Options(
        val gatt: Boolean,
        val republishMillis: Long,
        val declareRandomAddress: Boolean,
    )

    private val app = context.applicationContext ?: context
    private val machine = ReconnectBeaconMachine(ReconnectBeaconMachine.Config(options.gatt, options.republishMillis), sequence)
    private var beacon: BleBeacon? = null
    private var gatt: BeaconGattServer? = null
    private val timer = Runnable { run(machine.onTimer()) }

    /** 全部关掉了（广播、GATT、定时都不在了）。 */
    val isClean: Boolean get() = machine.isClean && beacon == null && gatt == null

    fun onListenerReady() = run(machine.onListenerReady())

    /** 马上换序列号重新发布（手机的经典蓝牙刚连上时）。 */
    fun republishNow(reason: String) {
        val actions = machine.republishNow()
        if (actions.isEmpty()) return
        log("马上重新发布：$reason")
        run(actions)
    }

    fun stop(reason: String) {
        if (machine.isStopped) return
        log("停广播：$reason")
        run(machine.stop())
    }

    private fun run(actions: List<Action>) {
        for (action in actions) execute(action)
    }

    private fun execute(action: Action) {
        when (action) {
            Action.OpenGatt -> openGatt()
            Action.CloseGatt -> {
                gatt?.close()
                gatt = null
            }
            is Action.StartAdvertising -> startAdvertising(action.sequence)
            Action.StopAdvertising -> {
                beacon?.stop()
                beacon = null
            }
            is Action.StartTimer -> {
                handler.removeCallbacks(timer)
                handler.postDelayed(timer, action.delayMillis)
            }
            Action.CancelTimer -> handler.removeCallbacks(timer)
            is Action.Status -> onStatus(action.status)
        }
    }

    private fun openGatt() {
        val server = arrayOfNulls<BeaconGattServer>(1)
        val events = object : BeaconGattServer.Events {
            override fun onReady() = post(server) { run(machine.onGattReady()) }

            override fun onFailed(message: String) = post(server) {
                log("GATT 服务开不了（$message），只发广播")
                gatt = null
                run(machine.onGattFailed())
            }

            override fun onClientConnected(address: String) = post(server) { run(machine.onGattClientConnected()) }
        }
        val created = BeaconGattServer(app, events, log)
        server[0] = created
        gatt = created
        created.open()
    }

    private fun startAdvertising(seq: Int) {
        val payload = BleAdvertisement.payload(host, seq, BleAdvertisement.Options(declareRandomAddress = options.declareRandomAddress))
        val holder = arrayOfNulls<BleBeacon>(1)
        val events = object : BleBeacon.Events {
            override fun onStarted() = postBeacon(holder) {
                log("广播已开始：序列号 $seq，载荷 ${payload.size} 字节${if (options.declareRandomAddress) "（声明随机地址）" else ""}")
                run(machine.onAdvertisingStarted())
            }

            override fun onFailed(message: String, permanent: Boolean) = postBeacon(holder) {
                log("广播没发出去：$message${if (permanent) "（这台车机发不了）" else ""}")
                beacon = null
                run(machine.onAdvertisingFailed(message, permanent))
            }
        }
        val created = BleBeacon(adapter, BleAdvertisement.SERVICE_DATA_UUID, payload, events)
        holder[0] = created
        beacon = created
        created.start()
    }

    /** 平台回调投回调度线程，只认还是当前对象的那个。 */
    private fun post(owner: Array<BeaconGattServer?>, block: () -> Unit) {
        handler.post { if (owner[0] != null && owner[0] === gatt) block() }
    }

    private fun postBeacon(owner: Array<BleBeacon?>, block: () -> Unit) {
        handler.post { if (owner[0] != null && owner[0] === beacon) block() }
    }
}
