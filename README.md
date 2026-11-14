# NiKConnect

Android 优先的 Nikon 相机本地连接、浏览与原文件导出工具。Kotlin + Jetpack Compose + PTP/IP 直连。照片链路完全本地，不经过自建服务端。

云创浏览是独立能力：手机有互联网时可浏览尼康云创 144 个官方色彩方案（目录与详情零登录可取）与优化校准参数；方案下发到相机仍由相机自身联网完成，App 不宣称代办同步。

当前真机基线：Nikon Zf（连接、照片列表、缩略图、JPEG/NEF 下载、串行队列、后台保活已验收；0.4 RAW 哈希对照与 0.5 云创原生页待验收）。

## 文档

- 产品与验收门：[docs/PRD.md](docs/PRD.md)
- 技术证据分级：[docs/references/TECHNICAL_REFERENCES.md](docs/references/TECHNICAL_REFERENCES.md)
- 工程规范：[CLAUDE.md](CLAUDE.md)（`AGENTS.md` 为其符号链接）
- 进展与问题记录：[PROGRESS.md](PROGRESS.md)
- 验收记录：[docs/acceptance/](docs/acceptance/)
- 云创接口实测：[docs/references/NIKON_CLOUD_RESEARCH.md](docs/references/NIKON_CLOUD_RESEARCH.md)
- NP3 格式与生成报告：[docs/research/](docs/research/)
- 交互原型（仅视觉参考）：[docs/prototype/](docs/prototype/README.md)

## 本地检查

需要 JDK 17 与 Android SDK（路径在未跟踪的 `local.properties`）。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

构建产物 `app/build/outputs/apk/debug/` 仅用于个人真机测试。

## 许可

见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 素材与版权

- 本 App **不内置任何 NP3 方案文件**，也不内置官方封面图与方案说明正文；方案列表、参数与封面在运行时通过尼康云创官方接口获取，或由相机侧自行拉取。
- 仓库里**不收录**第三方 `.NP3` 样本（他人作品）。`app/src/test/resources/` 下的 NP3 测试夹具改为按 `docs/research/np3-format.md` 现场拼字节，不依赖真实样本。
- 仓库不存放官方方案详情快照（参数/EXIF/说明正文）。需要复现的研究结论见 `docs/research/`，脚本需自备样本。
- 本项目与尼康无隶属或授权关系；名称与图标不代表官方。
