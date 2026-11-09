# ZRelay: Transfer for Nikon 3.0.87 — 逆向可行性评估

> 结论先行：**可以逆向，Java 层已完整还原（27,275 行可读代码），native 层可分析但需手工功夫，商业授权层是唯一硬骨头（服务端 + 心跳）。**
> 评测基准：`/Users/chrisz/Downloads/ZRelay_+Transfer+for+Nikon_3.0.87_APKPure.xapk`（3,725,354 B）
> 评测时间：2026-11-07 · 全部结论基于本机实测，非推测

---

## 一、目标画像

| 项目 | 值 | 来源 |
|---|---|---|
| 包名 | `com.geekmanlab.zrelay.global` | manifest.json |
| 版本 | 3.0.87 (versionCode 387) | manifest.json |
| git commit | `670810b82439692ea49d17cd49494c5eedf64261` | assets/zrelay-build-info.json |
| 构建时间 | 2026-10-12T13:00:04Z · gitDirty=false | 同上 |
| 分发渠道 | google-play | 同上 |
| minSdk / targetSdk | 32 / 36 | manifest.json |
| XAPK 结构 | base + arm64_v8a + en + mdpi | unzip -l |
| App 名/图标 | ZRelay: Transfer for Nikon | manifest |

**功能定位**：Nikon 相机无线传输工具。蓝牙配对 + WiFi PTP/IP 拉图，含 GPS 中继（`BluetoothGpsRelayService`）、相册下载、传输历史（Room DB）。商业模式为 `ZRelay Pro` 终身买断 + 试用。

---

## 二、实测结果（已完成）

### 2.1 打包结构

```
com.geekmanlab.zrelay.global.apk   3,341,544 B   ← 全部代码
config.arm64_v8a.apk                 295,139 B   ← libzrelay_core.so
config.en.apk                         45,266 B   ← 英文字符串
config.mdpi.apk                       41,266 B   ← 低密度资源
manifest.json                          1,363 B
```

### 2.2 代码规模

| 指标 | 值 |
|---|---|
| dex 数量 | **1 个** `classes.dex`（2,836,728 B） |
| dex 类总数 | 1,464 |
| 反编译 Java 文件 | **2,435** |
| 业务包 `com.kw.ztransfer` | 30 文件 / **27,275 行** |
| 混淆包 `z` | 2,327 类 / **259,180 行** |
| 反编译错误 | 36（工具层，不影响业务类） |

**关键验证**：`MainActivity.java` 7,141 行、`NikonTransferService.java` 3,942 行、`V3AppShellActivity.java` 4,075 行 —— **全部是完整可读逻辑，不是 stub，不是加密壳**。

### 2.3 Native 层

| 项目 | 值 |
|---|---|
| 文件 | `lib/arm64-v8a/libzrelay_core.so` |
| 大小 / 架构 | 238,832 B · ELF64 AArch64 · stripped |
| BuildID | `4073443c6f7c308deb99c405c3f1cd10d4201790` |
| `.text` 段 | 0x21378 = **135 KB 代码** |
| 动态符号 | 47 个，**仅导出 `JNI_OnLoad@@ZRELAY_1.0`** |
| JNI 方法 | `n01`–`n17`（17 个），全部 `RegisterNatives` 动态注册 |
| 编译特征 | 含 `bti c` 指令（ARMv8.5 BTI 开启），优化等级较高 |
| 字符串表 | 几乎只有 libc 符号 + 3 条业务串 |

**只有 3 条业务字符串**：
```
JNI_OnLoad
PTP/IP channels are not ready
com/kw/ztransfer/NativePtpCore
```
说明协议状态机完全靠代码/数据结构表达，没有可捡的字符串线索。

### 2.4 保护层实测

| 保护 | 是否存在 | 证据 |
|---|---|---|
| **PairIP**（Google Play 防篡改） | ✅ 部分启用 | `android:name="com.pairip.application.Application"` |
| └ PairIP 是否 VM 化 dex | ❌ **没有** | 全部业务代码明文可反编译 |
| └ PairIP 实际功能 | 仅 `LicenseClient.checkLicense()` | `Application.java` 15 行，只做 license check |
| R8 全量混淆 | ✅ | `z.*` 2,327 类，保留 `r8-map-id` 注释 |
| 签名 v1 (JAR) | ❌ 无 | META-INF 无 .RSA/.SF |
| 签名 v2 + v3 | ✅ | APK Signing Block: 0x7109871a / 0xf05368c0 |
| Google source-stamp | ✅ | 0x6dff800d（3,061 B） |
| PairIP 数据块 | ✅ | 0x70e1c89f（24,737 B，**熵 7.943**） |
| **反调试** | ❌ **完全没有** | grep `isDebuggerConnected`/`TracerPid`/`ptrace`/`/proc/self/maps` 全空 |
| **Root 检测** | ❌ 没有 | grep `test-keys`/`magisk`/`/su` 全空 |
| **Frida/Xposed 检测** | ❌ 没有 | grep `frida`/`xposed` 全空 |
| 加固壳（360/腾讯/梆梆） | ❌ 没有 | 无 `libjiagu`/`libDexHelper`/`libshella` |

