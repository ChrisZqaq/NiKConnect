# NiKConnect 全项目评审与重构决策报告

版本：1.0｜整理日期：2026-11-13｜审查基线：0.6.1-alpha

文档说明：本报告依据本次会话已完成的源码静态审查与公开资料检索整理；不是新增一轮代码审查，也不代表缺陷已经修复。源码路径与行号对应审查时点，后续代码变化后须重新核对。报告中的“工作树干净”“未新增文件”描述审查结束时的历史状态；本次文档交付新增 Markdown 与 Word 文件。

证据口径：源码事实、风险推断、建议方案与待真机验证分开阅读；高置信度静态判断不等同于设备复现通过。

# 总体决策

**保留 PTP/IP 与文件导出底座，重构会话所有权、云创请求治理和界面状态边界。现阶段推倒重来收益低、回归风险高；继续叠加账号排布等功能，也会放大现有问题。**

项目已经具备个人工具的实际价值，但距离成熟产品，主要差距不是功能数量，而是：**取消是否及时生效、状态是否真正隔离、失败是否准确表达、传输结果是否可信、验证是否可重复。**

**置信度：高。依据：当前源码、相关 JVM 测试、产品文档及验收记录；运行与真机结论另列。** 本轮为只读审查，没有修改应用代码、安装依赖、生成 APK 或提交 Git。最终工作树干净。

## 一、优先处理的缺陷

### 1. 云创频控治理没有形成闭环｜P1，高置信度

有两个独立问题：

