package com.jankxia.hyundaic.demo

import com.jankxia.hyundaic.core.hisight.HiSightInput
import com.jankxia.hyundaic.platform.media.SyntheticH264
import com.jankxia.hyundaic.platform.media.VideoDecoder
import java.util.Random
import java.util.concurrent.atomic.AtomicReference

/**
 * 演示用的送帧线程：按帧率画 [DemoScene]、编成合成码流，交给 [VideoDecoder.queueAccessUnit]，
 * 相当于协议层的视频回调。关键帧间隔 2 秒，横竖切换时换尺寸（解码器在新尺寸的关键帧上重建）。
 * [jitterMs] 让每帧的送出时刻在名义时刻前后随机偏一点（不累积），模拟手机发帧和传输的抖动，用来对照上屏节拍。
 */
internal class SyntheticFeeder(private val decoder: VideoDecoder, initialFps: Int) {

    @Volatile
    var fps: Int = initialFps.coerceIn(1, 120)
        set(value) {
            field = value.coerceIn(1, 120)
        }

    /** 每帧送出时刻在名义时刻前后随机偏多少毫秒（均匀分布），0 不偏。 */
    @Volatile
    var jitterMs: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_JITTER_MS)
        }

    /**
     * 暂停出帧：像手机画面不变时那样一帧都不发。用来检查切后台再回来、视图重建之后，
     * 解码器能不能靠重放缓存马上画出最后一帧（不暂停时新帧很快就来，看不出来）。
     */
    @Volatile
    var paused: Boolean = false

    @Volatile
    private var portrait = false

    @Volatile
    private var keyFrameRequested = false

    @Volatile
    private var running = false

    private val touches = AtomicReference<List<HiSightInput.Point>>(emptyList())
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "HyundaiHC-SyntheticFeeder").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            it.join(STOP_WAIT_MS)
        }
        thread = null
    }

    fun toggleOrientation() {
        portrait = !portrait
    }

    fun requestKeyFrame() {
        keyFrameRequested = true
    }

    /** 投屏视图交出的触摸：按下、移动时记下当前触点，抬起时清掉，下一帧把它们画出来。 */
    fun onInput(input: HiSightInput) {
        if (input !is HiSightInput.Touch) return
        touches.set(if (input.action == HiSightInput.Touch.Action.UP) emptyList() else input.points)
    }

    private fun loop() {
        val scene = DemoScene()
        var encoder: SyntheticH264? = null
        var encoderPortrait = false
        var encoderFps = 0
        var index = 0L
        var next = System.nanoTime()
        val random = Random()
        while (running) {
            val wantPortrait = portrait
            val currentFps = fps
            var current = encoder
            if (current == null || encoderPortrait != wantPortrait || encoderFps != currentFps) {
                val (w, h) = if (wantPortrait) PORTRAIT else LANDSCAPE
                // 关键帧间隔 2 秒，模拟手机按 GOP 出关键帧。
                current = SyntheticH264(w, h, keyFrameInterval = currentFps * 2)
                if (encoder != null && encoderPortrait != wantPortrait) touches.set(emptyList())
                encoder = current
                encoderPortrait = wantPortrait
                encoderFps = currentFps
                decoder.prepare(w, h, currentFps)
            }
            if (!paused) {
                scene.draw(current.frame, current.width, current.height, index, touches.get())
                val forceKeyFrame = keyFrameRequested
                if (forceKeyFrame) keyFrameRequested = false
                val au = current.encode(forceKeyFrame)
                decoder.queueAccessUnit(au, 0, au.size)
                index++
            }

            next += 1_000_000_000L / currentFps
            val jitterUs = jitterMs * 1000
            val offsetNs = if (jitterUs > 0) (random.nextInt(2 * jitterUs + 1) - jitterUs) * 1000L else 0L
            val sleepNs = next + offsetNs - System.nanoTime()
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                } catch (e: InterruptedException) {
                    break
                }
            } else if (sleepNs < -RESYNC_NS) {
                // 落后太多（例如进程被挂起过）：从现在重新计时，不一口气补发。
                next = System.nanoTime()
            }
        }
    }

    private companion object {
        val LANDSCAPE = 320 to 180
        val PORTRAIT = 180 to 320
        const val STOP_WAIT_MS = 1000L
        const val RESYNC_NS = 500_000_000L
        const val MAX_JITTER_MS = 20
    }
}
