# NiKConnect 工程规范与工作约定

面向个人使用的 Nikon 相机 Android 传图工具，Kotlin + Jetpack Compose + PTP/IP 直连。产品范围、验收门与证据规则见 [docs/PRD.md](docs/PRD.md)，进展记录见 [PROGRESS.md](PROGRESS.md)。

## 1. 分层与架构

| 层 | 目录 | 职责 | 禁止 |
|---|---|---|---|
| 协议 | `ptp/` | 小端编解码、PTP/IP 数据包、事务、数据集、操作码/响应码 | 不做 I/O 决策，不依赖 Android UI |
| 相机 | `camera/` | Wi-Fi 获取、候选地址、会话资源、分块下载、MediaStore 发布 | 不解析 UI 状态 |
| 云创 | `cloud/` | 云创 HTTP：零登录目录/详情、登录态账号接口、串行限速与频控退避、响应解析 | 不发 PTP 命令；token 不落盘、不进日志 |
| 界面 | `ui/` | 页面状态、用户操作、generation 管理、错误展示 | 不直接解析 PTP 字节，不自建 socket；不发 HTTP（经 `cloud/`） |
| 滤镜（未来） | `picturecontrol/` | NP3 解析、槽位模型、备份、写入读回 | 不在下载事务中夹带隐式副作用 |

硬约束（违反即 bug，不接受以性能为由豁免）：

- 相机命令保持串行：同一 `PtpIpClient` 事务 `@Synchronized`，迟包/错配/超时直接关闭双通道。
- 云创请求遵守服务端频控：`CloudCatalogClient` 内部串行限速与 `E01_0701` 退避，新增端点复用同一入口，不在页面里裸发请求。
- 会话隔离：`connectionId`/generation 是异步结果与断点数据的边界；跨连接不复用句柄、缩略图、任务或 `.part` 文件。
- 只读相机：不加入删除相机文件或静默覆盖的行为；第二阶段写操作必须先读、备份、用户确认、读回。
- 文件完整性高于速度：不以删除校验换取"提速"；协议常量（15740 端口、4MiB 分块、350ms settle delay）改动必须配对照实验。

## 2. 代码风格

- **Kotlin 官方风格**（`kotlin.code.style=official`，已配置于 `gradle.properties`），4 空格缩进，120 列。
- 命名：类/函数 PascalCase，变量/属性 camelCase，常量 UPPER_SNAKE，包名全小写。Compose 组件跟随 `NiKConnectTheme`/`NiKConnectApp` 模式。
- 非空断言 `!!`、`lateinit` 慎用；协议解析处用显式 `require`/边界检查并给出可诊断错误信息（中文错误文案可读优先）。
- 新增公共函数必须有 KDoc（一句话职责 + 关键约束）；私有实现可不写。
- 跟随现有文件风格；不做顺带的格式化或相邻重构（PRD 9.3：协议/分块改动与功能改动不混批）。

## 3. 测试规则

- 分层验证三态，如实标注：**代码已实现 / JVM 已通过 / 真机已通过**。JVM 与构建通过不得写成真机验证。
- 协议常量、编解码、事务、断点逻辑改动 → 必须先加/改 JVM 测试（`app/src/test/`，假相机 loopback 模式，见 `PtpIpClientLoopbackTest`）。
- 测试保护行为意图，不做仪式性断言；一个测试一个行为点。
- 本地检查命令（JDK 17+，SDK 路径在未跟踪的 `local.properties`）：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

网络受限时加 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`；依赖已缓存时可 `--offline`。

## 4. 版本与提交

- 版本号 `0.MINOR.PATCH`：MINOR 对应 PRD 阶段（0.2 基础导出、0.3 后台、0.4 RAW、0.5 云创原生浏览、0.6 云创账号、B0/B1 滤镜），PATCH 为该阶段内修复。版本写 `app/build.gradle.kts` 的 `versionName`/`versionCode`。
- 一个提交一个任务：开发 → 测试 → 文档 → commit，不攒大包。提交信息英文祈使句，格式 `type: summary`（feat / fix / refactor / docs / test / chore）。
- 纯重构（如改名）单独成批，不与功能混提交。
- 每个版本发布前打 tag（`v0.1.1` 风格）。

## 5. 验收与文档维护

- 每版本真机验收后，按 [docs/acceptance/TEMPLATE.md](docs/acceptance/TEMPLATE.md) 在 `docs/acceptance/` 落一份记录；未跑的项目留空并写原因，不填推测。
- 开发过程中持续更新 `PROGRESS.md`：批次完成记录"做了什么/验证了什么/还剩什么"；问题按"发现→分析→处置"记录。
- 文档先改 Markdown 源，再生成 DOCX/zip（见 docs/README.md 维护规则）。
- 真机事实、源码观察、逆向参考、推断分开记录（证据分级见 TECHNICAL_REFERENCES.md §1）。

## 6. Agent 协作

- 代码发现优先 codebase-memory-mcp；图谱不足再搜源文件。
- 修改前读调用方、协议处理和相关测试；审查请求只报告缺陷，不顺带修应用代码。
- 同一文件同一时间只允许一个写者。
- 不提交本机路径、凭据、APK 或真机抓包。APK 构建/发布需用户明确要求。
