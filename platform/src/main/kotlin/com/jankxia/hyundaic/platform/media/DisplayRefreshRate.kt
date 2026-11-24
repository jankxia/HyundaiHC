package com.jankxia.hyundaic.platform.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View

/**
 * 读屏幕刷新率，作为向手机声明帧率的依据（怎么换算见 [FrameRatePolicy]）。
 *
 * 读的是屏幕当前显示模式的刷新率（`Display.getMode()`，安卓 6.0 起才有）：安卓 12 起系统可能给单个应用
 * 改写 `Display.getRefreshRate()` 的返回值（按应用限帧），显示模式的值才是屏幕实际的刷新周期。
 * 读不到（含安卓 4.4，那里没有显示模式的概念）时退回 `Display.getRefreshRate()`。厂商有可能报错刷新率，
 * 真机上要核对一次。
 */
object DisplayRefreshRate {

    /** [display] 的刷新率（Hz），读不到返回 null。 */
    fun of(display: Display?): Float? {
        if (display == null) return null
        // 显示模式是安卓 6.0 起才有的：更早的系统直接读 getRefreshRate()。
        val fromMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                display.mode.refreshRate
            } catch (e: RuntimeException) {
                Float.NaN
            }
        } else {
            Float.NaN
        }
        if (fromMode.isFinite() && fromMode > 0f) return fromMode
        val legacy = display.refreshRate
        return if (legacy.isFinite() && legacy > 0f) legacy else null
    }

    /** 视图所在屏幕的刷新率；视图还没挂到窗口上时退回默认屏幕。 */
    fun of(view: View): Float? = of(view.display) ?: ofDefaultDisplay(view.context)

    /** 默认屏幕（车机主屏）的刷新率。可以用应用 Context。 */
    fun ofDefaultDisplay(context: Context): Float? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return of(manager.getDisplay(Display.DEFAULT_DISPLAY))
    }

    /** 默认屏幕按 [FrameRatePolicy.declaredFps] 换算出的声明帧率。 */
    fun declaredFps(context: Context, maxFps: Int = FrameRatePolicy.DEFAULT_MAX_FPS): Int =
        FrameRatePolicy.declaredFps(ofDefaultDisplay(context), maxFps)

    /**
     * [display] 的刷新时序，交给 [VideoDecoder.setDisplayTiming]；[display] 为 null 时返回 null。
     * 帧时间偏移和送显提前量是安卓 5.0 起公开的两项；更早的系统、或者读不到（异常）都记 0。
     */
    fun timingOf(display: Display?): DisplayTiming? {
        if (display == null) return null
        val hz = of(display)
        val period = if (hz != null && hz in PLAUSIBLE_MIN_HZ..PLAUSIBLE_MAX_HZ) (1e9 / hz).toLong() else 0L
        val hasTiming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        val appOffset = if (hasTiming) {
            try {
                display.appVsyncOffsetNanos
            } catch (e: RuntimeException) {
                0L
            }
        } else {
            0L
        }
        val deadline = if (hasTiming) {
            try {
                display.presentationDeadlineNanos
            } catch (e: RuntimeException) {
                0L
            }
        } else {
            0L
        }
        return DisplayTiming(period, appOffset, deadline)
    }

    private const val PLAUSIBLE_MIN_HZ = 20f
    private const val PLAUSIBLE_MAX_HZ = 500f
}
