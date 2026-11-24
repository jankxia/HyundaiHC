# HyundaiHC 移植到安卓 4.4（API 19）适配方案

> 目标设备
> - 车机：现代帕里斯帝（Hyundai Palisade）后装/车机系统，安卓 4.4（API 19）
> - 手机：华为 Mate60 Pro，HarmonyOS 7.0.0.111SP10（纯血鸿蒙）
> - 上游：fork 自 GGBondLab/OpenHC，原项目只在比亚迪安卓 10 + 纯血鸿蒙上验证过。

本文只评估**源码侧**的适配差距与改造路线；车机实机表现、手机侧协议参数需在阶段 0 核实后再定。

---

## 1. 现状基线（fork 改名后）

| 项 | 当前值 | 出处 |
| --- | --- | --- |
| 根工程名 | HyundaiHC | `settings.gradle.kts` |
| applicationId / namespace | `com.jankxia.hyundaic`（platform 为 `.platform`） | `app/build.gradle.kts`、`platform/build.gradle.kts` |
| minSdk | **23**（安卓 6.0） | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 36 / 36 | 同上 |
| AGP | 8.11.1 | 同上 |
| Kotlin | 2.1.20 | 同上 |
| JVM 字节码目标 | **Java 11**（core 与 android 模块均 `JVM_11`） | 根 `build.gradle.kts` |
| UI 框架 | **Jetpack Compose**（material3 + activity-compose） | `app/build.gradle.kts`、`app/src/main/.../ui` |
| 核心协议层 | `core/*` 纯 Kotlin/JVM， animalsniffer 用 API 23 签名检查 | `core/*/build.gradle.kts` |
| 协议身份参数 | `hyundaic-identity.json`（不进仓库，占位值） | `docs/identity.example.json` |

源码规模（`src/main` 下 Kotlin 文件数）：app 78、platform 81、core 共 92。
其中 app 的 24 个 UI 文件为 Compose；投屏画面本身用传统 `SurfaceView`（`projection/ProjectionView.kt`），不依赖 Compose。

---

## 2. 阶段 0：先核实，避免白做（动手改代码前必须完成）

这些信息源码里查不到，需要在车机上确认：

1. **真实系统版本**：设置 → 关于本机 / 系统信息里的 Android 版本号与内部版本号。
   - 若是 4.4.x = API 19，按本文方案；若实际是 4.0/4.1/4.2/4.3（API 14–18），差距更大，需另行评估。
2. **CPU 架构 ABI**：`armeabi` / `armeabi-v7a` / `x86`。4.4 老车机很多是 32 位 armeabi-v7a，决定 SO/解码兼容与打包 ABI 过滤。
3. **是否支持 USB Host（OTG）**：有线链路的前提。插 U 盘能否识别；USB 口能否挂主机模式。
4. **能否侧装 APK**：设置里是否允许「未知来源」；是否有系统应用管家/权限管家拦截。
5. **剩余存储 / 内存（RAM）**：4.4 车机 RAM 常为 1G 或更低，BC + zxing + coroutines 体积与方法数要压。
6. **车机是否原生安卓**：帕里斯帝原厂车机有非安卓（Linux/QNX）机型；若不是安卓，本方案不适用，需另走车机互联盒/外挂方案。
7. **手机侧**：Mate60 Pro 先在系统「关于手机」确认 HarmonyOS 版本号；手机上删除旧的其他 HiCar 车机配对记录（README 已知限制）。

> 用 ADB 接上后 `adb shell getprop ro.build.version.release`、`adb shell getprop ro.product.cpu.abi`、`adb shell cat /proc/version` 可一次性拿到上述信息。

---

## 3. 差距清单（按层分级）

### 3.1 工具链 / 字节码层

| # | 现状 | 4.4(API19) 要求 | 处置 | 难度 |
| --- | --- | --- | --- | --- |
| T1 | JVM target = Java 11 | 4.4 的 Dalvik/ART 不认 Java 11 字节码 | android/core 模块 `jvmTarget` 降到 **1.8**；开脱糖（desugar），并验证老运行时可加载 | 中 |
| T2 | Kotlin 2.1.20 标准库 | 老平台可跑，但需确认 stdlib 字节码级别与元数据 | 保留 Kotlin 2.x 先试；若 stdlib 有 API19 缺失调用，降到 1.9/1.8 | 中 |
| T3 | AGP 8.11.1 + build-tools 36 | AGP 构建本身不强制 minSdk≥21，可产出低 minSdk 包 | 工具链可保留；需安装对应 build-tools；D8 输出 API19 验证 | 低 |
| T4 | compileSdk 36 | 编译可对新高版本 SDK，运行时按 SDK_INT 分支 | 保留 compileSdk；lint 关掉高版本检查阻塞 | 低 |
| T5 | animalsniffer 签名 = API 23 | core 要跑到 API19 | 签名换成 android-api-level-19；逐文件清掉 API20–23 误用 | 中 |

### 3.2 依赖层（硬约束，多数要替换/移除）

| # | 依赖 | 现状 minSdk / 问题 | 4.4 处置 | 难度 |
| --- | --- | --- | --- | --- |
| D1 | **Jetpack Compose**（ui/material3/activity-compose，24 个 UI 文件） | 官方硬下限 **API 21**，API19 无法加载运行时 | **整包移除 Compose**；外壳 UI（首页/设置/无线页/投屏容器）用传统 View 重写。投屏 `SurfaceView` 保留 | 高 |
| D2 | `androidx.core-ktx:1.16.0` | 现版本 minSdk 21 | 降到仍支持 19 的老版本（core 1.9.x 及以下），或去掉 core-ktx、改用 framework API 自写薄封装 | 中 |
| D3 | `kotlinx-coroutines:1.10.2` | 库本身宽松，但新版可能带 Java8 默认方法/高版本调用 | 先试现行版本；出兼容问题降到 1.6.x 稳定线；StateFlow 等在老 ART 上实测 | 中 |
| D4 | `kotlinx-serialization-json:1.8.1` | 纯 Kotlin，字节码需 Java8 兼容 | 一般可保留；必要时降 1.5.x | 低 |
| D5 | `bcprov-jdk18on:1.79`（X25519/Ed25519 认证） | jdk18on 变体面向 Java8+，体积/方法数大，4.4 Dalvik 上需实测 | 评估换 `bcprov-jdk15on` 老变体；裁剪只留需要的算法；盯方法数与 dex 上限 | 中高 |
| D6 | `zxing-core:3.5.3`（二维码编码） | 纯 Java，兼容老平台 | 保留 | 低 |
| D7 | 64K 方法数 / multidex | 4.4 Dalvik 单 dex 64K；依赖多极易超限 | 配 androidx multidex 支持库（minSdk 14），并在真机验证二次 dex 加载与启动性能 | 中 |

### 3.3 平台 API 层（4.4 缺失或行为不同）

