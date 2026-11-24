package com.jankxia.hyundaic.wireless

// 「等已配对的手机回连」时 BLE 广播和 GATT 服务端的开关顺序。本文件是纯逻辑，不引用安卓类，由 JVM 单元测试覆盖；
// 按动作开关平台对象的接线见 ReconnectBeacon。

/** 这次连接里 BLE 广播的情况（界面、日志用）。 */
sealed class BeaconStatus {
    /** 正在开（GATT 服务、广播）。 */
    object Starting : BeaconStatus() {
        override fun toString() = "Starting"
    }

    /** 在发。[publishes] 是这次连接里发布了几次（每换一次序列号算一次）。 */
    data class Advertising(val publishes: Int) : BeaconStatus()

    /** 这次没发出去，[retryInMillis] 毫秒后再试。 */
    data class Retrying(val reason: String, val retryInMillis: Long) : BeaconStatus()

    /** 发不了（车机不支持、数据太长），这次连接不再试。 */
    data class Failed(val reason: String) : BeaconStatus()

    /** 停了（手机连上、连接结束）。 */
    object Stopped : BeaconStatus() {
        override fun toString() = "Stopped"
    }
}

/**
 * 广播和 GATT 服务端的开关顺序。输入是事件，输出是按顺序要做的动作；平台对象由调用方按动作开关，结果再交回来。
 * 只在一个线程上调用。
 *
 * 顺序：
 * 1. 蓝牙控制监听开好（[onListenerReady]，手机连上来有地方接）→ 开 GATT 服务端（两个服务都加好 [onGattReady]）→ 开广播；
 *    GATT 开不了照样发广播（实测的手机回连时不连 GATT）。
 * 2. 广播开始（[onAdvertisingStarted]）后，每隔 [Config.republishMillis] 换一个序列号重新发布（停掉、用新序列号再开）；
 *    0 表示不重发。
 * 3. 没发出去：永久失败（车机不支持）关掉 GATT、不再试；否则 [Config.retryMillis] 后换序列号再试。
 * 4. 有客户端连上 GATT（[onGattClientConnected]）：部分车机在可连接广播被连上后就不再发了，用同一个序列号停掉再开一次。
 * 5. [republishNow]（手机的经典蓝牙刚连上）：不等定时，马上换序列号重新发布，让手机把它当成一次新的发现。
 * 6. [stop]（手机经蓝牙连上、连接结束、兜底）：取消定时、停广播、关 GATT，之后什么都不做。可重复调用。
 *
 * 资源记账：[gattOpen]、[advertising]、[timerPending] 在发出「开」的动作时置位、发出「关」的动作时清掉
 * （平台对象自己失败结束的也清掉）；[stop] 之后三者都是 false（[isClean]），迟到的平台回调不会再开任何东西。
 */
