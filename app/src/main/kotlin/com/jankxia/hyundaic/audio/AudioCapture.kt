package com.jankxia.hyundaic.audio

import com.jankxia.hyundaic.core.dmsdp.audio.AudioEvent
import com.jankxia.hyundaic.core.dmsdp.audio.AudioStreamInfo
import java.io.Closeable

/**
 * 调试抓包：把音频连接上的原始字节、识别结果和状态写到应用私有目录，方便取回分析。
 * 只有调试构建里是真的实现（见 debug 源集的 `AudioCaptureFactory`），发布构建是空实现。
 *
 * 两个方法都在协议线程上调用，实现里不能阻塞（复制后交给自己的线程写盘）。
 */
internal interface AudioCapture : Closeable {
    fun onEvent(event: AudioEvent)

    fun onRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int)

    /** 把还没写完的写完、关闭文件。可以重复调用。 */
    override fun close()

    object None : AudioCapture {
        override fun onEvent(event: AudioEvent) = Unit

        override fun onRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) = Unit

        override fun close() = Unit
    }
}
