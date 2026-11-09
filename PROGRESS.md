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

## 2026-11-10 · 仓库规整（无功能改动）

- 0.1.1 未提交工作树提交为基线 `e07e137`。
- 清理：删除仓库内历史 APK、错误截图、ui-prototype、旧 prototype 副本、`.android-build`。
- 文档并入：NiKConnect 独立文档目录（PRD、references、prototype、验收模板）合并到本仓 `docs/`；删除内容重复的 `docs/NEXT_STEPS.md`（与 references 副本一致）。
- 改名：ZF Relay → NiKConnect。包名 `com.chrisz.zfrelay` → `com.chrisz.nikconnect`；app id、`app_name`、主题名 `Theme.ZfRelay` → `Theme.NiKConnect`、Compose 函数 `ZfRelayTheme/ZfRelayApp/ZfRelayContent` → `NiKConnect*`、MediaStore 目录 `Pictures/ZF Relay` → `Pictures/NiKConnect`、rootProject 名。
- 新增工程规范 `CLAUDE.md`（AGENTS.md 符号链接保留）与验收记录模板 `docs/acceptance/TEMPLATE.md`。

### 已知影响

- **改名后为新应用身份**：applicationId 变化，新 APK 安装不会覆盖旧 "ZF Relay"，需手动卸载旧应用；旧应用已发布的相册文件留在 `Pictures/ZF Relay`，新文件写入 `Pictures/NiKConnect`。

## 开放问题

1. 真实图库规模（120 / 300 / 更大）→ 决定 0.2 分页与批量元数据优先级。
2. 后台下载是否当前痛点 → 决定 0.3 排期。
3. RAW 文件类型与大小分布 → 0.4 样本。
4. NP3 写字段与机型能力（B0 探针前不排期）。
