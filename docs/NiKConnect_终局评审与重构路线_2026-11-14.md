# NiKConnect 终局评审与重构路线

评审日期：2026-11-14｜源码基线：0bbfbc4｜版本：0.9.3-alpha（versionCode 50）

## 1. 决策摘要

保留已有真机证据支持的 PTP/IP 传图核心，围绕文件身份、账号会话、云创命令与页面状态实施分批重构。当前主要问题并非框架落后，而是业务身份与副作用归属尚未形成一致边界。整体重写的回归成本高于收益。

最高优先级是三类用户资产：照片原件、账号凭据、云创收藏与槽位。先保护资产，再提高操作连续性，最后扩展新能力。建议暂停扩大云端写入功能面，先把现有操作变成可识别、可核对、失败可解释的闭环。此为评审建议，尚未修改产品行为。

本轮遵循项目“审查只报告”约定，只新增报告与生成脚本；没有修改应用源码、依赖、版本、工程规范，没有构建 APK、调用真实云创写接口、登录账号或操作相机。2026-11-13 报告作为历史记录保留。

## 2. 证据、覆盖与验证边界

置信度“高”表示直接读到对应源码或文档；“中”表示源码路径成立，但具体触发依赖服务端行为、设备或调度。源码确认不等于事故已经复现，所有风险均按此区分。

- 当前源码共 35 个主 Kotlin 文件：ptp 4、camera 4、cloud 9、ui 16、np3 1、MainActivity 1；评审覆盖这些模块的关键入口、调用与状态链路，未声称逐行形式化证明。
- 测试目录 18 个 Kotlin 文件、文本统计 144 处 @Test；这些数字是覆盖资产清点，绝非本轮通过数。
- 图谱 nikconnect-current 停留在 2026-11-11，覆盖落后于当前源码；采用图谱定位后回读当前源码，不依据旧图谱宣布死代码。
- 本轮尝试 `./gradlew --offline :app:testDebugUnitTest :app:lintDebug`，退出码 1，提示 `Unable to locate a Java Runtime.`；JVM 与 lint 未执行成功。未安装 JDK，未运行 APK 构建。
- 没有当前界面截图、Compose 仪器测试或真机交互；体验结论来自交互源码与用户任务推演，视觉质量、触控、字号和 TalkBack 仍待实测。
- 仓库 PROGRESS.md 记录 JPEG 8,344,798 字节/1.9 秒，NEF 16,928,768 字节/5.2 秒，SHA-256 与存储卡一致，并记载息屏及取消验证。这是已有项目真机记录，不是本轮重新实测，也不外推到所有 Nikon 机型。
- 本轮读取公开项目与官方文档；竞品没有安装运行，功能和成熟度判断只到公开资料层。

## 3. 与上一轮相比已经改善的部分

这些改进应保留，避免旧报告驱动反向重构：

| 已改善事项 | 当前观察 | 边界 |
|---|---|---|
| 云创基础请求调度 | 目录与账号默认共享 CloudHttp；gate 包住等待、发送和读取；E01_0701 在重试范围内解析 | 业务多步骤操作仍可交错；登录另建实例 |
| 下载取消与电源 | copyObject 每个 4MiB 块检查取消；WakeLock 在 execute 的 finally 无条件释放 | MediaStore 最后复制仍缺协作取消；未做本轮电量实验 |
| 手动断开 | 保留云创凭据与图库档位 | 异常断开路径仍重建总状态；传输历史被清空 |
| 图库选择与导航 | 分页追加不再直接清空选择；全选遵循当前格式；底部导航常驻 | 隐藏选择与按钮计数仍不一致 |
| 云创 UI | 账号页会更新 access token；未知响应结构明确失败 | refresh token 更新与多步骤恢复仍分散 |
| 图片缓存 | 查询串剥离形成稳定键，磁盘上限 200 文件/120MB、单次 8MB 上限 | 图片加载是否替换为成熟库需基准后决定 |
| 云创槽位 | H13 的固定位置数组成为依据，避免抹平空槽；移动不再无谓删加 | 云端状态与机身实际生效仍是不同证据 |