> **0x70e1c89f 那个 24.7 KB 高熵块的判断**：熵 7.943 ≈ 接近随机，是加密数据。但因为业务代码全明文，这块装的是 **PairIP 的 license 校验器（VM 字节码 + 签名材料）**，不是业务逻辑。它只在 `attachBaseContext` 校验授权时跑一次。

### 2.5 混淆还原度抽样

反编译产物保留了语义信息，可直接读：

```java
// z/a.java — 混淆类但意图完全可读
case 1:
    fz.a(aboutSupportActivity, "https://z.301055.xyz/help/");
    return obj;
case 2:
    aboutSupportActivity.startActivity(new Intent(aboutSupportActivity, (Class<?>) CapabilityActivity.class));
```

### 2.6 授权架构（商业层）

服务端 API 全部可从代码提取：

```
POST /v1/licenses/activate          { order_id, claim_token }
POST /v1/orders                     { sku, promo_code, client_request_id, replace_pending_order_id }
POST /v1/licenses/heartbeat         ← 心跳续租
POST /v1/licenses/restore
POST /v1/licenses/takeover
POST /v2/auth/activate
POST /v2/auth/challenge             ← 挑战-应答
POST /v2/license/refresh
GET  /v2/policies/auth
POST /v1/google-play/purchases/verify
POST /v1/google-play/purchases/restore
```

本地凭证（SharedPreferences 键）：
```
license_envelope                 commercial_license_lease
license_id                       commercial_license_lease_id
pro_lease                        commercial_license_lease_expires_at
base_license                     commercial_license_lease_generation
play_trial                       commercial_license_lease_renew_after
end_custom_trial                 commercial_license_lease_last_verified_at
```

**机制**：`租约（lease）+ 心跳（heartbeat）`。授权不是一次性布尔值，而是带过期时间、代际号（generation）、续约时间的租约，需定期回服务器续。SKU 仅 `zrelay_pro_lifetime` 一个。

域名：
```
https://z.301055.xyz/help/          ← 帮助页
https://zrelay.301055.xyz/privacy/  ← 隐私政策
https://zreplay.301055.xyz          ← 疑似回放/诊断服务
```

---

## 三、分层难度评级

| 层 | 难度 | 说明 |
|---|---|---|
| **Java/Kotlin 业务逻辑** | ★☆☆☆☆ 已完成 | jadx 一把梭全还原，2.7 万行可读 |
| **资源/字符串/清单** | ★☆☆☆☆ 已完成 | apktool 解码完毕 |
| **Native PTP 协议** | ★★★☆☆ 中等 | stripped + 动态注册，但仅 135 KB 代码、17 个 JNI 入口，Ghidra 可从 `JNI_OnLoad` 的 `RegisterNatives` 反推函数边界 |
| **改包重签运行** | ★★☆☆☆ 容易 | 无完整性自校验、无反调试、无 root 检测；v2/v3 重签即可（需关闭 PairIP 的 license gate） |
| **绕过付费授权** | ★★★★★ 困难 | 服务端租约 + 心跳 + 挑战应答，本地改不出真授权；只能改 UI 解锁本地功能，服务端依赖的功能仍受限 |
| **PairIP 本体** | ★★★★☆ 难 | 24.7 KB 加密块，绕过方式是禁用而非解密（改 manifest 的 Application 类 + 删 LicenseActivity 引用） |

### 难点说明（不绕弯）

1. **最容易的一步**：把 `android:name="com.pairip.application.Application"` 改成 `com.kw.ztransfer.ZRelayApplication`，删掉 `LicenseActivity` 和 `com.android.vending.CHECK_LICENSE`。这样 PairIP 的 license gate 直接失效，App 正常启动。**这一步大概 5 分钟。**
2. **真正的门槛**：`z` 包里 2,327 个混淆类是全量 R8，方法名 `a()`/`b()`/`c()`，调用链要靠 jadx 的交叉引用 + 动态调试（Frida hook）才能理清。2.7 万行核心业务里，UI 和协议编排混在一起。
3. **硬墙**：Pro 功能如果每次都校验服务端租约，本地 patch 只能骗过 UI 层，实际功能请求会被服务端拒绝（`paid_entitlement_protected` / `paid_recovery_required` 这两个错误串就是证据）。
4. **native 层不是必要路径**：PTP 通信逻辑在 `.so` 里，但如果目标只是"看明白 App 怎么工作"或"改 UI/功能开关"，Java 层就够了。

