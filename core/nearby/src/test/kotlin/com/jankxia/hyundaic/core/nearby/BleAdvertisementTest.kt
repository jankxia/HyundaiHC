package com.jankxia.hyundaic.core.nearby

import com.jankxia.hyundaic.core.common.identity.HostIdentity
import com.jankxia.hyundaic.core.common.toHex
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** BLE 广播载荷逐字节核对。车型编号、序列号都是合成值。 */
class BleAdvertisementTest {

    private val host = HostIdentity(modelId = "ABCDEF", subModelId = "01", brand = "HyundaiHC", deviceName = "HyundaiHC Car")

    @Test
    fun defaultPayloadIsElevenBytesInTagOrder() {
        val payload = BleAdvertisement.payload(host, sequence = 0x5A)
        assertEquals(BleAdvertisement.PAYLOAD_LENGTH, payload.size)
        // 版本 01、01、能力 01、03 + 车型前 3 字节、04 + 子编号、10 + 序列号。
        assertEquals("01010103abcdef0401105a", payload.toHex())
    }

    @Test
    fun modelIdIsSplitSixPlusTwo() {
        val other = HostIdentity(modelId = "123456", subModelId = "78", brand = "HyundaiHC", deviceName = "HyundaiHC Car")
        val payload = BleAdvertisement.payload(other, sequence = 0)
        assertEquals("010101031234560478" + "1000", payload.toHex())
    }

    @Test
    fun airDataIsFlagsThenServiceDataUnderFdee() {
        val payload = BleAdvertisement.payload(host, sequence = 0x5A)
        val air = BleAdvertisement.legacyAdvertisingData(payload)
        // Flags（一般可发现）+ 长度 0E、类型 16（16 位 UUID 的 Service Data）、UUID 小端 EE FD、载荷。
        assertEquals("020102" + "0e16eefd" + "01010103abcdef0401105a", air.toHex())
        assertEquals(18, air.size)
    }

    @Test
    fun serviceDataUuidIsTheSixteenBitFdee() {
        assertEquals(UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb"), BleAdvertisement.SERVICE_DATA_UUID)
    }

    @Test
    fun randomAddressVariantChangesAbilityAndAppendsMarker() {
        val payload = BleAdvertisement.payload(host, sequence = 0x5A, options = BleAdvertisement.Options(declareRandomAddress = true))
        assertEquals("01010f03abcdef0401105a" + "ff01", payload.toHex())
    }

    @Test
    fun txPowerGoesAfterSequenceAndBeforeRandomMarker() {
        val power = BleAdvertisement.payload(host, sequence = 0x5A, options = BleAdvertisement.Options(txPowerDbm = -7))
        assertEquals("01010103abcdef0401105a" + "11f9", power.toHex())
        val both = BleAdvertisement.payload(
            host, sequence = 0x5A, options = BleAdvertisement.Options(declareRandomAddress = true, txPowerDbm = 4),
        )
        assertEquals("01010f03abcdef0401105a" + "1104" + "ff01", both.toHex())
        assertEquals(22, BleAdvertisement.legacyAdvertisingData(both).size)
    }

    @Test
    fun sequenceWrapsAndIsRangeChecked() {
        assertEquals(1, BleAdvertisement.nextSequence(0))
        assertEquals(0, BleAdvertisement.nextSequence(0xFF))
        assertContentEquals(byteArrayOf(0x10, 0xFF.toByte()), BleAdvertisement.payload(host, 0xFF).copyOfRange(9, 11))
        assertFailsWith<IllegalArgumentException> { BleAdvertisement.payload(host, 256) }
        assertFailsWith<IllegalArgumentException> { BleAdvertisement.payload(host, -1) }
        assertFailsWith<IllegalArgumentException> { BleAdvertisement.Options(txPowerDbm = 128) }
    }

    @Test
    fun airDataLongerThanLegacyLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> { BleAdvertisement.legacyAdvertisingData(ByteArray(25)) }
        // 24 字节载荷正好 31 字节。
        assertEquals(31, BleAdvertisement.legacyAdvertisingData(ByteArray(24)).size)
    }
}
