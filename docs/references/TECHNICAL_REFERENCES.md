# NiKConnect 技术参考与证据记录

日期：2026-11-09  
用途：支撑 `../PRD.md`，把“真机事实、源码事实、逆向观察、开源参考、待验证推断”分开记录。

## 1. 证据等级与使用规则

| 等级 | 含义 | 可以怎样写进产品 |
|---|---|---|
| A | 用户真机已操作并验收 | 可写“已在指定设备组合验证”，不能外推到其他设备 |
| B | 本地源码/逆向工具直接观察 | 可写“样本/当前工作树中存在”，不等于运行行为已验证 |
| C | 公开开源项目实现或公开协议资料 | 可作实现参考，合并前需本地审查、测试与许可证确认 |
| D | 根据以上材料形成的推断 | 必须写“待验证”，不得进入已完成清单 |

本记录没有重新执行旧项目的真机测试，也没有在本轮重新抓取所有开源仓库。旧项目 0.1.0 的真机结论来自用户验收及 `PROGRESS.md`；0.1.1 的测试结论来自当前工作树已有记录。

## 2. 首要参考：ZF Relay 既有真机项目

### 2.1 项目位置与基线

- 项目：`/Users/chrisz/Documents/projects/nikon-connect`
- 当前 Git HEAD：`2bc3f43`（`feat: 0.1.0 - PTP/IP direct camera-to-phone transfer`）
- 注意：0.1.1 的多数修复仍在未提交工作树中；引用具体实现时以“当前工作树”为准。
- 真机：一加 Ace 5 Pro + Nikon Zf。
- 已验收：PTP/IP 初始化、照片列表、缩略图、JPEG 原图下载。
- 已记录的本地检查：19 项 JVM 测试、`lintDebug`、`assembleDebug`。
- 未验收：0.1.1 真机回归、NEF/NRW、批量、后台、其他设备组合。

原始附件：[`ZF_Relay_真机验证记录.md`](ZF_Relay_真机验证记录.md)、[`ZF_Relay_MVP审查与后续开发.md`](ZF_Relay_MVP审查与后续开发.md)。

### 2.2 可直接复用的协议实现

| 主题 | 本地源码位置 | 已观察内容 | 使用建议 |
|---|---|---|---|
| 数据包与操作码 | `app/src/main/java/com/chrisz/zfrelay/ptp/PtpPackets.kt:7-42` | 双通道初始化包、操作/响应/数据包；0x1001、0x1002、0x1003、0x1007、0x1008、0x100A、0x101B、0x90C4 | 作为 NiKConnect 初版最小协议集，不另起一套常量 |
| 小端编解码 | 同文件 `:54-161` | length/type、GUID、UTF-16 friendlyName、事务 ID、数据包解析 | 保留边界检查与畸形包测试 |
| 双通道连接 | `.../ptp/PtpIpClient.kt:44-98` | port 15740；InitCommand→InitEvent→OpenSession(1)→GetDeviceInfo；350ms settle delay | 延迟来自既有实测防护，调整前做对照 |
| 图库 | 同文件 `:100-119` | GetObjectHandles 后倒序取最近 120；逐个 GetObjectInfo；大缩略图失败回退标准 Thumb | 先分页/懒加载，再决定是否引入批量扩展 |
| 分块下载 | 同文件 `:121-138,236-238` | GetPartialObject(handle,offset,length)，CHUNK_SIZE=4MiB；空块/超长块检查 | 作为实现基线，不宣称所有相机最优 |
| 串行与故障隔离 | 同文件 `:140-189` | `@Synchronized` 事务；校验 transactionId；错配/超时/坏包关闭双通道 | 必须保留，避免迟包污染下一事务 |
| Socket 参数 | 同文件 `:193-211` | Proxy.NO_PROXY、4MiB receive buffer、TCP_NODELAY、keepAlive、30s I/O、10s connect | 性能修改须记录同条件对照 |
| 数据模型 | `.../ptp/PtpDatasets.kt:28-74` | DeviceInfo、PhotoObject、JPEG/RAW 判断、u32 数组 | RAW 判断存在不等于 RAW 下载已验收 |
| 原文件保存 | `.../camera/PhotoDownloader.kt:22-82` | 同 connectionId 的 `.part` 续写、长度校验、MediaStore IS_PENDING、失败清理 | 作为文件完整性底座 |
| 防跨会话续传 | 同文件 `:95-110` | 残片名绑定 connectionId/handle/size/safeName；只清理旧连接自有残片 | 不得为了“续传率”移除此边界 |

