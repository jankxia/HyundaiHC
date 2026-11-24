package com.jankxia.hyundaic.platform.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 同步解码器的设备自检：先用系统编码器编一段 H.264（一帧一个 I 关键帧，方便同步解码器从任意帧起步），
 * 再把这段码流原样喂给 [SyncVideoDecoder]，看它能不能解出画面、报出尺寸。
 *
 * 车载 4.4 机器上跑的正是这条同步路径（4.4~5.1 没有异步回调），但本地没有 4.4 镜像，
 * 所以在 API 21+ 的模拟器/真机上验证：同步接口在这些系统上一样可用，能证明这段代码真的解得动。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 21)
class SyncVideoDecoderTest {

    @Test
    fun decodesFramesEncodedBySystemEncoder() {
        val width = 320
        val height = 240
        val encoded = encode(width, height, FRAME_COUNT)
        assertTrue("编码器没给出参数集（SPS/PPS）", encoded.csd.isNotEmpty())
        assertTrue("编码器给的帧太少：${encoded.frames.size}", encoded.frames.size >= 3)

        val reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 4)
        // 输出不消费的话 BufferQueue 会满、解码器拿不到缓冲：取一张丢一张。
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.close()
            } catch (e: RuntimeException) {
                // 已关闭或者取图失败，丢掉即可。
            }
        }, Handler(Looper.getMainLooper()))

        var reported: Pair<Int, Int>? = null
        var unavailable: String? = null
        val decoder = SyncVideoDecoder(object : SyncVideoDecoder.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int) {
                reported = width to height
            }

            override fun onDecoderUnavailable(detail: String) {
                unavailable = detail
            }
        })
        try {
            decoder.setSurface(reader.surface)
            decoder.prepare(width, height, 15)
            // 参数集先单独送一次，之后就按帧送。
            decoder.queue(encoded.csd, 0, encoded.csd.size)
            for (frame in encoded.frames) decoder.queue(frame, 0, frame.size)

            val deadline = System.currentTimeMillis() + TIME_LIMIT_MS
            while (System.currentTimeMillis() < deadline && decoder.renderedCount < 3) {
                Thread.sleep(20)
            }
            assertTrue("解码器报了不可用：$unavailable", unavailable == null)
            assertTrue("只上屏了 ${decoder.renderedCount} 帧，编码器给了 ${encoded.frames.size} 帧", decoder.renderedCount >= 3)
            assertEquals("画面尺寸不对", width to height, reported)
        } finally {
            decoder.close()
            reader.close()
        }
    }

    /** 用系统 H.264 编码器编 [frameCount] 帧灰画面，返回 Annex-B 码流（参数集一帧、每帧一条）。 */
    private fun encode(width: Int, height: Int, frameCount: Int): Encoded {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MIME)
        val frames = mutableListOf<ByteArray>()
        val csd = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sent = 0
        var eosSent = false
        var done = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val deadline = System.currentTimeMillis() + TIME_LIMIT_MS
            while (!done && System.currentTimeMillis() < deadline) {
                if (sent < frameCount) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)
                        if (buffer != null) {
                            buffer.clear()
                            // NV12：Y 平面全填 0x40，UV 交错全填 0x80（纯灰色）。
                            val ySize = width * height
                            repeat(ySize) { buffer.put(0x40.toByte()) }
                            repeat(ySize / 2) { buffer.put(0x80.toByte()) }
                            encoder.queueInputBuffer(index, 0, ySize * 3 / 2, sent * 1000L / 15, 0)
                            sent++
                        }
                    }
                } else if (!eosSent) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = encoder.getOutputBuffer(outIndex)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val data = ByteArray(info.size)
                                out.get(data)
                                val annexB = toAnnexB(data)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    csd.write(annexB)
                                } else if (annexB.isNotEmpty()) {
                                    frames += annexB
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 有的编码器把参数集只在格式里给，不单独出一个 CONFIG 帧。
                        val outFormat = encoder.outputFormat
                        for (key in listOf("csd-0", "csd-1")) {
                            val csdBuffer = outFormat.getByteBuffer(key) ?: continue
                            val bytes = ByteArray(csdBuffer.remaining())
                            csdBuffer.get(bytes)
                            if (csd.size() == 0) csd.write(toAnnexB(bytes))
                        }
                    }
                }
            }
        } finally {
            try {
                encoder.stop()
            } catch (e: RuntimeException) {
                // 状态不对时 stop 会抛，照样 release。
            }
            encoder.release()
        }
        return Encoded(csd.toByteArray(), frames)
    }

    private class Encoded(val csd: ByteArray, val frames: List<ByteArray>)

    /**
     * 编码器输出可能是 Annex-B（带起始码），也可能是 AVCC（4 字节长度前缀）。统一成 Annex-B：
     * 解码器与 [AccessUnitScan] 都按起始码拆 NAL。
     */
    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))
        ) {
            return data
        }
        val out = ByteArrayOutputStream(data.size + 16)
        var pos = 0
        while (pos + 4 <= data.size) {
            val length = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (length <= 0 || pos + length > data.size) break
            out.write(START_CODE)
            out.write(data, pos, length)
            pos += length
        }
        return out.toByteArray()
    }

    private companion object {
        const val MIME = "video/avc"
        const val FRAME_COUNT = 8
        const val TIME_LIMIT_MS = 20_000L
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