## 4. 按严重性排序的缺陷与处置

P0：建议在扩大分发和写入使用前关闭的资产风险；P1：下一批可靠性重构；P2：一致性与工程细节。等级表示评审优先级，不表示本轮已发生事故。

### F01 / P0：同名照片去重会删除合法原件或认错照片

**高置信度，源码确认。** [PhotoDownloader.kt:59](../app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt#L59) 用同名同大小直接返回相册旧 URI，并删除新下载文件；[PhotoDownloader.kt:94](../app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt#L94) 对同名、同目录但大小不同的项目执行 resolver.delete。

不同存储卡、相机或编号回绕均可能产生 DSC_0001.JPG。大小不同不证明旧件损坏；大小相同不证明内容相同。当前实现既有旧原件被删风险，也有新照片丢失且显示成功的风险。

**改造：** 去掉基于文件名的破坏性推断。显示名称只作为展示；导入记录包含内容摘要、字节数、来源元数据、目标 URI 与发布时间。当前已有 .part 可流式计算摘要；只在候选冲突时比较已有文件或可信导入记录。无法证明相同就并存，用明确后缀保留两个文件。相册旧项不作为临时文件回收目标。

**验收：** 同名异大小、同名同大小异内容、两台机身同名、旧 URI 被用户删除、相册读取异常、真正重复导入六组用例；前四组保留应保留的原件，真正重复按可靠身份复用。

### F02 / P0：加密存储被日志与 debug 明文旁路

**高置信度，源码确认。** [CloudHttp.kt:177](../app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt#L177) 无构建门控地打印请求体和响应前 200 字；refresh 表单及 OIDC 响应可能含凭据片段。[CloudDebug.kt:28](../app/src/main/java/com/chrisz/nikconnect/cloud/CloudDebug.kt#L28) 把 access 与 refresh token 写成 token.txt；原始请求响应也落盘；[MainActivity.kt:28](../app/src/main/java/com/chrisz/nikconnect/MainActivity.kt#L28) 在 debug 自动启用。这里不读取实际 token 文件，未确认是否存在历史凭据残留。

**改造：** 认证端点禁止记录原始 body/响应，改用字段白名单：callId、状态码、耗时、业务错误码、重试次数。移除自动 token 导出；清理旧诊断缓存应作为单独获准操作。登录态清理、诊断导出与保存期限形成可测试合同。debug 也采用相同脱敏标准。

**验收：** 使用显式假 token 经过登录、刷新、失败、退出和诊断导出，扫描结果中假 token 及其前缀均不出现；普通业务诊断仍能定位失败步骤。

### F03 / P0：收藏身份以名称匹配，可能删除同名的另一方案

**高置信度，路径确认；同名账号场景待复现。** [CloudFavoriteViewModel.kt:77](../app/src/main/java/com/chrisz/nikconnect/ui/CloudFavoriteViewModel.kt#L77)、:102 按忽略大小写的名称 firstOrNull，:126 对匹配 localId 执行真实删除。[CloudAddToCameraViewModel.kt:111](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAddToCameraViewModel.kt#L111) 同样通过名字找新收藏。

**改造：** 保持 public resourceId、local recipeId、cameraId、slotIndex 为不同身份。优先使用响应或服务端来源字段关联；如果服务端不返回可靠映射，使用操作前后快照与唯一候选核对，歧义时展示候选并停止自动删除。不要把显示名升级为主键。

**验收：** 同名不同来源、大小写差异、用户重命名、LR002 返回 202 后读回延迟；所有删除都指向明确的 localId，未知身份不执行破坏性操作。

### F04 / P0：认证重试会重放已经部分成功的云创工作流

**高置信度，源码确认；重复效果依赖端点幂等性。** [CloudAddToCameraViewModel.kt:106](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAddToCameraViewModel.kt#L106) 在一个 withSession 块里收藏、入池、删占位、PATCH；:186 遇 401/403 后整个 block 再执行。后半段鉴权失败可能重放前半段已成功写入。

**改造：** 请求级认证重试与业务级恢复分开。统一账号续期单飞；每个写步骤记录 before/目标/已观察结果，遇到不确定提交先读回，不盲目重发。对“入池→替换”的步骤显式建小型命令状态，不建设通用工作流引擎。403 不一概视为过期，依据已知错误语义判断。

**验收：** 在每个写步骤之后分别注入 401、网络中断、超时但服务端已提交；不重复收藏、不误删占位，结果未知时显示“待核对”并提供重新读取。

### F05 / P0：多步骤槽位修改缺少完整前态与可靠失败恢复

**高置信度，源码路径确认；实际服务端后果待隔离样本验证。** [CloudAddToCameraViewModel.kt:118](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAddToCameraViewModel.kt#L118) 在 layout 缺失时合成空布局；:131 先删占位，:144 补偿仅 addRecipes 并吞掉失败。[CloudAccountViewModel.kt:499](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountViewModel.kt#L499) 也有删后 PATCH 与入池补偿。加回池子不证明原槽位恢复。

**改造：** 权威布局未知、账号变化、快照过期时禁用提交；保留固定九位完整 before；账号级写命令串行；提交前重新读回比较，发现冲突展示差异。若服务端没有版本/条件更新支持，客户端锁只能协调本进程，无法保证与官网操作全局原子性；必须如实呈现冲突边界。失败后读回，列出实际变化，只有重新读取一致才宣称已恢复。

**验收：** 空布局响应、布局字段缺失、每一中间步骤失败、补偿失败、官网并发修改；不从未知前态提交整份替换，不把“已尝试补偿”显示成“已恢复”。

### F06 / P1：取消收藏的关联影响可能被显示为“没有”

**高置信度。** [CloudFavoriteViewModel.kt:78](../app/src/main/java/com/chrisz/nikconnect/ui/CloudFavoriteViewModel.kt#L78) 查询槽位失败退成 emptyList；:159 每台相机仅 indexOfFirst。前者把未知依赖当无依赖，后者在允许重复引用时漏报位置。

**改造：** placements 使用 Loading/Known/Unknown，删除前强制核对关联影响；枚举所有引用位置。确认文案区分“从我的方案删除”和“从某台相机移除”，显示将清空的槽位。

**验收：** 关联查询失败时仍明确“影响未核对”；多相机、多位置全部列出；不能用空列表代替失败。

### F07 / P1：相机异常会重置无关的云创身份与偏好

**高置信度。** [MainViewModel.kt:347](../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L347) 在 repository 已断开分支创建 UiState(downloads,error)，丢失 token、guid、refresh 和偏好。手动断开已修，但错误路径未统一。

**改造：** 独立 CameraSessionState、AuthState、GalleryPreferences、TransferState。会话结束只清理相机资源，账号状态由认证所有者管理；列表记录不和 live handle 生命周期混为一谈。

**验收：** 缩略图超时、分页失败、主动断开、热点消失、重连失败五条路径均保留云创账号与档位；旧相机句柄不被重试。

### F08 / P1：下载队列生命周期脱离调用 scope，清理顺序有空隙

**高置信度（结构）；具体残留竞态中置信度。** [DownloadQueue.kt:48](../app/src/main/java/com/chrisz/nikconnect/ui/DownloadQueue.kt#L48) 使用无父 SupervisorJob 覆盖 scope Job；cancelAll 取消 pump/任务，却不关闭 supervisor。[MainViewModel.kt:404](../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L404) 的 session 清理只等待 sessions.track 的工作，队列工作另有生命周期。不能据此断言每次断开都会残留文件。

**改造：** 队列明确成为 session 的子 Job，提供有序 shutdown：停入队→取消→等待执行体完成→清理 session 临时文件。取消后即使保留任务历史，也禁止复用旧 live handle。持有相机连接的任务保持前台服务，不用 WorkManager 代替现场 socket 会话。

**验收：** 传输、发布、重连、onCleared 四处注入取消，所有子任务可完成关闭，旧结果不写回新会话。

### F09 / P1：最后发布阶段缺取消检查与中断恢复

**高置信度（代码缺口）；窗口触发待测。** [PhotoDownloader.kt:75](../app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt#L75) 的同步 copyTo 后直接清 IS_PENDING，没有复制过程/提交前取消检查；:113 启动只清 .part，没有发布中记录恢复。

**改造：** 发布采用分段可取消复制，清 pending 前最后校验任务归属与长度；应用自建 pending URI 用最小记录恢复，只处理自身未完成项。不可全表删除 pending。保留已成功发布原件与导入收据。NRW 与 NEF MIME 按真实格式区分（当前 :68 全部 RAW 标成 NEF）。

**验收：** 输出第一个块、最后一个块、update 前、进程重启四处中断，零字节项目不可见、已发布内容不误清理、状态准确。

### F10 / P1：图库分页没有稳定句柄快照

**高置信度。** [PtpIpClient.kt:111](../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L111) 每页重取全量 handles 再 reverse/drop/take，拍摄新增会推移 offset，导致重复与遗漏。

**改造：** 同一 connectionId 建立句柄快照，分页读取该快照；新拍照片以显式刷新建立新快照。追加层按身份去重只能兜底，不能解决遗漏。

**验收：** 读取第 1 页后插入或删除相机对象；已创建快照无重复、原有对象不因 offset 移动漏取；刷新明确进入新集合。

### F11 / P1：PTP 事务只有单包上限，没有聚合长度与阶段约束

**高置信度。** [PtpIpClient.kt:204](../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L204) 累积 ByteArrayOutputStream，START_DATA 只核对 transactionId；单包 8MiB 上限不能限制无限多个包。慢速持续输入也可能绕过单次读取超时形成长事务。

**改造：** 保持命令串行与错配关双通道，增加按 opcode/期望长度约束的聚合上限、START/DATA/END 顺序验证、声明长度校验与事务总时限；GetPartialObject 可依据请求长度约束，metadata 另设保守边界并用真实样本验证。不要随意更改 15740、4MiB 与 350ms。

**验收：** loopback 注入巨量小包、长度矛盾、无 START、重复 END、迟响应、持续 probe；可诊断失败并关双通道，既有正常样本仍通过。

### F12 / P1：认证续期与凭据传播分散

**高置信度（结构）；并发 refresh 效果依赖服务端策略。** [CloudAccountScreen.kt:104](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountScreen.kt#L104) 更新 token/guid 未传轮转 refresh；Favorite/Account/AddToCamera 各自持有续期策略。Favorite 的 refreshAttempted 与 Add 的 refreshTried 还是 VM 生命周期内的一次性门。

**改造：** AuthSessionStore 持有唯一内存状态并封装 SecureTokenStore；single-flight refresh，成功时原子更新 token 对与 account generation；页面不缓存旧 refresh。持久化失败需要可见结果（当前 SecureTokenStore.save 吞错），避免承诺免重登却实际未保存。

**验收：** 三页面同时过期仅一次 refresh；refresh 轮转同步传播；退出中旧请求不回写；磁盘/Keystore 失败时文案准确。

### F13 / P1：登录回跳校验接受空 state，调试能力未限制到 debug

**高置信度。** [CloudLoginScreen.kt:235](../app/src/main/java/com/chrisz/nikconnect/ui/CloudLoginScreen.kt#L235) 只在 state 非空时比较；[CloudOidc.kt:52](../app/src/main/java/com/chrisz/nikconnect/cloud/CloudOidc.kt#L52) 前缀匹配回调 URI。[CloudLoginScreen.kt:349](../app/src/main/java/com/chrisz/nikconnect/ui/CloudLoginScreen.kt#L349) 无条件启用 WebView debugging，另有 console 与 token 前缀日志。已有域名白名单值得保留，不能再说没有白名单。

**改造：** state 必填且等值；URI 的 scheme/host/path 精确匹配；成功回跳单次消费；调试开关与日志按构建与脱敏约束。目标采用系统浏览器授权+PKCE；但第三方客户端回调注册、服务端支持与账号功能可用性必须先证明，不能机械替换导致登录失效。

**验收：** 无 state、错 state、相似路径、重复回跳、过期 verifier 均不会建立登录态；release 构建不启用 WebView 调试。

### F14 / P1：旧相机的云创异步结果会写到新选择页面

**高置信度（缺守卫）；调度触发待测。** [CloudAccountViewModel.kt:346](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountViewModel.kt#L346) fillSlotFromLibrary 与 :474 saveLayout 缺少与 openCamera 一致的 requestId/selectedCamera 守卫。当前部分操作有 slotRequestId，覆盖并不完整。

**改造：** 所有读取结果绑定 accountGeneration/cameraId/requestId；写命令目标捕获为不可变值，完成后更新对应对象而非“当前页”。明确切换时操作继续还是取消；业务取消不可假装撤销已发出的服务端写入。

**验收：** A 相机请求挂起→切 B→A 返回；B 草稿、提示和 loading 不被 A 覆盖。账号切换同测。

### F15 / P2：用户看到的选择数量、返回层级与传输记录不连续

**高置信度。** [MainScreen.kt:428](../app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt#L428) 只下载 filteredPhotos 中的选中项，按钮数量却取全量 selected；:284 已下载标记只看 filename。[CloudAccountScreen.kt:88](../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountScreen.kt#L88) 系统返回直接 onBack，工具栏返回先退内层；[MainViewModel.kt:385](../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L385) 断开清传输记录。

**改造：** 选择采用明确合同：推荐跨筛选保留选择，下载按全部已选身份解析，并提示“其中 N 张不在当前筛选”；替代做法是筛选变化清选择，但不能计数与动作分裂。内层返回、系统返回、手势返回共用同一行为。传输历史保留成功 URI 与失败原因；结束会话后只提供查看/分享/清除，重试须重新定位来源。

**验收：** JPEG/RAW 来回筛选、分页、刷新、重连、退后台返回；数量、执行集合和终态完全一致。

## 5. 架构模块终局判断

| 模块 | 判断 | 推荐边界与动作 |
|---|---|---|
| ptp/Binary、Packets、Datasets、Client | 保留核心，补硬边界 | 编解码与事务不掺 UI；长度/阶段/期限测试先行；稳定快照放相机会话，不建通用协议插件平台 |
| CameraNetwork、Repository | 保留定向 socket 与资源代际 | 不使用全进程网络绑定；连接过程显示获取 Wi-Fi/探测/建会话阶段；热点释放等待来自历史观察，不假装即时可重连 |
| PhotoDownloader | 优先重构文件身份与发布 | 导入流水 Download→Validate→Publish→Receipt；不让 dedupe 删除正式资产 |
| ConnectionKeeperService | 保留当前前台服务路线 | 将用户可理解的任务进度/取消接入通知；先验证锁屏与系统限制再承诺长时稳定；当前服务只是保活，任务主要在 VM |
| SessionController、DownloadQueue | 合并生命周期所有权 | session 拥有子任务；单一任务状态源；关闭可等待；历史记录和 live handle 分离 |
| CloudHttp、Catalog/AccountClient | 保留入口与明确 schema 失败 | 统一脱敏日志、账号级 cooldown；HTTP 串行不代替业务锁；保留假 API 注入点 |
| CloudOidc、Capture、SecureTokenStore、Debug | 收敛认证路径与诊断 | 加密存储保留；统一 refresh；删除自动凭据导出设计；Capture 兼容路径有退出条件 |
| Cloud Account/Favorite/AddToCamera VMs | 重构最重要区域 | UI 发意图，命令协调器处理身份/快照/副作用/读回；先合并重复业务，不新增空壳 domain 层 |
| Catalog VM、Browse、RemoteImage | 大体保留，补刷新可靠性 | 种子/缓存/在线来源与更新时间清晰；旧请求 finally 不覆盖新 loading；有证据再换图片库 |
| MainScreen、AccountScreen、Sheets、Theme | 以用户任务重排，不做无证视觉换肤 | 连接→挑片→导入→确认；发现→收藏→排布→云端确认→机身确认分开 |
| np3/Np3File | 研究性能力保留隔离 | 当前 parse 最小长度为 26 却读取至名称区、TLV 长度也未完整验证；版本头/重复 tag/未知字段/截断应先补样本与测试，不提前接入机身写入 |
| 工程与文档 | 优先可重复验证 | 当前未见 CI 工作流；建议 JVM+lint（不含 APK）的最小 CI；PRD、PROGRESS 顶部与 0.9.3 的存储/收藏事实对齐 |

## 6. 减法清单：删重复责任，不删有证据的底层

1. 将 QueueTask→_queueTasks→DownloadItem 的多份状态投影收敛为一个 StateFlow 与纯展示映射，避免两个“任务真相”。
2. 云创三个 ViewModel 里的刷新、身份匹配、入池、删槽、排布恢复集中到小型业务组件；API 层保留可替换测试接口。
3. 自动 token.txt、原始 console 与自动 debug 收藏探针退出正常登录路径；研究探针留在隔离工具并显式触发。
4. 消除“未知→空布局/无关联/成功回滚”的隐式兜底；更少分支、更明确状态。
5. DownloadQueue 批量 enqueue 对同一输入内重复 handle 先去重；当前 byHandle 在 fold 前构建，重复输入可能新增重复行。无界 channel 与整表 map 在大图库有增长成本，先用实际 1k/10k 对象数据测量，再决定 map+顺序队列。
6. 不引入微服务、动态插件、通用 event bus、大型 DI/工作流框架、多进程导入或 AI 助手来掩盖当前一致性问题。不要以行数多作为单独拆分理由。

## 7. 用户旅程重新定义

### 7.1 连接与图库

首页主动作围绕“连接相机”。连接期间给出阶段、取消、最近地址；手动 IP 放高级选项。错误后保留诊断原因和下一步，例如检查热点、等待旧会话释放、重试，不自动反复轰击相机。当前已有连接改进需保留；阶段化是进一步建议。

图库优先最新拍摄、JPEG/RAW/两者、日期/拍摄批次；RAW+JPEG 成组只合并展示，导入仍精确到文件。选择状态独立于分页和筛选；已导入必须来自可靠记录。按钮显示“导入 12 张 · 约 380MB”，大小未知时显示待估算，不伪造时间。没有缩略图时保留可选文件卡与格式、文件名，不把预览失败当无法导入。

### 7.2 导入闭环

入队前给目标、剩余空间与重复项预检。空间估算考虑 .part 与 MediaStore 复制阶段同时存在的峰值及余量。显示等待/下载/校验/发布/完成，不把下载 100% 当已保存。批次结束给收据：计划数、成功数、跳过依据、失败数、目标相册；支持打开结果与仅处理失败项。

断开后记录仍可查看；从旧会话重试必须重新定位对象。后台通知与页面共用进度，不重复“已完成”消息。TalkBack 描述选中、格式、进度与错误；目标触控至少 48dp，字体放大 200% 后仍可完成选择和确认，需设备测试。

### 7.3 云创与账号

发现页免登录浏览，动作发生时才登录。将“方案目录”“我的方案”“云端相机排布”“机身已生效”分清，尤其避免把云端 PATCH 成功说成已写入机身。登录说明提供服务来源、保存登录状态与退出入口；启动不为了浏览目录强行进入账号流。

替换前展示九位 before/after 与受影响方案；一键操作可在内部有步骤，但用户只看到准备、提交、核对和明确结果。取消收藏显示关联相机和槽位，查询失败明确未知。草稿有未保存提示；系统返回与标题返回一致。

## 8. 外部研究：借鉴什么，拒绝照搬什么

以下公开资料在本轮读取或检索；项目功能是公开描述，不是本轮实机对比。

| 来源 | 可借鉴原则 | 不照搬的部分 |
|---|---|---|
| libgphoto2 | 机型能力、错误分类、协议回归与成熟兼容经验 | 桌面驱动整体迁入 Android；其许可证要求应在实际复用前核对，不能当无条件复制许可 |
| qDslrDashboard | 摄影师的连接、设备上下文、PTP/IP 工作流 | Qt/LibRaw 跨平台栈与大量遥控能力，当前产品主轴是可靠传图 |
| Rapid Photo Downloader | 导入预检、重复处理、结果收据、命名与备份的摄影工作流 | Linux 多进程/0MQ 架构与并行多设备，不适合当前单相机串行事务 |
| Android architecture/data layer | 数据单一来源、repository、可测试的状态流 | 为小应用机械新增多层接口或把每个点击包装成 use case |
| Android MediaStore | IS_PENDING 与共享媒体发布合同 | 不能用 pending 机制替代文件身份与崩溃恢复 |
| RFC 8252 | 外部 user-agent、PKCE、明确回调边界 | 未证明第三方回调可用前直接替换现有登录 |

另检索到 aero-shutter、Viewfinder 等相关项目，未完成运行、维护状态与代码质量评估，不列为成熟背书。GitHub 搜索结果空白不构成“没有竞品”的证据。

参考链接：

- [libgphoto2](https://github.com/gphoto/libgphoto2)
- [qDslrDashboard introduction](https://dslrdashboard.info/introduction/)
- [Rapid Photo Downloader](https://github.com/damonlynch/rapid-photo-downloader)
- [Android 数据层](https://developer.android.com/topic/architecture/data-layer)
- [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)
- [Android 共享媒体与 MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252)

## 9. 新能力：按真实痛点排优先级

| 候选能力 | 用户价值与依赖 | 决策 |
|---|---|---|
| 导入前检查器 | 空间不足、对象缺失、重复与目标位置提前可见；依赖 F01/F09 | 第一优先，和可靠性修复一起交付 |
| 导入收据与完整性凭证 | 知道哪些已保存、去哪了、哪些失败；流式摘要可佐证本地保存一致性 | 第一优先；若没有可信源摘要，只能称本地摘要，不能宣称与相机逐字节比对已证实 |
| 拍摄批次快速选片 | 按日期/时间断点成组，RAW+JPEG 配对，最近一次拍摄一键导入 | 第二优先，先证明身份与快照稳定；不依赖 AI |
| 云创排布差异预览与快照 | 替换前可理解影响，失败后可对照恢复 | 第一优先；快照只证明云端前态，机身确认另列 |
| 连接自检与脱敏诊断包 | 少走“热点连上却传不了”排查循环；只导出白名单字段 | 第二优先，默认本地，由用户发起导出 |
| NP3 只读检查与导出 | 显示参数、格式异常、兼容性证据；帮助用户管理自己的方案 | 后续小实验；先修 parser 边界与样本回归 |
| 机身槽位备份/恢复 | 高价值但涉及真实写操作与兼容性 | 阶段门：能力探测→读→备份→确认→写→读回；没有设备证据不承诺 |
| AI 推荐、社区、订阅、远控全家桶 | 当前缺少明确用户需求与成本收益证据 | 暂缓；不让个人工具变成运营平台 |

产品主张建议：一个可信、透明、少打扰的 Nikon 原片导入与创意方案助手。个人使用阶段不增加账号体系、广告和分析 SDK。增长以兼容矩阵、清晰故障模板、开源复现样本与摄影工作流文档为主；商业化在用户访谈和稳定性数据出现后再决策。数据指标默认用本地计数或显式自愿反馈，不推导不存在的留存与市场规模。

## 10. 分批路线与放行标准

| 批次 | 内容与依赖 | 验收门 |
|---|---|---|
| A 资产保护 | F01/F02/F03/F06；照片身份、脱敏、明确收藏身份、关联查询三态 | 同名碰撞不丢原件；假凭据无泄漏；歧义身份不自动删除 |
| B 云创一致性 | F04/F05/F12/F14；单飞认证、写命令协调、前后读回、代际守卫 | 每步失败注入与官网并发冲突实验；已知/未知结果如实区分 |
| C 会话与发布 | F07/F08/F09/F10；状态拆分、有序停止、发布恢复、稳定快照 | 断开/重连/发布取消/进程中断测试；旧句柄不跨会话 |
| D 协议独立加固 | F11 与 NP3 边界；不混入交互新功能 | loopback 回归、坏包边界、既有 JPEG/NEF 真机哈希对照 |
| E 体验闭环 | F15、预检、收据、返回一致、通知和可访问性 | 用户任务走查；TalkBack/200% 字体/息屏/弱网实测 |
| F 扩展实验 | 拍摄批次、NP3 只读检查、排布恢复 | 小范围反馈与能力证据决定去留，不提前公开承诺机身写入 |

每批单独提交与可回滚变更，协议常量与功能变更分批。报告不凭空估算精确工期；A 先于扩大使用，B/C 可在不同文件负责人下推进，D 需要单独设备对照窗口。

推荐测量：连接各阶段耗时、首屏时间、每文件下载/发布耗时、批次成功率、取消完成时间、相册重复/误删计数、云创结果未知率、凭据日志泄漏数。先收基线再设性能目标；资产误删与凭据泄漏目标为零。144 个测试注解不替代这些行为验收。

## 11. 本轮未验证与待补证清单

1. JVM、lint：缺 Java Runtime；恢复 JDK17+ 后运行原命令，失败必须逐项处理，不能沿用旧通过数。
2. MediaStore 身份碰撞、取消复制、进程中断恢复：需要 Android 测试环境/真机；当前为源码缺陷与风险路径。
3. 云创中间失败、同名收藏、refresh 轮转、官网并发修改：用 fake API 先做故障注入，再在明确的可恢复测试账号数据上做验证。
4. 真实机身同步、各型号兼容、长时间息屏、Android 厂商后台限制：本轮未测，既有 NEF 哈希只证明对应设备/样本记录。
5. 视觉与可访问性：没有截图或运行界面，不对视觉完成度打数值分。
6. 凭据历史残留：未读取真实密钥或诊断文件；只确认源码会写/会记，清理需单独说明对象与授权。
7. 没有应用修复提交、APK、发布、依赖安装、CI 实跑或竞品运行；新能力均为评审决策而非已实现功能。

## 12. 五项简报

- 整体思路：按照片、账号、云创资产优先分层治理；保留已有证据支持的协议与连接核心。
- 技能与工具：codebase-memory、ponytail-audit、ui-ux-pro-max、agent-reach；当前源码读查、Git、GitHub CLI、Exa、Gradle 尝试；minimax-docx 前置检查缺依赖，使用 office-docx/python-docx 回退。
- 素材来源：当前 0bbfbc4 源码、PRD/PROGRESS、现有测试与上述公开项目和官方文档；历史真机数据明确标识。
- 决策：不整体重写；先资产保护，再一致性与生命周期，最后导入闭环与高价值小工具。应用修复等待下一批明确执行任务。
- 未做事项与文件清单：详见第 11 节；本轮新增本 Markdown、同名 DOCX 与 create_final_review_20261114.py；旧报告、应用源码与工程配置未修改。
