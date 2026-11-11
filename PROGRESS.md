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

## 2026-11-10 · 0.2 批次 4：图库分页 + 顶栏改名

- PtpIpClient.listPhotos 重构为分页：先 GetObjectHandles 全量句柄（一次事务，轻量），再按页大小 60 分批 GetObjectInfo（最新优先），返回 (总数, 已加载对象, hasMore)；每页耗时以 Log.i("PtpIpClient") 打点（真机验收时记录）。TAG 并入已有 companion object。
- MainViewModel 新增 loadMorePhotos()：以当前 photos 为已加载前缀请求下一页并追加；UiState 新增 loadingMore/hasMore 字段；loadingMore 期间禁止重复触发。
- 图库网格底部新增"加载更多（已加载/总数）"页脚（hasMore 时显示，loadingMore 显示转圈）；总数仍来自 GetObjectHandles。
- 顶栏 "ZF RELAY" → "NIKCONNECT"（0.1 改名遗漏，MainScreen.kt:96）。
- 29 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 修复：已完成任务可重新下载

- 真机验收反馈：删除本地图片后再次点击同一张照片，因队列把 COMPLETED 也去重跳过而无法重新下载。
- DownloadQueue.enqueue 去重规则调整：WAITING/RUNNING 保持跳过；COMPLETED 与 FAILED/CANCELLED 一样重置为新任务重新排队（旧记录直接被新任务覆盖）。
- 测试拆分：原 `enqueue ignores duplicates and completed tasks` 改为 `enqueue ignores waiting duplicates`（执行中重复点击去重），新增 `re-enqueue a completed task downloads it again`（重新下载执行两次、记录仍为 1 条）。
- 30 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 真机反馈修复：已完成任务可重新下载

- 真机验收发现：删除本地图片后无法重新下载同一张——根因是 DownloadQueue.enqueue 把 COMPLETED 也当作"去重跳过"。
- 修复：enqueue 去重只跳过 WAITING/RUNNING；COMPLETED/FAILED/CANCELLED 一律重置 QueueTask 重新入队（旧记录被新任务覆盖）。
- 测试：原"忽略重复与已完成"用例拆为"忽略等待中重复"（gate 控制执行时机）+ 新增"重新入队已完成任务会再次下载"。30 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · RAW（NEF/NRW）下载支持（PRD 0.4 内容提前）

- PhotoDownloader：require 放开为 isJpeg||isRaw；publishJpeg 改名 publishImage，按 photo.isRaw 选 MIME image/x-nikon-nef（JPEG 仍 image/jpeg），仍发布到 Pictures/NiKConnect，保留相机原始扩展名。
- UI 放闸：图库下载按钮/整卡点击/多选全选与批量下载全部放开 RAW（isJpeg||isRaw）。
- 版本注记：PRD 中 RAW 归属 0.4（含分块对照实验与哈希验收）；本次只提前解锁传输与发布路径，0.4 验收项（真机 NEF 哈希一致）仍待真机执行。
- 30 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 图库视图切换（真机反馈：单图太大、浏览选择不便）

- 图库顶栏新增视图切换按钮（GridView 图标 + 大/中/小），三档循环切换 GridCells.Adaptive 最小列宽 168/96/64dp，档位 rememberSaveable 记忆。
- 小档位（compact）：缩略图 1:1 撑满卡片，隐藏文件名/大小/下载按钮；多选与整卡点击下载不受影响。
- 30 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 0.3 批次 1：前台服务保活连接（解决退后台断连）

- 新增 ConnectionKeeperService（前台服务，foregroundServiceType=connectedDevice）：连接成功即启动常驻低优先级通知"NiKConnect 已连接 / 传输保持中"；disconnect()/onCleared() 停止；START_NOT_STICKY。
- PtpIpClient 增加 onDisconnected 回调（close 时若曾连接过触发一次）：ViewModel 挂回调，意外断开时 invalidateSession + 停服务 + 状态置 DISCONNECTED 并提示"相机连接已断开"。
- CameraRepository 新增 clientOrNull() 供挂回调。
- 权限新增 FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE / POST_NOTIFICATIONS（Android 13+ 通知运行时授权待真机确认；minSdk 29，API 34+ 走 connectedDevice 类型 startForeground）。
- 新依赖 androidx.lifecycle:lifecycle-service:2.8.7；versionName 升 0.3.0-alpha（versionCode 3）。
- 测试：新增 2 个 JVM 用例（close 后回调触发、未连接 close 不触发），31 项全过；lintDebug + assembleDebug 通过。
- 待真机验证：退后台 >1 分钟不断连、通知显示、断连后 UI 提示。

## 2026-11-11 · 真机反馈修复批次：通知授权/视图记忆/长按多选

