package com.jankxia.hyundaic.projection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.jankxia.hyundaic.core.hisight.HiSightInput
import com.jankxia.hyundaic.platform.media.VideoDecoder

/**
 * [ProjectionView] 的 Compose 包装：显示 [decoder] 的画面，触摸换算后交给 [onInput]（主线程）。
 * [videoWidth]、[videoHeight] 来自解码器的 onVideoSizeChanged，0 表示还不知道（画面先铺满、触摸先不发）。
 * [touchResetKey] 每变一次，就调一次 [ProjectionView.resetTouches]（发送端报告触摸状态对不上时）。
 * 离开组合时解绑解码器；解码器本身由调用方创建和关闭。
 */
@Composable
fun ProjectionSurface(
    decoder: VideoDecoder?,
    videoWidth: Int,
    videoHeight: Int,
    onInput: (HiSightInput) -> Unit,
    modifier: Modifier = Modifier,
    immersive: Boolean = true,
    fullScreen: Boolean = true,
    touchResetKey: Int = 0,
) {
    val latestOnInput by rememberUpdatedState(onInput)
    // 已经处理过的 touchResetKey；第一次组合时的值不触发。
    val appliedReset = remember { intArrayOf(touchResetKey) }
    AndroidView(
        factory = { context -> ProjectionView(context).apply { this.onInput = { latestOnInput(it) } } },
        modifier = modifier,
        onRelease = { view -> view.attachDecoder(null) },
        update = { view ->
            view.fullScreen = fullScreen
            view.immersive = immersive
            view.setVideoSize(videoWidth, videoHeight)
            view.attachDecoder(decoder)
            if (appliedReset[0] != touchResetKey) {
                appliedReset[0] = touchResetKey
                view.resetTouches()
            }
        },
    )
}