| # | 模块 / 文件 | 现状 API | 4.4 处置 | 可行性 |
| --- | --- | --- | --- | --- |
| P1 | `platform/media/VideoDecoder.kt` `setCallback` | 异步 `MediaCodec.Callback`，**API 21** | 改同步模型：工作线程轮询 `dequeueInputBuffer` / `dequeueOutputBuffer`，去掉回调注册 | 可行，改造量中 |
| P2 | `platform/media/PcmTrack.kt` `AudioTrack.Builder` / `AudioFormat.Builder` | **API 21** | 改传统构造函数 `AudioTrack(...)`、`AudioFormat(...)` | 可行，低 |
| P3 | 通知渠道 `NotificationChannel` | **API 26** | 加 `SDK_INT >= O` 分支，4.4 不建渠道、直接发通知 | 可行，低 |
| P4 | 运行时权限 `requestPermissions` / `checkSelfPermission` | **API 23** | 4.4 安装即授予；加版本分支，老系统跳过申请直接走流程 | 可行，低 |
| P5 | 前台服务类型 `foregroundServiceType`、`FOREGROUND_SERVICE_*` 权限 | API 29/34 | 4.4 清单字段被忽略；服务按普通前台服务起，加版本判断 | 可行，低 |
| P6 | **BLE 外设/广播/GATT 服务端**：`BleBeacon.kt`、`BeaconGattServer.kt`、`LePairingPeripheral.kt` | **API 21**（外设角色/广播） | 4.4 只支持 BLE 中心角色，**外设广播与 GATT 服务端不可用**；裁剪自动回连的 BLE 部分 | 不可行（4.4 无替代公开接口） |
| P7 | **本地热点** `LocalHotspot.kt` `startLocalOnlyHotspot` | **API 26** | 4.4 无公开本地热点接口；反射 `setWifiApEnabled` 属系统隐藏 API，后装车机多半无权限/不支持 | 基本不可行 |
| P8 | WiFi Direct（P2P）：`WifiDirectGroup.kt`、`DirectGroupRules.kt` | API 14+ | 4.4 理论支持；但当前无线方案建立在「车机开热点」上，P2P 直连需另做适配验证 | 待验证 |
| P9 | 经典蓝牙 RFCOMM：`Rfcomm*Channel/Endpoint/Client/Server.kt` | API 5+ | 4.4 可用；旧蓝牙权限（`BLUETOOTH`/`BLUETOOTH_ADMIN`）已有 maxSdk 分支 | 可行 |
| P10 | `platform/net` VpnService / TUN 网卡 | API 14+ | 4.4 可用；需在真机验证 VPN 授权与路由 | 可行，待实测 |
| P11 | `platform/usb` USB Host / CDC-NCM | API 12+ | 取决于车机 USB 硬件与内核驱动；协议帧处理与系统版本无关 | 待实测 |
| P12 | 媒体键会话 `MediaKeySession.setCallback` | 需核对具体 androidx/media 版本下限 | 老系统可能要换成 `MediaSession` 框架 API 或广播按键 | 中 |

### 3.4 协议 / 身份层（与安卓版本无关，但连手机的前提）

| # | 项 | 现状 | 说明 |
| --- | --- | --- | --- |
| S1 | `hyundaic-identity.json` | 示例全是全零/占位：`modelId`、`subModelId`、`brand`、`deviceName`、`nearbyKeys(rootKey/constA/constB)`、`authService.packageName/serviceType/envelopeVersion` | 这份参数要和 Mate60 Pro 鸿蒙 7 手机侧 HiCar 的校验一致，**占位值根本连不上手机**。原项目不发布真实参数，需通过配对抓包/逆向提取，是独立于安卓版本的另一大难点。 |
| S2 | 手机兼容性 | 上游仅验证纯血鸿蒙；Mate60 Pro 鸿蒙 7 属纯血鸿蒙，协议大概率兼容 | 需真机实测握手、认证、出图、声音、回控。 |

---

## 4. 可行性结论

**有线链路（USB 数据线）在安卓 4.4 上理论可做**，链条为：USB Host 识别 → VPN 虚拟网卡 → TCP/RFCOMM 控制通道 → 认证 → 设备会话 → H.264 解码上屏（`SurfaceView` 保留）。需要付出：移除并重写 Compose 外壳、降级/替换 AndroidX 与 BC/coroutines、MediaCodec 与 AudioTrack 改老接口、权限/通知/服务加版本分支、解决 multidex 与方法数。

**无线链路在安卓 4.4 上基本不可行**：BLE 外设广播/GATT（API 21）与本地热点（API 26）都是老系统没有的公开能力，隐藏接口在后装车机上通常无权限。建议 4.4 版本**只保留有线**，无线相关模块（`app/wireless`、`platform/bluetooth` 中 BLE 三件、`platform/wifi` 热点部分）在 4.4 构建中裁剪掉或做空实现。

**这不是改一行 minSdk 能完成的降级移植**：核心工作量在 UI 重写、依赖降版和平台接口替换；协议层 `core/*` 最干净，基本可原样保留（只换签名检查级别）。

---

## 5. 分步改造路线

### 阶段 0：核实设备（见第 2 节）
产出：车机 Android 版本、ABI、USB Host、RAM、能否侧装；手机系统版本。任一项不满足（非安卓 / 无 USB Host）即停止后续移植。

### 阶段 1：工具链降版，先出一个能装的壳
- minSdk 暂设 19；`jvmTarget` 1.8；装 API 19 platform 与对应 build-tools。
- animalsniffer 签名换 API 19；core 模块跑通单元测试。
- 目标：core + session 在 JVM 测试环境编译通过。

### 阶段 2：依赖裁剪与降版
- 移除 Compose 全套依赖；core-ktx 降到支持 19 的版本或移除；coroutines/serialization/BC 按需降版；开启 multidex。
- 目标：app 模块依赖清单不再含有 minSdk>19 的库。

### 阶段 3：外壳 UI 用传统 View 重写
- 对照现有 24 个 Compose 文件的功能，用 `LinearLayout`/`ScrollView`/自定义 `View` 实现：首页（开始/状态/二维码入口）、设置页（分组开关/选项）、投屏容器（复用 `ProjectionView` 的 SurfaceView）、诊断导出入口。
- 状态管理直接复用 `ConnectionController` / `WirelessController` 的 StateFlow 或改回调，不引入新 UI 框架。

### 阶段 4：平台 API 降级
- 按 3.3 表逐项改：MediaCodec 同步化、AudioTrack/AudioFormat 老构造、通知/权限/服务加 SDK_INT 分支；裁剪 BLE 外设与本地热点，4.4 构建只编有线路径。

### 阶段 5：有线真机联调
- 阶段 0 的车机 + Mate60 Pro；按 README 流程插线、授权、输连接码；先出画面，再调声音、触摸、方向盘按键；用诊断日志定位协议/兼容问题。

### 阶段 6：身份参数接入
- 提取/填入真实 `hyundaic-identity.json`（S1）；未解决前只能进首页提示缺参数，无法连手机。

