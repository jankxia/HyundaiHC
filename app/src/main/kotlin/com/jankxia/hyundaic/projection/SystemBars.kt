package com.jankxia.hyundaic.projection

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 投屏时的沉浸式全屏：状态栏和底部导航栏都藏起来，内容铺到整块屏幕。
 *
 * 车机上只藏状态栏是不够的：导航栏还占着屏幕底部，画面就少一块（实测）。从屏幕边缘划一下栏会临时出现，
 * 过一会儿自己收回。安卓 11 起用 WindowInsetsController，更早的系统由 androidx 换成 systemUiVisibility 的
 * 全屏、隐藏导航栏和粘性沉浸标志，效果相同。
 */
internal object SystemBars {
    private val BARS = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()

    /**
     * [fullScreen] 为 false 时只藏状态栏、保留底部导航栏，内容不画到导航栏下面（画面让出它的位置）。
     */
    fun hide(window: Window, view: View, fullScreen: Boolean = true) {
        if (!fullScreen) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
            }
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 安卓 9 起：屏幕有挖孔时内容也铺到挖孔两侧的短边上（车机屏一般没有挖孔，这里只是不留黑边）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            if (attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attributes
            }
        }
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(BARS)
        }
    }

    fun show(window: Window, view: View) {
        WindowCompat.getInsetsController(window, view).show(BARS)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            if (attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = attributes
            }
        }
    }
}
