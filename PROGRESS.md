# NiKConnect 开发进展与问题记录

最后更新：2026-11-14

> 本文件是唯一的活动进展记录。每完成一个开发/测试批次就更新；问题按"发现→分析→处置"记录。历史归档见 [docs/references/ZF_Relay_真机验证记录.md](docs/references/ZF_Relay_真机验证记录.md)。

## 当前状态

- **版本**：0.6.11-alpha（0.6 批次 2：新增「收藏」——把官方方案复制进账号的收藏夹，之后才进可分配池；文案统一为「收藏 / 收藏夹」；JVM 91 项测试通过）
- **真机基线**：一加 Ace 5 Pro + Nikon Zf
- **未关闭验证**：0.6.9 删除后位置保持、重新加回不再 400；0.6.10 收藏官方方案后能否在账号页「＋」里挑到并排进槽位（均待用户真机验证）；0.4 RAW 哈希/分块对照；0.5 遗留三项（在线刷新频控、详情参数与官网逐项对照、蜂窝网络封面流量）；0.6 只读部分的细节项（槽位号与网页端逐位对照、已占用/空位区分、可分配方案数、token 长时失效）见 [docs/acceptance/0.6.md](docs/acceptance/0.6.md)；0.6 批次 2（拖拽排布、收藏到书）未开工

## 版本历史

| 版本 | 内容 | 真机 | 验收记录 |
|---|---|---|---|
| 0.1.0 | PTP/IP 连接、最近照片、缩略图、单张 JPEG 下载 | ✅ 用户验收 | [docs/acceptance/0.1.0.md](docs/acceptance/0.1.0.md) |
| 0.1.1 | R1–R4 修复：会话隔离、退出不阻塞、断线同步 UI、跨连接残片清理；19 项 JVM 测试 | ✅ 用户确认（2026-11-09） | [docs/acceptance/0.1.1.md](docs/acceptance/0.1.1.md) |
| 0.2 | 串行下载队列、传输页取消/重试、图库多选、图库分页 | ✅ 用户确认 | [docs/acceptance/0.2.md](docs/acceptance/0.2.md) |
| 0.3 | 前台服务保活、唤醒锁、UI 重整、单图查看器、跨会话已下载角标 | ✅ 用户确认 | [docs/acceptance/0.3.md](docs/acceptance/0.3.md) |
| 0.4 | RAW（NEF/NRW）传输与发布、图库三档视图 | ⏳ 哈希/分块对照待补 | [docs/acceptance/0.4.md](docs/acceptance/0.4.md) |
| 0.5 | 云创原生浏览页（目录/搜索/排序/详情/参数表，零登录） | ✅ 用户确认（2026-11-13） | [docs/acceptance/0.5.md](docs/acceptance/0.5.md) |
| 0.5.1 | token 抓取改为 WebView 拦截接口请求头（HttpOnly 也能拿）、网格滚动状态归位入口、登录页反馈 | ✅ 用户确认（2026-11-13） | 并入 0.5 记录 |
| 0.5.2 | guid 多源抓取（header/JWT/JS/URL）、云创页去叠层重做、默认排序改最新在前、入口强制回顶 | ✅ 用户确认（2026-11-13） | 并入 0.5 记录 |
| 0.6.0（批次 1） | 云创账号页：已注册相机列表、9 个云创槽位只读查看、可分配方案数；HTTP 传输收敛到 `CloudHttp` 单一入口 | ⏳ 待真机 | — |

## 2026-11-10 · 0.2 批次 1：串行下载队列（已完成）

- 新增 `ui/DownloadQueue`：`QueueStatus{WAITING,RUNNING,COMPLETED,FAILED,CANCELLED}`、`QueueTask`、`tasks: StateFlow<List<QueueTask>>`。
- 行为：FIFO 串行（PRD D-06，一次只向相机发一个任务）；失败不阻塞后续项；单任务 cancel（等待中直接标记，执行中只取消执行体、泵存活继续下一项）；cancelAll 绑定会话结束，未完成任务标 CANCELLED 后不再接受新任务；enqueue 去重（等待/执行/完成跳过，失败/取消重置重排）。
- 结构：pump 协程仅做通道 for 循环 + join 当前任务；每任务以 `pumpScope.launch` 子协程执行（SupervisorJob 隔离），cancel(RUNNING) 只取消子协程——避免早期版本 pump.cancel() 连带杀掉整个队列的问题。
- 测试：`DownloadQueueTest` 10 项 JVM 测试全绿（FIFO 顺序、失败不阻塞、进度字节、取消执行中任务继续队列、取消等待任务跳过、去重、失败重试、cancelAll 停队列、onError 回调、终态保留）。全套 29 项测试通过，lintDebug + assembleDebug 通过。
- 已按后续批次完成 ViewModel/TransferScreen 接入；批次 2–4 记录见下文。

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
- MainViewModel：cloudToken/cloudGuid/cloudRecipes/cloudLoading/cloudError 状态；此前 token 明文保存在 ui_prefs，现改为仅内存持有，guid 可保留；自动拉列表、退出和收藏回调。
- MainScreen：底部导航加第三 tab「云创」（Palette 图标）；CloudRecipesScreen——未登录→登录按钮；登录后网格浏览（Adaptive 96dp，多选，已选 N 项）→"收藏到相机"→addRecipesToBook→toast"相机下次连云创 Wi-Fi 时自动同步"；错误卡+重试。
- 39 项 JVM 测试全过；lint+assemble 通过。
- 真实 API 响应形状待用户登录后核对（parseRecipeList 三层适配，未识别形状会明确报错并显示原始片段）。

## 2026-11-11 · 云创页独立化（bug 修复）

- 用户反馈：云创页与相机连接绑定——未连接时看不到云创 tab；连接相机（Wi-Fi 直连、无互联网）时 WebView 打不开登录页。
- 修复：底部导航全程显示「云创」tab（照片/传输仍需连接相机）；未连接时选云创 tab 直接进入 CloudRecipesScreen，不经过 ConnectionScreen。云创功能独立于相机连接使用（登录/浏览/收藏走手机自身网络）。

