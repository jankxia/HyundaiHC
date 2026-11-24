package com.jankxia.hyundaic.platform.media

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import com.jankxia.hyundaic.platform.log.Log
import java.io.Closeable

/**
 * 媒体按钮会话：本应用不在前台时，系统按「谁在放媒体声音」把方向盘、媒体按钮派给它。
 *
 * - 建好就是活动的，播放状态固定为「正在播放」，声明播放、暂停、播放暂停、上一首、下一首、停止。
 *   安卓 8.0 起系统主要看哪个应用最近在放媒体声音（我们正放着手机的音乐），8.0 以下看活动会话和播放状态。
 * - 按键原样交给 [Listener.onMediaKey]（按下、抬起都有）；它返回 false 的才按系统默认方式转成播放控制命令。
 *   播放控制命令（来自车机界面上的媒体控件等）交给 [Listener.onCommand]。
 * - 回调都在主线程上。[close] 后不再回调。
 * - 不申请音频焦点，也不出声；什么时候建、什么时候关由调用方决定（例如只在替手机拿着媒体焦点时）。
 *
 * ## 安卓版本
 * 会话本身是 `android.media.session.MediaSession`，安卓 5.0（API 21）起才有。安卓 4.4 上没有它：
 * 那边建出来的会话什么都不做（[supported] 为 false），方向盘键只在投屏页前台时由界面收到并转给手机。
 * 高版本的那部分隔在 [MediaKeySession21] 里，4.4 不会加载它引用的类。
 */
class MediaKeySession(context: Context, tag: String, private val listener: Listener) : Closeable {

    interface Listener {
        /** 一个媒体按钮事件；返回 true 表示处理了。 */
        fun onMediaKey(event: KeyEvent): Boolean

        /** 一个播放控制命令。 */
        fun onCommand(command: MediaKeyForwarder.Command)
    }

    /** 这次会话能不能真的收到系统派的媒体按钮（安卓 4.4 上为 false）。 */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    private val impl: MediaKeySession21? =
        if (supported) MediaKeySession21(context.applicationContext ?: context, tag, listener) else null

    private var closed = false

    init {
        if (!supported) {
            Log.i(TAG, "安卓 4.4 没有媒体会话：不在前台时方向盘键收不到，投屏页在前台时照常转给手机")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        impl?.close()
    }

    private companion object {
        const val TAG = "HyundaiHC-MediaKey"
    }
}