---

## 6. 主要风险与不确定项

1. **车机系统真实性**：若非安卓，全部移植工作不成立（阶段 0 拦截）。
2. **解码兼容**：4.4 硬解 H.264 的 MediaCodec 能力、分辨率/帧率档位需真机验证；不支持时只能软解（性能/体积代价大）。
3. **方法数与内存**：BC 加密库 + zxing + coroutines 在 32 位 4.4 车机上的 dex 方法数、堆内存压力需实测。
4. **协议参数缺失**：没有真实 identity 参数，即使本地编译出 APK 也连不上 Mate60 Pro。
5. **鸿蒙 7 兼容性**：纯血鸿蒙大版本升级可能调整 HiCar 握手/能力通告，需真机抓日志比对。
6. **后装车机定制**：权限管家、USB 驱动、VPN 限制各家不同，README 所述权限问题在 4.4 上可能更严重。

---

## 7. 本次已完成的品牌改名记录

- 工程名 `rootProject.name`：OpenHC → **HyundaiHC**
- applicationId / namespace：`io.github.gcbond.openhc` → **`com.jankxia.hyundaic`**（platform `.platform`，btprobe `.btprobe`）
- 源码包目录：`io/github/gcbond/openhc` → **`com/jankxia/hyundaic`**（含 app/platform/core/tools 全部 main/test/debug/release 源集）
- Application 类：`OpenHcApp` → `HyundaiHcApp`；主题类：`OpenHcTheme` → `HyundaiHcTheme`
- 构建配置：`OPENHC_SIGNING`/`OPENHC_IDENTITY_DIR` 环境变量、`openhc.signing`/`openhc.identity.dir` 属性、`openhc-identity.json` 文件名 → 对应 `hyundaic.*` / `HYUNDAIC_*`
- 界面与通知文案、诊断目录名、logcat TAG 前缀：OpenHC → HyundaiHC
- 上游 issue/releases 链接保留指回原仓库 `GGBondLab/OpenHC`，未跟随改名

---

## 8. 模拟器基线验证进展（2026-11-24）

> 说明：雷电模拟器为 **安卓 9（SDK 28）x86_64**，只能验证改名后工程能否编译、安装、启动；**不能**作为安卓 4.4 兼容性依据，4.4 结论仍以阶段 0 真机核实为准。

- 测试设备：雷电 LDPlayer9，`emulator-5554`，Android 9 / API 28，ABI x86_64（含 arm 翻译），1280×720 @240dpi。
- 构建：`:app:assembleDebug` **BUILD SUCCESSFUL**，产出 `app/build/outputs/apk/debug/app-debug.apk`（约 15.4 MB）。
- 安装启动：`pm list packages` 确认包名 `com.jankxia.hyundaic`；`MainActivity` 正常 resumed，无 FATAL / ANR；首页标题 **HyundaiHC**、版本 0.2.6 正常渲染。
- 日志：logcat TAG 前缀 `HyundaiHC-Wireless` 生效；启动即报 `IDENTITY_MISSING`（缺真实 identity 参数，符合预期，未接手机）。
- 结论：改名（工程名 / 包名 / 源码目录 / 类名 / 文案）在真实构建 + 启动层面未破坏工程；基线在安卓 9 上可跑。

### 本地构建环境（Windows）
- JDK：Temurin 17.0.21；SDK 根 `C:\Users\jankx\AppData\Local\Android\Sdk`（已装 platform-34/36/37、build-tools 34/35/36）。
- Gradle：wrapper 指定 8.13 分发包经 services.gradle.org 下载卡死（0 字节）；改用本地缓存的 Gradle 8.14.6 直接 `gradle.bat` 构建（AGP 8.11.1 要求 8.13+，满足）。wrapper 版本未改。
- `local.properties` 已写 `sdk.dir`（被 .gitignore 忽略，不进仓库）。
- 已验证不可行：compileSdk 降到 34 会被 17 条 AndroidX/Compose AAR 元数据拒绝（要求 ≥35）；当前保持 compileSdk/targetSdk 36。

### 车机 WiFi 事实（用户补充）
- 车机具备 WiFi，**可作为 station 连接外部热点**（手机开热点 / 路由器，车机加入）；这与上游无线方案"车机开本地热点（`startLocalOnlyHotspot`，API 26）"是不同拓扑。
- 待核实：HiCar 无线握手是否允许车机走 station 模式（手机热点），还是仍要求车机侧做 BLE 外设广播 + 自建热点；4.4 上 BLE 外设与本地热点均不可用（见 3.3），无线链路整体仍按"4.4 裁剪"处理，station 模式仅作为后续真机验证的备选方向。

### 安卓 4.0 / 4.4 模拟器测试：当前受阻（2026-11-24 核实）

> 用户希望先用模拟器模拟安卓 4.0 测试；车机目标实为安卓 4.4（API 19）。安卓 4.0 = API 14/15，二者当前都被同一道门槛挡住。

- **APK 门槛**：`aapt2 dump badging` 实测当前 debug 包 `minSdkVersion:'23'`（安卓 6.0）。装到 API 19/14 设备会被 PackageManager 直接拒绝（`INSTALL_FAILED_OLDER_SDK`），在 4.x 系统上根本无法安装，与镜像无关。
- **本机 SDK**：`system-images/` 目录为空、无任何 AVD；`platforms/` 仅 android-34/36/37，没有 android-19。
- **雷电 LDPlayer 9.5.42**：唯一实例 `emulator-5554` 为安卓 9 / API 28；`ldconsole add` 只有 `--name`，`modify` 只能改分辨率/CPU/内存/机型/IMEI 等，**没有安卓版本参数**，新建实例同样内置安卓 9 镜像；安装目录内无 4.x 系统镜像。
- 现代 Android Emulator 官方也不提供、不启动 API 14/19 镜像；4.x 镜像只存在于很老的模拟器版本。
- **结论**：在完成 4.4 降级改造（minSdk 降到 19、移除 Compose、依赖降版等，见阶段 1–4）之前，无法在任何 4.x 模拟器上安装测试；现阶段只能在安卓 9 基线做编译/启动回归。降级改造完成后，再评估用老版本模拟器或真机验证 API 19 兼容性。

---

## 9. 阶段执行记录（2026-11-24）

### 阶段 1：工具链降版 —— 已完成（commit `917a97b`，已推送）

- `minSdk` 23 → **19**；`jvmTarget` / `sourceCompatibility` / `targetCompatibility` Java 11 → **1.8**（core JVM、android 模块、btprobe 同步）。
- animalsniffer 签名 `android-api-level-23:6.0_r3` → **`android-api-level-19:4.4.2_r4`**。
- 验证结果：`:core:session:build`（含全部 core 依赖、API19 签名检查、JUnit 单测）**BUILD SUCCESSFUL**；`:platform:assembleDebug` **BUILD SUCCESSFUL**（仅 LocalHotspot 废弃字段 warning，无线裁剪留阶段 4）。
- **当前中间态**：`app` 模块在 minSdk 19 下 manifest 合并被 `material3-android:1.3.2`（声明 minSdk 21）拒绝，暂不可编译；core / platform 可编。这是阶段 2/3 打通前的预期中间态。