## 2026-11-11 · 云创登录、队列会话与图库分页修复

- CloudLoginScreen：WebView 捕获 token 后留在登录页展示，提供复制 token 与显式“返回 App”；手动输入支持填写 guid；页面退出时销毁 WebView。
- MainViewModel：token 改为仅进程内存持有，删除旧明文偏好；guid 保留。连接切换保留云创状态，重建下载队列并取消旧队列；新队列状态重新接入 UI；销毁 ViewModel 时取消队列。
- PtpIpClient.listPhotos 改为按已消费句柄偏移返回单页；ViewModel 基于实际句柄数推进 offset，过滤后追加可展示对象，避免格式过滤导致分页重复/遗漏。
- 云创列表/收藏请求在凭据变化时忽略旧结果并正确传播协程取消；关闭 Android 自动备份，避免 App 数据被系统备份。
- 验证：40 项 JVM 测试全过；`:app:lintDebug`、`:app:assembleDebug` 通过（2026-11-11）；`git diff --check` 通过。真机回归仍待执行。

## 2026-11-12 · 0.5 批次 1：云创原生浏览页（替代 WebView 壳）

- 背景：用户反馈云创网页移动端未优化，要求按移动端设计规范重做展示界面；接口面已实测（docs/references/NIKON_CLOUD_RESEARCH.md §7/§8），目录与详情均零登录可取。
- 新增 `cloud/CloudCatalogClient`：目录 `POST {CWP}/bff/recipe/release`（sortType=0 带签名封面 URL）、详情 `GET {IR}/publicrecipe/0000000000000001/{id}?sortType=0&isPagenation=false`；Mutex 串行限速（默认 1.2s/请求）+ `E01_0701` 频控退避重试 3 次（3s×attempt）；`parseCatalog`/`parseDetail` 为纯函数供 JVM 测试。
- 新增 `ui/CloudCatalogViewModel`：先读 `assets/cloud/catalog_seed.json`（144 条离线索引，无封面）上屏，再后台刷新补封面；刷新失败只置错误、不清空已有列表；搜索（名称/英文代号）、排序（推荐/最新/热门）、仅 NEW 为派生过滤；详情单独加载，失败不污染列表。
- 新增 `ui/RemoteImage`：`produceState` 异步解码 + 内存 LruCache 24MB + `cacheDir/cloud_covers/<sha256>.jpg` 磁盘缓存（封面签名 URL 仅 1 小时有效，故缓存按 URL 哈希分文件）。
- 新增 `ui/CloudBrowseScreen`：标题「创意色彩灵感库」+「尼康云创 · N 个方案」；搜索框（14dp 圆角、surfaceVariant 填充）；横向 FilterChip 排序条；`PullToRefreshBox` + 双列网格（卡片 16dp 圆角、1.5:1 封面、底部渐变压名称、NEW 金色角标、英文代号 + 点赞数）；6 格骨架屏与空态；详情页含头图、作者/基准/设置名 chip、「色彩说明」正文、双列参数表（10 项优化校准）、样片机身/镜头/EXIF、上一/下一方案切换、云创同步说明脚注。触控目标统一 ≥48dp。
- 清理：删除旧 `CloudRecipesScreen`（多选收藏 UI 与 WebView 内嵌列表）；`MainViewModel` 去掉 `loadCloudRecipes`/`addRecipesToCamera` 与 `cloudRecipes`/`cloudLoading`/`cloudError`，只保留 token/guid；旧 `cloud/NikonCloudClient` 暂留（批次 2 账号接口用，当前无引用）。
- 测试：新增 `CloudCatalogClientTest` 9 项（目录含/无封面、缺 id 跳过、body 包裹与 error_code、详情参数与 EXIF 抽取、`-`/`0` 视为未设置、非法 JSON、缺 id）；全套 51 项 JVM 测试全过，lintDebug 0 error/6 warning（均为既有项），assembleDebug 通过。
- 版本：0.5.0-alpha（versionCode 5）。
- 待真机：封面加载与滚动性能、详情频控退避在真实网络下的表现、离线种子首屏与在线刷新的衔接。

## 2026-11-12 · 文档一致性更新（无代码改动）

- 起因：用户要求检查 0.5 落地后文档是否同步。
- `docs/PRD.md` v1.1→v1.2：改写 §3.1/§3.2 中"Git HEAD 仍是 0.1.0 的 2bc3f43""0.1.1 修复尚未真机回归"等严重过期表述；§6 路线图补 0.5 行与 0.6（计划中）行；§7.5 新增 C-11（零登录目录浏览）/C-12（详情参数与 EXIF）/C-13（频控退避与失败不清列表）；§8.1 补云创页界面条目；§9.1 新增 `cloud/` 模块行；§12 新增 0.5 真机验收行。
- `docs/README.md` 重写索引：补 `NIKON_CLOUD_RESEARCH.md`、`research/np3-format.md`、`research/np3-generation-report.md`、`data/recipes/`（list/details/generated）；维护规则补"本机无 pandoc/soffice 时先记未同步，不提交过期 docx"。
- `README.md` 删掉与现状冲突的"无账号、无云端、无服务端"，改为照片链路本地、云创浏览为独立能力的表述；真机基线补 0.2–0.4 已验收与 0.5 待验。
- `docs/references/TECHNICAL_REFERENCES.md` §2.1 更新仓库路径与基线、§8 未验证项按现状重写，新增 §9 云创与 NP3 证据表（7 条，含证据等级与文件位置）。
- `CLAUDE.md`（`AGENTS.md` 同源）分层表新增「云创 | `cloud/`」行，`ui/` 禁止项加"不发 HTTP（经 `cloud/`）"，硬约束新增云创频控串行条，版本映射补 0.5/0.6。
- 未同步：`docs/NiKConnect_PRD.docx` 与 `docs/NiKConnect_参考附件.zip` 仍是旧版——本机 `which pandoc soffice` 均无结果，无法就地生成；需要时再装工具重出，或由用户用 Word/WPS 手工导出。

