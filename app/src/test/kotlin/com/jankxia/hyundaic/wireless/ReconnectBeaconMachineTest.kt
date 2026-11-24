package com.jankxia.hyundaic.wireless

import com.jankxia.hyundaic.wireless.ReconnectBeaconMachine.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 等回连时广播和 GATT 的开关顺序，以及「收尾干净」：任何时候 stop，发出去的每个「开」都有对应的「关」，
 * 迟到的平台回调不会再开任何东西。序列号用合成的递增序列。
 */
class ReconnectBeaconMachineTest {

    /** 合成的序列号来源：从 [start] 起递增，模 256。 */
    private class Sequences(start: Int = 0xFE) {
        private var next = start
        fun take(): Int = next.also { next = (next + 1) and 0xFF }
    }

    private fun machine(gatt: Boolean = true, republish: Long = 120_000, retry: Long = 30_000, start: Int = 0xFE) =
        Sequences(start).let { seq -> ReconnectBeaconMachine(ReconnectBeaconMachine.Config(gatt, republish, retry)) { seq.take() } }

    /** 按动作记账的「平台」：开着的 GATT、广播、定时各有几个。 */
    private class Ledger {
        var gatt = 0
        var advertising = 0
        var timers = 0
        val started = ArrayList<Int>()
        val statuses = ArrayList<BeaconStatus>()

        fun apply(actions: List<Action>): List<Action> {
            for (a in actions) {
                when (a) {
                    Action.OpenGatt -> gatt++
                    Action.CloseGatt -> gatt--
                    is Action.StartAdvertising -> {
                        advertising++
                        started += a.sequence
                    }
                    Action.StopAdvertising -> advertising--
                    // 同时只有一个定时：新的替换旧的。
                    is Action.StartTimer -> timers = 1
                    Action.CancelTimer -> timers = 0
                    is Action.Status -> statuses += a.status
                }
                assertTrue(gatt in 0..1 && advertising in 0..1, "同时最多一个 GATT、一个广播：$a 之后 gatt=$gatt 广播=$advertising")
            }
            return actions
        }

        /** 平台对象自己结束（失败）的，账上也销掉。 */
        fun platformEndedAdvertising() {
            advertising--
        }

        fun platformEndedGatt() {
            gatt--
        }

        fun timerFired() {
            timers = 0
        }

        val clean: Boolean get() = gatt == 0 && advertising == 0 && timers == 0
    }

    @Test
    fun opensGattThenAdvertisesThenRepublishesWithANewSequence() {
        val m = machine()
        val ledger = Ledger()
        assertEquals(listOf(Action.Status(BeaconStatus.Starting), Action.OpenGatt), ledger.apply(m.onListenerReady()))
        assertEquals(listOf(Action.StartAdvertising(0xFE)), ledger.apply(m.onGattReady()))
        assertEquals(
            listOf(Action.Status(BeaconStatus.Advertising(1)), Action.StartTimer(120_000)),
            ledger.apply(m.onAdvertisingStarted()),
        )
        ledger.timerFired()
        assertEquals(listOf(Action.StopAdvertising, Action.StartAdvertising(0xFF)), ledger.apply(m.onTimer()))
        assertEquals(Action.Status(BeaconStatus.Advertising(2)), ledger.apply(m.onAdvertisingStarted()).first())
        ledger.timerFired()
        // 序列号模 256 绕回。
        assertEquals(Action.StartAdvertising(0x00), ledger.apply(m.onTimer()).last())
        assertEquals(listOf(0xFE, 0xFF, 0x00), ledger.started)

        val stop = ledger.apply(m.stop())
        assertEquals(listOf(Action.StopAdvertising, Action.CloseGatt, Action.Status(BeaconStatus.Stopped)), stop)
        assertTrue(m.isClean)
        assertTrue(ledger.clean)
    }

