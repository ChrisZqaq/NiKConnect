# NP3 生成验证报告：官网参数 → NP3 字节

> 目的：验证「用尼康云创官方参数表重建 NP3 文件」是否可行、缺口在哪。
> 样本：曾用 Timor88/NikonNP3 的 32 个真实 `.NP3`；参数：官网 144 个方案详情。
> **2026-11-14 更新**：第三方 `.NP3` 样本与方案详情快照已从仓库移除（避免再分发他人作品，见 README「素材与版权」）。
> 本文的结论基于当时的本地复现；复现脚本需自备样本，见 `tools/np3/np3_generate.py` 顶部说明。
> 复现：`python3 tools/np3/np3_generate.py --samples <你的NP3目录>`

## 1. 结论

| 结论 | 数值 | 依据 |
| --- | --- | --- |
| 10 个标量参数 → tag 编码正确 | **32/32 样本全对** | 逐 tag 比对，0 处不符 |
| 只用官网参数能否重建出字节一致的 NP3 | **0/32** | 差异仅落在 name、0x1F、0x20、BI0 尾部 |
| 补上样本自带曲线/色相表/尾部后 | **31/32 字节完全一致** | 唯一例外 `Bluegrain_jyota`（基准画面 MORNING，非 FLEXIBLE COLOR） |
| 本次交付的 10 例 | 参数 0 处不符；补曲线后 **10/10 字节一致** | 见 §4 |

**一句话**：官网参数足以完整还原「参数部分」；还原度缺口 100% 来自官网 API 不提供的三块数据 —— 色调曲线锚点、色相/饱和微调表、BI0 自定义曲线块。三者都是相机内 Picture Control 的曲线类数据，不在云创「配方」的字段里。

## 2. 字段映射表（baseName = FLEXIBLE COLOR）

编码规则：每个 tag 值 2 字节 = `hi`（0x80 基准有符号）+ `lo`（步进码）。

| tag | 参数 | 编码 | 验证 |
| --- | --- | --- | --- |
| 0x06 | sharpening | `hi = 4×v + 0x80`, `lo = 0x04` | 32/32 |
| 0x07 | clarity | 同上 | 32/32 |
| 0x16 | middleRangeSharp | 同上 | 32/32 |
| 0x14 | quickSharp | `hi = v + 0x80`, `lo = 0x01` | 32/32 |
| 0x19 | contrast | 同上 | 32/32 |
| 0x1a | highlight | 同上 | 32/32 |
| 0x1b | shadow | 同上 | 32/32 |
| 0x1c | whiteLevel | 同上 | 32/32 |
| 0x1d | blackLevel | 同上 | 32/32 |
| 0x1e | saturation | 同上 | 32/32 |

特殊值：

- API 值为 `gamma`（自定义曲线）→ 写 `0x0101`
- API 值为 `-`（未设置）→ 写 `0xff` + 步进码
- 例：`shadow = -70` → `0x3a01`；`clarity = +0.50` → `0x8204`

常量 tag（FLEXIBLE COLOR 基准下 32/32 一致）：
`0x03=0020 0x04=0000 0x05=ff01 0x08..0x0b=ff04 0x0c/0x0d=ff00 0x0e=ff04 0x0f..0x13=ff01 0x15=ff0a 0x17/0x18=ff04`

## 3. 缺口：API 拿不到的三块

| 位置 | 长度 | 内容 | 后果 |
| --- | --- | --- | --- |
| tag 0x1F | 28B | 14 个色调曲线锚点 | 影响成片的明暗映射 |
| tag 0x20 | 20B | 色相/饱和微调表 | 影响分色偏移 |
| 尾部 `BI0` 块 | 586B（仅 978B 版） | 253 点自定义色调曲线 | 决定「自定义曲线型」方案的全部影调 |

978B 与 392B 的唯一区别就是有/无这一块：32 个样本里 11 个带 BI0，21 个不带。