## 2026-11-12 · 0.5.1：token 抓取重做与列表入口位置

真机反馈三个问题（用户 21:24）：进入云创 tab 列表停在最后一行；登录后仍抓不到 token、点「提取 token」没反应；UI 里的「相机注册 ID（guid）」看不懂。

- **token 抓不到**：旧实现只在 WebView 页面 JS 环境里扫 localStorage/sessionStorage/cookie。尼康云创把 token 放在 HttpOnly cookie 或内存里时 JS 读不到，扫描必然返回空；而返回空时旧代码没有任何提示，所以「点了没作用」。改为在 `WebViewClient.shouldInterceptRequest` 里读请求头：页面自身调云创接口时 `access_token`（或 `Authorization: Bearer`）一定是明文，即使 cookie 是 HttpOnly 也能截到；同时从 URL 里提取 26 位 ULID 作为账号 guid；`onPageStarted` 再兜一层 `#access_token=`/`?access_token=`。JS 扫描降级为次要来源，未命中时弹提示「请先在网页里点开任意方案」。
- **列表停在最后一行**：代码里没有任何滚动到底的调用。最可能是 `rememberLazyGridState`（Compose 内部即 `rememberSaveable`）的状态被跨组合恢复。改为在 `CloudTab` 用普通 `remember { LazyGridState() }` 持有并下传：进出 tab 必从第一条开始，查看详情再返回仍保留位置。
- **「相机注册 ID」文案错误**：云创的 `guid` 不是相机 ID，是**账号**维度标识（26 位 ULID，出现在 `/camera/{guid}/`、`/publicrecipe/{guid}` 路径里）；0.5 的零登录浏览根本不需要它，只在 0.6 的账号接口（收藏、相机槽位）才用。文案改为「账号 guid（可留空)」，并说明来源。
- **界面文案纠错**：旧文案写「浏览器 F12 → Application → Local Storage」「抓包 URL 里的 26 位 ULID」——这是桌面浏览器思路，手机上根本没有开发者工具。改为「粘贴 access_token（一般无需手动，登录后会自动捕获）」「账号 guid（可留空，自动从接口地址提取）」；登录页新增状态行「自动捕获：等待接口请求…／成功（token 前 8 位…）」+「重新加载」按钮，引导语改成「登录后在网页里点开任意方案即可，无需手动操作」。
- 验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（51 项）、`:app:lintDebug`（0 error/6 warning）、`:app:assembleDebug`（--offline）通过；版本 0.5.1-alpha（versionCode 6）。真机待验：token 是否能在登录后自动出现、列表入口是否回到顶部。

## 2026-11-12 · 0.5.2：guid 抓取、界面去叠层、默认排序

真机反馈三条（用户 22:51）：token 能抓到但 guid 抓不到；云创 UI「很难看，太多叠层」；进入滤镜页仍显示在最下方，怀疑「最老的滤镜 id 是 1」。

- **guid 抓不到**：旧实现只从接口 URL 里提 26 位 ULID，账号接口路径里没有 ULID 时就永远空。现在四个来源依次尝试并各自独立补写（token 与 guid 分开存，不再要求同时命中）：
  1. 请求头：键为 `guid`/`userid` 或含 `guid` 的值，须匹配 ULID 或 UUID；
  2. JWT：`Base64 URL_SAFE` 解 payload，依次读 `guid`/`userGuid`/`user_guid`/`userId`/`user_id`/`uid`/`sub`；
  3. 接口 URL：先 ULID（26 位 Crockford Base32）再 UUID（36 位带横线）；
  4. 页面 JS：扫 localStorage/sessionStorage 里键含 `guid` 的值与 JSON 里的 `userGuid|guid|userId` 字段。
- **UI 叠层过多**：重写 `app/src/main/java/com/chrisz/nikconnect/ui/CloudBrowseScreen.kt`。卡片去掉封面上的渐变蒙版、叠字标题、NEW 角标，只留「封面 + 两行文字」，NEW 用一个 6dp 金点表示；工具栏压成单行「云创 + 数量 + 账号 + 刷新」；排序从 FilterChip 胶囊改成纯文字（选中主色加粗）；详情页去掉盖在头图上的半透明圆形返回按钮，改为常规顶栏 `DetailTopBar`；头图改为带外边距的圆角图；参数从「每项一张卡」改为一张平铺两列表（发丝分割线）；作者/baseName/settingName 三个 chip 合成一行次要色文字。
- **显示在最下方 / id=1 猜想**：id 不是数字而是 26 位 ULID，官方接口返回的目录顺序是**按上架时间升序**（第一条 `01JAY529N3SBMRS5ENMYH6CA6Q Negative Film Yellow 01a` 是 2024-10-24，最后一条 `01M3P67BB9NR1QMJHMQ870XW52 Mate Film by Choi matae` 是 2026-10-14）——所以「最老的在前」这个直觉是对的，列表天然从最老那头开始。两处处置：默认排序由「推荐（官方原序）」改为「最新」（按 `publicDate` 降序），进入即最新在前；同时在 `CloudBrowseContent` 加 `LaunchedEffect(Unit) { gridState.scrollToItem(0) }`，进入/返回列表一律落回顶部。
- 验证：`:app:testDebugUnitTest`（51 项 0 失败）、`:app:lintDebug`（0 error / 6 warning，均为既有项）、`:app:assembleDebug`（--offline）全部通过；版本 0.5.2-alpha（versionCode 7）；APK SHA-256 `de947f8481c8e6d987de869c1b13f9f531757f6ed6046de34e32f679b6ac48a1`。

## 2026-11-13 · 0.5 验收归档

