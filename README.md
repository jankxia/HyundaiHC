# HyundaiHC

**面向现代帕里斯帝车机的 HiCar 车机端，fork 自开源项目 [OpenHC](https://github.com/GGBondLab/OpenHC)。** 装在安卓车机上，华为手机用数据线或无线投屏到车机屏幕。

- 目标车机：现代帕里斯帝（Hyundai Palisade），安卓 4.4（API 19）
- 配对手机：华为 Mate60 Pro，HarmonyOS 7.0.0.111SP10（纯血鸿蒙）
- 包名：`com.jankxia.hyundaic`

> **当前状态：安卓 4.4 适配进行中。**
> 已完成工程改名与包名迁移，在安卓 9 模拟器上编译、安装、启动通过；安卓 4.4 降级适配尚未完成，暂不能在 4.x 车机上运行。
> 连接参数（`hyundaic-identity.json`）未接入前，安装包只能进入首页，无法连接手机。
> 适配差距、分层清单与改造路线见 [移植方案](docs/PORTING_ANDROID_4.4.md)。

上游原项目只在比亚迪安卓 10 车机 + 纯血鸿蒙手机上验证过；本分支针对现代帕里斯帝 + Mate60 Pro 做适配，设备组合不同，问题不能直接对照上游结论。

## 功能（继承自上游基线）

装在**车机**上，不是手机上。不需要 root、ADB、系统签名，不依赖车机自带的 HiCar，不用注册账号，不连任何服务器。车机要允许安装 APK。

- **有线**：USB 数据线（上游基线要求车机安卓 6.0 起；本分支目标降到安卓 4.4，尚在改造）。
- **无线**：手机开个人热点、车机当 WiFi 客户端连上它，第一次手机扫码连接，之后上车自动连上。
  上游基线是「车机开热点、手机连车机」，要求车机安卓 8.0 起；本分支把方向反过来（`WifiConnector` 让车机主动连手机热点，
  `CarHotspot` 读车机当前连接给手机用），安卓 4.4 也能走这条路。热点建议 2.4 GHz。
- **手机**：带 HiCar 的华为或荣耀手机。有线连接时，手机要关闭「USB 调试」。

- 画面：帧率自动跟随车机屏幕刷新率；清晰度三档可选；可全屏，也可保留车机底部导航栏。
- 触摸：多指操作，地图可以双指缩放。
- 声音：音乐和导航播报分开放；可以在设置里分别指定导航声道和音乐声道（编号同 DiPlay），带试听。
- 方向盘按键：上一首、下一首、播放 / 暂停。
- 免码回连：第一次输入车机上显示的连接码，之后不用再输。
- 自动连接手机：手机蓝牙连上车机、或打开 HyundaiHC（含开机自动打开）时，车机主动去连用无线连成功过的手机，不用扫码。
- 可选：插线时不再弹授权框、开机自动打开、无线结束后恢复车机 WiFi。

## 使用

1. 车机安装 HyundaiHC。装过自己编译的调试包的，先卸载再装，签名不同不能覆盖。
2. 第一次打开时，HyundaiHC 申请的权限（位置、附近设备、蓝牙等）都点允许。有的后装车机带系统自己的权限管家，会悄悄拦下这些申请：先到车机的权限管家（应用管家、权限管理之类）里给 HyundaiHC 打开位置、WiFi 状态等权限，再连接。
3. **有线**：首页点「开始」，手机插车机 USB 口，在手机上点「连接」，第一次输入车机显示的连接码。
4. **无线**：手机先开个人热点（建议 2.4 GHz），在首页「手机热点（车机连它）」填热点名和密码、点「让车机连上」，
   再点「无线连接」的「开始」，手机扫车机上的二维码。连成功过一次以后，上车手机蓝牙连上车机，HyundaiHC 就会自己连上手机
   （设置 → 启动 →「自动连接手机」，默认开）。

> 注意：以上使用流程来自上游基线描述；在帕里斯帝安卓 4.4 车机上能否走通，需待 4.4 适配完成后真机验证。

## 状态与已知限制

- 已完成：fork 改名（工程名 / 包名 / 源码目录 / 类名 / 文案）；debug 包在安卓 9（API 28）x86_64 模拟器上编译、安装、启动通过，无崩溃。
- 未完成：安卓 4.4（API 19）降级改造（Compose 外壳重写、依赖降版、MediaCodec/AudioTrack 降级、裁剪无线等）；真实连接参数接入；真机联调。
- 用过车机自带 HiCar 或同类应用的手机，第一次连 HyundaiHC 可能失败：在手机 HiCar 里删掉旧的车机记录，再扫码连接。
- 通话和车机麦克风在上游基线也尚未验证。

## 报告问题

请在 [Issues](https://github.com/jankxia/OpenHC/issues) 里写明：车机型号和安卓版本、手机型号和系统版本、有线还是无线、操作步骤、期望和实际结果、出问题的时间，并附上诊断日志：在 HyundaiHC「设置 → 诊断 → 导出诊断日志」，把生成的文件附上
（安卓 10 起在「下载/HyundaiHC」文件夹；更早的系统在 `Android/data/com.jankxia.hyundaic/files/HyundaiHC`）；
能接 ADB 的话，附上 `adb logcat -v time | grep HyundaiHC-` 的输出。

安全问题和连接参数的说明见 [安全说明](SECURITY.md)。

## 构建

需要 JDK 17 和 Android SDK 36：

```sh
./gradlew check assembleDebug
```

> 本地 Windows 环境若 Gradle wrapper 分发包下载受阻，可直接使用本地缓存的 Gradle 8.14.6 调用 `gradle.bat` 构建（AGP 8.11.1 要求 Gradle 8.13+）。SDK 路径写在 `local.properties` 的 `sdk.dir`。

安装包在 `app/build/outputs/apk/debug/`。代码结构见 [架构说明](docs/ARCHITECTURE.md)。

要连得上手机，还需要一份连接参数文件 `hyundaic-identity.json`（不随源码发布，格式见 [示例](docs/identity.example.json)）：
把它单独放进一个目录，在 `local.properties` 里写 `hyundaic.identity.dir=<目录>`（或设环境变量 `HYUNDAIC_IDENTITY_DIR`），构建时会打进安装包。示例占位值只能让应用进入首页，真实参数必须与手机侧校验的车机端一致，否则握手 / 加密 / 认证会被拒绝。

## 参与

欢迎提 Issue 和 Pull Request。提 Pull Request 前请注意：

- 不要提交反编译得到的代码（Java、smali、伪代码）或照着它改写的代码，代码、注释和提交说明里也不要写厂商应用的类名、文件路径。
  注释里写协议本身的事实（字段、长度、字节序、时序和为什么这样做）就好。
- 测试数据一律用编造的值，不要用真实设备的地址、序列号、抓包或日志原文。
- `core` 下的模块是纯 Kotlin，不能用 `android.*`，要能在安卓 6.0 上运行（构建时会检查）。
- 提交前跑通 `./gradlew check assembleDebug`。
- 提交的代码按本项目的 GPL-3.0 许可发布。

## 致谢

- **[DiPlay](https://github.com/shihabal3amri/DiPlay)**（作者 [shihabal3amri](https://github.com/shihabal3amri)）：安卓车机上的开源 CarPlay 接收端。
  HyundaiHC 的很多思路和做法参考了它。感谢作者的开源分享。
- **OpenHarmony security_device_auth**：配对认证部分的代码移植自它（Apache-2.0）。
  用到的其他第三方组件和许可见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。

## 声明

HiCar 是华为技术有限公司的商标。本项目与华为及任何车企无关，未获得它们的授权或认可；按现状提供，使用风险自负。

## 许可证

[GPL-3.0-only](LICENSE)
