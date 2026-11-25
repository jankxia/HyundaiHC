import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// 发布签名：密钥不进仓库。环境变量 HYUNDAIC_SIGNING 或 local.properties 里的 hyundaic.signing 指向一份配置文件
// （storeFile、storePassword、keyAlias、keyPassword）；都没有时正式包不签名，调试包照常用调试密钥。
val signingProperties: Properties? = (
    providers.environmentVariable("HYUNDAIC_SIGNING").orNull
        ?: localProperties.getProperty("hyundaic.signing")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> Properties().apply { file(path).inputStream().use { load(it) } } }

// 连接参数（hyundaic-identity.json，格式见 docs/identity.example.json）：不进仓库，构建时从本机目录打进 assets。
// 环境变量 HYUNDAIC_IDENTITY_DIR 优先，其次 local.properties 的 hyundaic.identity.dir；都没有就不打包，装上后首页提示缺少。
val identityFileName = "hyundaic-identity.json"
val identityDir: File? = (
    providers.environmentVariable("HYUNDAIC_IDENTITY_DIR").orNull
        ?: localProperties.getProperty("hyundaic.identity.dir")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.also { dir ->
        val names = dir.listFiles()?.map { it.name }?.sorted()
        require(names == listOf(identityFileName)) {
            "连接参数目录 $dir 里必须只有 $identityFileName（实际：$names），防止把别的私有文件打进安装包"
        }
    }

android {
    namespace = "com.jankxia.hyundaic"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jankxia.hyundaic"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode = 主版本 × 10000 + 次版本 × 100 + 补丁号。
        versionCode = 209
        versionName = "0.2.9"
        // minSdk 19 不支持原生多 dex：方法数超 64K 时需要 multidex 支持库。
        multiDexEnabled = true
    }

    sourceSets {
        getByName("main") {
            identityDir?.let { assets.srcDir(it) }
        }
    }

    signingConfigs {
        signingProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 代码压缩等协议层稳定后再开；开之前要先补齐保留规则。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle（core:auth 用）和 jspecify 都带了这份 OSGi 清单，打包时重名；安卓用不到 OSGi 清单。
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    // 会话编排：把四个协议层串成一次有线连接，执行器用 java.net，安卓上直接用。
    implementation(project(":core:session"))
    implementation(project(":platform"))
    // 连接状态用 StateFlow 交给界面。
    implementation(libs.kotlinx.coroutines.core)
    // 无线连接页显示配对二维码（Apache-2.0）。
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    // minSdk 19 多 dex 加载支持库。
    implementation(libs.androidx.multidex)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
