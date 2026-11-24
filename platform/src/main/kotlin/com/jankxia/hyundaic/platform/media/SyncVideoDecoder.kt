package com.jankxia.hyundaic.platform.media

import android.annotation.TargetApi
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.jankxia.hyundaic.core.common.MalformedMessageException
import com.jankxia.hyundaic.core.hisight.AccessUnit
import com.jankxia.hyundaic.core.hisight.AccessUnitSink
import com.jankxia.hyundaic.platform.log.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 安卓 4.1～5.1（API 16～22）的 H.264 硬解上屏。这些系统没有 [MediaCodec] 的异步回调（`setCallback` 是 API 23），
 * 也没有 `setOutputSurface`（API 23），所以这里用同步接口自己轮询：
 *
 * - **送帧线程**（协议线程）只做 NAL 扫描、参数集记录、按顺序排队；一帧数据复制一份进队列，不碰解码器。
 * - **解码线程**（本类自己的 Thread）循环 `dequeueInputBuffer` / `dequeueOutputBuffer`：有队列就送帧，
 *   有输出就 `releaseOutputBuffer(index, true)` 直接上屏。没活干就等 [Options.idleSleepMs]，不空转。
 * - **界面线程**：[setSurface] 换 Surface。这些系统不能中途换输出，只能释放解码器、在新 Surface 上重建，等下一个关键帧。
 *
 * 比 [VideoDecoder] 少的东西（都是有代价的选择）：不做上屏节拍（出帧立即送显）、不做重放缓存（切后台回来等手机新帧）、
 * 不切占位 Surface（Surface 没了就停解码）、不做低延迟键调优、不做解码器候选清单（用系统默认 H.264 解码器）。
 * 换来的是在 4.4 车机上能真正把画面解出来。
 *
 * ## 丢帧与恢复
 * - 解码器出错、配置失败、Surface 换了：释放解码器，丢到下一个能开始解码的帧（[AccessUnitScan.isSyncPoint]）为止。
 * - 队列积压超过 [Options.maxPendingFrames]：丢最旧的一帧并重新等关键帧（参考帧已经断了，硬解会花屏）。
 * - 参数集（SPS/PPS）变了：当场用新参数重建，尽量不断流。
 */
