package com.jankxia.hyundaic.startup

/**
 * 开机自启的延迟选项。纯逻辑，由 JVM 单元测试覆盖；接线见 [BootStart]、[BootStartReceiver]、[BootDelayReceiver]。
 *
 * 车机开机到 WiFi、蓝牙、系统界面就绪要一段时间，收到开机广播就马上打开界面，常常抢在系统前面、被桌面盖住或者
 * 打不开（安卓 10 起后台应用打开界面要悬浮窗权限，开机那一刻系统往往还没准备好）。所以给车主一个延迟：等系统缓过来
 * 再打开。可选的几档就是车机上实测够用的几个值，默认 [DEFAULT_SECONDS]。
 */
object BootDelayPolicy {

    /** 延迟打开界面的动作（[BootStart.scheduleDelayedLaunch] 用 PendingIntent 发，[BootDelayReceiver] 收）。 */
    const val ACTION_DELAYED_LAUNCH = "com.jankxia.hyundaic.action.DELAYED_BOOT_LAUNCH"

    /** 界面上的几档延迟（秒）。5 秒那档留给「开机广播来得晚、系统已经进桌面」的车机。 */
    val CHOICES_SECONDS: List<Int> = listOf(5, 10, 15, 30, 40, 50, 60)

    /** 默认延迟：30 秒，车机一般这时已经进桌面、WiFi 也连上了。 */
    const val DEFAULT_SECONDS = 30

    /** 存下来的值不管是谁写的，都落到一档合法的选择上；不认识的值回到默认。 */
    fun normalize(seconds: Int): Int = if (seconds in CHOICES_SECONDS) seconds else DEFAULT_SECONDS

    /** 闹钟从现在起多久响（毫秒）。 */
    fun delayMillis(seconds: Int): Long = normalize(seconds) * 1000L

    /** 界面上那一行说明。 */
    fun describe(seconds: Int): String = "开机后延迟 ${normalize(seconds)} 秒打开"
}
