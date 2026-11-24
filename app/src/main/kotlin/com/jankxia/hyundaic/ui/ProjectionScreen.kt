package com.jankxia.hyundaic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jankxia.hyundaic.R
import com.jankxia.hyundaic.connection.ConnectionUiState
import com.jankxia.hyundaic.core.hisight.HiSightInput
import com.jankxia.hyundaic.platform.media.VideoDecoder
import com.jankxia.hyundaic.projection.ProjectionSurface

/**
 * 全屏投屏页：手机画面铺满屏幕（藏起状态栏和导航栏），触摸交给 [onInput]。
 * 返回键回首页、连接保持（由调用方处理），首页可以再点「回到投屏画面」。
 */
@Composable
internal fun ProjectionScreen(
    state: ConnectionUiState,
    decoder: VideoDecoder?,
    touchResets: Int,
    onInput: (HiSightInput) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProjectionSurface(
            decoder = decoder,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            onInput = onInput,
            modifier = Modifier.fillMaxSize(),
            fullScreen = com.jankxia.hyundaic.connection.DisplayArea.activeFullScreen,
            touchResetKey = touchResets,
        )
        if (!state.firstFrame) {
            Text(stringResource(R.string.projection_waiting_frame), color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        state.decoderProblem?.let {
            Text(
                stringResource(R.string.projection_decoder_problem, it),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xAA000000))
                    .padding(8.dp),
            )
        }
    }
}