用户 `9246ea2`（0.5.2-alpha）真机确认「这版没问题了」。三轮反馈（0.5.0 抓不到 token / 停在最后一行 / guid 看不懂 → 0.5.1 token 拿到但 guid 空 / UI 叠层 → 0.5.2 全部收敛）已写入 [docs/acceptance/0.5.md](docs/acceptance/0.5.md)。记录同时列出四项未确认项（在线刷新频控、详情参数与官网逐项对照、guid 实际命中来源、蜂窝网络封面流量），不按推测填通过。

## 2026-11-13 · 0.6 批次 1：云创账号只读核对

用户确认 guid 已抓到，按 PRD 进入 0.6。批次 1 只做**读**，因为 `IF_IR_R_C001`（相机列表）、`IF_IR_R_C002`（槽位）的响应形状只在 Web chunk 里见过调用、没在真机看过返回体，先拿到真实响应再决定写路径的字段。

- **传输收敛**：新增 `app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt`，把串行限速（1.2s）、`E01_0701` 退避（3s×attempt，最多 3 次）、401/403 区分、`body` 包裹解包统一到一处；`CloudCatalogClient` 改为持有一个 `CloudHttp`，删掉自带的 transport 与 `NikonCloudClient`（147 行的旧类，含未被调用的 `parseRecipeList`）。这落实了 CLAUDE.md「新增端点复用同一入口」的约束。
- **新客户端**：`app/src/main/java/com/chrisz/nikconnect/cloud/CloudAccountClient.kt`
  - `listCameras()` → `GET $IR_HOST/camera/{guid}/`（Bearer，IF_IR_R_C001）
  - `cameraSlots(cameraId)` → `GET $IR_HOST/camera/{guid}/{cameraId}`（Bearer，IF_IR_R_C002）
  - `assignableRecipes()` → `POST $CWP_HOST/bff/recipe/camera`（header `access_token`，body `{"country":"CN"}`，IF_FR100_H13）
  - 解析一律「按特征嗅探」：`findObjectArray` 先按候选键（cameraList/cameras/slots/recipes…）找，再递归找第一个元素含 `id`/`pictureControlName`/`exist` 的对象数组；槽位号优先取 `slot`/`slotNum`，否则用数组顺序。形状不认识时返回空列表而不是抛错，避免把「未知」误报成「失败」。
- **界面**：`ui/CloudAccountViewModel.kt` + `ui/CloudAccountScreen.kt`。云创页工具栏人像图标 → 账号页：已注册相机列表（名称/型号/ID）→ 点进相机看 9 个槽位（序号、方案名、英文代号、修改日期，空位显灰「空」），顶部显示账号可分配方案数；解析失败时把**接口原始响应**摊开显示（不含 token），便于截图反馈。批次 2 的拖拽排布按钮先置灰占位。
- **测试**：`app/src/test/java/com/chrisz/nikconnect/cloud/CloudAccountClientTest.kt` 8 项——相机列表（body 包裹 / 裸数组 / cameraId 键 / 未知形状返回空）、槽位（数组顺序即槽位号、`exist` 字符串与布尔混用、声明式 `slot` 优先）、可分配方案（封面与代号两种键名）、`E01_0701` 抛带码异常、残缺 JSON 抛错。全仓 **54 项 JVM 测试 0 失败**；lint 0 error / 6 warning（均为既有项）。
- **未验**：真机上这三个端点是否都返回 200（尤其 `GET /camera/{guid}/` 在「账号下没绑定相机」时的形状）、`CloudHttp` 的 1.2s 串行限速在连续点击下是否够稳。

## 2026-11-13 · 0.6.1：`IF_IR_R_C001` 422 的根因与修正

真机点人像图标进账号页，`GET /camera/{guid}/` 返回 422。对照 Web chunk 确认路径拼法与官方一致（`index-02c60951e817d908.js` 模块 6982：`/camera/${i}/` + `Authorization: Bearer`），所以问题在 **guid 取错**而不是接口拼错。

- **根因**：0.5.2 的 `guidFromUrl` 是"URL 里第一个 26 位 ULID"。但云创详情的真实地址是 `GET /publicrecipe/0000000000000001/{recipeId}`——`0000000000000001` 只有 16 位不匹配，于是紧跟其后的**方案 ID** 被当成账号 guid。`acceptToken` 又是"guid 只写一次"，一旦先浏览过方案详情，错值就固定下来了。422 的语义也吻合：路由匹配上了，但路径参数过不了网关校验。
- **修正**（提交见本节末尾）：
  - 新建 `app/src/main/java/com/chrisz/nikconnect/cloud/CloudIdentifiers.kt`：`guidFromUrl` 改为**语义提取**——只认紧跟在 `camera`/`publicrecipe`/`localrecipe`/`publicbook` 之后的路径段，且必须是 ULID/UUID 形态，否则直接放弃（不再往后找）。另收 `?guid=`/`#userGuid=` 查询串。`guidFromJwt` 与 `guidCandidates` 也放这里，供 UI 与 ViewModel 共用。
  - `CloudLoginScreen` 删除本地三份 guid 提取实现，改用共享函数；新增 `startUrl` 参数，可直接把 WebView 开到网页「相机」页。
  - `CloudAccountViewModel` 逐候选试：先用手上 guid，失败再试 token 里 JWT claim 的值；只有 400/401/403/422 才换候选（频控与网络问题换 guid 无意义）。成功后把生效的 guid 写回 state 供后续槽位接口使用。
  - 账号页新增「重新获取账号 ID」：422/无 guid 时出现，点了直接打开网页 `https://imagingcloud.nikon.com.cn/recipe/camera`——该页自己会发 `IF_IR_R_C001`，`shouldInterceptRequest` 从请求 URL 读到真实 guid，不用用户手动抄。
- **测试**：新增 `app/src/test/java/com/chrisz/nikconnect/cloud/CloudIdentifiersTest.kt` 6 项，其中一条专门锁死「方案详情地址不得泄漏方案 ID」。

## 2026-11-13 · 0.6.2：按全项目评审报告修 A 批（可靠性）