以上路径均相对于 `/Users/chrisz/Documents/projects/nikon-connect`。证据等级：B；其中 0.1.0 的完整链路另有 A 级真机记录。

### 2.3 网络与生命周期实现

- `CameraNetwork.kt:55-90`：优先活动 Wi-Fi；无 INTERNET 能力时请求 `TRANSPORT_WIFI`；12s 超时；最终由目标 `Network.socketFactory` 创建 socket。
- `CameraRepository.kt:47-51`：手动地址→gateway→192.168.1.1，去重。
- `CameraRepository.kt:103-118`：generation 失效后关闭旧 acquisition/network/client。
- `SessionController.kt:6-47`：generation + Job 集合取消旧工作并拒绝迟到回写。

这些是 Android 相机 Wi-Fi 工具的核心，不应在 UI 重写时丢失。

### 2.4 测试参考

- `app/src/test/java/com/chrisz/zfrelay/ptp/PtpIpClientLoopbackTest.kt:31-59`：假相机双通道握手、从 offset 4 续读。
- `PtpIpClientFaultTest.kt`：事务错配、超时后关闭、建连/握手取消、分阶段错误。
- `PhotoDownloaderTest.kt`：同连接可续、跨连接不可续、定向清残片且保留已发布文件。

这些测试用于保护既有行为，不证明所有相机兼容。

### 2.5 0.1.1 真机验收门槛

沿用既有 `docs/NEXT_STEPS.md`：

1. 20 张 JPEG 与卡内源 SHA-256 一致。
2. 三次断线重连；旧任务和旧缩略图不作用于新会话。
3. 连接中退出不阻塞主线程。
4. 取消/失败不发布半成品，残片清理不影响已发布或无关文件。

只有上述验收通过，才把 R1–R4 从“代码已修”改为“真机已通过”。

## 3. Z-Relay Android 3.0.87：可用证据与边界

### 3.1 样本与工具

- 样本：`/Users/chrisz/Downloads/ZRelay_+Transfer+for+Nikon_3.0.87_APKPure.xapk`
- 包名：`com.geekmanlab.zrelay.global`；versionCode 387。
- 工作产物：`/Users/chrisz/Documents/deepseek-harness/Reverse/zrelay_work/`
- jadx 产出 2,435 个 Java 文件；业务逻辑不是加密壳中的 stub。
- 原始评估副本：[`ZRelay_Android_逆向可行性评估.md`](ZRelay_Android_逆向可行性评估.md)。

### 3.2 对 NiKConnect 有用的部分

| 观察 | 位置 | 可借鉴点 | 边界 |
|---|---|---|---|
| 标准 PTP 操作/事件/属性表 | `jadx_out/sources/z/th0.java:16-19` | 对照常量与解析语义 | R8 改名不影响数字常量，但仍需本地测试 |
| 统一事务入口 | `jadx_out/sources/z/zh0.java:94` | 协议事务集中处理，而非散落 UI | 不直接复制混淆结构 |
| 标准分块读取 | `z/zh0.java:1374-1460` | GetPartialObject 的 handle/offset/length | 已由旧项目独立实现并真机跑通，无需重写 |
| 0x200F 回退 | 同文件同区间 | 捕获 0x200F 后转 GetPartialObjectEx；64位 offset 拆两个 uint32 | 作为后续兼容性分支，当前不预设所有机型需要 |
| 另一组分块参数 | `com/kw/ztransfer/NikonTransferService.java:2537` | 日志记录 1024KiB 固定分块与 0x200F 回退 | 说明分块存在多种实现，不能把 1MiB 或 4MiB当协议硬要求 |

### 3.3 明确不采用的部分

服务端租约、心跳、配额、PairIP、付费授权和 native 核心不属于 NiKConnect。Android 样本中未找到 NP3 / Picture Control 槽位实现，不能用 Android 版证明滤镜链路。

**结论：** Android 逆向主要用于交叉验证传输细节；初版协议栈以已跑通的旧项目为主。

