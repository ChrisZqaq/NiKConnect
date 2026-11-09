# ZF Relay 工作约定

面向个人使用的 Nikon Zf Android 传图工具，使用 Kotlin、Compose 和 PTP/IP。

- 状态和已验证范围见 `PROGRESS.md`；审查与后续开发见 `docs/NEXT_STEPS.md`。
- 代码发现优先使用 codebase-memory-mcp；图谱不足时再搜索源文件。
- 修改前读调用方、协议处理和相关测试；审查请求只报告缺陷，不顺带修应用代码。
- 保持现有分层：`ptp/` 处理协议，`camera/` 处理网络和保存，`ui/` 处理界面状态。
- 相机命令保持串行；当前应用只读、下载，不加入删除相机文件的行为。
- 网络失败关闭会话，跨连接不能复用对象句柄、缩略图或下载断点。
- 不把构建或模拟相机测试通过写成真机验证通过。新增硬件能力先验证 Zf 实际行为。

## 本地检查

需要 JDK 17 和 Android SDK，SDK 路径放在未跟踪的 `local.properties` 中。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

不要提交本机路径、凭据、APK 或真机抓包。APK 的发布仍需用户明确要求。
