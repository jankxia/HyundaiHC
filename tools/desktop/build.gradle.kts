// 桌面调试程序：在电脑的 JVM 上直接连真手机跑一次完整的有线会话，视频写成 H.264 文件。
// 只用于开发调试，不打进安装包。用法见 README.md。
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.jankxia.hyundaic.tools.desktop.MainKt")
    applicationName = "hyundaic-desktop"
}

dependencies {
    implementation(project(":core:session"))
}

// `./gradlew :tools:desktop:run --args=…` 时，相对路径按仓库根目录解析。
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// 这两个测试校验的是 POSIX 权限语义（目录 700、文件 600），靠 `posix:permissions` 建文件属性。
// Windows 的 NTFS 不支持这个属性视图，`Files.createTempFile(…, PosixFilePermissions.asFileAttribute(…))`
// 直接抛 UnsupportedOperationException，测试本身在 Windows 上没有意义；CI 与开发机都是 Linux/macOS 时照常跑。
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        exclude("**/FileTrustStoreTest.class", "**/VideoFileSinkTest.class")
    }
}
