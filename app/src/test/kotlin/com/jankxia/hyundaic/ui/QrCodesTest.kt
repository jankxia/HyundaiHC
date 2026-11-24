package com.jankxia.hyundaic.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 二维码编码：编出来的码再解回来，内容原样（不做 URL 编码），四周留了静区。测试数据是合成值。 */
class QrCodesTest {

    private val link = "hicar://car/connect?devicename=HyundaiHC-Test&mac=0A:1B:2C:3D:4E:5F&moduleid=000000&submoduleid=00&pin=013579&ability=6"

    @Test
    fun encodedLinkDecodesBackToTheSameText() {
        val matrix = QrCodes.encode(link)
        assertEquals(matrix.width, matrix.height)
        // 每个模块放大成 4×4 个点再解，接近屏幕上的样子。
        val scale = 4
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i ->
            if (matrix.get((i % size) / scale, (i / size) / scale)) BLACK else WHITE
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(link, result.text)
    }

    @Test
    fun quietZoneIsLeftAroundTheCode() {
        val margin = 2
        val matrix = QrCodes.encode(link, margin)
        for (i in 0 until matrix.width) {
            for (m in 0 until margin) {
                assertFalse(matrix.get(i, m), "上边")
                assertFalse(matrix.get(m, i), "左边")
                assertFalse(matrix.get(i, matrix.height - 1 - m), "下边")
                assertFalse(matrix.get(matrix.width - 1 - m, i), "右边")
            }
        }
        // 紧挨静区的是定位图形的黑边。
        assertTrue(matrix.get(margin, margin))
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -1
    }
}
