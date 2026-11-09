# ZRelay iOS 版：REA 逆向可行性验证

## 结论

**可以执行，且已经用 REA 实际跑通。** 当前样本不仅能读取 IPA/APP 清单，还能恢复 Swift 业务类型、对象字段、协议/类分派关系，并用 Ghidra 反编译 arm64 业务函数。

它适合继续做两件事：

1. 提取 PTP/IP 连接、图库枚举、缩略图与分块下载实现，为自研客户端提供协议参照。
2. 提取 iOS 版新增的 NP3 / PictureControl 滤镜链路，包括导入、槽位读取、写入、调参与预览。

## 样本身份

- 外层路径：`/Applications/ZRelayMVP.app`
- 主程序：`/Applications/ZRelayMVP.app/Wrapper/ZRelayMVP.app/ZRelayMVP`
- Bundle ID：`com.geekmanlab.zrelay`
- 版本：1.1（build 335）
- 格式：thin Mach-O，arm64
- 大小：8,550,432 bytes
- SHA-256：`3158eaf99564d4a46b87eebc2d4b81fa5eb9a85daf2e951fac7ed955652f89b5`
- 签名：Apple App Store，Team ID `43XKC5B3K8`
- 最低系统：Info.plist 声明 iOS 18.0；Mach-O build minos 为 17.0

## REA 已完成的验证

### 1. 包结构与签名

REA `inspect_artifact` 完整枚举 33 个节点、34 个 occurrence、32 条关系，包含 Info.plist、Assets.car、场景图片、SC_Info 和签名数据。

REA `inspect_signature` 确认为 App Store 正式签名。

### 2. Mach-O 与加密范围

REA `inspect_macho` 返回 67 个 load commands、47 个依赖和完整段/节信息。

`LC_ENCRYPTION_INFO_64`：

- `cryptoff = 1126400`（`0x113000`）
- `cryptsize = 4096`
- `cryptid = 1`
- `LC_MAIN entryoff = 1129124`，位于该加密页内

因此应精确描述为：**入口所在的 4 KiB 页被 FairPlay 标记加密，其他代码与 Swift/Objective-C 元数据仍然可读。** 如需覆盖入口页，再从真实 iOS 设备取得运行时解密后的 Mach-O。

### 3. Swift 业务结构

REA `inspect-native-dispatch-metadata --max-records 20000` 成功解码：

- 606 / 606 个 Swift declarations（complete）
- 134 个 Objective-C 类
- 625 个 ivar
- 901 条 conformances
- 97 个 dispatch slots
- 6,208 个 relative pointers

已确认的核心模块：

- 协议：`PtpIpClient`、`PtpChannel`、`PtpConstants`、`PtpPacketType`、`PtpOperationCode`、`PtpResponseCode`、`PtpEventCode`、`PtpParsers`
- 图库：`GalleryController`、`GalleryItem`、`GalleryDataset`、`GalleryPage`、`GalleryThumbnailCache`
- 下载：`DownloadService`、`DownloadTask`、`DownloadTaskStore`、`DownloadEvent`
- 滤镜：`Np3Document`、`Np3Record`、`Np3FileReader`、`Np3ImportService`、`PictureControlCodec`、`PictureControlClient`、`PictureControlSession`、`PictureControlStore`、`PictureControlViewModel`、`PictureControlPreviewRenderer`
- 槽位与写入：`PictureControlSlotRead`、`PictureControlSlotWriteReport`、`PictureControlPendingWrite`、`PtpPictureControlCamera`

`PtpIpClient` 的已恢复字段包括 host、port、initiatorGUID、initiatorName、commandQueue/eventQueue、commandChannel/eventChannel、transactionCounter、sessionOpen、applicationMode、eventRecords、deviceInfo 等。

这说明 iOS 版不是只有连接/下载壳：**NP3 导入、滤镜槽位读写、调参与预览模块已经存在。**

### 4. 函数级反编译

使用 REA CLI + Ghidra 12.1.4 对地址 `0x100232c94` 的业务函数进行分析成功。该地址来自 `PhotosLibrarySaver: PhotosLibraryWriting` 的 Swift witness table。

Ghidra 返回：

```c
void FUN_100232c94(undefined8 param_1, undefined8 param_2)
{
  FUN_100232ca4(param_1, param_2, 2);
  return;
}
```

对应汇编：

```asm
0x100232c94: mov w2, #0x2
0x100232c98: b   0x100232ca4
```

继续沿调用边反编译 `0x100232ca4`，得到实际的照片权限判断逻辑：

```c
IVar1 = PHPhotoLibrary::authorizationStatusForAccessLevel_(
    (ID)&_OBJC_CLASS___PHPhotoLibrary,
    "authorizationStatusForAccessLevel:",
    param_3);
uVar2 = (undefined4)(0x403010200 >> ((IVar1 & 7) << 3));
if (4 < IVar1) uVar2 = 1;
return uVar2;
```

同时返回了函数体范围、调用边、引用、基本块、Swift calling convention 与 high-pcode value flow。由此确认：**REA 在该 iOS 样本上已走到函数级反编译，而不只是文件清单识别。**

## 推荐下一步

1. 从 `PtpOperationCode` / `PtpParsers` 入手，恢复操作码表、包结构与响应解析。
2. 跟踪 `PtpIpClient` 的 command/event channel，恢复连接、OpenSession、GetObjectHandles、GetObjectInfo、GetThumb 与 GetPartialObject 调用链。
3. 以 `PictureControlCodec` 和 `PtpPictureControlCamera` 为主目标，恢复 NP3 字段映射、槽位读取与写入事务。
4. 用 Android 反编译结果交叉验证标准 PTP 部分；iOS 端重点补 PictureControl，避免重复分析已经掌握的传输逻辑。

## 限制与未验证项

- Swift 优化与 stripped symbols 使函数名大量变成 `FUN_*`；需要依靠 Swift metadata、引用和调用图恢复语义，反编译可读性低于 Android Java 层。
- 606 个 Swift 类型/声明已完整枚举，不代表 606 个业务函数都已还原。
- FairPlay 的 4 KiB 入口页尚未通过真机运行时 dump 补齐。
- Hopper 通道受 macOS Automation 权限限制；本次函数级结果来自 REA + Ghidra。
- 尚未连接真实 Nikon 相机验证协议行为，也未执行或修改原 App。

## 产物

- `dispatch_metadata_full.json`：完整 Swift/Objective-C 元数据与分派信息
- `rea_search_ptp.json`：Ghidra 搜索验证记录
- `function_photos_saver.json`：函数级反编译 Evidence
- `decompile_photos_saver_impl.json`：后续实现函数反编译输出
- `ZRelayMVP.rea-snapshot.json`：REA 分析快照
