package com.jankxia.hyundaic.core.nearby

import com.jankxia.hyundaic.core.common.ByteWriter
import com.jankxia.hyundaic.core.common.hexToBytes
import com.jankxia.hyundaic.core.common.identity.HostIdentity
import java.util.UUID

/**
 * 车机的 BLE 广播：没配对的手机靠它弹「发现车机」，配对过的手机靠它回连。两种情况车机发的是**同一份**广播。
 *
 * 广播数据里只有一段 Service Data，挂在 16 位服务 UUID `FDEE` 下，载荷按 `[tag][值]` 顺序拼接（tag 1 字节，长度隐含）：
 *
 * | 偏移 | 内容 |
 * |---|---|
 * | 0 | 版本 `01` |
 * | 1 | `01` |
 * | 2 | 能力：`01`；声明「本机用随机地址广播」时 `0F`（见 [Options.declareRandomAddress]） |
 * | 3 | tag `03`，后跟车型编号前 6 位转成的 3 字节 |
 * | 7 | tag `04`，后跟车型编号后 2 位转成的 1 字节 |
 * | 9 | tag `10`，后跟 1 字节序列号 |
 * | （可选） | tag `11`，后跟 1 字节有符号发射功率（dBm），见 [Options.txPowerDbm] |
 * | （可选，最后） | `FF 01`：声明随机地址时追加 |
 *
 * 默认 11 字节。车型编号必须按 6 + 2 拆开；整 8 位当一个字段发，手机按 tag 解出的长度对不上，会把广播丢掉。
 *
 * **载荷里没有任何和配对关系、单车身份有关的字段。** 手机拿到的车机地址就是这条广播的发送地址，
 * 再和车型编号一起算「车机 ID」（与 [com.jankxia.hyundaic.core.common.identity.DeviceId] 同一算法）去查自己的信任表：
 * 查得到就免码回连，查不到就当新车。所以发送地址必须是车机经典蓝牙的公共地址 —— 系统蓝牙栈开了 LE 隐私时，
 * 广播用的是随机地址，手机会去寻呼一个不存在的地址，必然超时。应用改不了这一点。
 *
 * 序列号：每发布一次加 1（模 256），初值随意。手机把序列号变了的广播当成一次新的发现。
 *
 * 广播参数（平台层负责）：传统广播、可连接（因而可扫描）、间隔约 100 ms、中档发射功率、扫描响应为空、
 * 不带设备名和功率字段；可连接广播最前面的 Flags（`02 01 02`）由系统蓝牙栈加，见 [legacyAdvertisingData]。
 *
 * 无状态，线程安全。
 */
object BleAdvertisement {
    /** Service Data 所在的服务 UUID（16 位 `FDEE`）。 */
    val SERVICE_DATA_UUID: UUID = UUID.fromString("0000FDEE-0000-1000-8000-00805F9B34FB")

    const val VERSION = 0x01
    const val TAG_MODEL_ID = 0x03
    const val TAG_SUB_MODEL_ID = 0x04
    const val TAG_SEQUENCE = 0x10
    const val TAG_TX_POWER = 0x11

    /** 声明随机地址时追加在末尾的一项：tag `FF`、值 `01`。 */
    const val TAG_RANDOM_ADDRESS = 0xFF

    /** 偏移 2 的能力字节：平常 `01`。 */
    const val ABILITY_DEFAULT = 0x01

    /** 偏移 2 的能力字节：声明本机用随机地址广播。 */
    const val ABILITY_RANDOM_ADDRESS = 0x0F

    /** 默认载荷长度（不带可选项）。 */
    const val PAYLOAD_LENGTH = 11

    /** 传统广播数据的上限（字节）。 */
    const val MAX_LEGACY_DATA = 31

    /** AD 类型：Flags。 */
    private const val AD_FLAGS = 0x01

    /** AD 类型：16 位 UUID 的 Service Data。 */
    private const val AD_SERVICE_DATA_16 = 0x16

    /** 一般可发现（LE General Discoverable）；不置「不支持经典蓝牙」位。 */
    private const val FLAGS_GENERAL_DISCOVERABLE = 0x02

    /** 广播载荷的可选项。 */
    class Options(
        /**
         * 声明「本机用随机地址广播」：偏移 2 改成 [ABILITY_RANDOM_ADDRESS]，末尾追加 `FF 01`。
         * 较新的车机实现在自己的广播地址是随机地址时这样发；手机看到后怎么找车机地址没有验证，所以默认关。
         */
        val declareRandomAddress: Boolean = false,
        /** 带上发射功率（dBm，-127..127）；null（默认）不带这一项。 */
        val txPowerDbm: Int? = null,
    ) {
        init {
            require(txPowerDbm == null || txPowerDbm in -127..127) { "发射功率要在 -127..127 dBm 之间" }
        }
    }

    /**
     * 拼 Service Data 载荷。
     *
     * @param sequence 序列号，0..255。
     * @throws IllegalArgumentException 序列号超出范围。
     */
    fun payload(host: HostIdentity, sequence: Int, options: Options = Options()): ByteArray {
        require(sequence in 0..0xFF) { "序列号要在 0..255 之间" }
        val out = ByteWriter(PAYLOAD_LENGTH + 4)
            .u8(VERSION)
            .u8(0x01)
            .u8(if (options.declareRandomAddress) ABILITY_RANDOM_ADDRESS else ABILITY_DEFAULT)
            .u8(TAG_MODEL_ID).bytes(host.modelId.hexToBytes())
            .u8(TAG_SUB_MODEL_ID).bytes(host.subModelId.hexToBytes())
            .u8(TAG_SEQUENCE).u8(sequence)
        options.txPowerDbm?.let { out.u8(TAG_TX_POWER).u8(it and 0xFF) }
        if (options.declareRandomAddress) out.u8(TAG_RANDOM_ADDRESS).u8(0x01)
        return out.toByteArray()
    }

    /** 下一个序列号（加 1，模 256）。 */
    fun nextSequence(current: Int): Int = (current + 1) and 0xFF

    /**
     * 系统蓝牙栈按 [payload] 实际发出的传统广播数据：`02 01 02`（Flags）+ `长度 16 EE FD 载荷`。
     * 只用来记日志、核对；平台层交给系统的只有 Service Data 本身。
     *
     * @throws IllegalArgumentException 超出传统广播 31 字节的上限。
     */
    fun legacyAdvertisingData(payload: ByteArray): ByteArray {
        val serviceDataLength = 1 + 2 + payload.size
        val total = 3 + 1 + serviceDataLength
        require(total <= MAX_LEGACY_DATA) { "广播数据 $total 字节，超出传统广播的 $MAX_LEGACY_DATA 字节" }
        val out = ByteArray(total)
        out[0] = 0x02
        out[1] = AD_FLAGS.toByte()
        out[2] = FLAGS_GENERAL_DISCOVERABLE.toByte()
        out[3] = serviceDataLength.toByte()
        out[4] = AD_SERVICE_DATA_16.toByte()
        // 16 位 UUID 在空口上是小端：FDEE → EE FD。
        val short = (SERVICE_DATA_UUID.mostSignificantBits ushr 32).toInt() and 0xFFFF
        out[5] = (short and 0xFF).toByte()
        out[6] = (short ushr 8).toByte()
        payload.copyInto(out, 7)
        return out
    }
}
