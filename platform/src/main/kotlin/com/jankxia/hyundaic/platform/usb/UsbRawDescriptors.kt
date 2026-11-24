package com.jankxia.hyundaic.platform.usb

/**
 * 解析 `UsbDeviceConnection.getRawDescriptors()` 给出的原始描述符字节。
 *
 * 为什么需要它：安卓 4.4 上 `UsbInterface.getAlternateSetting()` 不存在（API 21 才有），
 * 而 NCM 数据口的批量端点只存在于非 0 的备用设置（实测是备用 1）。要自己发 SET_INTERFACE
 * 控制请求就得知道备用设置号，只能从描述符字节里读。
 *
 * 另外 4.4 上 `UsbDevice.getInterface(i)` 可能只给出备用 0（端点为空），这时也靠这里的
 * 端点信息判断"系统到底有没有把带端点的那个备用设置暴露出来"。
 */
internal object UsbRawDescriptors {

    /** 一个接口的某个备用设置。 */
    data class Interface(
        val id: Int,
        val alternateSetting: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpoints: List<Endpoint>,
    ) {
        /** 端点地址集合，用来把安卓给的 `UsbInterface` 对象反查成备用设置号。 */
        val endpointAddresses: Set<Int> get() = endpoints.map { it.address }.toSet()
    }

    data class Endpoint(val address: Int, val type: Int, val maxPacketSize: Int)

    private const val TYPE_CONFIG = 2
    private const val TYPE_INTERFACE = 4
    private const val TYPE_ENDPOINT = 5

    /** 端点地址的方向位（bit7）：1 表示 IN（设备 → 主机）。 */
    private const val ENDPOINT_DIRECTION_IN = 0x80

    /** 解析失败或字节不够时跳过那一条，不抛异常：这串字节来自系统，坏掉也不该让连接流程崩。 */
    fun parse(data: ByteArray): List<Interface> {
        val out = mutableListOf<Interface>()
        var pos = 0
        var current: Interface? = null
        while (pos + 2 <= data.size) {
            val length = data[pos].toInt() and 0xFF
            if (length < 2 || pos + length > data.size) break
            when (data[pos + 1].toInt() and 0xFF) {
                TYPE_CONFIG -> current = null
                TYPE_INTERFACE -> {
                    current?.let { out += it }
                    current = null
                    if (length >= 9) {
                        val id = data[pos + 2].toInt() and 0xFF
                        val alt = data[pos + 3].toInt() and 0xFF
                        current = Interface(
                            id = id,
                            alternateSetting = alt,
                            interfaceClass = data[pos + 5].toInt() and 0xFF,
                            interfaceSubclass = data[pos + 6].toInt() and 0xFF,
                            interfaceProtocol = data[pos + 7].toInt() and 0xFF,
                            endpoints = emptyList(),
                        )
                    }
                }
                TYPE_ENDPOINT -> {
                    if (current != null && length >= 7) {
                        val address = data[pos + 2].toInt() and 0xFF
                        val attributes = data[pos + 3].toInt() and 0xFF
                        val maxPacket = ((data[pos + 5].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                        current = current.copy(
                            endpoints = current.endpoints + Endpoint(
                                address = address,
                                type = attributes and 0x03,
                                // wMaxPacketSize 的高位是每微帧的附加包数，取低 11 位才是包长。
                                maxPacketSize = maxPacket and 0x7FF,
                            ),
                        )
                    }
                }
            }
            pos += length
        }
        current?.let { out += it }
        return out
    }

    /**
     * 同一个接口号下，端点地址集合完全一致的那个备用设置号；找不到返回 null。
     * 备用 0 一般是空端点集合，带批量端点的那个通常就是备用 1。
     */
    fun alternateFor(interfaces: List<Interface>, id: Int, endpointAddresses: Set<Int>): Int? =
        interfaces.firstOrNull { it.id == id && it.endpointAddresses == endpointAddresses }?.alternateSetting

    /**
     * 把安卓 `UsbInterface` 的端点地址集合取出来：用来和描述符里的备用设置对上号。
     * 端点地址就是这个接口可见端点对象的 `address`。
     */
    fun endpointAddressesOf(addresses: List<Int>): Set<Int> = addresses.toSet()

    /** 端点是不是 IN（设备 → 主机）。 */
    fun isIn(address: Int): Boolean = (address and ENDPOINT_DIRECTION_IN) != 0
}
