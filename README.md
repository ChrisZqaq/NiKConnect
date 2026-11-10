# NiKConnect

Android 优先的 Nikon 相机本地连接、浏览与原文件导出工具。Kotlin + Jetpack Compose + PTP/IP 直连，无账号、无云端、无服务端。

当前真机基线：Nikon Zf（PTP/IP 连接、照片列表、缩略图、JPEG 原图下载已验收）。

## 文档

- 产品与验收门：[docs/PRD.md](docs/PRD.md)
- 技术证据分级：[docs/references/TECHNICAL_REFERENCES.md](docs/references/TECHNICAL_REFERENCES.md)
- 工程规范：[CLAUDE.md](CLAUDE.md)（`AGENTS.md` 为其符号链接）
- 进展与问题记录：[PROGRESS.md](PROGRESS.md)
- 验收记录：[docs/acceptance/](docs/acceptance/)
- 交互原型（仅视觉参考）：[docs/prototype/](docs/prototype/README.md)

## 本地检查

需要 JDK 17 与 Android SDK（路径在未跟踪的 `local.properties`）。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

构建产物 `app/build/outputs/apk/debug/` 仅用于个人真机测试。

## 许可

见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