### 阶段 2：依赖裁剪与降版 —— 待办（已核查 import）

app 非 UI 业务代码实际用到的 AndroidX（已逐个核对 import）：

| 依赖 | 现状 | 处置 |
| --- | --- | --- |
| Compose BOM / activity-compose / ui / material3 / ui-tooling(-preview) | minSdk 21，硬下限 | **整包移除**（app 与 btprobe）；外壳 UI 阶段 3 用传统 View 重写 |
| `androidx.core:core-ktx:1.16.0` | 现代 AndroidX 默认 minSdk 21 | **降为 1.12.0**（已实测：core 1.13.0 才把 minSdk 提到 19，1.12.0 及更低兼容 API 19；classes.jar 已核验含 `ContextCompat`、`IntentCompat`、`NotificationChannelCompat/ManagerCompat`、`ServiceCompat`、`WindowCompat/WindowInsetsCompat/WindowInsetsControllerCompat` 全部所用类）；`core.content.edit`、`core.net.toUri` 等 ktx 扩展在 1.12.0 同版本随附，缺哪个再改 framework 自写薄封装 |
| `androidx.activity:activity-compose:1.10.1`（ComponentActivity、ActivityResultContracts） | minSdk 21 | **移除**；`MainActivity` 改继承 `android.app.Activity`；运行时权限改 `onRequestPermissionsResult`；VPN 授权改 `startActivityForResult` |
| `kotlinx-coroutines-core:1.10.2` | core JVM 编译已过 | 暂留；老 ART 上 StateFlow/调度实测，出问题再降 1.6.x |
| `kotlinx-serialization-json:1.8.1` | 纯 Kotlin | 暂留；必要时降 1.5.x |
| `bcprov-jdk18on:1.79` | Java8+ 变体 | 评估换 `bcprov-jdk15on`；裁剪仅需算法；盯 dex 方法数 |
| `zxing-core:3.5.3` | 纯 Java | 保留 |
| multidex | 4.4 Dalvik 单 dex 64K | 开 multidex（minSdk<21 需 androidx multidex 支持库），真机验证二次 dex 加载与启动性能 |

### 阶段 3：外壳 UI 传统 View 重写 —— 待办（规模已盘点）

- 待重写：`app/.../ui/` 下 **22 个 Compose 文件、3193 行**（Widgets 502、HomeScreen 297、WirelessScreen 278、AudioSettings 243、BootStart 226 等）。
- 可原样复用：`app/` 下 **56 个非 UI 业务文件**——`connection/`、`wireless/`、`settings/`、`audio/`、`diagnostics/`、`startup/`、`projection/ProjectionView/Surface`、`trust/` 等；状态层 `ConnectionUiState` / `WirelessUiState` 是纯 Kotlin data/sealed，不依赖 Compose。
- 三屏对接：首页（有线/无线两栏 + 开始/停止/重试/重置信任 + 二维码入口）、设置页（各分组开关/选项）、全屏投屏页（复用 `ProjectionView` 的 SurfaceView）。View 侧订阅 `controller.state` / `wireless.state` StateFlow，在主线程刷新文本、按钮与可见性；阶段 4 再处理平台 API。
- 投屏容器 `ProjectionSurface.kt` 当前用 Compose `AndroidView` 包 SurfaceView，重写时直接用传统 `SurfaceView` 加入布局。

### 阶段 3 接线调研结论（2026-11-24，已通读源码）

- **非 UI 业务代码仅 3 个文件引用 Compose / androidx.activity**，其余 53 个业务文件干净，可原样保留：
  - `MainActivity.kt`：`ComponentActivity` + `setContent`；业务调度（权限申请、VPN 授权、USB/开机拉起、start/stop）与 UI 解耦，保留；UI 部分整体替换。
  - `wireless/WirelessStartFlow.kt`：入参 `ComponentActivity`，用 `registerForActivityResult(RequestMultiplePermissions)`。改回框架 `android.app.Activity`，权限走 `onRequestPermissionsResult`；4.4 版无线裁剪后此类先占位/停用。
  - `projection/ProjectionSurface.kt`：纯 Compose `AndroidView` 包装，归档删除即可；底层 `projection/ProjectionView.kt` 是纯传统 `SurfaceView/FrameLayout`，直接复用。
- **控制器公开接口（View 侧对接用）**：
  - `ConnectionController.state: StateFlow<ConnectionUiState>`、`decoder`、`touchResets`；操作 `start()` / `stop()` / `retryNow()` / `resetTrustStoreAndStart()` / `reportBlocked(Notice)` / `sendInput(HiSightInput)`。
  - `ConnectionUiState` 字段：`phase`、`step`、`pinCode`、`phoneConnected`、`video`、`firstFrame`、`inputReady`、`videoWidth/Height`、`decoderProblem`、`notice`、`attempt`、`active`。
- **归档策略**：`app/.../ui/` 22 个 Compose 文件先整体移出 `main/kotlin` 编译集（仓库内 `legacy/` 留存参考，不删除），避免残留 Compose 引用报错；文案映射（`StatusTexts.kt`/`WirelessTexts.kt` 里的 phase/notice 文案）后续抽成纯 Kotlin 供 View 复用。
- **建议执行顺序**：① 版本目录/构建脚本去 Compose 插件与依赖、去 `activity-compose`；② 归档 `ui/` 与 `ProjectionSurface.kt`；③ 新写框架 `MainActivity`（代码构建 `LinearLayout`/`ScrollView`，订阅 `connection.state` 刷新有线状态文本与开始/停止/重试按钮，设置/投屏先占位）；④ 改 `WirelessStartFlow` 入参类型；⑤ 编译出 debug 包，在安卓 9 模拟器回归骨架不崩；⑥ 再逐屏补设置页分组、投屏页 SurfaceView 接线、无线（4.4 裁剪）。
- **布局方式**：车机低配 4.4，采用传统 XML/代码构建 `LinearLayout`/`ScrollView`/`TextView`/`Button`/`SurfaceView`，不引入任何 Compose 或现代 View 库。

### 阶段 2/3 落地记录（2026-11-24 第二轮，debug 出包）

> 目标检查点：在 minSdk 19 下把 app 编译出 debug 包，并在安卓 9 模拟器确认传统 View 骨架不崩。本轮达成。

