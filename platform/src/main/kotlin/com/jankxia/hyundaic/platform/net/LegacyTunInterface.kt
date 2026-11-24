package com.jankxia.hyundaic.platform.net

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 安卓 4.4（API 19）的 TUN 收发。
 *
 * `android.system.Os` 是 API 21 才有的，4.4 上 [TunInterface] 整个用不了。这里改用
 * `FileInputStream` / `FileOutputStream` 直接对 TUN 描述符做 read/write —— TUN 就是个字符设备，
 * read/write 系统调用本身在所有版本都一样，少的只是 `Os` 那层包装和 `poll`。
 *
 * 两点差异：
 * - **没有 poll**：读是阻塞的（[WiredVpnService] 在 4.4 上不调 `setBlocking(false)`，本来就是阻塞 fd）。
 *   唤醒靠关描述符：阻塞中的 read 会抛 IOException，返回 -1。
 * - **不关流**：描述符的所有权在 [ParcelFileDescriptor]，关流会把同一个 fd 一起关掉。两个流只作为
 *   强引用字段一直持有（对象活着就不会被回收），最终由 `descriptor.close()` 统一关闭。
 *
 * 停止顺序与 [TunInterface] 一致：[wakeup] → 等读线程退出 → [close]。
 */
internal class LegacyTunInterface(private val descriptor: ParcelFileDescriptor) : PacketTunnel, Closeable {

    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val lock = Any()

    @Volatile
    private var closed = false

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
        if (closed) return -1
        return try {
            val n = input.read(buffer, offset, maxLength)
            // 阻塞读不会返回 0：读到 0 说明网卡已经没了（EOF）。
            if (n > 0) n else -1
        } catch (e: IOException) {
            -1
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (closed) return false
        return try {
            output.write(buffer, offset, length)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** 关描述符叫醒阻塞中的读线程；可重复调用。 */
    override fun wakeup() {
        if (closed) return
        closed = true
        synchronized(lock) {
            try {
                descriptor.close()
            } catch (e: IOException) {
                // 已经关掉或者系统不认，没有补救办法。
            }
        }
    }

    override fun close() {
        wakeup()
    }
}