评审报告见 [docs/NiKConnect_全项目评审与重构决策_2026-11-13.md](docs/NiKConnect_全项目评审与重构决策_2026-11-13.md)。逐条核对源码后，**认定 12 条成立并已修**，其余记为"认同但本批不做"（见文末）。

**云创请求治理**
- `CloudHttp.send()` 原来只在算间隔时持锁，释放锁后才发 HTTP → 慢请求与后续请求重叠。改为 gate 覆盖「等间隔 + 发请求 + 读响应」整段，进程内单飞；间隔用 `System.nanoTime()` 单调时钟。
- `E01_0701` 是 **HTTP 200 响应体里的业务码**，原实现的退避只包住 `send()`，编码在 `parseElement()` 里，等于没退避。新增 `businessErrorCode(raw)` 纯函数，在 `request()` 里先判业务码再返回，频控退避才真正生效。
- 新增 `CloudHttp.shared`：目录客户端、账号客户端默认共用同一实例——"同名类"不等于"同一调度器"。

**状态与任务边界**
- `MainViewModel.disconnect()` 原来 `_state.value = UiState()`，把云创 token/guid、图库视图档位一起清掉。改为只重置相机会话相关字段，保留 `cloudToken`/`cloudGuid`/`galleryViewTier`（传输页选中态退回照片页）。
- `CloudAccountViewModel` 的 token 从构造期不可变字段改为可变 `accessToken`，新增 `updateCredentials()`；`CloudAccountScreen` 用 `LaunchedEffect(token, guid)` 下发新凭据。同 guid 重新登录后不会继续用旧 token。
- 槽位请求加代次 `slotRequestId`：切相机、返回列表、重登都让旧响应失效，迟到响应不再回填。

**取消与唤醒锁**
- `PtpIpClient.copyObject()` 改为 `suspend`，每块请求前 `ensureActive()`——整块事务已完整收完才退出，不会在 TCP 流里留半个事务。
- `PhotoDownloader` 在发布相册前再检查一次取消。
- 唤醒锁：`finally` 里原来按"队列还有没有活动任务"判断，而任务终态是 `execute` 返回后才写入 → 必然漏释放。改为无条件 `releaseWakeLock()`，下一任务自己重新 acquire。

**体验确定性**
- 底部导航三个入口常驻；`传输` 页与连接状态解耦，断线后仍能看到已完成/失败结果与原因（原来断线直接变回连接页）。
- 图库选择集改用 `listSaver` 且不再以「当前页句柄列表」为 key：翻页加载更多不再清空已选。
- 「全选」改为只作用于**当前筛选下可见**的 JPEG/RAW（原来 JPEG 视图会把隐藏的 RAW 也选上），「下载所选」同样按筛选过滤。
- 云创封面缓存：key 改为「去掉签名查询串的地址」（签名每小时轮换不再无限堆文件），加 200 文件 / 120MB 上限 + 按访问时间淘汰 + 单张 8MB 下载上限。

**凭据与响应契约**
- `CloudLoginScreen` 增加可信域白名单（`nikon.com.cn`/`nikon.com`/`nikonimaging.com`）：非尼康域的请求头、URL、页面存储一律不参与 token/guid 捕获；非 http(s) scheme 不加载，域外链接交系统浏览器。
- `CloudAccountClient` 的解析不再"认不出来就当空账号"：`emptyArrayOrFail()` 区分「真的空列表」与「结构变了」，后者抛 `CloudException(httpStatus = -2)` 并带原始片段。
- 账号页文案由「App 只负责排布」改为「本页当前只做只读查看，排布写入在后续批次」。

**本批不做（附理由）**
- 拆 `CameraSessionOwner`/`CloudRepository` 大结构：认同方向，但属纯结构重构，会牵动 PTP 会话、队列、保活服务三处，必须单独一批 + 真机回归，混在本批会把可靠性修复的验证信号冲掉。
- 文件名当已下载身份：需要新增「来源元数据 + 本地 URI」数据模型（含 MediaStore 迁移），属 0.7 功能项，本批只记录不改。
- 删除 `np3/` 与 B0 探针：**用户 2026-11-13 决定保留不动**——当前零业务调用、零运行时开销、测试全绿，作为研究资产留仓，等 PTP 槽位探针真机跑通后再决定是否进 UI。
- 事务级累计上限、START_DATA 阶段顺序校验：需要先补协议层测试夹具，否则改协议代码没有验证手段。
- 分页句柄快照：现有 offset 分页在"传输中相机新增文件"下可能重复，但改成快照要引入失效时机（刷新/重连/拍摄完成），本批不引入新状态。

## 2026-11-13 · 0.6.3：账号 ID 改走 Keycloak userinfo（422 根因）

真机连续两轮报 `IF_IR_R_C001 HTTP 422`，`重新获取账号 ID` 也无效。回到 Web 端 chunk 找「guid 到底从哪来」：

- `_app-6f8f948013527c4a.js` 的登录初始化里是这么写的：
  `let e = await keycloak.loadUserInfo(); ... let D = { authenticated: I, guid: e.guid, accessToken: keycloak.token, languageCode: e.languageCode, countryCode: e.countryCode }`
  即 **guid 是 OIDC userinfo 的返回字段**，不是从任何 URL 里解析出来的。
- 配置来源 `https://imagingcloud.nikon.com.cn/json/keycloak_Prod.json`：`realm=user`、`auth-server-url=https://auth.accounts.cld.nikon.com.cn/auth`、`resource=c0004`。
  → userinfo = `GET https://auth.accounts.cld.nikon.com.cn/auth/realms/user/protocol/openid-connect/userinfo`，仅需 `Authorization: Bearer <token>`。
- 旁证（无 token 探测，`Authorization: Bearer faketoken`）：`/camera/0000000000000001/` → 401、`/camera/xyz/` → 401、`/camera/01HXDNNTHY1AZ6RVMMXSXY3BTK/` → **422**。只有 26 位 ULID 形态才穿过网关进到业务校验层 → 之前「从 URL 嗅探第一个 ULID」很容易拿到方案 ID／bookId，形态合法但账号不对，服务端只能回 422。这也解释了为什么「重新获取账号 ID」没用：重抓的仍然是同一批 ULID。