**这不是解析失误，是数据源限制**：官网详情接口的 `pictureControl` 只有 10 个标量字段（光渡 APK 解析器 `gz.java:23` 同样是这 10 个）；曲线数据从来没有下发过，它随 NP3 文件走，而 NP3 文件本身官方不开放下载。

## 4. 十例对照结果

生成物：`docs/data/recipes/generated/*.NP3`（**只用官网参数**，name 取 `settingName`，曲线取中性值，无 BI0 尾）。

| 样本 | 大小 | 参数不符 | 与原始差异 | 补 name+曲线+尾后 |
| --- | --- | --- | --- | --- |
| Negative_Cy_01a | 392B | 0 | 43B（11.0%） | 字节一致 |
| ClrMode_Teal0r_01b | 392B | 0 | 38B（9.7%） | 字节一致 |
| MovieG&O_InstantF | 392B | 0 | 7B（1.8%） | 字节一致 |
| CRYSClear_Eisuke | 392B | 0 | 18B（4.6%） | 字节一致 |
| VINTAGEPort_Fuji | 392B | 0 | 15B（3.8%） | 字节一致 |
| Catgrain yw | 392B | 0 | 27B（6.9%） | 字节一致 |
| MOSS_Nagisa | 978B | 0 | 619B（63.3%） | 字节一致 |
| QUIET_Nagisa | 978B | 0 | 616B（63.0%） | 字节一致 |
| Winter Hues_Eeva | 978B | 0 | 606B（62.0%） | 字节一致 |
| Japanesque_Arashida | 978B | 0 | 602B（61.6%） | 字节一致 |

392B 组的差异分布：name 字段（导入时自定义名 vs API settingName）+ 0x1F + 0x20。978B 组的差异几乎全部是缺掉的 586B BI0 尾。

**name 字段注意**：NP3 里存的是导入相机时用户起的名字，不等于 API 的 `settingName`。例：样本 `Negative_Cy_01a` 对应官方方案 `Fuji_ClassicNega`。

## 5. 对产品的意义

1. **可以自建「云创方案 → NP3」生成器**：写得出的部分（10 个参数）已经 100% 正确，生成出来的文件相机能识别（结构、tag 集、尾部都合法）。
2. **但生成的方案与官方原版在影调上不等价**，因为曲线缺失。三种应对：
   - a. 生成时使用中性曲线（当前 `generated/` 里的做法）：参数对、影调偏平，适合"参数模板"用途；
   - b. 从相机侧读回同族方案的曲线做模板（需要 PTP 槽位读取能力）；
   - c. 用样本库里已有的 32 个 NP3 的曲线当模板，按 baseName 匹配复用。
3. **最稳的路线仍是 PTP 槽位写入**：把 NP3 原字节写进相机槽位，不经过"参数重建"，影调 100% 保真。

## 6. 未验证项

- 生成的 NP3 未经真机写入测试（未验证相机是否接受 `generated/` 里的文件）。
- 0x1F / 0x20 / BI0 三个块的内部结构只做到"逐字节可用"，未做到语义解析（未确认锚点到 253 点曲线是否为同一插值算法）。
- MORNING 等非 FLEXIBLE COLOR 基准未标定常量表（仅 1 个样本，不足以拟合）。

## 7. 如何取得「准确的」NP3（原始字节）

按可行性排序。共同前提：NP3 是相机侧 Picture Control 的完整快照（含曲线），所以要么从相机拿，要么让相机产出。

### R1 相机导出到存储卡（最干脆，可进 App）

相机菜单「管理 Picture Control / 管理优化校准」→「保存到存储卡」。
卡上路径 `NIKON/CUSTOMPC/*.NP3`（也可直接放存储卡根目录，相机同样能载入）。
文件名为 `01.NP3`…`99.NP3`（每类型上限 99，单卡合计 297）；双卡机型只读主卡槽。
卡必须先用该相机格式化；格式化会清掉卡上 NP3。

**NiKConnect 可行性**：卡上文件可走现有 PTP 通道读取（`GetObjectHandles` → `GetObject`），也可读卡器直拷。需真机确认相机是否把 `NIKON/CUSTOMPC` 里的对象暴露给 PTP。