internal class ReconnectBeaconMachine(
    private val config: Config,
    /** 下一个序列号（每次重新发布取一个）。 */
    private val nextSequence: () -> Int,
) {
    class Config(
        /** 挂不挂 GATT 服务端。 */
        val gatt: Boolean,
        /** 多久换一次序列号重新发布；0 表示不重发。 */
        val republishMillis: Long,
        /** 广播没发出去（不是永久失败）时多久后再试。 */
        val retryMillis: Long = DEFAULT_RETRY_MILLIS,
    ) {
        init {
            require(republishMillis >= 0 && retryMillis > 0) { "时间不能为负，重试间隔要大于 0" }
        }
    }

    sealed class Action {
        object OpenGatt : Action() {
            override fun toString() = "OpenGatt"
        }

        object CloseGatt : Action() {
            override fun toString() = "CloseGatt"
        }

        /** 用序列号 [sequence] 拼载荷，开一次新的广播。 */
        data class StartAdvertising(val sequence: Int) : Action()

        object StopAdvertising : Action() {
            override fun toString() = "StopAdvertising"
        }

        /** [delayMillis] 后调 [onTimer]；同时只有一个定时，新的替换旧的。 */
        data class StartTimer(val delayMillis: Long) : Action()

        object CancelTimer : Action() {
            override fun toString() = "CancelTimer"
        }

        data class Status(val status: BeaconStatus) : Action()
    }

    private enum class Phase { IDLE, OPENING_GATT, STARTING, ADVERTISING, WAITING_RETRY, FAILED, STOPPED }

    private var phase = Phase.IDLE
    private var sequence = NO_SEQUENCE
    private var publishes = 0

    /** 正在开的这次广播只是「关了再开」（序列号不变），不算一次新的发布。 */
    private var reopening = false

    /** GATT 服务端开着（含正在开）。 */
    var gattOpen = false
        private set

    /** 广播开着（含正在开）。 */
    var advertising = false
        private set

    /** 有一个定时在等。 */
    var timerPending = false
        private set

    /** 全部关掉了：没有开着的 GATT、广播和定时。 */
    val isClean: Boolean get() = !gattOpen && !advertising && !timerPending

    val isStopped: Boolean get() = phase == Phase.STOPPED

    /** 当前（最近一次）用的序列号；还没发过为 -1。 */
    val currentSequence: Int get() = sequence

    fun onListenerReady(): List<Action> {
        if (phase != Phase.IDLE) return emptyList()
        val out = ArrayList<Action>(3)
        out += Action.Status(BeaconStatus.Starting)
        if (config.gatt) {
            phase = Phase.OPENING_GATT
            gattOpen = true
            out += Action.OpenGatt
        } else {
            startAdvertising(newSequence = true, out)
        }
        return out
    }

    fun onGattReady(): List<Action> {
        if (phase != Phase.OPENING_GATT) return emptyList()
        return ArrayList<Action>(1).also { startAdvertising(newSequence = true, it) }
    }

    /** GATT 服务端开不了（平台对象已经自己关掉）：不影响广播。 */
    fun onGattFailed(): List<Action> {
        if (!gattOpen) return emptyList()
        gattOpen = false
        if (phase != Phase.OPENING_GATT) return emptyList()
        return ArrayList<Action>(1).also { startAdvertising(newSequence = true, it) }
    }

    fun onAdvertisingStarted(): List<Action> {
        if (phase != Phase.STARTING) return emptyList()
        phase = Phase.ADVERTISING
        if (!reopening) publishes++
        reopening = false
        val out = ArrayList<Action>(2)
        out += Action.Status(BeaconStatus.Advertising(publishes))
        if (config.republishMillis > 0) startTimer(config.republishMillis, out)
        return out
    }

    /** 广播没发出去（平台对象已经结束）。 */
    fun onAdvertisingFailed(reason: String, permanent: Boolean): List<Action> {
        if (phase != Phase.STARTING) return emptyList()
        advertising = false
        reopening = false
        val out = ArrayList<Action>(3)
        if (permanent) {
            phase = Phase.FAILED
            closeGatt(out)
            out += Action.Status(BeaconStatus.Failed(reason))
        } else {
            phase = Phase.WAITING_RETRY
            startTimer(config.retryMillis, out)
            out += Action.Status(BeaconStatus.Retrying(reason, config.retryMillis))
        }
        return out
    }

    /** 定时到了：在发就换序列号重新发布，在等重试就再开一次。 */
    fun onTimer(): List<Action> {
        if (!timerPending) return emptyList()
        timerPending = false
        val out = ArrayList<Action>(2)
        when (phase) {
            Phase.ADVERTISING -> {
                stopAdvertising(out)
                startAdvertising(newSequence = true, out)
            }
            Phase.WAITING_RETRY -> startAdvertising(newSequence = true, out)
            Phase.IDLE, Phase.OPENING_GATT, Phase.STARTING, Phase.FAILED, Phase.STOPPED -> Unit
        }
        return out
    }

    /**
     * 马上换序列号重新发布（不等定时）：在发就停掉、用新序列号再开；在等重试就马上重试。其他时候（还在开、已经停了）不用做。
     */
    fun republishNow(): List<Action> {
        val out = ArrayList<Action>(3)
        when (phase) {
            Phase.ADVERTISING -> {
                cancelTimer(out)
                stopAdvertising(out)
                startAdvertising(newSequence = true, out)
            }
            Phase.WAITING_RETRY -> {
                cancelTimer(out)
                startAdvertising(newSequence = true, out)
            }
            Phase.IDLE, Phase.OPENING_GATT, Phase.STARTING, Phase.FAILED, Phase.STOPPED -> Unit
        }
        return out
    }

    /** 有客户端连上了 GATT：在发的话关了再开一次，序列号不变（不让手机当成新发现）。 */
    fun onGattClientConnected(): List<Action> {
        if (phase != Phase.ADVERTISING) return emptyList()
        val out = ArrayList<Action>(3)
        cancelTimer(out)
        stopAdvertising(out)
        reopening = true
        startAdvertising(newSequence = false, out)
        return out
    }

    /** 全部关掉，之后什么都不做。可重复调用。 */
    fun stop(): List<Action> {
        if (phase == Phase.STOPPED) return emptyList()
        val wasIdle = phase == Phase.IDLE
        phase = Phase.STOPPED
        val out = ArrayList<Action>(4)
        cancelTimer(out)
        stopAdvertising(out)
        closeGatt(out)
        if (!wasIdle) out += Action.Status(BeaconStatus.Stopped)
        return out
    }

    private fun startAdvertising(newSequence: Boolean, out: MutableList<Action>) {
        if (newSequence || sequence == NO_SEQUENCE) sequence = nextSequence()
        phase = Phase.STARTING
        advertising = true
        out += Action.StartAdvertising(sequence)
    }

    private fun stopAdvertising(out: MutableList<Action>) {
        if (!advertising) return
        advertising = false
        out += Action.StopAdvertising
    }

    private fun closeGatt(out: MutableList<Action>) {
        if (!gattOpen) return
        gattOpen = false
        out += Action.CloseGatt
    }

    private fun startTimer(delayMillis: Long, out: MutableList<Action>) {
        timerPending = true
        out += Action.StartTimer(delayMillis)
    }

    private fun cancelTimer(out: MutableList<Action>) {
        if (!timerPending) return
        timerPending = false
        out += Action.CancelTimer
    }

    companion object {
        /** 广播没发出去后多久再试。 */
        const val DEFAULT_RETRY_MILLIS = 30_000L
        private const val NO_SEQUENCE = -1
    }
}
