# NiKConnect 文档索引

## 主文档

- [PRD.md](PRD.md)：产品原则、范围、分阶段需求、验收门与风险。
- `NiKConnect_PRD.docx`：PRD 的可阅读 Word 版本，由 Markdown 源文档生成。
- `NiKConnect_参考附件.zip`：打包本目录（不含 docx）供外发。

## 工程与进展

- 仓库根 `CLAUDE.md`：代码风格与工程规范（`AGENTS.md` 为其符号链接）。
- 仓库根 `PROGRESS.md`：进展与问题记录（持续更新）。
- [acceptance/](acceptance/)：验收记录，每版本一份，模板见 [acceptance/TEMPLATE.md](acceptance/TEMPLATE.md)。

## 技术与证据

- [references/TECHNICAL_REFERENCES.md](references/TECHNICAL_REFERENCES.md)：旧项目源码、Z-Relay Android/iOS 逆向、开源项目和最小协议集的证据表。
- [references/ZF_Relay_真机验证记录.md](references/ZF_Relay_真机验证记录.md)：旧项目原始进度记录副本。
- [references/ZF_Relay_MVP审查与后续开发.md](references/ZF_Relay_MVP审查与后续开发.md)：R1–R4 审查与既有验收标准副本。
- [references/ZRelay_Android_逆向可行性评估.md](references/ZRelay_Android_逆向可行性评估.md)：Android 样本评估副本。
- [references/ZRelay_iOS_REA_可行性验证.md](references/ZRelay_iOS_REA_可行性验证.md)：iOS 样本 REA 验证副本。

## KIMI-CODE 原型

- [prototype/README.md](prototype/README.md)：运行方法与范围说明。
- [prototype/index.html](prototype/index.html)：单文件交互原型。

## 维护规则

1. 先更新 Markdown 源文档，再生成 DOCX / 重打包 zip。
2. 真机事实、源码观察、开源参考和推断分开记录。
3. 未执行的真机测试保持“未验证”，不得写成“已支持”。
4. 近期顺序：0.1.1 真机回归 → 0.2 基础导出 → 按需后台/RAW → B0/B1 滤镜验证。
