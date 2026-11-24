package com.jankxia.hyundaic.platform.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.annotation.TargetApi
import java.io.Closeable

/**
 * [MediaKeySession] 在安卓 5.0 起用的实现：真的建一个 `MediaSession` 收媒体按钮和播放控制命令。
 * 单独放在这里，安卓 4.4 上不会加载这个类（也就不会碰到它引用的高版本类）。
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
internal class MediaKeySession21(
    context: Context,
    tag: String,
    private val listener: MediaKeySession.Listener,
) : Closeable {

    private val session = MediaSession(context, tag)
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