修法：
- `cloud/CloudHttp.kt` 新增 `AUTH_HOST = "https://auth.accounts.cld.nikon.com.cn/auth/realms/user"`。
- `cloud/CloudAccountClient.kt` 新增 `CloudIdentity(guid, language, country, subject)` 与 `suspend fun fetchIdentity()`（`GET $AUTH_HOST/protocol/openid-connect/userinfo`，callId `OIDC_USERINFO`），解析 `parseIdentity()`：优先 `guid`/`userGuid`/`user_guid`/`userId`/`user_id`，再退回「形态合法的 sub」；都没有就抛错并带原始片段——**不再猜**。
- `ui/CloudAccountViewModel.refreshCameras()` 改成两步：先 userinfo 拿权威 guid；401/403 直接判定「登录态已失效，退出重登」（不再拿错 guid 撞 422）；拿到 guid 就用它查相机，候选嗅探降级为最后退路。
- `ui/CloudAccountScreen.kt` 新增 `GuidLine`（账号 ID 明文一行，可选中复制），出问题时用户能直接读值反馈；`GuidRefreshBar` 文案改「服务端不认这个账号 ID」+「退出重登」。
- 测试：`CloudAccountClientTest` 新增 4 项（guid 声明、sub 兜底、无 guid 抛错、非对象抛错）。JVM 70 项全绿。

## 2026-11-13 · 0.6.4：登录页极简化（自动返回）

用户反馈：登录页上方堆了「手动输入」「复制 token」「再查一次」「自动捕获状态」等一堆东西，而且手机上根本没有 F12，手动输入这条路本来就不成立。

- `ui/CloudLoginScreen.kt` 重写为「一个 WebView + 一行标题」：顶栏只有关闭按钮（48dp）与「登录尼康云创」。
- `acceptToken()` 抓到 token 后立即 `completed = true` 并 `onDone(CloudAuthState)`——用户完成登录就自动回 App，不需要任何确认动作。
- 删除：手动 token/guid 输入框、「使用该 token」、已捕获 token 的显示卡（含显示/隐藏、复制）、「再查一次」、「重新加载」常驻行、自动捕获状态行。
- 保留：可信域白名单（`nikon.com.cn`/`nikon.com`/`nikonimaging.com`）过滤请求头与 URL；非 http(s) scheme 不加载；域外链接交系统浏览器；JWT/localStorage 兜底提取。
- 仅在 **25 秒仍未抓到** 时出现一行灰字「完成登录后会自动返回」+「重新加载」，避免用户对着白屏干等。
- 顺手移除 `settings.databaseEnabled = true`（deprecated，且 `domStorageEnabled` 已覆盖），避免新增 lint 警告。
- 账号页「重新获取账号 ID」改语义为「重新登录」：账号 ID 现在由 userinfo 权威获取，不再需要去网页相机页抓值；`CLOUD_CAMERA_URL` 引用一并从浏览页删除。

## 2026-11-13 · 0.6.5：C001 真身校准（`deviceId`）

真机回报：

```
IF_IR_R_C001 返回的相机列表结构无法识别（255 字节）：
{"cameras":[{"deviceId":"01JTC8JSRZAW1B2FZZYGF8GYBW","name":"Zf","serialNo":"8032632…",
 "custProductMngNo":"1","productUrl":"https://imagingcloud.nikon.com.cn/img…"}]}
```

- **422 已经消失**：0.6.3 的「账号 ID 走 Keycloak userinfo」在真机成立，之前的 sniffing 就是错的那条路。
- 新问题纯粹是解析：相机对象主键叫 **`deviceId`**，我之前只认 `id`/`cameraId`，于是"结构无法识别"。
- `cloud/CloudAccountClient.kt`：`CloudCamera` 新增 `serial: String = ""`；`parseCameras` 的 `mustHave` 顺序改为 `deviceId` → `id` → `cameraId` → `device_id`，id 取值同理；`serial` 取 `serialNo`/`serial_no`/`serialNumber`/`serial`。
- 槽位解析同步放宽：`SLOT_KEYS` 追加 `pictureControls`/`pictureControl`/`controlList`，`mustHave` 追加 `recipeId`（C002 真身还没见到，先放宽再按真身校准）。
- `ui/CloudAccountScreen.kt` 相机行副标题改为 `型号 · SN 8032632 · 01JTC8JS`（能直接对上机身序列号，方便确认是哪台机器）。
- 测试：新增「real camera payload uses deviceId」1 项，JVM 71 项全绿。

## 2026-11-13 · 0.6.6：槽位排布写入（批次 2 第一批）

从 Web chunk `camera-129ad77c551770c0.js` 拿到 C004 的确切契约（不是猜的）：

```js
// 拖拽结束的提交逻辑
let c = l.map((e,s) => e.id.startsWith("blank") ? null : { slotNo: s + 1, recipeId: e.id })
        .filter(e => null !== e);
if (c.length) { if (!await R(m, _, x.deviceId, c)) a.push("/systemError") }

// R = PATCH /camera/{guid}/{deviceId}
//   headers: Content-Type: application/json + Authorization: Bearer <token>
//   body:    { recipes: c }
```

关键语义：**空位不发送**，靠"不在数组里"表达；`slotNo` 从 1 开始；数组按 `slotNo` 顺序。

