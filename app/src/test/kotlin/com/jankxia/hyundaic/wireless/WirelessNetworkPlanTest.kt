package com.jankxia.hyundaic.wireless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据通道判定：安卓 4.4（API 19）没有本地热点，但车机自带热点和 WiFi 直连组不要求系统版本。 */
class WirelessNetworkPlanTest {

    @Test
    fun localHotspotNeedsApi26() {
        assertFalse(WirelessNetworkPlan.hotspotSupported(19))
        assertFalse(WirelessNetworkPlan.hotspotSupported(25))
        assertTrue(WirelessNetworkPlan.hotspotSupported(26))
        assertTrue(WirelessNetworkPlan.hotspotSupported(33))
    }

    @Test
    fun android44WithCarHotspotIsSupported() {
        // 车机自带热点：车主先开车机热点，应用只读参数，任何版本都能用。
        assertTrue(WirelessNetworkPlan.channelSupported(19, carHotspot = true, directGroup = false))
    }

    @Test
    fun android44WithWifiDirectIsSupported() {
        // WiFi 直连组是 API 14 起，minSdk 19 一定满足。
        assertTrue(WirelessNetworkPlan.channelSupported(19, carHotspot = false, directGroup = true))
    }

    @Test
    fun android44WithNothingConfiguredIsUnsupported() {
        // 4.4 上既没配车机热点、也没开直连：开不了本地热点，无线这条路不通。
        assertFalse(WirelessNetworkPlan.channelSupported(19, carHotspot = false, directGroup = false))
    }

    @Test
    fun modernSystemWorksWithoutEitherAlternative() {
        assertTrue(WirelessNetworkPlan.channelSupported(28, carHotspot = false, directGroup = false))
        assertTrue(WirelessNetworkPlan.channelSupported(26, carHotspot = false, directGroup = false))
    }
}