## 4. Z-Relay iOS 1.1：第二阶段滤镜的重要参照

### 4.1 样本与 REA 实测

- App：`/Applications/ZRelayMVP.app`
- 主二进制：`Wrapper/ZRelayMVP.app/ZRelayMVP`
- bundle id：`com.geekmanlab.zrelay`；版本 1.1（build 335）。
- Mach-O：thin arm64，8,550,432 B。
- SHA-256：`3158eaf99564d4a46b87eebc2d4b81fa5eb9a85daf2e951fac7ed955652f89b5`。
- REA 解出 606/606 Swift declarations、134 个 ObjC 类、625 个 ivars，并完成过真实函数级 Ghidra 反编译。
- FairPlay 仅标记入口所在 4KiB 页；其他代码与元数据可读。
- 原始报告副本：[`ZRelay_iOS_REA_可行性验证.md`](ZRelay_iOS_REA_可行性验证.md)。
- 完整元数据：`/Users/chrisz/Documents/deepseek-harness/Reverse/zrelay_ios/dispatch_metadata_full.json`。

### 4.2 传输模块

已观察类型：`PtpIpClient`、`PtpChannel`、`PtpOperationCode`、`PtpResponseCode`、`PtpEventCode`、`PtpParsers`、`CameraSession`、`GalleryController`、`DownloadService`。

`PtpIpClient` ivars 包含 host、port、initiatorGUID、initiatorName、commandQueue/eventQueue、commandChannel/eventChannel、transactionCounter、sessionOpen、eventRecords、deviceInfo、transactionOpcodes 等，说明 iOS 端同样采用直接 socket 的 PTP/IP 结构。

**使用边界：** 传输需求已由旧项目覆盖，无需为了“完整逆向”把 iOS 所有传输函数恢复一遍。

### 4.3 NP3 / Picture Control 模块

已观察类型包括：

- `Np3Document`、`Np3Record`、`Np3FileReader`、`Np3ImportService`
- `PictureControlCodec`
- `PictureControlClient`、`PictureControlSession`、`PictureControlStore`、`PictureControlViewModel`
- `PictureControlSlotRead`、`PictureControlSlotWriteReport`、`PictureControlPendingWrite`
- `PtpPictureControlCamera`
- `PictureControlPreviewRenderer`、`PictureControlTuneScreen`

**能得出的结论：** 该 iOS 样本确实存在本地 NP3 导入、滤镜模型、槽位读写状态与预览相关模块，值得作为第二阶段函数级恢复入口。

**不能得出的结论：** 类型名没有证明 NP3 每个扩展字段均能写入 Nikon Zf，也没有证明具体 opcode、字节布局、槽位范围和云端来源。B0 必须沿 `PictureControlCodec` + `PtpPictureControlCamera` 恢复真实读写数据，并做一次可恢复的单槽真机测试。

## 5. 开源项目参考地图

以下项目来自此前公开调研。除本地已有的 aero-shutter 副本外，本轮未逐行复审；它们提供实现线索，不构成 NiKConnect 的功能或兼容性证据。