**依赖裁剪（阶段 2 收尾）**
- 版本目录移除：`activity-compose`、Compose BOM/ui/ui-tooling/ui-tooling-preview/material3、`kotlin-compose` 插件；根 `build.gradle.kts` 去掉 `kotlin.compose` 插件别名；`settings.gradle.kts` 注释掉 `:tools:btprobe`（Compose 实现，源码留存 `tools/btprobe`，不参与构建）。
- `core-ktx` 定版 **1.12.0**；新增 `androidx.multidex:multidex:2.0.1`（minSdk 14，兼容 19）。
- 实测 dex 方法数 **71660 > 65536**，单 dex 装不下；`defaultConfig.multiDexEnabled = true`，`HyundaiHcApp` 改继承 `MultiDexApplication`。出包含 `classes.dex` + `classes2.dex`。
- 调试钩子归并：原 `debug` 源集的 `AudioCaptureFactory`/`DebugOverrides`（真抓包/读系统属性）随 debug 归档；`release` 源集里的同名空实现（`AudioCapture.None`、`forceRfcommSdp=false`）移到 `main`，debug/release 统一走空实现。

**源码归档（阶段 3）**
- 归档到仓库根 `legacy/`（移出 sourceSet，保留参考历史，不删除）：
  - `legacy/app-main-ui/`：22 个 Compose UI 文件；`legacy/app-main-projection/`：`ProjectionSurface.kt`、`WirelessStartFlow.kt`；`legacy/app-test-ui/`：3 个 Compose 相关单测；`legacy/app-debug/`：整个 debug 源集（两个 demo Activity、合成码流喂数、调试覆盖开关、demo 资源与 manifest）。
- `MainActivity` 重写为框架 `android.app.Activity`：`registerForActivityResult` 权限/VPN 合约改为 `requestPermissions` + `onRequestPermissionsResult`、`startActivityForResult` + `onActivityResult`；业务调度（`handleLaunch`/`handleUsbAttach`/`requestStart`/`askNextPermission`/`continueStart`/`dispatch`、CarHome 前后台、MediaKeys 键转发、无线让出）保留；界面为代码构建的 `LinearLayout` 骨架（标题/状态/提示/连接码文本 + 开始/停止/重试/授权 VPN/清除信任按钮），`controller.addHostListener` + `mainHandler` 500ms 轮询刷新状态。

**构建与回归结果**
- `:app:assembleDebug` **BUILD SUCCESSFUL**；APK `app/build/outputs/apk/debug/app-debug.apk`（约 7.09 MB，2 个 dex）。
- `aapt dump badging`：package `com.jankxia.hyundaic`，versionName 0.2.6，`sdkVersion:'19'`，`targetSdkVersion:'36'`，launchable `.MainActivity`，label **HyundaiHC**。
- 雷电模拟器（安卓 9/API 28）`install -r` 成功；`MainActivity` Displayed +1.65s，无 FATAL/ClassNotFound/NoSuchMethod；骨架渲染标题、阶段 IDLE、开始（缺 identity 置灰）/停止按钮，重试/VPN/重置信任按钮按 notice 条件隐藏。API 28 走原生 multidex（日志 `VM has multidex support, MultiDex support library is disabled`）；真机 API 19 才由支持库解压 second dex，待阶段 5 真机验证加载与启动性能。
- `:tools:desktop:compileKotlin` **BUILD SUCCESSFUL**；core 各模块 JVM 单测通过。
- **已知本地单测环境失败（非本轮引入）**：`FileTrustStoreTest.laterPairingsReplaceTheWholeFile` 在 Windows 抛 `IOException: 把临时文件改名为 trust-store.bin 失败`。原因是 Windows `File.renameTo` 不能覆盖已存在目标（Android/Linux 可覆盖）；`FileTrustStore.writeAtomically` 用 `java.io.File.renameTo`，API 19 无 `java.nio.file.Files.move`，业务代码不改。该用例在 Linux/Android 上通过，仅 Windows 本地 JVM 单测复现。

**阶段 3 遗留（后续补回）**
- 设置页 11 个分组开关/选项；全屏投屏页 SurfaceView 接线（复用底层 `projection/ProjectionView.kt`）；无线界面（4.4 裁剪，先占位）；首页 phase/notice/step 文案映射——原 `StatusTexts.kt`/`WirelessTexts.kt` 在已归档的 Compose 文件里，需抽成纯 Kotlin 供 View 复用；当前骨架仅显示枚举 `toString` 与原始 notice。
- `:app:assembleRelease` **BUILD SUCCESSFUL**（未开 minify，未配签名时出未签名包）；release 源集已清空，空实现钩子在 main，release 编译通过。

### 阶段 4：平台 API 降级 —— 本轮进展（2026-11-24）

> 说明：本机无 API19 真机/镜像，本轮改动只做编译期 + 安卓 9 模拟器启动回归；4.4 运行时行为仍待阶段 5 真机核实。

**本轮已改**
- **P2 音频轨老构造**（`platform/media/PcmTrack.kt`）：按用途建轨的 `AudioTrack.Builder`/`AudioFormat.Builder`（API21）隔离到 `buildWithAttributes()`；SDK <21 走老构造 `AudioTrack(streamType, sampleRate, channelMask, ENCODING_PCM_16BIT, bufferBytes, MODE_STREAM)`。`AudioUsage` 新增纯 Kotlin `streamType(): Int`（CALL→VOICE_CALL、RINGTONE→RING、其余→MUSIC），`AudioFocusController` 焦点老接口复用同一映射。
- **P4 运行时权限直通**（`app/MainActivity.kt`）：框架 `Activity.requestPermissions` 是 API23；`askNextPermission()` 开头判断 `SDK_INT < M` 直接 `continueStart()`，4.4 安装即授予、不调该方法。其余 `checkSelfPermission` 均走 `ContextCompat`（<23 返回 GRANTED，安全）。
- **P10 VPN Builder 版本分支**（`platform/net/WiredVpnService.kt`）：`addAllowedApplication`/`allowFamily(AF_INET6)`/`setBlocking` 均 API21，包进 `SDK_INT >= LOLLIPOP` 分支；4.4 跳过——TUN 对本应用生效这点在车机单应用场景放宽，IPv6 不主动放行（本就只配 IPv4 路由）。

**核查通过、无需改**
- **P3 通知渠道**：`NotificationChannelCompat`/`NotificationManagerCompat` 内部 <O 空操作；**P5 前台服务**：`ServiceCompat.startForeground` + `foregroundType()` 已做 Q 分支，<Q 传 0。
- `MicCapture` 用 `AudioRecord` 老构造（API5+）；`MicAacEncoder` 用同步 `dequeueInputBuffer/dequeueOutputBuffer`（非异步回调）；`net/usb` 骨架为老接口。
- JDK 层扫描：无 `java.time`/`java.util.function`/`java.util.stream`/`java.nio.file`/`java.util.Base64`/`HexFormat` 误用（出现的 `.toList()` 均为 Kotlin stdlib 扩展，兼容 19）。

