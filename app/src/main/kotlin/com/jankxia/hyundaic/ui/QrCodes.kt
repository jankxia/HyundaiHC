package com.jankxia.hyundaic.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码编码（ZXing，Apache-2.0），传统 View 版：编成 Bitmap 交给 ImageView。
 * 原 Compose 版在 legacy/app-main-ui/ui/QrCode.kt，本文件只保留编码与位图转换，不引 Compose。
 */
internal object QrCodes {
    /**
     * 编成模块矩阵：一个模块一个点，四周留 [margin] 个模块的空白（扫码要有静区）。
     * 内容按 UTF-8 编码、不做 URL 编码，纠错等级 M。
     */
    fun encode(content: String, margin: Int = 2): BitMatrix {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // 宽高给 0：按最小尺寸出，一个模块一个点，放大交给界面。
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /**
     * 编成位图并放大到 [sizePx]：白底黑块，不随深色模式反色（反色了很多手机扫不出来）；
     * 放大用最近邻（`filter = false`），边缘保持清楚。
     */
    fun bitmap(content: String, sizePx: Int, margin: Int = 2): Bitmap {
        val matrix = encode(content, margin)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) BLACK else WHITE }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (sizePx <= 0 || w == sizePx) return raw
        val scaled = Bitmap.createScaledBitmap(raw, sizePx, sizePx, false)
        if (scaled !== raw) raw.recycle()
        return scaled
    }
}

/** 不透明黑（ARGB 0xFF000000）。 */
private const val BLACK = -0x1000000

/** 不透明白（ARGB 0xFFFFFFFF）。 */
private const val WHITE = -1