- 通知权限（问题①）：MainActivity 启动时显式请求 POST_NOTIFICATIONS（Android 13+ 此前只声明未请求，导致首次连接不弹窗、通知不显示）。
- 视图档位记忆（问题③）：viewTier 从 GalleryScreen rememberSaveable 上移到 MainViewModel.UiState.galleryViewTier（切 Tab 保留），并持久化到 SharedPreferences（重装/重启保留）；档位切换走 model::setGalleryViewTier。
- 长按多选（问题④第一半）：PhotoCard Card→Modifier.combinedClickable，长按任意图片进入多选并选中该图；滑动手势批量多选暂缓（实现成本高，观察需求后定）。
- 退后台无法下载（问题②）：确认为批次 2 范围（唤醒锁 + Network 绑定），待下一批。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 0.3 批次 2：唤醒锁（退后台/锁屏下载不中断）

- WAKE_LOCK 权限；MainViewModel 持 PARTIAL_WAKE_LOCK（"NiKConnect:download"，30 分钟超时防泄漏）。
- 下载队列 execute 每任务开始 acquire，finally 检查队列无 WAITING/RUNNING 后释放——批量下载/锁屏期间 CPU 不休眠。
- 网络侧无需改动：socket 在批次 4 已绑定 camera Network（network::newSocket），路由不漂移。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · UI 重整批次 1：对齐 nikon-companion-prototype.html

- 主题：设计令牌换为原型深色摄影器材风——主色金棕 #C3A36E、连接绿 #79A88D、bg #12100E、surface #1A1714/#221E1A、文本 #E9E5DC/#969087、描边 #35312C。
- 连接页品牌化：NP 徽标 + "NIKCONNECT / PTP/IP 直连传图" 头部。
- 图库：全部/JPEG/RAW FilterChip 筛选条（rememberSaveable，作用视图与全选/批量下载）；多选改底部选择栏（全选/取消/下载所选 N 项，底部 Surface 常驻），顶栏只留视图切换按钮；长按进入多选保留。
- 传输页：新增统计头（传输中/已完成/失败 三张指标卡）。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。
- 原型中超出 0.x 范围未做：console 实时取景、recipes 滤镜、portfolio 统计、单图查看器（下一批）。

## 2026-11-11 · 0.3 批次 3 + 单图查看器

- 快速重连：连接成功记录 lastHost（SharedPreferences）；连接页在输入框上方显示"重新连接 {host}"按钮；连接成功清除断开错误。
- 单图查看器（原型 viewer 落地）：点击缩略图全屏 Dialog 打开大图（ContentScale.Fit），顶栏文件名+关闭，底部"选择此照片"（进多选并选中）/"下载这张"（触发下载并关闭）。
- 图库整卡点击行为调整：非多选点击改为打开查看器（下载入口移入查看器与卡片下载按钮）；长按多选不变。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 0.3 真机反馈修复：查看器修复 + 已下载角标

- 单图查看器无效果（用户反馈④）：根因是 PhotoCard 的 onClick 仍直接调 onDownload，onOpen 未接线——点卡改开查看器（JPEG/RAW），下载入口移入查看器与多选。
- 已下载状态（用户反馈③）：PhotoCard 缩略图左下角绿色对勾角标（DownloadStatus.COMPLETED 句柄集合）；查看器"下载这张"已下载后禁用并显示"已下载"。
- 断连后传输记录清空（用户反馈③前半）：确认为现状行为（disconnect 清 UiState.downloads）；跨会话的"哪些已下载"持久化记录排入后续（需 MediaStore 查询或本地清单文件）。
- 断开 Wi-Fi 后无重连按钮（用户反馈②）：确认为相机热点随断连关闭所致，非 bug；重连按钮仅在 App 内断连（非 Wi-Fi 消失）后出现。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 跨会话"已下载"持久化（0.3 收尾）

- 连接成功后查询 MediaStore（RELATIVE_PATH = Pictures/NiKConnect）得到已存在文件名集合 downloadedFilenames，存入 UiState。
- 图库角标 = 会话内 COMPLETED 句柄 ∪ 文件名在 MediaStore 中的句柄——新会话连上相机后，之前下载过的照片也显示绿色对勾。
- 查看器"下载这张"同样合并两个来源；下载过即禁用显示"已下载"。
- 边界：按 DISPLAY_NAME 精确匹配（相机原始文件名）；用户在系统相册删图后 MediaStore 查不到，角标消失，重新下载行为正确。
- 31 项 JVM 测试全过；lintDebug + assembleDebug 通过。

## 2026-11-11 · 0.2/0.3 验收记录归档

- 新增 docs/acceptance/0.2.md、0.3.md（按 TEMPLATE.md 逐项填写；未执行项如实标注：哈希对照与取消残片留待 0.4 一并补做）。
- 0.3 核心目标（退后台/锁屏不断连）真机验证通过。

## 2026-11-11 · 0.4 准备：版本号 0.4.0-alpha + 验收模板

- RAW 传输/发布路径已于此前提交（8390461 对应 rebase 后链）；0.4 剩余为真机验收项。
- versionName 0.4.0-alpha（versionCode 4）。
- 新增 docs/acceptance/0.4.md：JPEG+NEF 哈希样本表（含手机端操作指引）、分块基线对照表、0.2 遗留补做项（JPEG 哈希+取消残片）。
- 发现 PRD 7.4 提到 Wi-Fi Lock，代码中未实现（0.3 只做了 WakeLock+Network 绑定）——待评估是否补。

## 2026-11-11 · 尼康云创色彩方案调研