| 项目 | 主要参考价值 | 当前使用方式 |
|---|---|---|
| [AchooLuv/NikonLink](https://github.com/AchooLuv/NikonLink) | Kotlin/Android，PTP/IP、RAW/视频传输、4MiB 分块、续传 | 对照 Kotlin 传输与文件处理；合入前审查当前版本 |
| [wan-1230/N-Link](https://github.com/wan-1230/N-Link) | BLE/Wi-Fi/USB、图库、队列、遥控与 LiveView | 近期只参考图库/队列；遥控和 LiveView 不进入当前范围 |
| [jaminxx-aha/ZPill](https://github.com/jaminxx-aha/ZPill) | Flutter，SnapBridge BLE、Wi-Fi 凭据、USB/无线路由 | 仅在未来确有 BLE 配网需求时研究；首版不加 BLE |
| [subhashraveendran/aero-shutter](https://github.com/subhashraveendran/aero-shutter) | Go + Capacitor，PTP/IP、ObjectAdded、自动导入、重连、socket 绑定 | 旧项目已有本地副本并声明 MIT 参考；优先复用该已审查链路 |
| [AbandonS-ED/Viewfinder](https://github.com/AbandonS-ED/Viewfinder) | Flutter/Dart 的较小 PTP/IP 实现 | 用于对照最小握手和包处理，不作为主代码底座 |
| [qDslrDashboard](https://github.com/sergei-dyshel/qDslrDashboard) | C++/Qt，多相机 PTP/PTP-IP 实现 | 桌面端协议和属性参考；不引入其完整产品范围 |
| [ssssota/nikon-flexible-color-picture-control](https://github.com/ssssota/nikon-flexible-color-picture-control) | NP3 文件读写与参数模型 | 第二阶段用于交叉验证 NP3 解析；字段写入相机仍需真机验证 |
| [libgphoto2](https://github.com/gphoto/libgphoto2) | PTP 常量、数据集和相机行为参考 | 查标准/厂商扩展定义；不直接假定 Android 行为一致 |
| [digiCamControl](https://github.com/dukus/digiCamControl) | Nikon Picture Control 结构和 Windows 端实践 | 对照旧版 PictureControl 布局；NP3 新字段需另行验证 |

### 5.1 aero-shutter 本地副本

路径：`/Users/chrisz/Documents/projects/nikon-connect/reference/aero-shutter`

可重点对照：

- `internal/ptpip/packet.go`、`codes.go`、`dataset.go`
- `internal/camera/camera.go`、`gateway.go`
- `internal/importer/importer.go`
- `mobile/src/lib/ptpip/`、`mobile/src/lib/network.ts`

旧项目 `THIRD_PARTY_NOTICES.md` 已声明其 MIT 参考关系。继续复制代码时应保留许可证和来源说明。

### 5.2 官方 SDK 边界

此前调研的 Nikon Remote Module SDK 面向 Windows/macOS，不提供 Android/iOS 同名库。NiKConnect Android 端继续走 PTP/IP，不把官方桌面 SDK 写入移动端依赖。

## 6. 初版最小协议集

| 环节 | 操作/机制 |
|---|---|
| 传输 | 两条 TCP 通道，端口 15740，全字段小端 |
| 初始化 | InitCommandRequest/Ack、InitEventRequest/Ack |
| 会话 | OpenSession(0x1002)、必要时 CloseSession(0x1003) |
| 设备 | GetDeviceInfo(0x1001) |
| 对象列表 | GetObjectHandles(0x1007) |
| 元数据 | GetObjectInfo(0x1008) |
| 缩略图 | NIKON_GET_LARGE_THUMB(0x90C4)，回退 GET_THUMB(0x100A) |
| 原文件 | GetPartialObject(0x101B)，按 offset/length 分块 |
| 后续兼容分支 | 遇 0x200F 时再评估 GetPartialObjectEx；不作为首版必做 |
| 后续事件 | ObjectAdded(0x4002) 等仅在自动导入需求成立时启用 |

初版不必实现全部尼康厂商扩展。滤镜相关操作码在取得 iOS 函数/真机证据前，不在本文写成已确认实现。

## 7. KIMI-CODE 原型的使用边界

附件：`../prototype/`

- 原文件：`/Users/chrisz/Documents/projects/nikon-connect/prototype/`
- 三个核心文件已按 SHA-256 对照复制，内容一致。
- 运行零 npm 依赖：`npm run dev`，默认 `http://localhost:7100`。
- 可复用：连接反馈、图库选择、大图查看、传输队列、深色视觉与等宽数字。
- 近期不承诺：Live View、参数拨盘、远程快门、滤镜预览、作品集、地点和年度统计。
- 所有相机、照片、速度和任务状态均为原型模拟数据。

## 8. 未验证项清单

1. 0.1.1 当前工作树尚未按 R1–R4 做真机回归。
2. NEF/NRW、大批量、后台保活、自动重连、跨连接续传均未验收；其中跨连接续传是明确不做，而非待实现。
3. 只有一加 Ace 5 Pro + Nikon Zf 有真机证据；固件版本应补录。
4. 120 个对象逐个 GetObjectInfo 的实际耗时需用真实图库测量，不能预设必须引入批量扩展。
5. Z-Relay iOS 的 NP3 类型存在不等于字段布局、写操作码或机型支持已验证。
6. 尼康云创在线数据接口、授权和同步方式尚无足够证据；当前只规划本地 NP3。
7. 原型是交互稿，不是协议、性能或功能完成证明。
