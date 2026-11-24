package com.jankxia.hyundaic.platform.media

import android.annotation.TargetApi
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.util.Locale

/** 系统里的 H.264 解码器，按优先顺序排好，配置失败时依次换下一个。 */
internal object DecoderCatalog {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    class Candidate(
        val name: String,
        val hardware: Boolean,
        /** 解码器声明支持这个尺寸。不支持的也会排在最后试一下：有的解码器报的能力偏保守。 */
        val sizeSupported: Boolean,
        /** 支持安卓标准的低延迟模式（安卓 11 起）。 */
        val lowLatency: Boolean,
    )

    private class Entry(val info: MediaCodecInfo, val capabilities: MediaCodecInfo.CodecCapabilities)

    /** 解码器清单第一次查要几十毫秒，查一次缓存起来（运行中不会变）。 */
    private val decoders: List<Entry> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) loadDecoders() else emptyList()
    }

    /** 枚举系统里的编解码器（安卓 5.0 起才有枚举接口）。 */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun loadDecoders(): List<Entry> {
        val infos = try {
            codecInfos(MediaCodecList(MediaCodecList.REGULAR_CODECS))
        } catch (e: RuntimeException) {
            emptyList()
        }
        return infos.mapNotNull { info ->
            if (info.isEncoder) return@mapNotNull null
            if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) return@mapNotNull null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) return@mapNotNull null
            // 加密内容专用的解码器要配 MediaCrypto，用不上。
            if (info.name.lowercase(Locale.ROOT).endsWith(".secure")) return@mapNotNull null
            val capabilities = try {
                info.getCapabilitiesForType(MIME)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            Entry(info, capabilities)
        }
    }

    /**
     * 系统里的编解码器清单。枚举接口是安卓 5.0 起才有的，这里只管取；
     * 更早的系统不查清单（见 [candidates] 的说明）。
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun codecInfos(list: MediaCodecList): List<MediaCodecInfo> = list.codecInfos.toList()

    /**
     * 能解 [width]×[height] 的解码器在前，硬件解码器在前；同一档里保持系统清单的顺序（一般是厂商推荐的顺序）。
     * 清单查不到任何 H.264 解码器时返回空列表，调用方再按类型让系统挑。
     */
    fun candidates(width: Int, height: Int): List<Candidate> = decoders
        .map { entry ->
            Candidate(
                name = entry.info.name,
                hardware = isHardware(entry.info),
                sizeSupported = sizeSupported(entry.capabilities, width, height),
                lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    entry.capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
            )
        }
        .sortedWith(compareByDescending<Candidate> { it.sizeSupported }.thenByDescending { it.hardware })

    /**
     * 解码器是否声明支持这个尺寸。安卓 5.0 起有按尺寸查的接口；更早的接口只有档次和级别，
     * 查不出尺寸，一律当作支持（反正配置失败会当场换下一个，排序只是少走弯路）。
     */
    private fun sizeSupported(capabilities: MediaCodecInfo.CodecCapabilities, width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        return try {
            capabilities.videoCapabilities?.isSizeSupported(width, height) ?: false
        } catch (e: RuntimeException) {
            false
        }
    }

    /** 安卓 10 起系统直接标明是否纯软件实现；更早只能按名字判断（系统自带的软件解码器有固定前缀）。 */
    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return !info.isSoftwareOnly
        val name = info.name.lowercase(Locale.ROOT)
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw."))
    }
}