    @Test
    fun withoutGattAdvertisingStartsRightAway() {
        val m = machine(gatt = false)
        assertEquals(listOf(Action.Status(BeaconStatus.Starting), Action.StartAdvertising(0xFE)), m.onListenerReady())
        assertFalse(m.gattOpen)
        assertEquals(emptyList(), m.onGattReady(), "没开 GATT 就没有 GATT 的回调")
    }

    @Test
    fun noRepublishWhenTheIntervalIsZero() {
        val m = machine(gatt = false, republish = 0)
        m.onListenerReady()
        assertEquals(listOf(Action.Status(BeaconStatus.Advertising(1))), m.onAdvertisingStarted())
        assertFalse(m.timerPending)
        assertEquals(emptyList(), m.onTimer())
    }

    @Test
    fun gattFailureStillAdvertises() {
        val m = machine()
        val ledger = Ledger()
        ledger.apply(m.onListenerReady())
        ledger.platformEndedGatt()
        assertEquals(listOf(Action.StartAdvertising(0xFE)), ledger.apply(m.onGattFailed()))
        assertFalse(m.gattOpen)
        ledger.apply(m.onAdvertisingStarted())
        val stop = ledger.apply(m.stop())
        assertFalse(Action.CloseGatt in stop, "已经自己关掉的 GATT 不再关一次")
        assertTrue(ledger.clean && m.isClean)
    }

    @Test
    fun transientAdvertisingFailureRetriesWithANewSequence() {
        val m = machine(gatt = false, retry = 30_000)
        val ledger = Ledger()
        ledger.apply(m.onListenerReady())
        ledger.platformEndedAdvertising()
        assertEquals(
            listOf(Action.StartTimer(30_000), Action.Status(BeaconStatus.Retrying("合成：忙", 30_000))),
            ledger.apply(m.onAdvertisingFailed("合成：忙", permanent = false)),
        )
        assertFalse(m.advertising)
        ledger.timerFired()
        assertEquals(listOf(Action.StartAdvertising(0xFF)), ledger.apply(m.onTimer()))
        assertEquals(Action.Status(BeaconStatus.Advertising(1)), ledger.apply(m.onAdvertisingStarted()).first())
        ledger.apply(m.stop())
        assertTrue(ledger.clean && m.isClean)
    }

    @Test
    fun permanentFailureClosesGattAndGivesUp() {
        val m = machine()
        val ledger = Ledger()
        ledger.apply(m.onListenerReady())
        ledger.apply(m.onGattReady())
        ledger.platformEndedAdvertising()
        assertEquals(
            listOf(Action.CloseGatt, Action.Status(BeaconStatus.Failed("合成：不支持"))),
            ledger.apply(m.onAdvertisingFailed("合成：不支持", permanent = true)),
        )
        assertTrue(ledger.clean && m.isClean)
        assertEquals(emptyList(), m.onTimer())
        assertEquals(listOf(Action.Status(BeaconStatus.Stopped)), m.stop())
    }

    @Test
    fun gattClientConnectionReopensWithTheSameSequence() {
        val m = machine()
        val ledger = Ledger()
        ledger.apply(m.onListenerReady())
        ledger.apply(m.onGattReady())
        ledger.apply(m.onAdvertisingStarted())
        assertEquals(
            listOf(Action.CancelTimer, Action.StopAdvertising, Action.StartAdvertising(0xFE)),
            ledger.apply(m.onGattClientConnected()),
        )
        // 关了再开不算一次新的发布。
        assertEquals(Action.Status(BeaconStatus.Advertising(1)), ledger.apply(m.onAdvertisingStarted()).first())
        assertEquals(0xFE, m.currentSequence)
        assertEquals(emptyList(), machine().onGattClientConnected(), "没在发时不理")
    }