- **锁没有覆盖请求执行。** [CloudHttp:85–110](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt#L85-L110>) 只在计算间隔时持锁，随后释放锁再执行 HTTP；慢请求会与后续请求重叠。
- **业务频控错误位于重试循环之外。** [CloudHttp:66–75](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt#L66-L75>) 重试 `send()`，但 HTTP 200 中的 `E01_0701` 在后续解析时才抛出，见 [CloudHttp:133–147](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt#L133-L147>)。因此声明的业务错误退避没有覆盖实际解析路径。

此外，目录客户端与账号客户端各自默认创建 `CloudHttp()`；账号页还会重复创建客户端。统一了类名，并不等于统一了调度器。依据：[目录客户端:51–66](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudCatalogClient.kt#L51-L66>)、[账号客户端:39–54](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudAccountClient.kt#L39-L54>)。

**重构：** 注入进程内共享的云创请求协调器；串行执行 API 请求，将状态码、业务错误解析和退避放在同一个执行边界；使用单调时钟，统一冷却时间。封面图片另设有界加载策略，不机械地占用账号 API 队列。

**验收：** 目录、详情、账号并发触发时最大在途 API 请求数为 1；HTTP 200 携带频控码时确实退避；取消等待中的请求后不继续发送。

### 2. 相机会话与云创身份耦合｜P1，高置信度

[MainViewModel:366–372](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L366-L372>) 在断开时执行 `_state.value = UiState()`，连同云创 token、guid、视图偏好一起重置；错误路径也会构造新的整体状态，见 [MainViewModel:328–335](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L328-L335>)。

**用户影响：** 本地相机操作会意外影响互联网账号体验，违背本地链路与云创分离的产品原则。

**重构：** 拆成 `CameraSessionState`、`TransferState`、`CloudAuthState`、`UserPreferences`。断开只改变相机会话及其任务，不重建整个应用状态。

**验收：** 登录云创→连接相机→断开→重连，云创身份与图库偏好保持；显式退出云创才清除身份。

### 3. 下载取消缺少分块协作点｜P1，高置信度；时延待实测

[PhotoDownloader:34–46](<../app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt#L34-L46>) 在整个同步复制完成后才检查协程取消；[PtpIpClient:180–196](<../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L180-L196>) 的分块循环没有取消检查。现有取消测试使用可挂起的 `await()`，并未覆盖真实同步 socket 读取，见 [DownloadQueueTest:81–101](<../app/src/test/java/com/chrisz/nikconnect/ui/DownloadQueueTest.kt#L81-L101>)。

**用户影响：** 点击取消后，可能仍把后续数据全部读取完才停止发布；这与用户理解的“停止传输”存在明显偏差。

**重构：** 每次发起下一块请求前检查取消；当前事务要么完整排空后停止，要么显式关闭连接，避免留下半个事务污染后续请求。发布相册阶段也应定义取消边界。

**验收：** 慢速假相机中途取消后不再请求下一块；无半成品发布；取消后下一个任务的行为明确且有测试。

### 4. 唤醒锁释放时机错误｜P1，高置信度

下载执行体的 `finally` 调用释放判断时，队列任务仍是 `RUNNING`；终态是在执行体返回之后才写入。因此最后一项完成后，锁可能仍持有到超时。依据：[MainViewModel:91–100](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L91-L100>)、[MainViewModel:284–290](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L284-L290>)、[DownloadQueue:122–135](<../app/src/main/java/com/chrisz/nikconnect/ui/DownloadQueue.kt#L122-L135>)。

**重构：** 单任务锁就在其 `finally` 无条件释放；若采用整队列锁，则由唯一队列所有者依据活动状态转换管理，不混用两种策略。

**验收：** 成功、失败、取消、断开四条路径结束后锁均释放；长任务超过当前 30 分钟上限的行为单独验证。

### 5. 账号 ViewModel 可能保留旧凭据｜P1，高置信度

[CloudAccountScreen:60–64](<../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountScreen.kt#L60-L64>) 仅以 guid 作为 ViewModel key，而 token 是构造时固定值。相同账号重新登录后，旧实例可能继续使用旧 token；退出界面本身也不等于清理 Activity 所持有的 ViewModel。

**重构：** 使用独立认证会话标识和明确的凭据更新/清理机制；token 不进入 key、日志或诊断文本。退出时取消账号请求并清空账号数据。

**验收：** 同 guid 换 token 后新请求使用新身份；退出后旧请求不回填，旧凭据不继续驻留业务对象。

### 6. WebView 凭据捕获缺少来源约束｜P1，高置信度

[CloudLoginScreen:301–340](<../app/src/main/java/com/chrisz/nikconnect/ui/CloudLoginScreen.kt#L301-L340>) 对导航放行，捕获请求头、URL 和页面存储时没有先校验可信 HTTPS origin。登录页还暴露了显示、复制 token 的调试交互，见 [CloudLoginScreen:215–265](<../app/src/main/java/com/chrisz/nikconnect/ui/CloudLoginScreen.kt#L215-L265>)。

**重构：** 对登录导航、API 凭据提取分别制定精确域名白名单；外部链接交给系统浏览器；普通界面仅显示登录状态。明确 WebView cookie、站点存储与退出账号的清理语义，不能把“原生变量仅存内存”等同于“整个登录过程零持久化”。

**验收：** 非可信页面或请求里的 token/guid 不被接纳；退出行为符合产品定义；诊断输出经过脱敏。

## 二、直接影响体验的确定性问题

| 问题 | 源码依据 | 建议与验收 |
|---|---|---|
| 加载下一页会清空选择 | [图库:304–305](<../app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt#L304-L305>) 以全部句柄列表作为保存状态的输入 | 选择绑定会话而非当前页数组；加载更多后保留已选项 |
| JPEG/RAW 筛选下“全选”会选中隐藏格式 | [图库:427–441](<../app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt#L427-L441>) 使用全部 `state.photos` | 默认“全选当前筛选的已加载照片”，明确数量和范围 |
| 断线后传输页入口消失 | [主导航:142–173](<../app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt#L142-L173>) 将传输入口绑定连接状态 | 导航保持稳定；离线仍能查看结果、失败原因和重新连接动作 |
| 文件名被当作已下载身份 | [MainViewModel:345–358](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L345-L358>)、[图库:297–302](<../app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt#L297-L302>) | 文件名会跨卡、跨机重复；保存来源元数据与本地 URI，区分“曾导入”和“本地仍存在” |
| 账号槽位请求没有迟到结果保护 | [CloudAccountViewModel:113–141](<../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountViewModel.kt#L113-L141>) | 切换相机、返回列表时取消旧任务，并校验请求代次与 cameraId |
| 未识别响应被解释成空账号 | [CloudAccountClient:69–90](<../app/src/main/java/com/chrisz/nikconnect/cloud/CloudAccountClient.kt#L69-L90>) | 区分成功空列表、响应结构变化、认证失败；停止递归寻找任意带 id 的数组 |
| 封面缓存缺少自身容量治理 | [RemoteImage:35–56](<../app/src/main/java/com/chrisz/nikconnect/ui/RemoteImage.kt#L35-L56>) | 加磁盘预算、淘汰、同请求合并、响应大小限制；签名 URL 变化不应无限累积旧缓存 |

**产品承诺也需收紧。** 当前账号页仍写“App 只负责排布”，同时拖拽排布尚未实现，见 [账号页:193–202](<../app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountScreen.kt#L193-L202>)。应明确写成“当前只读查看”，开发批次说明移到开发文档。

## 三、架构怎么改，而不是怎么堆层

### 建议结构

```text
Compose 页面
  ├─ 相机/图库状态
  ├─ 传输状态
  └─ 云创目录/认证/账号状态
       ↓
CameraSessionOwner        CloudRepository
  ├─ 网络与连接生命周期    ├─ 共享 API 调度与退避
  ├─ 串行 PTP 事务          ├─ 明确响应契约
  ├─ 会话内图库快照         └─ 目录与详情缓存
  └─ DownloadQueue
       └─ PhotoDownloader → MediaStore
```

**先在现有单 app 模块内划清职责，不引入微服务、多模块大拆分或通用插件框架。**

- **协议层：保留并加固。** 双通道关闭、事务编号校验、包长度检查值得保留。补交易累计数据上限、START_DATA 长度与阶段顺序检查；当前单包上限不等于整笔事务有上限。依据：[事务处理:199–230](<../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L199-L230>)。
- **相机层：集中会话所有权。** 当前生命周期分散在 Repository、ViewModel、队列、保活服务。服务只发通知，实际连接由 ViewModel 管理，见 [保活服务:22–37](<../app/src/main/java/com/chrisz/nikconnect/camera/ConnectionKeeperService.kt#L22-L37>)。若要求离开 Activity 后继续传输，应让服务或应用级所有者真正拥有会话，而不是只让服务“陪跑”。
- **分页：固定连接内句柄快照。** 当前每页重新获取并反转全部句柄，见 [PtpIpClient:115–137](<../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L115-L137>)。拍摄新增文件时偏移分页可能重复或遗漏；显式刷新再建立新快照。
- **下载：保留暂存文件和 IS_PENDING。** 这是正确方向。补保存阶段的失败注入、空间不足、进程中断后的 pending 项恢复/清理验证；NRW 的 MIME 也应按实际格式区分，而不是统一声明 NEF。
- **界面：拆状态，而非只拆文件。** 将大页面拆成多个文件只能改善阅读；真正要解决的是身份、连接、选择、任务结果的生命周期彼此独立。

### 值得删减或隔离的内容

- `shrink:` 合并 QueueTask 与 DownloadItem 的重复状态表达，或只保留一个明确的展示投影，避免双份 Flow 中转。依据：[MainViewModel:30–38](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L30-L38>)、[MainViewModel:85–123](<../app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt#L85-L123>)。
- `yagni:` NP3 编解码和 B0 探针暂不扩展进普通用户流程。源码搜索显示当前没有应用业务调用；与“不维护 NP3”的产品定位不一致。保留研究价值时移到明确的研究边界，删除则另行批准。依据：[NP3 实现](<../app/src/main/java/com/chrisz/nikconnect/np3/Np3File.kt>)、[探针接口:147–178](<../app/src/main/java/com/chrisz/nikconnect/ptp/PtpIpClient.kt#L147-L178>)。
- `shrink:` 账号接口拿到真实脱敏样本后，用明确适配器替代递归猜测响应。

没有完成可编译迁移实验，因此不虚报可删除行数或依赖数。

## 四、从用户任务重新组织体验

**核心体验应是：连接 → 找到照片 → 选对照片 → 确认导入 → 找到结果。**

1. **连接页变成诊断向导。** 展示 Wi-Fi 获取、TCP、握手、读取相机四阶段；错误给下一步动作。手填 IP 放到高级选项，不让用户先理解网络术语。
2. **图库让用户放心选。** 普通点击预览、长按多选；跨分页保留选择；筛选后明确隐藏选中项数量；导入按钮同时显示张数与总字节数。
3. **传输页变成结果中心。** 区分排队、读取、保存到相册、完成；支持打开本地文件、重试失败项和查看失败原因。断开不会让结果页消失。
4. **云创独立成立。** 游客浏览不要求登录；账号失效只影响账号动作，不阻断目录。返回详情列表恢复位置；“重新进入回顶部”和“从详情返回保留位置”分别定义，避免用强制回顶修补所有场景。
5. **先验证可用性，再改视觉。** 当前没有运行中截图或设备交互证据，色彩、字号、帧率和 TalkBack 质量不作已通过判断。保留现有去叠层方向，专项验证大字体、48dp 触控、语义状态及系统返回行为。

## 五、外部借鉴与新增能力

本轮实际检索了 GitHub、技术资料和产品评测，**不是全平台用户研究**。

| 对照来源 | 应借鉴 | 不照搬 |
|---|---|---|
| [libgphoto2](https://github.com/gphoto/libgphoto2/) | 协议实现与设备能力差异的长期积累 | 为个人 Android 工具整体引入庞大原生后端 |
| [Fimagena/libptp](https://github.com/Fimagena/libptp) | Java PTP/IP 实现的交叉核对价值 | 未验证就替换已经真机跑通的链路 |
| [qDslrDashboard](https://dslrdashboard.info/) | 按设备逐步修正属性和兼容性的工程方法 | 遥控、堆栈、延时等整套复杂功能 |
| [SnapBridge 实测](https://www.dpreview.com/articles/nikons-snapbridge-app-tested-how-well-does-it-actually-work/) | AP/STA 网络切换与格式筛选带来的真实使用摩擦 | 将单篇评测当作所有手机相机组合的事实 |
| [Android 生命周期文档](https://developer.android.com/topic/libraries/architecture/coroutines) | 工作所有权和取消边界 | 把前台通知等同于任务生命周期已经正确 |

**最值得新增的不是 AI 滤镜，而是三个低负担、高价值工具：**

- **导入前检查单：** 已选数量、JPEG/RAW 分布、总大小、可用空间、疑似重复项、网络状态。先解决“我会不会选错、存不下”。
- **导入回执：** 来源元数据、本地 URI、大小、完成时间、可选本地 SHA-256。明确区分“算出本地哈希”和“已与卡内源文件比对”。
- **脱敏诊断包：** 版本、机型与固件、阶段耗时、错误码、队列终态，不含 token、账号标识和照片内容；由用户主动导出。

JPEG+RAW 配对选择、按拍摄日期批量导入可作为下一档；自动跨会话续传、自动重试写操作、Live View、多品牌、AI 修图继续后置。

## 六、实施顺序与验收门

| 批次 | 范围 | 进入下一批的条件 |
|---|---|---|
| A：可靠性修复 | 云创共享调度、业务频控重试、凭据生命周期、取消与唤醒锁 | 可控假服务/假相机测试覆盖成功、取消、错误和迟到结果 |
| B：状态与任务边界 | 相机与云创状态分离、统一会话所有者、图库快照 | 断开重连不串数据；Activity 生命周期行为明确 |
| C：体验闭环 | 多选、筛选、稳定导航、保存阶段、结果打开 | 从连接到本地文件的一条完整设备验收链 |
| D：实用增强 | 检查单、回执、诊断包、格式配对 | 每项有独立真实使用场景与验收，不以功能数量作为成功指标 |

不把协议常量调整与 UI 改造混批；维持 15740、4MiB 和 350ms 基线，性能变化必须同条件对照。

## 七、本轮验证与未验证项

**已确认：** 当前版本配置为 0.6.1-alpha；工作树干净；现有主要生产层、界面与相关测试已做静态审查；没有发现需要整体重写的证据。图谱索引落后于当前源码，结论以实际文件读取为准。

**本轮测试没有运行成功。** 执行：

```bash
./gradlew --offline :app:testDebugUnitTest :app:lintDebug
```

启动即报 `Unable to locate a Java Runtime.`；随后检查常见 JDK 与应用安装位置，未找到可用运行时。没有安装 JDK，也没有把历史“60 项通过”冒充本轮结果。

**重要未验证清单：**

- RAW/JPEG 与卡内原文件 SHA-256 一致性，仍见 [0.4 验收表](<../docs/acceptance/0.4.md#L12-L38>)。
- 当前云创账号接口响应、9 槽语义、认证更新与退出闭环。
- 真实慢网络取消时延、锁屏耗电、Activity 销毁、进程终止及保存中断。
- 300+ 对象图库、传输中刷新、相机新增文件后的分页稳定性。
- 双网络路由、蜂窝封面流量、缓存增长与滚动帧率。
- 无障碍、大字体与其他相机/ROM 兼容性。
- 当前跟踪目录中未发现 GitHub Actions 或 Android 仪器测试；其他外部 CI 是否存在未核验。

### 五项简报

- **整体思路：** 保住已验证底座，先修生命周期和身份隔离，再打磨任务闭环。
- **技能与工具：** codebase-memory、ponytail-audit、ui-ux-pro-max、agent-reach；源码读取、内容搜索、Git、GitHub CLI、Exa、Gradle 检查尝试。
- **素材来源：** 当前源码与测试、[产品需求](<../docs/PRD.md>)、[进展记录](<../PROGRESS.md>)、[0.5 验收](<../docs/acceptance/0.5.md>)及上述公开来源。
- **决策：** 不整体重写；账号排布写能力后置；优先完成 A 批可靠性修复。
- **未做事项与文件清单：** 未修改或新增文件，未构建 APK，未执行设备验收，未做全平台舆情调查。Agent Reach 检测到 1.6.1 更新，本轮未升级。

**最终判断：NiKConnect 的上限，不是成为功能最满的尼康工具，而是成为“连接得明白、选择得放心、导入有凭据、出错能恢复”的可靠工具。当前最值得投入的重构，恰好都指向这个目标。**