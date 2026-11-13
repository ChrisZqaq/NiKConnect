# NiKConnect 文档索引

## 主文档

- [PRD.md](PRD.md)：产品原则、范围、分阶段需求、验收门与风险。
- `NiKConnect_PRD.docx`：PRD 的可阅读 Word 版本；由 Markdown 源生成，改动以 PRD.md 为准。
- `NiKConnect_参考附件.zip`：打包本目录（不含 docx）供外发。

## 工程与进展

- 仓库根 `CLAUDE.md`：代码风格与工程规范（`AGENTS.md` 为其符号链接）。
- 仓库根 `PROGRESS.md`：进展与问题记录（持续更新）。
- [acceptance/](acceptance/)：验收记录，每版本一份，模板见 [acceptance/TEMPLATE.md](acceptance/TEMPLATE.md)。最新：[acceptance/0.6.md](acceptance/0.6.md)（云创账号只读，2026-11-13 真机通过）；另见 [acceptance/0.5.md](acceptance/0.5.md)（云创原生浏览，含四项遗留）。

## 技术与证据

- [references/TECHNICAL_REFERENCES.md](references/TECHNICAL_REFERENCES.md)：旧项目源码、Z-Relay Android/iOS 逆向、开源项目和最小协议集的证据表。
- [references/NIKON_CLOUD_RESEARCH.md](references/NIKON_CLOUD_RESEARCH.md)：尼康云创接口实测记录——零登录目录/详情、签名封面 URL 有效期、IP 级频控行为、登录态收藏与相机槽位 API（C001–C005）。
- [research/np3-format.md](research/np3-format.md)：NP3 二进制结构（`NCP\0` 魔数、头、TLV、BI0 自定义曲线尾）逆向结论。
- [research/np3-generation-report.md](research/np3-generation-report.md)：云创 API 参数 → NP3 的字段映射标定、十例字节对照与缺口说明。
- [data/recipes/list.json](data/recipes/list.json)：云创官方书 144 个方案索引（已剔除签名 URL）。
- [data/recipes/details/](data/recipes/details/)：144 个方案详情快照（参数、EXIF、说明，签名 URL 已剥离 query）。
- [data/recipes/generated/](data/recipes/generated/)：按 API 参数生成的 10 个 NP3 样本，用于字节对照。
- [references/ZF_Relay_真机验证记录.md](references/ZF_Relay_真机验证记录.md)：旧项目原始进度记录副本。
- [references/ZF_Relay_MVP审查与后续开发.md](references/ZF_Relay_MVP审查与后续开发.md)：R1–R4 审查与既有验收标准副本。
- [references/ZRelay_Android_逆向可行性评估.md](references/ZRelay_Android_逆向可行性评估.md)：Android 样本评估副本。
- [references/ZRelay_iOS_REA_可行性验证.md](references/ZRelay_iOS_REA_可行性验证.md)：iOS 样本 REA 验证副本。

## KIMI-CODE 原型

- [prototype/README.md](prototype/README.md)：运行方法与范围说明。
- [prototype/index.html](prototype/index.html)：单文件交互原型。

## 维护规则

1. 先更新 Markdown 源文档，再生成 DOCX / 重打包 zip（本机暂无 pandoc/soffice 时先记未同步，不提交过期 docx）。
2. 真机事实、源码观察、开源参考和推断分开记录。
3. 未执行的真机测试保持"未验证"，不得写成"已支持"。
4. 近期顺序：0.6 批次 2（拖拽排布 `PATCH /camera/{guid}/{cameraId}`、收藏到书）→ 0.4 遗留 RAW 哈希与分块对照 → 0.5 遗留三项。0.5 与 0.6 批次 1 均已于 2026-11-13 真机通过。