    @Test
    fun republishNowSkipsTheTimerAndTakesANewSequence() {
        val m = machine()
        val ledger = Ledger()
        assertEquals(emptyList(), m.republishNow(), "还没开始时不用做")
        ledger.apply(m.onListenerReady())
        assertEquals(emptyList(), m.republishNow(), "GATT 还在开时不用做")
        ledger.apply(m.onGattReady())
        assertEquals(emptyList(), m.republishNow(), "广播还在开时不用做")
        ledger.apply(m.onAdvertisingStarted())
        assertEquals(
            listOf(Action.CancelTimer, Action.StopAdvertising, Action.StartAdvertising(0xFF)),
            ledger.apply(m.republishNow()),
        )
        assertEquals(Action.Status(BeaconStatus.Advertising(2)), ledger.apply(m.onAdvertisingStarted()).first())

        // 再发布一次，这次没发出去、在等重试：马上重试，不等定时。
        assertEquals(Action.StartAdvertising(0x00), ledger.apply(m.republishNow()).last())
        ledger.platformEndedAdvertising()
        ledger.apply(m.onAdvertisingFailed("合成：忙", permanent = false))
        assertEquals(listOf(Action.CancelTimer, Action.StartAdvertising(0x01)), ledger.apply(m.republishNow()))

        ledger.apply(m.stop())
        assertTrue(ledger.clean && m.isClean)
        assertEquals(emptyList(), m.republishNow(), "停了之后不用做")
    }

    @Test
    fun stopIsIdempotentAndSilentBeforeAnythingStarted() {
        val m = machine()
        assertEquals(emptyList(), m.stop(), "还没开始：没有要关的，也不报状态")
        assertTrue(m.isStopped && m.isClean)
        assertEquals(emptyList(), m.stop())
        assertEquals(emptyList(), m.onListenerReady(), "停了之后不再开始")
    }

    @Test
    fun lateCallbacksAfterStopOpenNothing() {
        val m = machine()
        val ledger = Ledger()
        ledger.apply(m.onListenerReady())
        ledger.apply(m.stop())
        assertTrue(ledger.clean)
        for (late in listOf(
            m.onGattReady(), m.onGattFailed(), m.onAdvertisingStarted(), m.onAdvertisingFailed("合成", false),
            m.onTimer(), m.onGattClientConnected(), m.onListenerReady(),
        )) {
            assertEquals(emptyList(), late)
        }
        assertTrue(m.isClean)
    }

    /** 在每一个可能的时刻 stop，账上都要清零（平台对象自己失败结束的那一步已经销账）。 */
    @Test
    fun stoppingAtAnyPointLeavesNothingOpen() {
        val script: List<(ReconnectBeaconMachine, Ledger) -> Unit> = listOf(
            { m, l -> l.apply(m.onListenerReady()) },
            { m, l -> l.apply(m.onGattReady()) },
            { m, l -> l.apply(m.onAdvertisingStarted()) },
            { m, l -> l.apply(m.onGattClientConnected()) },
            { m, l -> l.apply(m.onAdvertisingStarted()) },
            { m, l ->
                l.timerFired()
                l.apply(m.onTimer())
            },
            { m, l ->
                l.platformEndedAdvertising()
                l.apply(m.onAdvertisingFailed("合成：忙", permanent = false))
            },
            { m, l ->
                l.timerFired()
                l.apply(m.onTimer())
            },
            { m, l -> l.apply(m.onAdvertisingStarted()) },
        )
        for (gatt in listOf(true, false)) {
            for (cut in 0..script.size) {
                val m = machine(gatt = gatt)
                val ledger = Ledger()
                for (step in script.take(cut)) {
                    // 没开 GATT 时跳过 GATT 的回调（状态机本来也会忽略）。
                    step(m, ledger)
                }
                ledger.apply(m.stop())
                assertTrue(ledger.clean, "GATT=$gatt，第 $cut 步后停：账上还有 gatt=${ledger.gatt} 广播=${ledger.advertising} 定时=${ledger.timers}")
                assertTrue(m.isClean)
                if (cut > 0) assertEquals(BeaconStatus.Stopped, ledger.statuses.last())
            }
        }
    }
}