**待改（后续轮次，按风险/体量排序）**
- **P1 视频解码器同步化（最大块）**：`VideoDecoder` 深度依赖 `MediaCodec.setCallback(CodecCallback, handler)` 异步回调（API21）——空闲输入缓冲由 `onInputBufferAvailable` 推进 `freeInputs`，出帧/格式变化/报错由 `onOutputBufferAvailable/onOutputFormatChanged/onError` 驱动，pacer、重放、统计都挂在回调上。需改解码线程轮询 `dequeueInputBuffer`/`dequeueOutputBuffer`，识别 `INFO_OUTPUT_FORMAT_CHANGED`，错误走异常/返回码。另有 `setOnFrameRenderedListener`（API23，仅统计，可版本跳过）、`ImageFormat.PRIVATE`（API21，占位 Surface 需换 RGB_565 或版本分支）。
- **P12 媒体键会话**：`MediaKeySession` 用 `MediaSession`/`PlaybackState`（API21），4.4 类不存在。替代为老 `RemoteControlClient`（API14，已弃用），或 4.4 裁剪会话、方向盘键仅走 Activity `onKeyDown`（`MediaKeys.onWindowKey` 已有）。
- **P6 BLE 外设三件（裁剪）**：`BleBeacon`（`startAdvertising` API21）、`BeaconGattServer`、`LePairingPeripheral`（GATT 服务端 + 广播 API21）。4.4 只支持 BLE 中心角色，外设广播/GATT 服务端不可用，做空实现或归档。
- **P7 本地热点（裁剪）**：`LocalHotspot.startLocalOnlyHotspot`（API26）；`app/wireless` 随 P6/P7 一并裁剪，4.4 构建只留有线路径。
- 附：animalsniffer 签名检查只覆盖 core 纯 JVM 模块；挂到 Android 模块会因取不到 Java SourceSet 报错（插件对 AGP 支持有限），platform/app 的高版本 API 目前靠人工 grep + 版本分支核对。

### 阶段 4：无线通道判定修正（2026-11-24，推送 6d95eae）

**结论修正**：4.4 不是"不能无线"，是"不能由车机自动开热点"。无线拆三段看：

- **蓝牙**：二维码里是车机**经典蓝牙公共地址**（`PairingQrCode` 的 `mac=`），手机扫码后走 RFCOMM 直连车机，API 5 起就有，4.4 可用。BLE 广播只在"被动等手机回连"才发（`beaconPlan` 里 `kind != Kind.AUTO` 直接返回 null），该模式已不自动进入，随 P6 裁掉即可。
- **数据通道**三条，`WirelessNetworkPlan.channelSupported` 改成按通道判定：
  - 车机开本地热点 `startLocalOnlyHotspot`：API 26 ❌
  - 车机自带热点（车主手动开车机热点，`CarHotspot` 只读自己热点网卡地址 + 车主填的 SSID/密码）：任何版本 ✅ **4.4 可走**
  - WiFi 直连组 `WifiDirectGroupOwner`：API 14 ✅ 4.4 支持（代码标记"试验"，手机侧认不认待真机）
- 原 `begin()` 里 `hotspotSupported(SDK_INT)` 无条件拦死，连上述两条可用的分支都不试；已改为提前定通道再判。旧系统且未配车机热点时返回新 notice `OldSystemNeedsCarHotspot`。`reconnectConditions()` 的自动回连判定同步改。

**配套（骨架最小可用）**
- 无线入口：开始/停止按钮、`ImageView` 二维码（`ui/QrCodes.kt`，ZXing 编码到 Bitmap，不引 Compose）、连接码与原因文本。
- 车机热点配置：SSID / 密码 / 5GHz / 启用，存 `WirelessPreferences`（`carHotspot`、`carHotspotName`、`carHotspotPassword`、`carHotspotFiveGhz`），勾启用时关掉 `wifiDirect`（二选一）。
- 单测 `WirelessNetworkPlanTest`：19+车机热点=true、19+直连=true、19+都没有=false、26/28 无需替代=true。

**车机操作顺序（真机验证用）**：车机通电 → 在车机设置里把 WiFi 开成**热点/AP 模式**（不是普通 WiFi 开关）→ 启动 HyundaiHC → 填热点名称密码并勾启用 → 点"开始无线连接" → 手机扫码。

**真机验证的前置已解除**：identity 真实参数已从上游公开分发的 `OpenHC-0.2.0.apk`（SHA-256 与官方校验值一致）解包取得，写入 `identity/hyundaic-identity.json`，构建脚本打进 `assets/hyundaic-identity.json`（424 字节）。模拟器上已确认身份加载成功、无线启动越过身份检查，卡在"车机蓝牙地址 / 定位权限"（模拟器无真实蓝牙硬件，属环境限制）。

---

### 阶段 5：全量 lint 清账 —— API19 防线（2026-11-24）

> 起因：之前靠人工 grep + 版本分支自查，漏了一大批高版本 API 调用。改用 Android Lint 的 `NewApi` 做机器普查，一次暴露出 platform **155 个错误**、app **5 个错误**，全部是 minSdk 19 下会 `NoSuchMethodError` / `NoClassDefFoundError` 的调用点。本轮把这些清到 **0**。

**platform：155 → 0**

| 位置 | 实际要求 | 处理 |
| --- | --- | --- |
| `AudioPlayer` 的 `write(ByteBuffer,int,int,int)` | API 23 | 4.4 走三参数 `write(byte[],int,int)` |
| `AudioPlayer.setVolume(float)` | API 21 | 4.4 用 `setStereoVolume(l, r)` |
| `AudioPlayer.bufferSizeInFrames` | API 23 | 4.4 按申请的字节数 / 每帧字节数换算（仅统计用） |
| `AudioPlayer` / `MicAacEncoder` / `VideoDecoder` 的 `getInputBuffer/getOutputBuffer(idx)` | API 21 | 4.4 用 `getInputBuffers()[i]` / `getOutputBuffers()[i]` |
| `PcmTrack` 的 `AudioTrack.Builder` | **API 23**（不是 21） | 门槛从 LOLLIPOP 改 M；21/22 与 4.4 一样走老构造 |
| `AudioUsage.attributes()/trackAttributes()` | API 21 | 标 `@TargetApi(21)`，4.4 只用 `streamType()` |
| `DisplayRefreshRate` 的 `Display.getMode()` | API 23 | 4.4 退回 `getRefreshRate()` |
| 同上 `appVsyncOffsetNanos` / `presentationDeadlineNanos` | API 21 | 4.4 记 0 |
| `DecoderCatalog` 的 `MediaCodecList()` / `codecInfos` / `videoCapabilities` | API 21 | 4.4 返回空清单，调用方退回 `createDecoderByType`；尺寸检查一律视为支持 |
| `WifiStatus` / `LocalHotspot` / `WifiDirectGroup` 的 `is5GHzBandSupported`、`WifiInfo.getFrequency` | API 21 | 4.4 记"不支持 / 0" |
| 同上 `ConnectivityManager.NetworkCallback` / `requestNetwork` | API 21 | 4.4 跳过"按住蜂窝网络"这一步 |
| `MediaKeySession` 的 `MediaSession` / `PlaybackState` | API 21 | 拆成外壳 + `MediaKeySession21`；4.4 的会话 `supported=false`、不加载高版本类，方向盘键只在界面前台时生效 |
| `VideoDecoder` 的 `MediaCodec.setCallback` | **API 23**（不是 21） | `configureLocked` 开头按版本拒绝，经 `onDecoderUnavailable` 明说"C 系统没有投屏解码接口"，不再崩 |
| 同上 `setOnFrameRenderedListener` / `setOutputSurface` / `CodecException` | API 23 | 随上面的门禁一并不执行 |
| `BleBeacon` / `LePairingPeripheral` 的 BLE 广播 | API 21 | 类标 `@TargetApi(21)`；`support()` 在 4.4 返回"BLE 广播要安卓 5.0 起" |
| `NcmUsbLink` 的 `setInterface` / `alternateSetting` | API 21 | `open()` 在 4.4 直接抛 `IOException` 说明有线要 5.0 起 |
| `UsbDescriber` 的 `alternateSetting` | API 21 | 4.4 记 0 |
| `TunInterface` 的 `android.system.Os` | API 21 | 类标 `@TargetApi(21)`；`VpnTunnel.open()` 在 4.4 直接抛 `IOException` |

