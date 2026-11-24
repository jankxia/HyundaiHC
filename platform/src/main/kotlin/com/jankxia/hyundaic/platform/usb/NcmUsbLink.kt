package com.jankxia.hyundaic.platform.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.jankxia.hyundaic.core.common.MalformedMessageException
import com.jankxia.hyundaic.platform.net.NtbOutLayout
import com.jankxia.hyundaic.platform.net.NtbPort
import java.io.Closeable
import java.io.IOException

/**
 * 抢下手机的 USB 网卡接口，用两个批量端点收发 NTB。
 *
 * 车机内核里如果有 CDC NCM 驱动，手机一切过来它就会先绑上；强占（`claimInterface(…, true)`）会把它踢开，
 * 普通应用就能做到（实测）。关闭后内核驱动可以重新绑上。
 *
 * 关闭前要先停掉所有在用 [receive] / [send] 的线程（见 [com.jankxia.hyundaic.platform.net.NcmDataPath.stop]）。
 */
internal class NcmUsbLink private constructor(
    private val connection: UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val dataInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    override val receiveSize: Int,
    override val outLayout: NtbOutLayout,
    override val maxOutBlockLength: Int,
    override val zlpPacketSize: Int,
    /** 手机报的 NTB 参数；取不到时为 null（用了默认值）。 */
    val parameters: NtbParameters?,
) : NtbPort, Closeable {

    private var closed = false

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val n = try {
            connection.bulkTransfer(bulkIn, buffer, minOf(receiveSize, buffer.size), timeoutMs)
        } catch (e: RuntimeException) {
            return -1
        }
        if (n >= 0) return n
        // bulkTransfer 超时和出错都返回 -1，只能看耗时区分：设备拔掉、权限没了时会立刻返回。
        return if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs / 2) 0 else -1
    }

    override fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean = try {
        connection.bulkTransfer(bulkOut, buffer, length, timeoutMs) == length
    } catch (e: RuntimeException) {
        false
    }

    /** 释放接口并关闭连接。可重复调用。 */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(dataInterface)
            connection.releaseInterface(controlInterface)
        } catch (e: RuntimeException) {
            // 设备已拔出时释放会失败，照样关连接。
        }
        connection.close()
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val NOTIFICATION_TIMEOUT_MS = 300
        private const val DEFAULT_ZLP_PACKET_SIZE = 512

        /** 标准 USB 请求 SET_INTERFACE：主机 → 设备、标准请求、接收者为接口（bmRequestType 0x01），bRequest 0x0B。 */
        private const val SET_INTERFACE_REQUEST_TYPE = 0x01
        private const val SET_INTERFACE_REQUEST = 0x0B

        /**
         * 打开切成 USB 网卡的手机：强占控制口和数据口 → 读 NTB 参数 → 数据口切到备用 0（NCM 功能复位）→
         * 需要时压低手机的 NTB 上限 → 切到带端点的备用设置 → 读掉一条连接通知。
         * 任何一步失败都关掉连接并抛 [IOException]，消息说明是哪一步。
         *
         * @param log 记录过程的回调（不含设备标识）。
         */
        fun open(usbManager: UsbManager, device: UsbDevice, sdkInt: Int, log: (String) -> Unit): NcmUsbLink {
            // 安卓 4.4（API 19）没有 setInterface，也读不到 getAlternateSetting：改用标准 USB SET_INTERFACE
            // 控制请求切备用设置，备用设置号从原始描述符里解析（见 UsbRawDescriptors）。
            val legacyInterfaceSwitch = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
            val selection = NcmInterfaces.select(UsbDescriber.describe(device))
                ?: throw IOException(
                    "没找到 NCM 控制口或带批量端点的数据口（安卓 ${Build.VERSION.RELEASE}）：" +
                        "4.4 上如果系统只暴露数据口的备用 0（没有端点），这条线走不通",
                )
            val control = device.getInterface(selection.control.index)
            val dataActive = device.getInterface(selection.dataActive.index)
            val dataIdle = selection.dataIdle?.let { device.getInterface(it.index) }
            val bulkIn = dataActive.getEndpoint(selection.bulkIn.index)
            val bulkOut = dataActive.getEndpoint(selection.bulkOut.index)
            val notification = selection.notification?.let { control.getEndpoint(it.index) }

            val connection = try {
                usbManager.openDevice(device)
            } catch (e: RuntimeException) {
                null
            } ?: throw IOException("打开设备失败（没有授权或已拔出）")
            try {
                if (!connection.claimInterface(control, true) || !connection.claimInterface(dataActive, true)) {
                    throw IOException("抢占 NCM 接口失败")
                }
                val parameters = readNtbParameters(connection, control.id, log)
                // 备用设置号：5.0 起直接读接口对象；4.4 没有这个属性，按端点集合从原始描述符反查。
                val rawInterfaces = if (legacyInterfaceSwitch) {
                    runCatching { UsbRawDescriptors.parse(connection.rawDescriptors) }.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
                val activeAddresses = endpointAddressesOf(dataActive)
                val activeAlt = if (legacyInterfaceSwitch) {
                    UsbRawDescriptors.alternateFor(rawInterfaces, dataActive.id, activeAddresses) ?: run {
                        log("原始描述符里没找到和数据口端点一致的备用设置，按备用 1 试")
                        1
                    }
                } else {
                    dataActive.alternateSetting
                }
                val idleAlt = if (legacyInterfaceSwitch && dataIdle != null) {
                    UsbRawDescriptors.alternateFor(rawInterfaces, dataIdle.id, endpointAddressesOf(dataIdle)) ?: 0
                } else {
                    0
                }
                if (dataIdle != null) {
                    val switched = if (legacyInterfaceSwitch) {
                        setAlternateLegacy(connection, dataIdle.id, idleAlt, log)
                    } else {
                        connection.setInterface(dataIdle)
                    }
                    if (!switched) log("数据口切到备用 $idleAlt 失败，继续")
                }
                val plan = CdcNcm.receivePlan(parameters?.ntbInMaxSize, sdkInt)
                plan.setInputSize?.let { size ->
                    // 规范要求数据口在备用 0 时才能改 NTB 输入上限。
                    val payload = CdcNcm.encodeNtbInputSize(size)
                    val n = connection.controlTransfer(
                        CdcNcm.REQUEST_TYPE_OUT, CdcNcm.SET_NTB_INPUT_SIZE, 0, control.id,
                        payload, payload.size, CONTROL_TIMEOUT_MS,
                    )
                    log(
                        if (n == payload.size) "已把手机的 NTB 上限压到 $size 字节（系统单次传输上限）"
                        else "压低手机 NTB 上限失败（返回 $n），超过 $size 字节的块会被截断丢弃",
                    )
                }
                val switched = if (legacyInterfaceSwitch) {
                    setAlternateLegacy(connection, dataActive.id, activeAlt, log)
                } else {
                    connection.setInterface(dataActive)
                }
                if (!switched) throw IOException("数据口切到备用 $activeAlt 失败")
                notification?.let {
                    // 手机切好后会在通知端点上报连接状态与速率；读掉一条，与实测通过的做法一致。读不到也不要紧。
                    connection.bulkTransfer(it, ByteArray(16), 16, NOTIFICATION_TIMEOUT_MS)
                }
                val layout = parameters
                    ?.takeIf { NtbOutLayout.isValid(it.outDivisor, it.outAlignment) }
                    ?.let { NtbOutLayout.of(it.outDivisor, it.outPayloadRemainder, it.outAlignment) }
                    ?: NtbOutLayout.DEFAULT
                val maxOut = parameters?.ntbOutMaxSize
                    ?.coerceIn(CdcNcm.MIN_NTB_SIZE.toLong(), 0xFFFFL)
                    ?.toInt()
                    ?: CdcNcm.MIN_NTB_SIZE
                val zlp = bulkOut.maxPacketSize.takeIf { it > 0 } ?: DEFAULT_ZLP_PACKET_SIZE
                log(
                    "USB 网卡就绪：每次接收 ${plan.receiveSize} 字节，出向上限 $maxOut 字节，$layout，" +
                        "端点包长 IN ${bulkIn.maxPacketSize} / OUT ${bulkOut.maxPacketSize}",
                )
                return NcmUsbLink(
                    connection, control, dataActive, bulkIn, bulkOut,
                    plan.receiveSize, layout, maxOut, zlp, parameters,
                )
            } catch (e: IOException) {
                closeQuietly(connection, control, dataActive)
                throw e
            } catch (e: RuntimeException) {
                closeQuietly(connection, control, dataActive)
                throw IOException("打开 USB 网卡出错: ${e.javaClass.simpleName}", e)
            }
        }

        /** 安卓 `UsbInterface` 的端点地址集合：只用来和描述符里的备用设置对号。 */
        private fun endpointAddressesOf(iface: UsbInterface): Set<Int> =
            (0 until iface.endpointCount).map { iface.getEndpoint(it).address }.toSet()

        /**
         * 安卓 4.4 没有 setInterface：自己发一条标准 USB SET_INTERFACE 请求。
         * bmRequestType 0x01（主机 → 设备、标准、接收者为接口），bRequest 0x0B，
         * wValue 备用设置号，wIndex 接口号，没有数据阶段。
         */
        private fun setAlternateLegacy(
            connection: UsbDeviceConnection,
            interfaceId: Int,
            alternate: Int,
            log: (String) -> Unit,
        ): Boolean {
            val result = try {
                connection.controlTransfer(
                    SET_INTERFACE_REQUEST_TYPE, SET_INTERFACE_REQUEST, alternate, interfaceId,
                    ByteArray(0), 0, CONTROL_TIMEOUT_MS,
                )
            } catch (e: RuntimeException) {
                log("SET_INTERFACE 出错（${e.javaClass.simpleName}），接口 $interfaceId 没能切到备用 $alternate")
                return false
            }
            if (result >= 0) {
                log("接口 $interfaceId 切到备用 $alternate（SET_INTERFACE 返回 $result）")
                return true
            }
            log("接口 $interfaceId 切到备用 $alternate 被拒（返回 $result）")
            return false
        }

        private fun readNtbParameters(connection: UsbDeviceConnection, controlId: Int, log: (String) -> Unit): NtbParameters? {
            val buffer = ByteArray(CdcNcm.NTB_PARAMETERS_LENGTH)
            val n = connection.controlTransfer(
                CdcNcm.REQUEST_TYPE_IN, CdcNcm.GET_NTB_PARAMETERS, 0, controlId,
                buffer, buffer.size, CONTROL_TIMEOUT_MS,
            )
            val parameters = try {
                NtbParameters.parse(buffer, maxOf(n, 0))
            } catch (e: MalformedMessageException) {
                log("读 NTB 参数失败（返回 $n），用默认值")
                return null
            }
            log(
                "NTB 参数：格式 0x${Integer.toHexString(parameters.formatsSupported)}，" +
                    "入向上限 ${parameters.ntbInMaxSize}，出向上限 ${parameters.ntbOutMaxSize}，" +
                    "出向对齐 ${parameters.outDivisor}/${parameters.outPayloadRemainder}/${parameters.outAlignment}",
            )
            if (!parameters.supportsNtb16) log("手机说不支持 NTB16，照常按 NTB16 收发")
            return parameters
        }

        private fun closeQuietly(connection: UsbDeviceConnection, vararg interfaces: UsbInterface) {
            try {
                for (iface in interfaces) connection.releaseInterface(iface)
            } catch (e: RuntimeException) {
                // 照样关连接。
            }
            connection.close()
        }
    }
}
