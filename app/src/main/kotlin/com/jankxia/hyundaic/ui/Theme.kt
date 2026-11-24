package com.jankxia.hyundaic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 界面的配色、字号和几个公共尺寸。车机上常年开着，固定用深色，不跟系统深浅色走。

/** 配色。 */
internal object Palette {
    /** 页面底色（深蓝灰）。 */
    val Background = Color(0xFF111822)

    /** 卡片、分组框：白色 15% 叠在底色上。 */
    val Card = Color(0x26FFFFFF)
    val Text = Color.White

    /** 次要文字：说明、页脚。 */
    val Muted = Color(0xFFB0B6C0)

    /** 小标题（分组名、卡片名）。 */
    val Accent = Color(0xFF8FB8FF)

    /** 要用户注意的：缺东西、出错、需要重开才生效。 */
    val Warn = Color(0xFFFFB74D)

    /** 开关打开、选项选中。 */
    val On = Color(0xFF2D7DF6)
    val SwitchOff = Color(0xFF4A505A)

    /** 顶栏按钮（「设置」「返回」）和普通操作按钮。 */
    val Button = Color(0xFF59606B)
    val SegmentBackground = Color(0x1AFFFFFF)
    val Divider = Color(0x1FFFFFFF)

    /** 连接码方框。 */
    val PinBox = Color(0xFF565D65)
    val PinText = Color(0xFFEBF4FE)

    /** 胶囊主按钮：从左到右的蓝色渐变。 */
    val PrimaryStart = Color(0xFF1590FA)
    val PrimaryEnd = Color(0xFF00ACEB)

    /** 胶囊次按钮。 */
    val Secondary = Color(0xFF365173)

    /** 弹出框、菜单的底色（比页面略亮）。 */
    val Raised = Color(0xFF1E2632)
}

/** 公共尺寸。 */
internal object Dimens {
    /** 页面左右留白；窄屏（宽不到 [WIDE_PAGE_MIN]）收成 [PageSideNarrow]。 */
    val PageSide = 48.dp
    val PageSideNarrow = 16.dp
    val WIDE_PAGE_MIN = 600.dp

    /** 低于这个高度算矮屏：字号、间距收一档。 */
    val CompactHeight = 560.dp
    val CardRadius = 12.dp
    val CardPadding = 14.dp
}

/** 当前是不是矮屏（见 [Dimens.CompactHeight]），由各页面的根布局按实际高度提供。 */
internal val LocalCompact = compositionLocalOf { false }

/** 各处文字样式。只定字号、粗细，颜色由调用处给；字距一律为 0（中文加字距反而难看）。 */
internal object UiType {
    private val base = TextStyle(letterSpacing = 0.sp)

    fun title(compact: Boolean) = base.copy(fontSize = if (compact) 26.sp else 34.sp)
    val topButton = base.copy(fontSize = 18.sp)
    val pill = base.copy(fontSize = 18.sp)
    fun action(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp)

    // 首页卡片
    fun cardHeader(compact: Boolean) = base.copy(fontSize = if (compact) 12.sp else 14.sp)
    val cardHeading = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.em)
    val status = base.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val hint = base.copy(fontSize = 13.sp, lineHeight = 1.45.em)
    fun message(compact: Boolean) = base.copy(fontSize = if (compact) 17.sp else 20.sp, lineHeight = 1.5.em)
    fun footer(compact: Boolean) = base.copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = 1.4.em)

    // 设置页
    fun groupTitle(compact: Boolean) =
        base.copy(fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em)
    fun rowTitle(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 17.sp)
    fun rowDesc(compact: Boolean) = base.copy(fontSize = if (compact) 12.sp else 13.sp, lineHeight = 1.45.em)
    fun rowNote(compact: Boolean) =
        base.copy(fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, lineHeight = 1.4.em)
    fun option(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp)
    fun value(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp, fontFamily = FontFamily.Monospace)
    fun chevron(compact: Boolean) = base.copy(fontSize = if (compact) 18.sp else 20.sp)
}

/**
 * 全应用的主题：固定深色。还在用的 Material 组件（输入框、弹框）从这里取色：
 * 主色是开关蓝，「错误」用提醒用的琥珀色，和自己画的控件一致。
 */
@Composable
internal fun HyundaiHcTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Palette.On,
        onPrimary = Color.White,
        primaryContainer = Palette.On,
        onPrimaryContainer = Color.White,
        secondary = Palette.Accent,
        onSecondary = Palette.Background,
        tertiary = Palette.Accent,
        background = Palette.Background,
        onBackground = Color.White,
        surface = Palette.Background,
        onSurface = Color.White,
        surfaceVariant = Palette.Raised,
        onSurfaceVariant = Palette.Muted,
        surfaceContainerLowest = Palette.Background,
        surfaceContainerLow = Palette.Raised,
        surfaceContainer = Palette.Raised,
        surfaceContainerHigh = Palette.Raised,
        surfaceContainerHighest = Palette.Raised,
        outline = Palette.Button,
        outlineVariant = Palette.SwitchOff,
        error = Palette.Warn,
        onError = Palette.Background,
        errorContainer = Palette.Warn,
        onErrorContainer = Palette.Background,
    )
    // 中文不加字距；其余沿用默认字号。
    val d = Typography()
    val typography = Typography(
        bodyLarge = d.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = d.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = d.bodySmall.copy(letterSpacing = 0.sp),
        labelLarge = d.labelLarge.copy(letterSpacing = 0.sp),
        labelMedium = d.labelMedium.copy(letterSpacing = 0.sp),
        labelSmall = d.labelSmall.copy(letterSpacing = 0.sp),
        titleMedium = d.titleMedium.copy(letterSpacing = 0.sp),
        titleSmall = d.titleSmall.copy(letterSpacing = 0.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
