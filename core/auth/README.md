# core:auth —— 配对认证

- 首次连接：车机给出 6 位连接码，双方做口令认证（PAKE，3072 位有限域 SPEKE），再交换长期身份（Ed25519 公钥），
  得到 16 字节会话密钥。
- 再次连接：用首次配对保存的长期身份做免码认证（STS：X25519 临时密钥 + Ed25519 互签），得到新的 16 字节会话密钥。
- 外层的 HiCar 认证 JSON（`oper_type` 1/3）的解析与生成，以及认证连接上的分帧。
- 长期身份与信任列表的存储接口（[`TrustStore`](src/main/kotlin/com/jankxia/hyundaic/core/auth/TrustStore.kt)），
  core 里只有接口、内存实现和版本化的持久格式；落盘与私钥保护由平台层实现。

## 承载

认证消息**不走**控制通道（nearby 帧）。握手完成后，手机在控制通道上请求车机为业务 19（标签 `hicarAuth`）
新开一条 TCP（无线时是蓝牙上的一条 RFCOMM 子通道）；车机监听、把端口回给手机，手机连进来。之后这条连接上的字节全部交给本模块：

```
认证 TCP 收到的字节 ──▶ HiCarAuthSession.feed(bytes, offset, length)
                         └─▶ [Send(bytes) …]  原样写回同一条 TCP
                             HandshakeAnswered(mode, pinCode)   首次配对时 pinCode 可显示给用户
                             Succeeded(sessionKey, …) / Failed(…)
TCP 断开 ──────────────▶ HiCarAuthSession.onConnectionClosed()
```

这条 TCP 上是一个接一个的 UTF-8 JSON 对象，没有长度头也没有换行，单条上限 10240 字节；分帧在本模块里做。
本模块不碰 Socket、不开线程、不计时；超时与断线由调用方处理。

无线连接用 `HiCarAuthConfig(…, pin = 当前连接码, usbConnection = false)`：握手应答里 `usb_connection` 为 false、
不下发 `pin_code`（手机从二维码或用户输入拿到连接码），首次配对的口令就是二维码和屏幕上显示的那个连接码；
之后的认证消息与有线逐字节相同。手机握手里的 `usb_connection` 和配置不一致时按 `UnsupportedConnection` 失败。

## 接口分层

| 类型 | 作用 |
|---|---|
| `HiCarAuthSession` | 外层会话：字节流分帧、设备握手（`oper_type=1`）、把认证库消息装进 `oper_type=3` |
| `AccessoryAuthenticator` | 车机角色（HC_ACCESSORY）的认证库接口：喂入一条原始认证消息，返回要回发的消息和结果 |
| `KotlinAccessoryAuthenticator` | 上面接口的纯 Kotlin 实现；以后需要时可以换成别的实现 |
| `TrustStore` / `InMemoryTrustStore` / `TrustStoreCodec` | 长期身份与已配对手机的存储接口、内存实现、版本化字节格式 |

随机数由调用方注入 `SecureRandom`。会话密钥以 16 字节 `ByteArray` 交出，不进日志。

## 行为对齐与验证

`KotlinAccessoryAuthenticator` 照原库（OpenHarmony deviceauth_lite）的车机角色实现，验证分三层：

- **取随机数的次序与长度**：`SequenceRandom` 是 `SHA-256(seed || BE32(i))` 拼接流，测试断言每一步累计取了多少字节
  （例如首发应答 96 字节：长期身份 32 + salt 16 + 挑战 16 + 私有指数 32）。次序错了后续消息一定错位，这条收紧得住。
- **消息结构**：把消息里的长十六进制材料换成长度占位（`shapeOf`）后逐字段对照向量，
  钉住字段名、字段顺序、空白、十六进制大小写与各段长度（salt 32 / 挑战 32 / epk 768 / kcf 64 …）。
- **不含随机材料的消息**：错误通知（`{"message":32896, "payload":{"errorCode":…}}`）与字段错误的应答仍逐字节相同，
  错误码、结果码序列也必须一致；38 个错误路径探针里 24 个属于这一类。
- 端到端的一致性是另一条：`RoundTripTest` 用真随机数跑车机 ↔ 测试手机的完整配对与回连，
  断言双方导出同一个会话密钥；`HiCarAuthSessionTest`、`WirelessHandshakeTest` 的外层分帧与信封同样跑这条链路。

**为什么不逐字节对照整段会话**：`accessory-vectors.json` 声明随机数是 `SHA-256(seed || BE32(i))`，
但向量里记录的 challenge / salt 在这条流里（前 16 KB、各种计数编码、各种前缀后缀，共 210 种组合）都不存在，
说明录制这批向量时用的桩与文件里写的不是同一套。随机材料既然取不到同一组值，KCF 就绑不上双方看到的挑战，
整段会话必然在配对确认处按 `VERIFY_PROOF_FAILED` 分叉——那不是实现的问题，而是缺少同一随机源。
要恢复整段逐字节对照，必须用 C 侧测试桩重新生成向量（需要能编译 deviceauth_lite 的环境），而不是靠猜测。

## 移植来源

算法与消息格式移植自 OpenHarmony `security_device_auth`（`frameworks/deviceauth_lite`，OpenHarmony-v4.0-Release，
Apache-2.0）。移植的文件头保留了出处与许可声明（第三方声明待登记）。
