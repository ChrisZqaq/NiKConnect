# ZF Relay

一个面向尼康相机的极简 Android 传图应用。通过相机 Wi-Fi 与 PTP/IP 协议连接相机，浏览并下载照片，不依赖云端服务。

> 当前为早期验证版本，仅在一加 Ace 5 Pro 与 Nikon Zf 的组合上完成过手动真机验证，不代表已适配其他设备。

## 当前功能

- 通过相机热点建立 PTP/IP 连接
- 读取照片列表并显示缩略图
- 下载 JPEG 原图到手机相册
- 显示下载进度与基础错误信息
- 同一连接内下载中断后安全重试

## 安装

从 [GitHub Releases](https://github.com/ChrisZqaq/ZConnect/releases) 下载 APK。当前附件使用调试签名，安装前请确认来源；它不是应用商店正式发布版。

系统要求：Android 10 或更高版本。

## 使用

1. 在 Zf 的「网络菜单 → 连接至智能设备 → Wi-Fi 连接」中开启相机 Wi-Fi。
2. 手机连接该热点。
3. 打开 ZF Relay，点击「连接」。
4. 浏览照片并选择下载。

## 构建

需要 JDK 17 与 Android SDK。项目使用 Gradle Wrapper，无需单独安装 Gradle。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## 开发状态与限制

当前进展与已知限制见 [PROGRESS.md](PROGRESS.md)。MVP 审查、开发顺序和验收标准见 [后续开发计划](docs/NEXT_STEPS.md)。

## 许可与参考

项目采用 [MIT License](LICENSE)。协议实现参考及第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
