package com.jankxia.hyundaic.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 开机自启的延迟档位：只认边界内的那几档，别的值回到默认；闹钟按开机以来的时长算。 */
class BootDelayPolicyTest {

    @Test
    fun onlyTheOfferedDelaysAreValid() {
        assertEquals(listOf(5, 10, 15, 30, 40, 50, 60), BootDelayPolicy.CHOICES_SECONDS)
        assertEquals(30, BootDelayPolicy.DEFAULT_SECONDS)
        assertTrue(BootDelayPolicy.DEFAULT_SECONDS in BootDelayPolicy.CHOICES_SECONDS)
    }

    @Test
    fun everyOfferedChoiceSurvivesNormalization() {
        for (seconds in BootDelayPolicy.CHOICES_SECONDS) {
            assertEquals(seconds, BootDelayPolicy.normalize(seconds))
        }
    }

    @Test
    fun unknownValuesFallBackToTheDefault() {
        for (bad in listOf(0, -10, 3, 20, 45, 120, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(BootDelayPolicy.DEFAULT_SECONDS, BootDelayPolicy.normalize(bad), "$bad")
        }
    }

    @Test
    fun delaysAreCountedInMilliseconds() {
        assertEquals(5_000L, BootDelayPolicy.delayMillis(5))
        assertEquals(10_000L, BootDelayPolicy.delayMillis(10))
        assertEquals(60_000L, BootDelayPolicy.delayMillis(60))
        // 存进来的怪值先落到默认档，再换算
        assertEquals(30_000L, BootDelayPolicy.delayMillis(0))
    }

    @Test
    fun theHintShowsTheEffectiveDelay() {
        assertEquals("开机后延迟 30 秒打开", BootDelayPolicy.describe(30))
        assertEquals("开机后延迟 15 秒打开", BootDelayPolicy.describe(15))
        assertEquals("开机后延迟 30 秒打开", BootDelayPolicy.describe(7), "非法值按默认显示")
    }

    @Test
    fun theDelayedActionIsItsOwn() {
        assertEquals("com.jankxia.hyundaic.action.DELAYED_BOOT_LAUNCH", BootDelayPolicy.ACTION_DELAYED_LAUNCH)
        assertEquals(false, BootStartPolicy.isBootBroadcast(BootDelayPolicy.ACTION_DELAYED_LAUNCH))
    }
}