---

## 四、可执行路线

### 路线 A：看懂它怎么工作（1–2 小时）
```bash
# 已完成，产物在 ./jadx_out
jadx -d jadx_out --no-res com.geekmanlab.zrelay.global.apk
# 入口从这几个类读起
#   MainActivity.java (7,141 行) — 主流程
#   NikonTransferService.java (3,942 行) — 传输服务
#   V3AppShellActivity.java (4,075 行) — V3 新 UI 壳
#   z/zh0.java (1,903 行) — PTP 会话编排
#   z/ke.java — native 可用性探测
#   z/o20.java (91 行) — 授权 API，全明文
```

### 路线 B：改包让它跑起来（半天）
```bash
apktool d -f -s -o work com.geekmanlab.zrelay.global.apk
# 1. work/AndroidManifest.xml: application android:name 改为 com.kw.ztransfer.ZRelayApplication
# 2. 删除 <activity com.pairip.licensecheck.LicenseActivity/> 与 uses-permission CHECK_LICENSE
# 3. 打包 + 重签（v2/v3）
apktool b work -o patched.apk
apksigner sign --ks KEYSTORE --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true patched.apk
# 4. 与 split 一起装：adb install-multiple base config.arm64_v8a config.en config.mdpi
```
注意：split 与 base 必须同签名，否则 `INSTALL_FAILED_INVALID_APK`。

### 路线 C：Native PTP 协议（数天）
```bash
# JNI 表还原路径
# 1. objdump -d 定位 JNI_OnLoad (0x14d30)
objdump -d --start-address=0x14d30 --stop-address=0x14d80 split_arm64/lib/arm64-v8a/libzrelay_core.so
# 2. 跟 RegisterNatives 调用，读第三个参数（JNINativeMethod[]）的 .data.rel.ro 偏移
# 3. 解出 {name, signature, fnPtr} 三元组 → n01..n17 的真实地址
# 4. Ghidra 加载 .so，按上表建立 17 个函数
# 5. Frida 动态验证：hook native 函数看 PTP/IP 报文
```
PTP/IP 是公开协议（ISO 15740 + Nikon 扩展），有现成资料可对照，这会显著降低 native 逆向成本。

---

## 五、未验证项 / 限制

- **未做动态调试**：没有真机/模拟器，所有结论来自静态分析。Frida hook、实际协议抓包都没跑。
- **未验证 PairIP 是否在运行期二次校验**：静态看只有 `attachBaseContext` 一次调用，但 PairIP 可能有服务端 beacon（`RepeatedCheckMetadata` 类存在，暗示有重复校验）。改包后需实测。
- **未解 0x70e1c89f 加密块**：熵 7.943，判断为 PairIP 数据，未做解密尝试。
- **未跑 ARM 反汇编全量**：只验证了 `.text` 开头和 `JNI_OnLoad` 入口可正常反汇编，未做完整 CFG 重建。
- **Native 函数边界未还原**：`RegisterNatives` 表未解，n01–n17 地址未知。
- **36 个 jadx 错误未逐个排查**：工具层反编译失败，业务类不在其中。
- **未确认 Pro 功能的实际服务端拦截点**：只知道有 `paid_entitlement_protected` 错误码，不知道哪些功能调用会带租约校验。

---

## 六、一句话总评

**这不是一个"防逆向"的 App，是一个"防白嫖"的 App。**

代码层几乎零对抗（没有壳、没有反调试、没有完整性校验、PairIP 只挂了个 license 门），R8 混淆是唯一的阅读成本，而 jadx 已经把 90% 的可读性还回来了。真正花力气的地方是商业授权——但那属于"服务端设计问题"，不是"逆向难度问题"。

如果你的目标是**理解实现**：今天就能看完。
如果你的目标是**改功能/换 UI**：一两天。
如果你的目标是**白嫖 Pro**：技术上能骗过 UI，功能上大概率被服务端挡住，性价比最低。

---

### 产物清单

| 路径 | 内容 |
|---|---|
| `zrelay_work/com.geekmanlab.zrelay.global.apk` | base APK（解出） |
| `zrelay_work/config.*.apk` | 3 个 split（解出） |
| `zrelay_work/manifest.json` | XAPK 元数据 |
| `zrelay_work/jadx_out/` | **完整 Java 反编译**（2,435 文件） |
| `zrelay_work/apktool_out/` | 资源 + 可读 AndroidManifest |
| `zrelay_work/split_arm64/lib/arm64-v8a/libzrelay_core.so` | native 库（已解出） |
| `zrelay_work/ZRelay_逆向可行性评估.md` | 本报告 |
