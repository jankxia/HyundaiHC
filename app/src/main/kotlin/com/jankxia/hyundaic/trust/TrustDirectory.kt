package com.jankxia.hyundaic.trust

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 应用私有目录下「不参与备份」的那一层，里面再建 [name] 子目录。
 *
 * 安卓 5.0 起有 `getNoBackupFilesDir()`；4.4 没有这个接口，退到 `filesDir/no_backup`
 * （本应用的清单里关掉了备份，两者实际效果一样）。
 */
fun noBackupDirectory(context: Context, name: String): File {
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        context.noBackupFilesDir
    } else {
        File(context.filesDir, "no_backup")
    }
    return File(base, name)
}