**app：5 → 0**

- **主题**（`AndroidManifest.xml`）：原来直接用 `@android:style/Theme.Material.NoActionBar`（API 21），**安卓 4.4 上 Activity 启动就会崩**。改成自定义 `@style/AppTheme`：`values` 用 `Theme.Holo.NoActionBar`，`values-v21` 用 Material。这是本轮最要紧的一处。
- `BootStart.canDrawOverlays`：`Settings.canDrawOverlays` 是 API 23，4.4 按"有权限"处理。
- 信任库目录：`Context.getNoBackupFilesDir()` 是 API 21 → 抽成 `trust/TrustDirectory.kt` 的 `noBackupDirectory()`，4.4 落到 `filesDir/no_backup`。
- `DiagnosticsExport`：`checkSelfPermission` 是 API 23 → 包进 `granted()`（`@TargetApi(23)`）+ 版本判断。

**结果**

- `:platform:lintDebug` 0 errors / 28 warnings；`:app:lintDebug` 0 errors / 379 warnings（其中 373 条是归档 Compose 界面后遗留的未用字符串资源，不影响运行）。
- `assembleDebug` / `assembleRelease` 均通过；APK `minSdk 19`、包名 `com.jankxia.hyundaic`、label `HyundaiHC`、`assets/hyundaic-identity.json` 424 字节。

**4.4 上现在实际能做什么**（诚实口径）

| 能 | 不能 |
| --- | --- |
| 启动、身份加载、经典蓝牙配对与扫码、PCM 播放与麦克风、界面前台时的方向盘键、车机自带热点 / WiFi 直连两条数据通道 | 投屏画面（`VideoDecoder` 门禁）、有线 NCM（`setInterface` 缺失）、有线 VPN TUN（`android.system.Os` 缺失）、BLE 外设、本地热点、后台媒体键 |

也就是说：阶段 5 时点，4.4 车机能"连上、有声音"，**没有画面**。画面这条已在阶段 6 按"同步轮询版解码器"补上（见下节）：4.4/5.x 走 `dequeueInputBuffer/dequeueOutputBuffer` + `getInputBuffers()` 数组，Surface 变就重建解码器。

### 阶段 6：4.4～5.1 投屏解码 —— 同步轮询版（2026-11-24）

> 起因：`MediaCodec.setCallback` 是 API 23，`setOutputSurface` 也是 API 23。原来 1290 行的 `VideoDecoder` 整个是围着异步回调 + 上屏节拍 + 重放缓存写的，4.4～5.1 一行都跑不起来，之前只能门禁拒绝（"有声音无画面"）。

**做法：不动 6.0+ 那条成熟路径，另起一套同步实现，按版本二选一。**

| 文件 | 作用 |
| --- | --- |
| `platform/.../media/SyncVideoDecoder.kt`（新增） | 4.1～5.1 的 H.264 同步解码：自己的解码线程循环 `dequeueInputBuffer` / `dequeueOutputBuffer`，出帧就 `releaseOutputBuffer(index, true)` 直接上屏 |
| `app/.../projection/VideoSink.kt`（新增） | `VideoSink` 接口 + `AsyncVideoSink` / `SyncVideoSink` 两个包装 + `VideoSinks.create()` 工厂，按 `SDK_INT >= 23` 选实现 |
| `app/.../projection/ProjectionView.kt` | `attachDecoder()` 参数从 `VideoDecoder?` 改成 `VideoSink?`（其余逻辑不变） |
| `ConnectionController` / `WirelessController` | `videoDecoder: VideoDecoder?` → `videoSink: VideoSink?`；`prepareDecoder()` 改走 `VideoSinks.create()`；`mutableDecoder`/`decoder` 的泛型同步换成 `VideoSink?` |

**同步版的取舍（有意为之）**

- 不做上屏节拍：出帧立即 `releaseOutputBuffer(index, true)`。4.4 车机 720p 下影响有限，换来实现简单。
- 不做重放缓存：Surface 没了就释放解码器停解，回来等手机下一个关键帧（会黑一下）。
- 不切占位 Surface：`ImageReader(PRIVATE)` 本身是 API 21，4.4 用不了。
- 不查解码器候选清单：直接用 `createDecoderByType("video/avc")` 的系统默认实现（`DecoderCatalog` 在 4.4 本来也返回空清单）。
- 队列上限 48 帧：满了丢最旧一帧并重新等关键帧（参考链已断，硬解会花屏，宁可等 IDR）。
- 参数集变了（SPS/PPS）当场用新参数重建；Surface 换了必重建（没有 `setOutputSurface`）。
- `KEY_PRIORITY` 只在 API 19+ 设置，`KEY_OPERATING_RATE` 只在 API 21+ 设置，其余档位只给尺寸 + 参数集。

**验证方式（关键，不是只编译过）**

新增设备自检 `platform/src/androidTest/.../SyncVideoDecoderTest.kt`：用系统 H.264 编码器编 8 帧 320×240 灰画面（`COLOR_FormatYUV420SemiPlanar`，每帧一个 I），把 Annex-B 码流（含 AVCC→Annex-B 兜底转换）原样喂给 `SyncVideoDecoder`，断言：

- `onDecoderUnavailable` 没被调用；
- `renderedCount >= 3`（真的解出来上屏了，不是只收帧）；
- `onVideoSizeChanged` 报出 320×240。

结果（雷电 LDPlayer9，安卓 9 / API 28，x86_64）：

```
Starting 1 tests on emulator-5554 - 9
Finished 1 tests on emulator-5554 - 9
BUILD SUCCESSFUL in 3m 40s

<testsuite name="...SyncVideoDecoderTest" tests="1" failures="0" errors="0" skipped="0" time="1.654">
<testcase name="decodesFramesEncodedBySystemEncoder" time="0.398" />
```

