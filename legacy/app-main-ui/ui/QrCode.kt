package com.jankxia.hyundaic.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.jankxia.hyundaic.R

/** 二维码编码（ZXing，Apache-2.0）。纯逻辑，由 JVM 单元测试覆盖。 */
internal object QrCodes {
    /**
     * 编成模块矩阵：一个模块一个点，四周留 [margin] 个模块的空白（扫码要有静区）。内容按 UTF-8 编码、不做 URL 编码，
     * 纠错等级 M（车机屏幕清楚，够用，码也不至于太密）。
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
}

/**
 * 显示二维码：白底黑块，不随深色模式反色（反色了很多手机扫不出来）；放大时不做平滑，边缘保持清楚。
 */
@Composable
internal fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { QrCodes.encode(content).toImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.wireless_qr_description),
        modifier = modifier.background(Color.White),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

private fun BitMatrix.toImageBitmap(): ImageBitmap {
    val w = width
    val h = height
    val pixels = IntArray(w * h) { i -> if (get(i % w, i / w)) BLACK else WHITE }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 不透明黑（ARGB 0xFF000000）。 */
private const val BLACK = -0x1000000

/** 不透明白（ARGB 0xFFFFFFFF）。 */
private const val WHITE = -1
