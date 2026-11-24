package com.jankxia.hyundaic.platform.media

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.annotation.TargetApi

/**
 * 建 16 位 PCM 的流模式音轨。正式播放（[AudioPlayer]）和设置页的试听（[AudioPreview]）都用它，
 * 试听听到的就是连上以后实际走的路。
 *
 * - 声道 [AudioStreamChoice.AUTO]：按 [AudioUsage] 的属性建（[trackAttributes]），安卓 8.0 起可以要低延迟输出。
 * - 声道 1～20：用按声道号建音轨的老接口，车机厂商自己加的声道号要靠它。老接口没有低延迟输出。
 *   系统不认的号多半照样建成、当媒体放（标准安卓只直接拒绝 10 号），所以建成不代表车机认了这个号，
 *   以车机上实际听到的为准；只有抛异常或没初始化成功才算被拒，这时退回按用途建。
 */
object PcmTrack {

    class Built(
        val track: AudioTrack,
        /** 这一路实际走了哪条路，给日志和试听结果看；按用途建、属性也没有特别之处时为空。 */
        val route: String,
        /** 向系统要了低延迟输出。 */
        val lowLatencyRequested: Boolean,
        /** 指定的声道号被系统拒绝，退回了按用途建。 */
        val streamRejected: Boolean,
    )

    /**
     * 建一条音轨。[stream] 是 [AudioStreamChoice] 的值；[bufferBytes] 是申请的缓冲。
     * 按用途也建不起来时抛异常，消息给日志看。
     */
    fun build(
        usage: AudioUsage,
        stream: Int,
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
        lowLatency: Boolean,
    ): Built {
        val choice = AudioStreamChoice.normalize(stream)
        var rejection: String? = null
        if (choice != AudioStreamChoice.AUTO) {
            val byNumber = byStreamNumber(choice, sampleRate, channelMask, bufferBytes)
            byNumber.getOrNull()?.let { track ->
                return Built(track, "声道 $choice 号（按号建，没有低延迟输出）", lowLatencyRequested = false, streamRejected = false)
            }
            val why = byNumber.exceptionOrNull()
            rejection = "声道 $choice 号被系统拒绝（${why?.javaClass?.simpleName}：${why?.message}），改按用途建"
        }
        // AudioTrack.Builder 是安卓 6.0（API 23）起才有的；更低的版本（含安卓 5.x、4.4）走老构造，按流类型建轨。
        // Builder 路径隔离在独立函数里，老系统不加载这些类。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return buildWithAttributes(usage, sampleRate, channelMask, bufferBytes, lowLatency, rejection)
        }
        val track = try {
            @Suppress("DEPRECATION")
            AudioTrack(
                usage.streamType(), sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM,
            )
        } catch (e: Exception) {
            throw IllegalStateException("AudioTrack 老接口建轨失败（${e.javaClass.simpleName}：${e.message}）")
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack 没有初始化成功")
        }
        return Built(track, rejection ?: "", lowLatencyRequested = false, streamRejected = rejection != null)
    }

    /** API 23+：按 AudioAttributes 建轨（AudioTrack.Builder 是安卓 6.0 起才有的）。 */
    @TargetApi(Build.VERSION_CODES.M)
    private fun buildWithAttributes(
        usage: AudioUsage,
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
        lowLatency: Boolean,
        rejection: String?,
    ): Built {
        val attrs = usage.trackAttributes()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs.attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        var lowLatencyRequested = false
        // 安卓 8.0 以下没有性能模式，照常建（走系统默认的输出路径）。
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            lowLatencyRequested = true
        }
        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack 没有初始化成功")
        }
        val route = listOfNotNull(rejection, attrs.note.ifEmpty { null }).joinToString("；")
        return Built(track, route, lowLatencyRequested, streamRejected = rejection != null)
    }

    /** 按声道号建：抛异常或没初始化成功都算被拒（没初始化成功的当场释放）。 */
    private fun byStreamNumber(stream: Int, sampleRate: Int, channelMask: Int, bufferBytes: Int): Result<AudioTrack> {
        val track = try {
            // 故意用老接口：厂商加的声道号只有它认，新的属性写法只认标准用途。
            @Suppress("DEPRECATION")
            AudioTrack(stream, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return Result.failure(IllegalStateException("没有初始化成功"))
        }
        return Result.success(track)
    }
}