### R2 NX Studio 从照片反推（无云创机型唯一路）

用支持云创的机身（Z6III / Zf / Z8 FW3.0+）把配方同步进机 → 拍一张 → NX Studio 导入该 NEF → 优化校准面板导出 `.NP3`。
或直接在 NX Studio 里调参数后导出（曲线由软件生成）。
依据：[Nikon NX Studio 帮助 · 导入/导出自定义优化校准文件](https://www.nikonimglib.com/nxstdo/onlinehelp/cn/copy_custom_picture_controls_50.html)、[Picture Control Utility 2 · 导出自定义优化校准](https://nikonimglib.com/npcu/onlinehelp/cn/pc013000.html)

### R3 PTP 槽位直读（ZRelay 路线）

`GetVendorPropCodes` → `GetPicCtrlData` 直接取槽位字节，不依赖存储卡。见 §5-3。

### R4 云创服务端（已排除浏览器侧）

`/recipe/camera` 页只发 `PUT /camera/{id}/{slot}`（`Authorization: Bearer`），NP3 的组装与下发都在服务端，下发目标是相机主动拉取 → **浏览器/抓包拿不到 NP3 字节**。
`POST /bff/recipe/release/download` 路由不存在（API Gateway 对未匹配路由回 403 `Missing Authentication Token`）。

### R5 社区分享

已有 [Timor88/NikonNP3](https://github.com/Timor88/NikonNP3) 32 个样本；小红书 / 闲鱼 / 微信「云创滤镜包」常见整套转存（来源不可信，仅作素材）。

### R6 自建闭环：生成 → 进机 → 再导出

把本仓 `generated/` 的参数型 NP3 放卡 → 相机载入 → 再从相机「保存到存储卡」导出。
若导出文件出现 `BI0` 尾（978B），说明**相机自己会补齐曲线**，这条路就能把参数型 NP3 变成准确 NP3。
一次实验即可判定，是当前最高性价比的验证。

## 8. 批量取回 144 个 NP3 的自动化评估

约束（官方手册口径）：

- 相机自定义优化校准槽位只有 **C-1…C-9**（9 个）
- 「载入/保存 → 复制到照相机」可按文件批量放入 C 槽，但每次最多 9 个
- 「复制到存储卡」目标位置 1–99，逐次操作
- 云创下发的方案存在相机**独立区**，不占 C-1…C-9（删除云创方案不影响 C 槽）

因此完整链是：云创下发 → 相机里「保存/编辑」把方案落到 C 槽（9 个/轮）→「复制到存储卡」→ 读卡。144 个 ≈ 16 轮 × 3 类操作。

自动化可行性拆解：

| 环节 | 能否脚本化 | 手段 |
| --- | --- | --- |
| 配方下发到相机 | ✅ | `PUT https://api.user.ir.imagingcloud.nikon.com.cn/camera/{cameraId}/{slot}` + `Authorization: Bearer <token>`（需相机在 Imaging Cloud 注册且在线） |
| 云创区 → C 槽 | ❌ 目前只能菜单 | 无公开 PTP/云端接口 |
| C 槽 → 存储卡 | ❌ 目前只能菜单 | 同上 |
| 读存储卡上的 NP3 | ✅ | 标准 PTP `GetObjectHandles`/`GetObject`，或读卡器 |
| 直接读 C 槽字节 | 待验证 | 厂商 opcode（ZRelay 的 `GetPicCtrlData` 证明可行，需逆向出具体码） |

已知坏消息：`libgphoto2` 的 PTP 驱动里**没有任何 Picture Control 相关实现**（查过 `camlibs/ptp2/library.c`，`ptp_nikon_*` 全是拍摄/连拍/缩略图/liveview），所以没有现成 CLI 可用。

结论：**下发能自动化，相机菜单那两步不能**。真正的全自动要等 PTP 槽位读探针跑通，把「C 槽 → 卡」替换成「C 槽 → PTP 直读」；「云创区 → C 槽」仍需每轮 9 次菜单操作。