- `cloud/CloudHttp.kt`：新增 `patch()`，并为所有请求增加 `contentType` 参数（默认仍是 `text/plain;charset=UTF-8`，与 Web 端 IF_FR 接口一致；C004 显式传 `application/json`）。
- `cloud/CloudAccountClient.kt`：新增 `CloudSlotAssignment(slotNo, recipeId)`、`suspend fun replaceSlots(cameraId, assignments)`、纯函数 `buildSlotBody()`（过滤空 recipeId、按 slotNo 升序）。
- `ui/CloudAccountViewModel.kt`：`draft`（编辑态副本，与服务端数据分离）+ `saving`/`saveError`/`savedNotice`；`beginEdit`/`cancelEdit`/`moveSlot(from,to)`/`clearSlot(position)`/`saveLayout`/`consumeNotice`。`saveLayout` 成功后**重新 GET 一次 C002**，以服务端结果覆盖 draft——不信本地。
- `ui/CloudAccountScreen.kt`：槽位区改为可编辑——`编辑排布` 进入编辑态，`长按拖动换位`（`detectDragGesturesAfterLongPress` + 固定 64dp 行高算位移），`点击清空该槽位`，`保存到相机`（保存中显示进度），`撤销` 丢弃 draft。
- 测试：新增 3 项 body 构造用例（排序、空 recipeId 过滤、全空），JVM 74 项全绿。

## 2026-11-13 · 0.6.7：清空到底改走 DELETE，空槽位可挑方案

真机反馈（0.6.6）：相机上只有一个槽位，无法换位；把唯一槽位删掉并保存 → `IF_IR_R_C004` 422。

根因（有依据）：0.6.6 的保存逻辑只要 draft 里有内容就一律 PATCH，**全清空时发的是 `{"recipes":[]}`**。而 Web 端的写法是

```js
if (c.length) { if (!await R(m, _, x.deviceId, c)) a.push("/systemError") }
else V().debug("newRecipeIdList length 0")   // ← 空数组根本不发请求
```

即空数组不在 C004 契约里；删除单个槽位方案在 Web 端走的是另一条：`IF_IR_R_C005` = `DELETE /camera/{guid}/{deviceId}/{recipeId}`。

- 新增 `planSlotWrite(before, after): CloudSlotWritePlan`（纯函数，在 `cloud/CloudAccountClient.kt`）：
  - 顺序没变 → `noop`
  - 全部清空 → `deletes`（逐条 C005），`assignments = null`
  - 其它 → 一次 C004 全量覆盖（空位靠缺席表达）
- `saveLayout()` 改用计划执行，失败时 `rawSnippet` 打印**实际请求体** + 响应原文。
- `describe()` 里 422 不再武断说"账号 ID 不对"（空数组/缺字段都会 422），改为如实回显服务端原文。
- 空槽位可挑方案：`openSlotPicker`/`fillSlot` + `loadAssignable`（`IF_FR100_H13`）；编辑态点击空槽位弹 `SlotPicker` 底部抽屉，来源是**账号可分配池**。池为空时提示"先在云创网页收藏方案"——收藏（`POST /publicrecipe/{guid}`）属于下一批。
- 测试 +4（顺序没变、换位、全清空走删除、空位新增），JVM 78 项全绿。

## 2026-11-13 · 0.6.8：删除走 C005，槽位固定 9 格

真机反馈（0.6.7）：换位置可以，相机也能识别；但删除不行，也没有空槽位可加。报错 `IF_IR_R_C004 HTTP 400`，请求体是**带两个 slotNo 的 PATCH**。

两个问题，各自的根因不同：

1. **删除没有被"表达"出来**。上一版把"清空"做成"点击槽位把 draft 置空，再靠 PATCH 缺省表达删除"。真机上点击没有改变 draft（拖动与点击共用一个 `pointerInput`，点击被长按手势吃掉），于是 PATCH 原样把两条又发了一遍；而服务端对缺省/重复的排布回 400。改法：删除不再靠 PATCH 缺省，改为**行尾垃圾桶图标 → 确认 → 立即 `DELETE /camera/{guid}/{deviceId}/{recipeId}`（C005）→ 重新 GET**。这也与 Web 端删除实现一致。
2. **没有空槽位可加**：真机 C002 只返回已占用的项（2 个方案就 2 条），界面照抄就只有 2 行，自然没有"＋"。改法：新增纯函数 `padSlots(slots, count = 9)`，UI 永远按 9 格渲染，空位 `index` 保留真实槽位号，点空位行或「＋」从可分配池挑方案。

- `cloud/CloudAccountClient.kt`：新增 `padSlots()`；`CloudSlot` 的空槽位默认值不再必填。
- `ui/CloudAccountViewModel.kt`：新增 `deleteSlot(recipeId)`（立即 C005 + 重新 GET + 代次保护）；`openCamera`/`saveLayout`/`deleteSlot` 的响应统一过 `padSlots`；删除旧的 `clearSlot()`。
- `ui/CloudAccountScreen.kt`：编辑态——拖动把手 + 空位「＋」；非编辑态——占用行尾垃圾桶（删除中显示进度）、确认弹窗；`pendingDelete` 提到屏幕层避免与槽位区状态耦合。
- 测试 +2（补齐 9 格、有空洞时不挪位），JVM 80 项全绿。

## 2026-11-14 · 0.6.9：槽位号以真实排布为准（`recipeIds`）

真机反馈（0.6.8，用户 m05499）：删掉第 1 位后，App 里第 2、3 个自动前移；相机同步后仍是 2、3，第 1 位空着；把删掉的方案加回第 3 位则 `IF_IR_R_C004 HTTP 400`（响应体为空）。

根因是**槽位号从数组下标推出来的**：

- `IF_IR_R_C002` 的 `localRecipes` 只含**已占用**项、不带槽位号。删掉第 1 位后它返回 `[B, C]`，我们按顺序编号成 1:B、2:C，而相机上是 2:B、3:C。
- 真实排布在 `IF_FR100_H13` 的 `camera[].recipeIds`：定长 9 的数组，空位是 `null`。Web 端就是 `recipeIds.map(id => localRecipes.find(...))` 拼出来的（见 `camera-129ad77c551770c0.js` 的 `N(...)`）。
- 于是从"错误编号"再 PATCH，`slotNo` 1/2/3 与服务端 2/3 冲突 → 400。

改法：

