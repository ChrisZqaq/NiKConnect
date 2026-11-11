# 尼康云创（Nikon Imaging Cloud）调研报告

调研日期：2026-11-11。方法：curl 静态抓取 Web 端（Next.js chunk 反解）、ZRelay 本机逆向成果复用、社区 NP3 开源资料验证。全部结论标注【已确认/推断】。

## 1. ZRelay 的做法【已确认，来自 libzrelay_core.so 逆向】

ZRelay **不调用云创 API**。它的色彩方案集成完全走**相机端 PTP 命令**：

- 原生库 `libzrelay_core.so`（arm64，C++，JNI 动态注册到 `com.kw.ztransfer.NativePtpCore`）导出的命令名清单（strings 提取）：
  - **GetPicCtrlCapability** / **GetPicCtrlData** —— Picture Control 能力探测与数据读取（只读，无 SetPicCtrl）
  - GetDevicePropDescEx / GetDevicePropValueEx / **SetDevicePropValueEx** —— 设备属性读写（写回 Picture Control 走这里）
  - AdvancedTransfer（参数仅允许 0x2001/0x2002）—— 厂商传输命令；0x9434 OperationNotUsed 探测 AdvancedTransfer 是否广播
  - GetVendorCodes / GetVendorPropCodes —— 厂商 opcode/属性码枚举
  - 其余：GetEventEx、GetPartialObjectEx、TerminateCapture 等
- Java 侧 NativePtpCore 仅 18 个 native 方法（n01–n17），n15(i)/n16(i,i2) 是厂商 opcode 编码器（C(1)=AdvancedTransfer、C(10)=ConfirmPairing、C(22)/C(26)/C(27)/C(36)=能力组、C(40)=GetDevicePropDesc、C(41)/C(28)=GetVendorPropCodes、C(42)/C(43)=Get/SetDevicePropValue），index→opcode 映射在 native 层。
- NP3 **文件本体** ZRelay 不解析——只有相机侧字节流。上传/下载 NP3 文件是云创 App 或 SD 卡路线。

## 2. 尼康云创 Web 端结构【已确认，来自前端 chunk 反解】

Next.js 站点。API 全集（从 index chunk 提取）：

| 端点 | 方法 | 认证 | 用途 |
|---|---|---|---|
| `https://api.user.cwp.imagingcloud.nikon.com.cn/bff/recipe` | POST | header `access_token` | 方案列表（body: filter/country/lang_code，IF_FR100_H05） |
| `https://api.user.ir.imagingcloud.nikon.com.cn/camera/{guid}/` | GET | Bearer | 已注册相机列表 |
| `.../camera/{guid}/{op}` | POST | Bearer | 发送方案到相机（body: recipeIds） |
| `.../localrecipe/{guid}` | PUT | Bearer | 本地书更新（recipeIds） |
| `.../publicbook/{guid}` | POST | Bearer | 建公共书（name） |
| `.../publicrecipe/{guid}` | POST | Bearer | 收藏进书（bookId, recipeIds） |
| `https://api.user.imt.imagingcloud.nikon.com.cn/recipe/uploadcredential` | POST | Bearer | S3 上传凭证（cn-north-1 直传，仅上传） |

认证：Keycloak。配置 `/json/keycloak_Prod.json`：realm=`user`，auth-server-url=`https://auth.accounts.cld.nikon.com.cn/auth`，clientId=`c0004`。OIDC discovery 正常。

**关键发现：Web 端没有 NP3 文件下载端点**。产品形态是"浏览方案→收藏→发送到相机"，NP3 字节只在相机与云之间流经。未登录访问详情/数据接口均 302 或跳 sorry 页。

## 3. NP3 文件格式【已确认，有开源实现】

- 魔数 `NCP\0`，TLV 结构。实测解析 24Look_24Frames.NP3 成功。
- 参考库：`ssssota/nikon-flexible-color-picture-control`（npm 1.0.0，TypeScript 读写，已验证）。字段：name/comment、sharpening/midRange/clarity、contrast/highlights/shadows/whiteLevel/blackLevel/saturation、toneCurve（257 点）、colorBlender（8 色 hue/chroma/brightness）、colorGrading（高/中/低）。
- 语料：`github.com/shouryan01/Nikon-Recipes`（142 个官方 NP3，已 clone 到 /tmp/nikon-recipes）。
- 装载途径（不依赖云）：SD 卡 `NIKON/CUSTOMPC/` 拷入 NP3 → 相机菜单"管理优化校准→载入/保存"；或 NX Studio 从 RAW 提取导出。

## 4. 接入建议（B0/B1 路线）

**推荐：ZRelay 同款相机端路线 + NP3 本地导入导出，不走云 API。**

理由：云 API 需 Nikon ID 登录态与 Keycloak 授权链（合规与稳定性风险高，PRD 开放问题 #6 明确"证据不足不进排期"）；相机端 PTP 路线符合"本地优先、无账号"的产品原则，且 GetPicCtrlData/SetDevicePropValueEx 的可行性有 ZRelay 真机背书（同 Zf 机型）。

B0 技术探针清单（待排期）：
1. 在 Zf 上探测 GetPicCtrlCapability（vendor opcode 需枚举：GetVendorPropCodes 先行）
2. GetPicCtrlData 读当前槽位字节 → 与 SD 卡已知 NP3 对照格式
3. 单槽写回实验（SetDevicePropValueEx）前先备份，测试槽可恢复
4. NP3 本地导入（SD 卡文件夹浏览）/导出（写 .NP3 文件，参照开源库字段序）

如坚持云路线：需用户 Chrome 带 `--remote-debugging-port=9222` 启动以抓登录态（本次 CDP 未开，未抓 Bearer token；API 形状已从代码反解，不依赖抓包）。

## 5. 未决项

- GetPicCtrlCapability 的具体 opcode 值（在 n15 映射表里，需真机枚举或进一步逆 native）
- 公共书接口返回体是否含 NP3 字节（需登录验证；倾向只含元数据）
- Zf 云优化校准（CLOUD CUSTOM）槽位与 NP3 的兼容性（固件相关）