同步接口在 API 28 上一样可用，所以这条用例能证明"这段代码真的解得动"，而不是只证明编译通过。**注意它没有在真正的 API 19 上跑过**：本地没有 4.4 镜像（见第 8 节），4.4 车机上仍需真机验证。

**已知限制**

- 4.4/5.x 没有 `setOutputSurface`：SurfaceView 销毁（切后台、视图重建）就释放解码器，回来要等手机发关键帧。
- 没有节拍：抖动大时两帧可能落进同一次刷新、上一帧被挤掉（现象是偶发跳帧，不会花屏）。
- 没有低延迟键调优（`KEY_LOW_LATENCY` 是 API 30），4.4 只靠 `KEY_PRIORITY`。
- 4.4 上 `MediaCodec` 的同步 API 是 4.1 才有的，所以 minSdk 若再降到 14/15 这条路径也不成立。

### 阶段 7：有线链路在 4.4 上跑通 —— 两处 API 21 缺口（2026-11-24）

> 起因：上一轮有线链路在 4.4 上是硬拒的（`NcmUsbLink.open` 直接抛 "USB 网卡要安卓 5.0 起"、`VpnTunnel.open` 拒 "TUN 要安卓 5.0 起"）。挡住的其实只有两个 API：`UsbDeviceConnection.setInterface` / `UsbInterface.getAlternateSetting`（21）与 `android.system.Os`（21）。协议本身（NCM/NTB16）与 `VpnService`（14）在 4.4 上都在。

**缺口一：切换数据口的备用设置**

4.4 没有 `setInterface()`，也不告诉你某个接口有几个备用设置、各自端点是什么。但这两件事都能绕过：

| 手段 | 说明 |
| --- | --- |
| 标准控制请求 `SET_INTERFACE` | `bmRequestType=0x01`、`bRequest=0x0B`、`wValue=备用设置号`、`wIndex=接口号`、无数据、timeout 1000ms，走 `UsbDeviceConnection.controlTransfer`（API 12）；返回 ≥0 视为成功（`NcmUsbLink.setAlternateLegacy`） |
| 原始描述符解析 | `getRawDescriptors()`（API 12）拿整段配置描述符，自己按 bInterfaceNumber / bAlternateSetting / bInterfaceClass / bInterfaceSubClass / bNumEndpoints 走一遍（`UsbRawDescriptors`），再用"端点地址集合"反查要切到哪个备用设置（`alternateFor`） |

`NcmUsbLink` 因此新增 `legacyInterfaceSwitch = SDK_INT < 21` 分支：4.4 上先解析原始描述符拿到 `rawInterfaces`，用 `alternateFor(1, 该数据口的端点地址)` 反查活动备用设置号（批量 0x81/0x02 那一组），空闲设置号取同一接口里不带端点的那一组；切来切去都发 `SET_INTERFACE`，5.0+ 仍走原来的 `setInterface`。

`res/xml/usb_device_filter.xml` 里已经声明了华为的三个 product，4.4 上插线即触发，别名 `UsbAttachAlias` 保证不弹系统选择框，这条链路不需要改。

**缺口二：4.4 没有 `android.system.Os`**

5.0+ 的 `TunInterface` 用 `Os.read(fd)/Os.write(fd)` + `Os.poll` + `ParcelFileDescriptor` 做非阻塞读写与唤醒。4.4 没有 `Os`，但 `ParcelFileDescriptor.getFileDescriptor()` 拿到的就是个普通 fd，用 `FileInputStream` / `FileOutputStream` 直接包一层就能阻塞读写：

- `LegacyTunInterface.read()`：`FileInputStream.read(buffer)`，返回 0 视为 EOF（→ -1），IOException 且已关闭时返回 -1；
- `write()` / `flush()` 直接落到 `FileOutputStream`；
- `wakeup()` 把 `closed` 置位并关掉 `ParcelFileDescriptor`，阻塞中的 read 立刻抛 "Bad file descriptor" 被捕获成 -1，读线程退出；
- 两个流不主动 close（所有权在 PFD，重复关会报错），生命周期统一交给 `ParcelFileDescriptor`。

配套前提已经天然成立：`WiredVpnService.establishTun` 在 4.4 上本来就跳过了 `setBlocking` / `addAllowedApplication` / `allowFamily`（全是 21+），`Builder.establish()` 交回的 fd 就是阻塞的，正好配阻塞读。

`VpnTunnel` 的 `tunnel` 字段类型从 `TunInterface` 改成 `PacketTunnel`，构造时按版本分派（`SDK_INT >= 21` 走 `TunInterface`，否则 `LegacyTunInterface`），`close()` 用 `is Closeable` 判型后调 `close()`，否则 `wakeup()`。

**验证**

- 新增 JVM 单测 `platform/src/test/.../UsbRawDescriptorsTest.kt`：手工拼一段 NCM 描述符（控制口 0/备用 0 + 中断 IN 0x83；数据口 1/备用 0 无端点；数据口 1/备用 1 批量 0x81+0x02），断言接口数、class/subclass、端点类型与 `maxPacketSize`、`alternateFor` 在端点集合匹配时返回 1、不匹配时返回 null，另有一条"截断描述符"用例保证不抛异常。

```
:platform:testDebugUnitTest --tests "...UsbRawDescriptorsTest"   BUILD SUCCESSFUL in 22s
```

- `:app:compileDebugKotlin` BUILD SUCCESSFUL in 42s。
- `:platform:lintDebug :app:lintDebug` BUILD SUCCESSFUL in 2m 51s，`NewApi` 仍为 0（阶段 5 清过账，本轮没有回潮）。

**已知限制（4.4 专属，必须真机确认）**

- 4.4 的 `UsbDevice.getInterface(i)` 是否会把数据口的备用 1（带批量端点那一组）也列出来，取决于厂商内核/系统实现。若系统只暴露备用 0（端点为空），`NcmInterfaces.select` 就拿不到数据口 → 报"没找到 NCM 控制口或带批量端点的数据口（安卓 4.4）……这条线走不通"，**是明确报错，不是崩溃**，但这条线在那种机器上确实无解。
- `SET_INTERFACE` 控制请求可能被手机固件或内核的 CDC 驱动拒绝（返回负值），此时同样落到上面那句报错。
- "关 fd 唤醒阻塞读"依赖内核对已关闭 fd 上阻塞 read 的处理，4.4 上行为未实测；真机若出现停止后线程卡住，改成"手动设置超时 + 周期检查 closed"更稳。
- 4.4 没有 `addAllowedApplication`：TUN 不只对本应用生效。当前只往 `192.168.66.0/24` 加了一条路由，影响面被限制在这一网段，但严格说不是应用级隔离。
- **以上全部只有编译、lint 与 JVM 单测支撑，没有 API 19 真机/镜像证据**（本地无 4.4 镜像，见第 8 节）；雷电 API 28 跑的是 `SDK_INT >= 21` 分支，走的仍是 `setInterface`/`TunInterface`，测不到本轮新增的 4.4 路径。

