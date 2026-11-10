# NiKConnect 开发进展与问题记录

最后更新：2026-11-10

> 本文件是唯一的活动进展记录。每完成一个开发/测试批次就更新；问题按"发现→分析→处置"记录。历史归档见 [docs/references/ZF_Relay_真机验证记录.md](docs/references/ZF_Relay_真机验证记录.md)。

## 当前状态

- **版本**：0.1.1（真机已验收，用户 2026-11-09 确认）
- **真机基线**：一加 Ace 5 Pro + Nikon Zf
- **下一阶段**：0.2 基础导出（多选、串行队列、单任务取消、图库分页）

## 版本历史

| 版本 | 内容 | 真机 | 验收记录 |
|---|---|---|---|
| 0.1.0 | PTP/IP 连接、最近照片、缩略图、单张 JPEG 下载 | ✅ 用户验收 | [docs/acceptance/0.1.0.md](docs/acceptance/0.1.0.md) |
| 0.1.1 | R1–R4 修复：会话隔离、退出不阻塞、断线同步 UI、跨连接残片清理；19 项 JVM 测试 | ✅ 用户确认（2026-11-09） | [docs/acceptance/0.1.1.md](docs/acceptance/0.1.1.md) |

## 2026-11-10 · 0.2 批次 1：串行下载队列（开发中）

- 新增 `ui/DownloadQueue`：`QueueStatus{WAITING,RUNNING,COMPLETED,FAILED,CANCELLED}`、`QueueTask`、`tasks: StateFlow<List<QueueTask>>`。
- 行为：FIFO 串行（PRD D-06，一次只向相机发一个任务）；失败不阻塞后续项；单任务 cancel（等待中直接标记，执行中只取消执行体、泵存活继续下一项）；cancelAll 绑定会话结束，未完成任务标 CANCELLED 后不再接受新任务；enqueue 去重（等待/执行/完成跳过，失败/取消重置重排）。
- 结构：pump 协程仅做通道 for 循环 + join 当前任务；每任务以 `pumpScope.launch` 子协程执行（SupervisorJob 隔离），cancel(RUNNING) 只取消子协程——避免早期版本 pump.cancel() 连带杀掉整个队列的问题。
- 测试：`DownloadQueueTest` 10 项 JVM 测试全绿（FIFO 顺序、失败不阻塞、进度字节、取消执行中任务继续队列、取消等待任务跳过、去重、失败重试、cancelAll 停队列、onError 回调、终态保留）。全套 29 项测试通过，lintDebug + assembleDebug 通过。
- 待办：接入 MainViewModel/TransferScreen（批次 2）。

## 2026-11-10 · 仓库规整（无功能改动）

- 0.1.1 未提交工作树提交为基线 `e07e137`。
- 清理：删除仓库内历史 APK、错误截图、ui-prototype、旧 prototype 副本、`.android-build`。
- 文档并入：NiKConnect 独立文档目录（PRD、references、prototype、验收模板）合并到本仓 `docs/`；删除内容重复的 `docs/NEXT_STEPS.md`（与 references 副本一致）。
- 改名：ZF Relay → NiKConnect。包名 `com.chrisz.zfrelay` → `com.chrisz.nikconnect`；app id、`app_name`、主题名 `Theme.ZfRelay` → `Theme.NiKConnect`、Compose 函数 `ZfRelayTheme/ZfRelayApp/ZfRelayContent` → `NiKConnect*`、MediaStore 目录 `Pictures/ZF Relay` → `Pictures/NiKConnect`、rootProject 名。
- 新增工程规范 `CLAUDE.md`（AGENTS.md 符号链接保留）与验收记录模板 `docs/acceptance/TEMPLATE.md`。

### 已知影响

- **改名后为新应用身份**：applicationId 变化，新 APK 安装不会覆盖旧 "ZF Relay"，需手动卸载旧应用；旧应用已发布的相册文件留在 `Pictures/ZF Relay`，新文件写入 `Pictures/NiKConnect`。

## 2026-11-10 · 0.2 批次 2：ViewModel/传输页接入下载队列

- MainViewModel 弃用旧的 downloads 自管状态：新增 `downloader` 快照字段（每次连接成功刷新），`DownloadQueue` 长期挂在 viewModelScope，`queue.tasks` collect 进 UiState.downloads。
- DownloadStatus 扩展为 WAITING/DOWNLOADING/COMPLETED/FAILED/CANCELLED，映射自 QueueStatus。
- 传输页（MainScreen.kt TransferScreen）新增"排队中/已取消"状态显示；等待/执行中任务有"取消"按钮（queue.cancel）；失败/取消任务有"重试"按钮（重新 enqueue，队列去重逻辑自动重置）。
- 图库下载按钮不再受"当前有下载"限制——串行队列允许排队；重复点击同一张由队列去重。
- disconnect/invalidateSession 时 downloader 置空并 cancelAll：等待/执行中任务标 CANCELLED，COMPLETED/FAILED 记录保留。
- 29 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-10 · 0.2 批次 3：图库多选模式

- GalleryScreen 新增多选模式：顶栏"多选"按钮进入（标题变"已选 N 项"），再点"取消"退出并清空选择。
- PhotoCard 重构：Card 整卡可点（多选模式下点卡切换选中；普通模式点卡直接下载 JPEG），多选时右上角 CheckCircle/RadioButtonUnchecked 指示，选中卡用 secondaryContainer 高亮；多选模式隐藏单个下载按钮。
- 底部固定下载栏（Surface tonalElevation）：全选/取消全选（仅统计 JPEG），"下载所选 (N)"按钮 → MainViewModel.downloadAll（过滤 RAW 后 enqueue，队列去重+串行执行）。
- 下载完成后自动退出多选模式；selected 以 handle 集合保存（rememberSaveable + photos key）。
- 29 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 开放问题

1. 真实图库规模（120 / 300 / 更大）→ 决定 0.2 分页与批量元数据优先级。
2. 后台下载是否当前痛点 → 决定 0.3 排期。
3. RAW 文件类型与大小分布 → 0.4 样本。
4. NP3 写字段与机型能力（B0 探针前不排期）。
