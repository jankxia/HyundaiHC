package com.jankxia.hyundaic.projection

import android.os.Build
import android.view.Surface
import com.jankxia.hyundaic.core.hisight.AccessUnit
import com.jankxia.hyundaic.core.hisight.AccessUnitSink
import com.jankxia.hyundaic.platform.log.Log
import com.jankxia.hyundaic.platform.media.DisplayTiming
import com.jankxia.hyundaic.platform.media.SyncVideoDecoder
import com.jankxia.hyundaic.platform.media.VideoDecoder
import java.io.Closeable

/**
 * 界面与连接层共用的解码出口。安卓 6.0 起走异步回调的 [VideoDecoder]（有上屏节拍、重放、占位 Surface），
 * 4.1～5.1 走同步轮询的 [SyncVideoDecoder]（这些系统没有 `setCallback`）。调用方只按这个接口用，不用关心差别。
 */
interface VideoSink : AccessUnitSink, Closeable {
    /** 协商出的画面参数（宽、高、帧率），在第一个关键帧到达前调用。 */
    fun prepare(width: Int, height: Int, fps: Int)

    /** 输出 Surface；null 表示画面没了。 */
    fun setSurface(surface: Surface?)

    /** 屏幕刷新时序（只对异步版有意义）。 */
    fun setDisplayTiming(timing: DisplayTiming?)
}

/** 安卓 6.0 起的异步解码器。 */
class AsyncVideoSink(private val decoder: VideoDecoder) : VideoSink {
    override fun prepare(width: Int, height: Int, fps: Int) = decoder.prepare(width, height, fps)

    override fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    override fun setDisplayTiming(timing: DisplayTiming?) = decoder.setDisplayTiming(timing)

    override fun onAccessUnit(unit: AccessUnit) = decoder.onAccessUnit(unit)

    override fun close() = decoder.close()
}

/** 安卓 4.1～5.1 的同步轮询解码器。 */
class SyncVideoSink(private val decoder: SyncVideoDecoder) : VideoSink {
    override fun prepare(width: Int, height: Int, fps: Int) = decoder.prepare(width, height, fps)

    override fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    override fun setDisplayTiming(timing: DisplayTiming?) = decoder.setDisplayTiming(timing)

    override fun onAccessUnit(unit: AccessUnit) = decoder.onAccessUnit(unit)

    override fun close() = decoder.close()
}

/** 按系统版本挑解码器。 */
object VideoSinks {
    fun create(
        onVideoSizeChanged: (width: Int, height: Int) -> Unit,
        onDecoderUnavailable: (detail: String) -> Unit,
        options: VideoDecoder.Options = VideoDecoder.Options(),
    ): VideoSink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return AsyncVideoSink(
                VideoDecoder(
                    object : VideoDecoder.Listener {
                        override fun onVideoSizeChanged(width: Int, height: Int) = onVideoSizeChanged(width, height)

                        override fun onDecoderUnavailable(detail: String) = onDecoderUnavailable(detail)
                    },
                    options,
                ),
            )
        }
        Log.i(TAG, "安卓 ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）：用同步轮询解码器")
        return SyncVideoSink(
            SyncVideoDecoder(
                object : SyncVideoDecoder.Listener {
                    override fun onVideoSizeChanged(width: Int, height: Int) = onVideoSizeChanged(width, height)

                    override fun onDecoderUnavailable(detail: String) = onDecoderUnavailable(detail)
                },
            ),
        )
    }

    private const val TAG = "HyundaiHC-VideoSinks"
}
