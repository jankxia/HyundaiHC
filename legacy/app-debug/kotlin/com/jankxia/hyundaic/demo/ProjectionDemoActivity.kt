package com.jankxia.hyundaic.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jankxia.hyundaic.R
import com.jankxia.hyundaic.core.hisight.HiSightInput
import com.jankxia.hyundaic.platform.media.DisplayRefreshRate
import com.jankxia.hyundaic.platform.media.FrameRatePolicy
import com.jankxia.hyundaic.platform.media.VideoDecoder
import com.jankxia.hyundaic.platform.media.VideoStats
import com.jankxia.hyundaic.projection.ProjectionSurface
import com.jankxia.hyundaic.settings.VideoPreferences
import java.util.Locale

/**
 * 仅调试构建：不连手机，把运行时现生成的合成码流解码到全屏投屏视图上，叠一层统计和触点信息。
 * 码流不放在 assets 里：生成器是纯 Kotlin 写的（I_PCM + P_Skip），不依赖车机的编码器，也不往仓库里放二进制。
 * 切到后台时送帧照常进行，回来时可以确认画面立即恢复（解码器在后台输出到占位 Surface）。
 * 「暂停出帧」模拟手机画面不变时不发帧：暂停后切后台再回来，画面应当马上回到暂停时的那一帧（解码器重放缓存）。
 * 「上屏节拍」当场切换解码器的节拍（初值取设置页的开关），「送帧抖动」让送帧时刻前后随机偏几毫秒：
 * 帧率等于刷新率、抖动 ±4～8ms 时，节拍关的「上屏」明显少于「解码」，开了应当基本相等，代价看「节拍多等」。
 */
class ProjectionDemoActivity : ComponentActivity() {

    private lateinit var decoder: VideoDecoder
    private lateinit var feeder: SyntheticFeeder

    private var videoWidth by mutableIntStateOf(0)
    private var videoHeight by mutableIntStateOf(0)
    private var statsText by mutableStateOf("")
    private var touchText by mutableStateOf<String?>(null)
    private var resyncText by mutableStateOf<String?>(null)
    private var feedFps by mutableIntStateOf(0)
    private var feedPaused by mutableStateOf(false)
    private var framePacing by mutableStateOf(true)
    private var feedJitterMs by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refreshRate = DisplayRefreshRate.ofDefaultDisplay(this)
        val declaredFps = FrameRatePolicy.declaredFps(refreshRate)
        val fpsChoices = listOf(declaredFps, 30, 60).distinct()

        decoder = VideoDecoder(listener = object : VideoDecoder.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int) = runOnUiThread {
                videoWidth = width
                videoHeight = height
            }

            override fun onStats(stats: VideoStats) = runOnUiThread { statsText = stats.toString() }

            override fun onKeyFrameNeeded(reason: VideoDecoder.ResyncReason) = runOnUiThread { resyncText = reason.name }
        }, options = VideoPreferences(this).decoderOptions())
        framePacing = decoder.framePacing
        feeder = SyntheticFeeder(decoder, declaredFps)
        feedFps = feeder.fps
        feeder.start()

        val refreshText = refreshRate?.let { String.format(Locale.ROOT, "%.2f", it) }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ProjectionSurface(
                    decoder = decoder,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    onInput = ::onInput,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xAA000000))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val small = 12.sp
                    Text(statsText, color = Color.White, fontSize = small)
                    Text(
                        stringResource(
                            R.string.demo_refresh,
                            refreshText ?: stringResource(R.string.demo_refresh_unknown),
                            declaredFps,
                            feedFps,
                        ),
                        color = Color.White,
                        fontSize = small,
                    )
                    Text(
                        touchText?.let { stringResource(R.string.demo_touch, it) } ?: stringResource(R.string.demo_touch_none),
                        color = Color.White,
                        fontSize = small,
                    )
                    resyncText?.let { Text(stringResource(R.string.demo_resync, it), color = Color.White, fontSize = small) }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { feeder.toggleOrientation() }) { Text(stringResource(R.string.demo_toggle_orientation)) }
                    Button(onClick = {
                        val next = fpsChoices[(fpsChoices.indexOf(feeder.fps) + 1) % fpsChoices.size]
                        feeder.fps = next
                        feedFps = next
                    }) { Text(stringResource(R.string.demo_cycle_fps)) }
                    Button(onClick = { feeder.requestKeyFrame() }) { Text(stringResource(R.string.demo_key_frame)) }
                    Button(onClick = {
                        feedPaused = !feedPaused
                        feeder.paused = feedPaused
                    }) { Text(stringResource(if (feedPaused) R.string.demo_resume_feed else R.string.demo_pause_feed)) }
                    Button(onClick = {
                        framePacing = !framePacing
                        decoder.framePacing = framePacing
                    }) { Text(stringResource(if (framePacing) R.string.demo_pacing_on else R.string.demo_pacing_off)) }
                    Button(onClick = {
                        val next = JITTER_CHOICES[(JITTER_CHOICES.indexOf(feedJitterMs) + 1) % JITTER_CHOICES.size]
                        feeder.jitterMs = next
                        feedJitterMs = next
                    }) { Text(stringResource(R.string.demo_jitter, feedJitterMs)) }
                }
            }
        }
    }

    override fun onDestroy() {
        feeder.stop()
        decoder.close()
        super.onDestroy()
    }

    private fun onInput(input: HiSightInput) {
        feeder.onInput(input)
        if (input is HiSightInput.Touch) {
            touchText = input.action.name + " " + input.points.joinToString(" ") { "${it.id}@(${it.x},${it.y})" } +
                " / ${input.width}x${input.height}"
        }
    }

    private companion object {
        /** 「送帧抖动」依次切换的档位（毫秒）。 */
        val JITTER_CHOICES = listOf(0, 4, 8)
    }
}