- 调研报告：docs/references/NIKON_CLOUD_RESEARCH.md。
- 核心结论：尼康云创 Web 端无 NP3 直接下载端点（产品形态=收藏→发送到相机）；ZRelay 的做法是走相机端 PTP（GetPicCtrlCapability/GetPicCtrlData + SetDevicePropValueEx），不调云 API、不解析 NP3 文件。
- NP3 格式已破解（NCP\0 魔数 TLV），有开源解析库（ssssota/nikon-flexible-color-picture-control）与 142 官方 NP3 语料（shouryan01/Nikon-Recipes）。
- B0/B1 建议路线：相机端 PTP + NP3 本地导入导出，不走云 API。
- 0.4 进展：用户确认"取消下载无残片"（补做项③通过）。

## 2026-11-11 · B0 探针简化模型确认 + JVM 层 PTP 探针封装

- 用户确认 B0 简化路线：不做 NP3 解析，App 仅做云创方案浏览（元数据）+ 相机端 PTP 方案读写；传输由云创↔相机完成。
- PtpPackets.kt：OpCode 补 GET_DEVICE_PROP_DESC/VALUE/SET_DEVICE_PROP_VALUE(0x1014/15/16)、NIKON_ADVANCED_TRANSFER(0x9201 候选)、NIKON_GET_VENDOR_PROP_CODES(0x9028 候选)；新增 PropCode.NIKON_VENDOR_BASE=0xD000。
- PtpIpClient.kt：probeCommand(opcode,params)（失败记 responseCode 不抛）、probeVendorPropCodes()、readPropValue(propCode)；ProbeResult 数据类。
- 测试：LoopbackTest 增 3 用例（探针成功带数据预览/失败记 0x2005/属性码参数下发），fakeProbeCamera 假相机按 opcode 映射应答。全套测试通过。
- 真机待验：opcode 实际值（0x9028/0x9201 是候选）、云创推送触发方式（相机连云创 Wi-Fi vs 主动命令）。

## 2026-11-11 · B0 batch 2：云创 API 客户端（JVM 层）

- 用户拍板简化模型：App 只做云创方案浏览+收藏（替换相机注册列表），传输由相机↔云创完成（相机连家庭路由 Wi-Fi 自动同步，外部文档已确认该机制，单 Wi-Fi 冲突坑不存在）；云创列表需登录态。
- 新增 cloud/NikonCloudClient.kt：accessToken+guid 构造；listRecipes（POST /bff/recipe，header access_token）、addRecipesToBook（POST /publicrecipe/{guid}，Bearer）、listCameras（GET /camera/{guid}）；parseRecipeList 形状适配（recipes/data.recipes/result.recipes 三层包裹候选，id/recipeId、name/recipeName/title 字段兼容——真实形状待登录后核对）。
- 依赖：kotlinx-serialization-json 1.7.3 + 序列化插件 2.0.21。
- 新增 5 个 JVM 测试（形状解析/未知形状/非法 JSON/缺 id 跳过），全套 39 项 0 失败；lint+assemble 通过。
- 待做：登录态接入（WebView 抓 token）、云创浏览 UI、探针入口（真机 opcode）。

## 2026-11-11 · B0 batch 2 完成：云创登录 + 方案浏览 UI（提交待查）

- 新增 CloudLoginScreen：WebView 打开云创登录页，尝试从 localStorage（kc-c0004 前缀）提取 keycloak token；失败可"手动输入"粘贴 token/guid（云创 Web F12 → Local Storage 取）。
- MainViewModel：cloudToken/cloudGuid/cloudRecipes/cloudLoading/cloudError 状态；onCloudLogin 持久化（ui_prefs/cloud_token, cloud_guid）+ 自动拉列表；cloudLogout；addRecipesToCamera 收藏回调。
- MainScreen：底部导航加第三 tab「云创」（Palette 图标）；CloudRecipesScreen——未登录→登录按钮；登录后网格浏览（Adaptive 96dp，多选，已选 N 项）→"收藏到相机"→addRecipesToBook→toast"相机下次连云创 Wi-Fi 时自动同步"；错误卡+重试。
- 39 项 JVM 测试全过；lint+assemble 通过。
- 真实 API 响应形状待用户登录后核对（parseRecipeList 三层适配，未识别形状会明确报错并显示原始片段）。

## 2026-11-11 · 云创页独立化（bug 修复）

- 用户反馈：云创页与相机连接绑定——未连接时看不到云创 tab；连接相机（Wi-Fi 直连、无互联网）时 WebView 打不开登录页。
- 修复：底部导航全程显示「云创」tab（照片/传输仍需连接相机）；未连接时选云创 tab 直接进入 CloudRecipesScreen，不经过 ConnectionScreen。云创功能独立于相机连接使用（登录/浏览/收藏走手机自身网络）。

## 开放问题

1. 真实图库规模（120 / 300 / 更大）→ 决定 0.2 分页与批量元数据优先级。
2. 后台下载是否当前痛点 → 决定 0.3 排期。
3. RAW 文件类型与大小分布 → 0.4 样本。
4. NP3 写字段与机型能力（B0 探针前不排期）。
