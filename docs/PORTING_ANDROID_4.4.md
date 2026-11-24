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
