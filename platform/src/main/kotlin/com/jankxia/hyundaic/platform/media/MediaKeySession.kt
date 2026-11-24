package com.jankxia.hyundaic.platform.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
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
 */
class MediaKeySession(context: Context, tag: String, private val listener: Listener) : Closeable {

    interface Listener {
        /** 一个媒体按钮事件；返回 true 表示处理了。 */
        fun onMediaKey(event: KeyEvent): Boolean

        /** 一个播放控制命令。 */
        fun onCommand(command: MediaKeyForwarder.Command)
    }

    private val session = MediaSession(context.applicationContext ?: context, tag)
    private var closed = false

    init {
        session.setCallback(Callback(), Handler(Looper.getMainLooper()))
        // 安卓 8.0 起这两个标志总是生效、接口已弃用；更低的版本要显式声明才收得到媒体按钮。
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.isActive = true
    }

    override fun close() {
        if (closed) return
        closed = true
        session.isActive = false
        session.release()
    }

    private inner class Callback : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            if (closed) return false
            val event = keyEventOf(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
            return listener.onMediaKey(event) || super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onPlay() = command(MediaKeyForwarder.Command.PLAY)

        override fun onPause() = command(MediaKeyForwarder.Command.PAUSE)

        override fun onSkipToNext() = command(MediaKeyForwarder.Command.NEXT)

        override fun onSkipToPrevious() = command(MediaKeyForwarder.Command.PREVIOUS)

        override fun onStop() = command(MediaKeyForwarder.Command.STOP)

        private fun command(command: MediaKeyForwarder.Command) {
            if (!closed) listener.onCommand(command)
        }
    }

    private companion object {
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP

        fun keyEventOf(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }
}
