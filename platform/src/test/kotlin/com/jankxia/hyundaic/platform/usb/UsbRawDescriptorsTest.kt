package com.jankxia.hyundaic.platform.usb

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 原始描述符解析：4.4 上要靠它拿 NCM 数据口的备用设置号（`getAlternateSetting` 是 API 21）。
 * 这里用一段手工拼的 NCM 描述符（控制口 + 数据口备用 0 空端点 + 数据口备用 1 带批量端点）验证解析结果。
 */
class UsbRawDescriptorsTest {

    @Test
    fun parsesNcmInterfacesAndFindsAlternateSettings() {
        val interfaces = UsbRawDescriptors.parse(sampleNcmDescriptors())

        assertEquals(3, interfaces.size, "应该是 控制口 + 数据口备用0 + 数据口备用1 三条")

        val control = interfaces.first { it.id == 0 }
        assertEquals(0, control.alternateSetting)
        assertEquals(2, control.interfaceClass)
        assertEquals(13, control.interfaceSubclass)
        assertEquals(1, control.endpoints.size)
        assertEquals(0x83, control.endpoints[0].address)
        assertEquals(UsbEndpointDescription.TYPE_INTERRUPT, control.endpoints[0].type)

        val idle = interfaces.first { it.id == 1 && it.alternateSetting == 0 }
        assertTrue(idle.endpoints.isEmpty(), "备用 0 应该没有端点")

        val active = interfaces.first { it.id == 1 && it.alternateSetting == 1 }
        assertEquals(2, active.endpoints.size)
        assertEquals(0x81, active.endpoints[0].address)
        assertEquals(UsbEndpointDescription.TYPE_BULK, active.endpoints[0].type)
        assertEquals(64, active.endpoints[0].maxPacketSize)
        assertEquals(0x02, active.endpoints[1].address)
        assertTrue(!UsbRawDescriptors.isIn(active.endpoints[1].address))

        // 按端点集合反查备用设置号：带批量端点的那个是 1，空端点那个是 0。
        assertEquals(1, UsbRawDescriptors.alternateFor(interfaces, 1, setOf(0x81, 0x02)))
        assertEquals(0, UsbRawDescriptors.alternateFor(interfaces, 1, emptySet()))
        assertNotNull(UsbRawDescriptors.alternateFor(interfaces, 0, setOf(0x83)))
        assertTrue(UsbRawDescriptors.alternateFor(interfaces, 1, setOf(0x99)) == null, "端点对不上就不该给号")
    }

    @Test
    fun stopsAtTruncatedDescriptor() {
        // 末尾少两个字节的坏数据：解析不抛异常，能读多少算多少。
        val bad = sampleNcmDescriptors().copyOf(10)
        val interfaces = UsbRawDescriptors.parse(bad)
        assertTrue(interfaces.size <= 1, "坏数据不应解析出多个接口：${interfaces.size}")
    }

    /** 设备描述符 + 配置描述符 + 控制口（中断 IN）+ 数据口备用 0（无端点）+ 数据口备用 1（批量 IN/OUT）。 */
    private fun sampleNcmDescriptors(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(desc(1, 0x12, 0x01, 0x00, 0x02, 0xEF, 0x02, 0x01, 64, 0x12, 0xD1, 0x7D, 0x10, 0x00, 0x01, 1, 2, 3, 1, 9))
        out.write(desc(2, 0x09, 0x00, 0x02, 0x01, 0x00, 0xC0, 0x32))
        out.write(desc(4, 0, 0, 1, 0x02, 0x0D, 0x00, 0))
        out.write(desc(5, 0x83, 0x03, 0x10, 0x00, 0x05))
        out.write(desc(4, 1, 0, 0, 0x0A, 0x00, 0x00, 0))
        out.write(desc(4, 1, 1, 2, 0x0A, 0x00, 0x00, 0))
        out.write(desc(5, 0x81, 0x02, 0x40, 0x00, 0x00))
        out.write(desc(5, 0x02, 0x02, 0x40, 0x00, 0x00))
        return out.toByteArray()
    }

    /** 拼一条描述符：bLength 自动算，bDescriptorType 是 [type]，后面是按字节给的正文。 */
    private fun desc(type: Int, vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 2)
        out[0] = out.size.toByte()
        out[1] = type.toByte()
        for (i in body.indices) out[i + 2] = body[i].toByte()
        return out
    }

    private class ByteArrayOutputStream {
        private val bytes = mutableListOf<Byte>()
        fun write(data: ByteArray) {
            for (b in data) bytes += b
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }
}