- `cloud/CloudAccountClient.kt`：新增 `CloudAssignable(recipes, layouts)`、`parseLayouts(raw)`（读 `camera[].recipeIds`）、`fetchAssignable()`（一次 H13 同时拿池与排布）、顶层纯函数 `mergeSlots(recipeIds, local)`（按 `recipeIds` 定位、空位补空、`localRecipes` 只提供名字/代号）；`cameraSlots(cameraId, recipeIds)` 接排布参数；`SLOT_KEYS` 前置 `localRecipes`。
- `cloud/CloudAccountClient.kt`：`planSlotWrite(before, after)` 改吃 `List<CloudSlot>`，`slotNo` 取 `CloudSlot.index`，不再按"第几个已占用方案"重编号。
- `ui/CloudAccountViewModel.kt`：新增私有 `fetchSlots(client, cameraId)`——先 H13 拿 `recipeIds` 与可分配池（顺带刷新池，避免删除后仍能选到陈旧项），再 C002 取详情合成；`openCamera`/`deleteSlot`/`saveLayout` 全部走它。
- 测试 +6（空洞保留、`recipeIds` 优先于数组序、无排布时退回数组序、全删九格空、`parseLayouts` 正例/缺省），JVM 86 项全绿；lint 0 error / 6 warning。

## 2026-11-14 · 0.6.10：收藏到我的书（官方方案 → 可分配池）

上一轮把可分配池的问题讲清了：账号页「＋」里只有 3 个，是因为 `IF_FR100_H13` 的 `recipe[]` 只含**账号里已有**的方案，而官方 144 个属于公共目录（`bookId=01HXDNNTHY1AZ6RVMMXSXY3BTK` 那本）。要让它能排进相机槽位，必须先复制一份进自己的书。这一步补上。

接口与语义（来自 Web chunk，非猜测）：

- `IF_FR100_H05` = `POST /bff/recipe` header `access_token` body `{filter,country,lang_code}` → 返回 `books[]`（每项 `id`/`label`/`recipeCount`）、`allLocalRecipes`、`cameras`、`authority`。收藏的目标容器就在这里。
- `IF_IR_R_PR001` = `POST /publicrecipe/{guid}` Bearer body `{bookId, recipeIds}` → 返回新建出来的本地方案 `recipeIds`（**新 ID，与官方方案 ID 不同**）。
- `IF_IR_R_PB002` = `POST /publicbook/{guid}` Bearer body `{name}` → 返回新书 `id`。
- 容量约束（Web 端 disabled 条件）：一本书 ≤ 200 个方案、一个账号 ≤ 10 本书。App 里直接前置拦截，不把注定失败的请求发出去。

改动：

- `cloud/CloudAccountClient.kt`：新增 `CloudBook(id,label,recipeCount)`（带 `MAX_BOOKS=10` / `MAX_RECIPES_PER_BOOK=200`）、`CloudMyRecipes(books,recipeCount,authority)`；方法 `fetchMyRecipes()`（H05）、`favoriteRecipes(bookId, recipeIds)`（PR001）、`createBook(name)`（PB002）；纯函数 `parseMyRecipes`/`parseFavoriteResult`/`parseCreatedBookId`/`buildFavoriteBody`/`buildBookBody`；`CloudHttp.post()` 补 `contentType` 参数。
- `ui/CloudFavoriteViewModel.kt`（新）：`CloudFavoriteState(books,recipeCount,loading,error,working,notice)`；`loadBooks()`、`favoriteInto(recipeId, book)`、`createBookAndFavorite(recipeId, name="NiKConnect")`；收藏成功后静默重拉书列表（方案数会变）。
- `ui/CloudFavoriteSheet.kt`（新）：详情页「收藏到我的书」按钮（未登录时降级为「登录云创后可收藏」→ 打开登录页）；`ModalBottomSheet` 列出所有书（含方案数、满 200 置灰）与「新建书并收藏」；错误/重试/进度齐备。
- `ui/CloudBrowseScreen.kt`：`CloudRecipeDetailScreen` 增加 `favoriteToken`/`favoriteGuid`/`onNeedLogin`，参数与 `CloudTab` 的登录态打通；按钮位于方案名与 chip 行下方。

验证：JVM 91 项全绿（+5：书列表解析、无书不报错、收藏结果 ID、新建书缺 id 报错、两个请求体），lint 0 error / 6 warning。

## 2026-11-14 · 0.6.11：收藏入口文案改为「收藏 / 收藏夹」

用户反馈（m05670）：`不是收藏到我的书，你就直接收藏就好了，我的书太奇怪了`。功能不变，只改称呼：

- 详情页按钮：`收藏到我的书` → `收藏`；未登录态 `登录云创后可收藏到我的书` → `登录云创后可收藏`。
- 底部抽屉标题：`收藏到哪本书` → `收藏`；副标 `复制一份到你的收藏夹，之后才能排进相机槽位`。
- 空态与新建议：`还没有收藏夹。新建一个即可收藏。`、`新建收藏夹并收藏`、`未命名收藏夹`。
- 容量提示：`一个账号最多 10 个收藏夹，先在网页端删一个。`、`《X》已满 200 个方案，换一个收藏夹或新建。`
- 接口语义不变：`IF_FR100_H05` 的 `books[]` 在服务端叫 book，UI 一律称「收藏夹」。

## 开放问题

1. 真实图库规模（120 / 300 / 更大）→ 决定 0.2 分页与批量元数据优先级。
2. 后台下载是否当前痛点 → 决定 0.3 排期。
3. RAW 文件类型与大小分布 → 0.4 样本。
4. NP3 写字段与机型能力（B0 探针前不排期）。
5. 云创账号语义：收藏到书（`IF_IR_R_PR001`，0.6.10 已接）与相机 9 槽排布（`IF_IR_R_C004`，0.6.6–0.6.9 已接）的分工已确定——先收藏进书，方案才进可分配池，才能排进槽位；剩余待定项是「删除书里的方案」是否也要在 App 内做。
6. 云创详情接口 IP 级频控（E01_0701）：是否需要把 144 个详情预取进 assets（体积/时效权衡）。