class SyncVideoDecoder @JvmOverloads constructor(
    private val listener: Listener = object : Listener {},
    private val options: Options = Options(),
) : AccessUnitSink, Closeable {

    /** 回调都在解码线程上，更新界面请自己切线程。 */
    interface Listener {
        /** 画面尺寸（[H264Sps] 里的显示尺寸）定下来或变了。 */
        fun onVideoSizeChanged(width: Int, height: Int) {}

        /** 建不起解码器（没有可用的 H.264 解码器、系统版本不够等）。 */
        fun onDecoderUnavailable(detail: String) {}
    }

    class Options(
        /** 待送队列上限。超过就丢最旧的一帧并重新等关键帧。 */
        val maxPendingFrames: Int = 48,
        /** 没活干时解码线程等多久（毫秒）。越大越省电、延迟越高。 */
        val idleSleepMs: Long = 4,
    ) {
        init {
            require(maxPendingFrames > 0) { "maxPendingFrames 必须是正数" }
            require(idleSleepMs >= 0) { "idleSleepMs 不能是负数" }
        }
    }

    private class Frame(val data: ByteArray, val ptsUs: Long)

    private val lock = Any()
    private val idleMonitor = Object()

    private val scan = AccessUnitScan()
    private val params = ParameterSets()
    private val pending = ArrayDeque<Frame>()

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var closed = false

    // 以下由 lock 保护
    private var codec: MediaCodec? = null
    private var codecSps: H264Sps? = null
    private var waitingForSync = true
    private var hintFps = 0
    private var lastPtsUs = 0L
    private var notifiedWidth = 0
    private var notifiedHeight = 0
    private var decodedFrames = 0L
    private var droppedFrames = 0L
    private var renderedFrames = 0L
    private var unavailableReported = false

    private val outputInfo = MediaCodec.BufferInfo()

    /** 已经上屏的帧数（任意线程可读）。自检和日志用。 */
    val renderedCount: Long
        get() = synchronized(lock) { renderedFrames }

    private val thread = Thread(::loop, "HyundaiHC-SyncVideo").also {
        it.isDaemon = true
        it.start()
    }

    /** 协商结果：记下帧率，只用于 API 21+ 的 operating-rate 提示。任意线程。 */
    fun prepare(@Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int, fps: Int) {
        if (fps <= 0) return
        synchronized(lock) { hintFps = fps }
    }

    /** 4.1～5.1 没有屏幕刷新时序接口，这里什么都不做，留成与 [VideoDecoder] 一样的调用形状。 */
    fun setDisplayTiming(@Suppress("UNUSED_PARAMETER") timing: DisplayTiming?) = Unit

    /**
     * 换输出 Surface（界面线程）。给 null 或换新 Surface 时都会释放当前解码器（这些系统不能中途改输出），
     * 之后等下一个关键帧重建。返回时解码器已经不会再往旧 Surface 上画。
     */
    fun setSurface(newSurface: Surface?) {
        val target = newSurface?.takeIf { it.isValid }
        synchronized(lock) {
            if (closed || target === surface) return
            surface = target
            teardownLocked(clearPending = true)
        }
        wake()
    }

    /** HiSight 视频回调：在调用 feedVideo 的线程上同步执行，帧数据只在回调期间有效。 */
    override fun onAccessUnit(unit: AccessUnit) {
        queue(unit.data, unit.offset, unit.length, unit.presentationTimeUs, unit.gapBefore != null)
    }

    /**
     * 送一帧 Annex-B 数据。只在调用期间读 [data]，要留的部分复制一份进队列；返回 true 表示收下了
     * （已经排队，或者只剩参数集）。除了范围非法不会抛异常。
     */
    fun queue(
        data: ByteArray,
        offset: Int,
        length: Int,
        streamPtsUs: Long = -1,
        afterGap: Boolean = false,
    ): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        if (closed) return false
        scan.scan(data, offset, length)
        synchronized(lock) {
            if (closed) return false
            // 参数集单独来了也要记下：4.4 上重建解码器时要用最新的 SPS/PPS。
            try {
                if (scan.spsStart >= 0) params.updateSps(data, scan.spsStart, scan.spsEnd)
                for (i in 0 until scan.ppsCount) params.updatePps(data, scan.ppsStart(i), scan.ppsEnd(i))
            } catch (e: MalformedMessageException) {
                Log.w(TAG, "参数集格式不对：${e.message}")
                droppedFrames++
                return false
            }
            if (scan.sliceCount == 0 && !scan.isParameterSetsOnly) return false
            if (scan.isSyncPoint) waitingForSync = false
            if (afterGap && !scan.isSyncPoint) waitingForSync = true
            if (waitingForSync) {
                if (scan.isParameterSetsOnly) return true
                droppedFrames++
                return false
            }
            if (pending.size >= options.maxPendingFrames) {
                // 队列满了说明解码一侧跟不上：丢掉最旧的一帧，参考链已经断，回去等关键帧。
                pending.pollFirst()
                droppedFrames++
                waitingForSync = true
                Log.w(TAG, "待送队列已满（${options.maxPendingFrames} 帧），丢帧并等关键帧")
            }
            pending.addLast(Frame(data.copyOfRange(offset, offset + length), ptsUs(streamPtsUs)))
        }
        wake()
        return true
    }

    /** 换一路新码流：释放解码器、忘掉参数集，等下一个关键帧。 */
    fun reset() {
        synchronized(lock) {
            if (closed) return
            teardownLocked(clearPending = true)
            params.clear()
        }
        wake()
    }

    /** 释放解码器和线程。任意线程可重复调用。 */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            teardownLocked(clearPending = true)
            surface = null
        }
        wake()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.i(TAG, "同步解码器已关闭：解码 $decodedFrames 帧，上屏 $renderedFrames 帧，丢 $droppedFrames 帧")
    }

    // ---------------------------------------------------------------- 解码线程

    private fun loop() {
        while (true) {
            val didWork: Boolean
            synchronized(lock) {
                if (closed) return
                didWork = stepLocked()
            }
            if (!didWork) {
                synchronized(idleMonitor) {
                    try {
                        idleMonitor.wait(options.idleSleepMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
    }

    /** 解码线程做一轮活：送一帧或出一帧。返回 true 表示确实动了，不用等。 */
    private fun stepLocked(): Boolean {
        val s = surface ?: return false
        if (!s.isValid) {
            teardownLocked(clearPending = true)
            return false
        }
        var c = codec
        if (c == null) {
            if (waitingForSync || pending.isEmpty()) return false
            val sps = params.sps
            if (sps == null || !params.isComplete) {
                // 关键帧之前没收到完整 SPS/PPS：等参数集。
                waitingForSync = true
                droppedFrames += pending.size
                pending.clear()
                return false
            }
            c = configureLocked(s, sps) ?: return false
        }
        if (feedLocked(c)) return true
        return drainLocked(c)
    }

    /** 从队列取一帧送进解码器。返回 true 表示送了。 */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun feedLocked(c: MediaCodec): Boolean {
        val frame = pending.pollFirst() ?: return false
        val index = try {
            c.dequeueInputBuffer(0)
        } catch (e: IllegalStateException) {
            teardownLocked(clearPending = true)
            return true
        }
        if (index < 0) {
            // 没有空闲输入缓冲：放回队首，下一轮再试。
            pending.addFirst(frame)
            return false
        }
        val buffer = inputBufferOf(c, index)
        if (buffer == null) {
            pending.addFirst(frame)
            Log.w(TAG, "输入缓冲 $index 取不到，跳过")
            return true
        }
        try {
            buffer.clear()
            if (frame.data.size > buffer.capacity()) {
                // 输入缓冲装不下一帧：重建解码器时把上限要得更大。
                Log.w(TAG, "一帧 ${frame.data.size} 字节，输入缓冲只有 ${buffer.capacity()} 字节，重建解码器")
                teardownLocked(clearPending = false)
                pending.addFirst(frame)
                waitingForSync = true
                return true
            }
            buffer.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "送帧失败，重建解码器", e)
            teardownLocked(clearPending = true)
            return true
        }
        decodedFrames++
        return true
    }

    /** 取一帧出来上屏。返回 true 表示取到并处理了。 */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun drainLocked(c: MediaCodec): Boolean {
        val index = try {
            c.dequeueOutputBuffer(outputInfo, 0)
        } catch (e: IllegalStateException) {
            teardownLocked(clearPending = true)
            return true
        }
        when {
            index >= 0 -> {
                try {
                    c.releaseOutputBuffer(index, true)
                    renderedFrames++
                } catch (e: IllegalStateException) {
                    teardownLocked(clearPending = true)
                }
                return true
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                applyOutputFormatLocked(c)
                return true
            }
            index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return true
            else -> return false
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun applyOutputFormatLocked(c: MediaCodec) {
        val format = try {
            c.outputFormat
        } catch (e: RuntimeException) {
            return
        }
        Log.i(TAG, "解码器输出格式：$format")
        // 宽高按 SPS 的显示尺寸更可靠：有的解码器只给按宏块对齐的编码尺寸。
        codecSps?.let { updateVideoSize(it.width, it.height) }
    }

    // ---------------------------------------------------------------- 解码器生命周期

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun configureLocked(s: Surface, sps: H264Sps): MediaCodec? {
        val c = try {
            MediaCodec.createDecoderByType(DecoderCatalog.MIME)
        } catch (e: Exception) {
            reportUnavailable("这台车机没有可用的 H.264 解码器（${e.message}）")
            waitingForSync = true
            pending.clear()
            return null
        }
        val inputSize = maxOf(sps.codedWidth * sps.codedHeight, MIN_INPUT_SIZE)
        val format = MediaFormat.createVideoFormat(DecoderCatalog.MIME, sps.width, sps.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(params.csd0()))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(params.csd1()))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize)
        // KEY_PRIORITY 是 API 19 才有的键；4.1～4.3 上只给尺寸和参数集。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && hintFps > 0) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, hintFps)
        }
        return try {
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            codecSps = sps
            unavailableReported = false
            Log.i(
                TAG,
                "同步解码器已启动：${sps.width}x${sps.height}，profile ${sps.profileIdc}，" +
                    "系统 ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            )
            updateVideoSize(sps.width, sps.height)
            c
        } catch (e: RuntimeException) {
            Log.w(TAG, "配置同步解码器失败：${e.message}")
            releaseQuietly(c)
            reportUnavailable("这台车机的 H.264 解码器配置失败（${e.message}）")
            waitingForSync = true
            pending.clear()
            null
        }
    }

    private fun teardownLocked(clearPending: Boolean) {
        val c = codec
        codec = null
        codecSps = null
        if (c != null) releaseQuietly(c)
        if (clearPending) {
            droppedFrames += pending.size
            pending.clear()
        }
        waitingForSync = true
    }

    private fun releaseQuietly(c: MediaCodec) {
        try {
            c.stop()
        } catch (e: RuntimeException) {
            // 出错状态下 stop 会抛，照样 release。
        }
        try {
            c.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "释放解码器失败：${e.message}")
        }
    }

    private fun reportUnavailable(detail: String) {
        if (unavailableReported) return
        unavailableReported = true
        Log.e(TAG, detail)
        try {
            listener.onDecoderUnavailable(detail)
        } catch (e: RuntimeException) {
            Log.e(TAG, "回调出错", e)
        }
    }

    private fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == notifiedWidth && height == notifiedHeight) return
        notifiedWidth = width
        notifiedHeight = height
        try {
            listener.onVideoSizeChanged(width, height)
        } catch (e: RuntimeException) {
            Log.e(TAG, "尺寸回调出错", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun inputBufferOf(c: MediaCodec, index: Int): ByteBuffer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            c.getInputBuffer(index)
        } else {
            val buffers: Array<ByteBuffer>? = c.inputBuffers
            buffers?.getOrNull(index)
        }
    } catch (e: IllegalStateException) {
        null
    }

    /** 递增的微秒时间戳：同步模式下只用于让解码器按顺序出帧，用本机单调时钟足够。 */
    private fun ptsUs(streamPtsUs: Long): Long {
        val now = SystemClock.elapsedRealtime() * 1000
        lastPtsUs = if (now > lastPtsUs) now else lastPtsUs + 1
        return if (streamPtsUs >= 0) streamPtsUs else lastPtsUs
    }

    private fun wake() {
        synchronized(idleMonitor) { idleMonitor.notifyAll() }
    }

    private companion object {
        const val TAG = "HyundaiHC-SyncVideo"

        /** 输入缓冲至少这么大：画面很小时关键帧也可能比按像素估的大。 */
        const val MIN_INPUT_SIZE = 512 * 1024
    }
}
