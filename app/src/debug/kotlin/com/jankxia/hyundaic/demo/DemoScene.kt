package com.jankxia.hyundaic.demo

import com.jankxia.hyundaic.core.hisight.HiSightInput
import com.jankxia.hyundaic.platform.media.Yuv
import com.jankxia.hyundaic.platform.media.YuvFrame

/**
 * 演示画面，每一样都对应一项要在车机上目测的内容：
 * - 网格（每 20 像素一条线）：缩放是否均匀、有没有被拉伸；
 * - 画面最外圈 2 像素的黄框：四条边都看得到，说明画面没被裁掉、也没被导航栏挡住；
 * - 四角色块（左上红、右上绿、左下蓝、右下白）：方向对不对；
 * - 来回弹的青色方块：流畅度；
 * - 顶部 12 个小格是帧序号的二进制，每帧都变：能看出有没有卡住；
 * - 品红十字准星：换算后的触点坐标，应当正好在手指下面。
 */
internal class DemoScene {

    fun draw(frame: YuvFrame, width: Int, height: Int, index: Long, touches: List<HiSightInput.Point>) {
        frame.fill(BACKGROUND)
        for (x in 0 until width step GRID_STEP) frame.fillRect(x, 0, 1, height, GRID)
        for (y in 0 until height step GRID_STEP) frame.fillRect(0, y, width, 1, GRID)

        frame.fillRect(0, 0, width, BORDER, YELLOW)
        frame.fillRect(0, height - BORDER, width, BORDER, YELLOW)
        frame.fillRect(0, 0, BORDER, height, YELLOW)
        frame.fillRect(width - BORDER, 0, BORDER, height, YELLOW)

        frame.fillRect(CORNER_INSET, CORNER_INSET, CORNER, CORNER, RED)
        frame.fillRect(width - CORNER_INSET - CORNER, CORNER_INSET, CORNER, CORNER, GREEN)
        frame.fillRect(CORNER_INSET, height - CORNER_INSET - CORNER, CORNER, CORNER, BLUE)
        frame.fillRect(width - CORNER_INSET - CORNER, height - CORNER_INSET - CORNER, CORNER, CORNER, WHITE)

        frame.fillRect(bounce(index * 3, width - BLOCK), bounce(index * 2, height - BLOCK), BLOCK, BLOCK, CYAN)

        for (bit in 0 until COUNTER_BITS) {
            val on = (index ushr bit) and 1L == 1L
            frame.fillRect(COUNTER_X + bit * 8, COUNTER_Y, 6, 6, if (on) WHITE else DIM)
        }

        for (p in touches) {
            frame.fillRect(p.x - CROSS, p.y, CROSS * 2 + 1, 1, MAGENTA)
            frame.fillRect(p.x, p.y - CROSS, 1, CROSS * 2 + 1, MAGENTA)
            frame.fillRect(p.x - 2, p.y - 2, 5, 5, MAGENTA)
        }
    }

    /** 三角波：在 0..span 之间来回。 */
    private fun bounce(step: Long, span: Int): Int {
        if (span <= 0) return 0
        val t = (step % (2L * span)).toInt()
        return if (t <= span) t else 2 * span - t
    }

    private companion object {
        const val GRID_STEP = 20
        const val BORDER = 2
        const val CORNER = 16
        const val CORNER_INSET = 6
        const val BLOCK = 24
        const val COUNTER_BITS = 12
        const val COUNTER_X = 30
        const val COUNTER_Y = 8
        const val CROSS = 12

        val BACKGROUND = Yuv.of(36, 38, 46)
        val GRID = Yuv.of(70, 74, 88)
        val YELLOW = Yuv.of(255, 214, 0)
        val RED = Yuv.of(230, 40, 40)
        val GREEN = Yuv.of(40, 200, 60)
        val BLUE = Yuv.of(40, 80, 240)
        val WHITE = Yuv.of(245, 245, 245)
        val DIM = Yuv.of(70, 70, 70)
        val CYAN = Yuv.of(0, 200, 230)
        val MAGENTA = Yuv.of(240, 0, 200)
    }
}
