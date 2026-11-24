package com.jankxia.hyundaic.connection

import android.annotation.SuppressLint
import android.content.Context

/**
 * 投屏画面区域：「全屏显示」开时是整块屏幕；关时保留系统底部导航栏，画面让出它的位置（比如车机装了常驻
 * 悬浮底栏，全屏时会被它挡住）。声明给手机的屏幕尺寸必须和画面区域一致，手机才按它编码、不拉伸。
 */
internal object DisplayArea {
    /**
     * 这次连接开始时用的全屏设置。投屏页按它藏栏，保证画面区域和这次声明给手机的尺寸一致：
     * 连接中途改设置不影响本次（手机中途不理会尺寸变化）。
     */
    @Volatile
    var activeFullScreen: Boolean = true

    /** 实测的底部导航栏高度（像素，不管它此刻显不显示）；0 表示还没测到。由界面拿到窗口插入量时记下。 */
    @Volatile
    var measuredNavigationBarHeight: Int = 0

    /** 声明用的屏幕参数：全屏时是整块屏幕，不全屏时高度减去底部导航栏。 */
    fun screenFor(context: Context, fullScreen: Boolean): ScreenSpec {
        val screen = DeviceInfo.screen(context)
        if (fullScreen) return screen
        val bar = navigationBarHeight(context)
        val height = (screen.height - bar).coerceAtLeast(1) and 1.inv()
        return screen.copy(height = height)
    }

    /** 底部导航栏高度：优先用实测值，没有就用系统资源里的默认高度，再没有就是 0。 */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun navigationBarHeight(context: Context): Int {
        measuredNavigationBarHeight.takeIf { it > 0 }?.let { return it }
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
