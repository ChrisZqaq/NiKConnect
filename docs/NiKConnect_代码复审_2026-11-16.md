# NiKConnect 代码复审报告

日期：2026-11-16

> 本文归档本轮已交付的代码审查结论；本轮仅审查，未修改应用代码。正文中“未新增或修改文件”“工作区保持干净”指审查结束时的历史状态；此次归档新增 Markdown、Word 和生成脚本。

审查基线为 **`19315df`，`0.11.8-alpha / versionCode 65`**，重点检查上次评审后的修复及相关调用链。发现 **4 项 P1、3 项 P2**；其中几项属于“局部实现已改，但端到端行为尚未闭环”。

**1. [P1] 切换云创方案后，收藏按钮可能删除上一方案**

依据：[CloudFavoriteSheet.kt:76](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudFavoriteSheet.kt:76>)、[CloudFavoriteViewModel.kt:80](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudFavoriteViewModel.kt:80>)。

收藏 ViewModel 的 key 只有账号 guid，各方案共用同一个实例。切换方案时，`loadCollection()` 只设置 `loading`，没有清除上一方案的 `collected`；按钮又仅用 `!state.working` 控制可点击性。

因此，从已收藏的 A 切到 B，在 B 查询完成前，按钮仍可能显示“已收藏 · 点击取消”，确认后传入的仍是 A 的本地 ID。若 A 的查询尚未结束，B 的加载还会被 `loading` 守卫跳过，旧结果随后继续覆盖当前页面。

建议按 `guid + recipeId` 隔离状态，或为查询增加方案身份与请求代次校验；身份核对期间停用收藏、取消与“加到相机”动作。

**置信度：高。** 状态复用、旧值保留和删除入口均有直接源码依据。

**2. [P1] 槽位写入失败后的“补偿”没有恢复原排布**

依据：[CloudAddToCameraViewModel.kt:143](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudAddToCameraViewModel.kt:143>)、[CloudAccountViewModel.kt:542](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountViewModel.kt:542>)。

两条写入路径都先删除旧方案，再 PATCH 新排布。后续失败时，只调用 `addRecipes()` 将旧方案重新加入可分配池，没有提交旧槽位位置，也没有读回确认恢复结果；补偿失败还被 `runCatching` 吞掉。

现有测试假接口也明确表达：**C003 入池不会自动改变槽位排布**，见 [CloudAddToCameraViewModelTest.kt:108](</Users/chrisz/Documents/projects/NiKConnect/app/src/test/java/com/chrisz/nikconnect/ui/CloudAddToCameraViewModelTest.kt:108>)。所以“加回池”不足以兑现“恢复槽位”。

建议保存写前完整排布；失败后重新读取实际状态，恢复缺失方案及原位置，再读回验证。恢复失败应明确显示部分完成状态，保留人工恢复所需信息。

**置信度：高。** 补偿步骤缺失已确认；不同故障下的服务端最终状态仍需故障注入验证。

**3. [P1] 账号页读取真实排布失败时，仍会生成可编辑的错误槽位**

依据：[CloudAccountViewModel.kt:598](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountViewModel.kt:598>)、[CloudAccountClient.kt:61](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/cloud/CloudAccountClient.kt:61>)。

`fetchSlots()` 将 H13 失败转换为 `null`，然后继续读取 C002。`mergeSlots(null, local)` 会把已占用方案密集编号为第 1、2、3 位。

例如真实布局为 `[空, A, 空, B]`，页面可能展示为 `[A, B, 空, 空]`。用户据此保存，生成的写入计划就建立在错误位置上。

详情页“加到相机”已经对未知排布停止提交，但账号编辑页仍保留这条回退路径。

建议把“排布未知”和“排布已知且为空”分开；未知状态下展示读取失败并停用编辑，恢复 H13 后再开放写入。

**置信度：高。**

**4. [P1] 同名同大小仍被当成同一照片，可能丢弃新下载内容**

依据：[PhotoDownloader.kt:66](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt:66>)、[PhotoDownloader.kt:118](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/camera/PhotoDownloader.kt:118>)。

“同名不同大小删除旧文件”的问题已修复，但同名同大小命中后，代码仍直接删除本次完整下载的 `.part`，返回旧文件 URI，并将任务标记为成功。

换卡、换机身、编号回绕时，两张不同照片可以具有相同文件名和字节数。当前流程会让用户以为新照片已经导入，实际留下的是旧照片。

建议既然新内容已经完整下载，就与候选旧文件做内容哈希或逐字节比较；确认相同才复用，否则保留两份。

**置信度：高。** 条件与丢弃路径已确认；属于条件触发的数据完整性问题。

**5. [P2] “断开后保留已完成下载”仍会被订阅覆盖**

依据：[MainViewModel.kt:114](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt:114>)、[MainViewModel.kt:390](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/MainViewModel.kt:390>)。

`disconnect()` 虽然过滤保留 `COMPLETED`，但同时把 `_queueTasks` 设置为空。另一条 collector 持续执行：

```kotlin
_state.update { state ->
    state.copy(downloads = tasks.map { it.toDownloadItem() })
}
```

它收到空列表后，会把刚保留的记录再次清空。重连时 `replaceDownloadQueue()` 和新队列的初始空状态也存在同样问题。底层文件仍在，但传输页的结果入口会消失。

建议把已完成导入记录与当前连接队列分开持有；只有活动任务随会话重建。

**置信度：高。**

**6. [P2] refresh 锁实现了串行，没有实现结果复用**

依据：[CloudHttp.kt:243](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/cloud/CloudHttp.kt:243>)、[CloudAccountScreen.kt:104](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/CloudAccountScreen.kt:104>)。

两个请求携带同一个旧 refresh token 进入时，第二个会等锁，但获得锁后仍再次提交旧 token。锁内没有检查凭据是否已经更新，也没有复用第一个请求的结果。

同时，账号页调用 `updateCredentials(token, guid)` 时遗漏新 refresh token；收藏页更新凭据的方法也没有 refresh 参数。各 ViewModel 因此可能继续持有旧值。

建议由统一账号会话持有 token 对和版本；续期时检查失败请求使用的版本是否仍为当前版本，已更新则直接复用。

**置信度：高。** 重复提交路径已确认；是否立即导致续期失败取决于服务端轮换策略。

**7. [P2] 当前页筛选为零结果时，“加载更多”入口消失**

依据：[MainScreen.kt:430](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt:430>)、[MainScreen.kt:499](</Users/chrisz/Documents/projects/NiKConnect/app/src/main/java/com/chrisz/nikconnect/ui/MainScreen.kt:499>)。

分页每次读取部分对象，但“加载更多”只存在于有筛选结果的网格分支中。如果前 60 个对象全是 JPEG，切到 RAW 就显示“这台相机没有 RAW 照片”，即使后续页面实际有 RAW，也没有入口继续读取。首批对象全是不支持的格式时，整体空列表分支也存在类似问题。

建议零结果页面继续依据 `hasMore` 提供加载入口；文案使用“已加载照片中没有 RAW”，只有完整扫描结束后才判断整台相机没有该格式。

**置信度：高。**

## 已确认的修复进展

本轮也确认了修复进展：同名不同大小文件已改为并存、MediaStore 复制阶段已加入取消检查、NRW MIME 已区分、分页已沿用句柄快照、下载队列作用域已关联宿主、多步写操作已拆为单请求续期重试。这些改动方向正确。

## 验证与限制

验证执行了：

```bash
./gradlew --offline :app:testDebugUnitTest :app:lintDebug
```

命令退出码为 **1**，启动阶段报 `Unable to locate a Java Runtime.`，因此本轮没有新增 JVM、lint 或真机通过证据。图谱停留在 11 月 11 日，本轮判断均已回到当前源码核对。工作区保持干净，未修改应用代码。

## 五项简报

- **整体思路**：重点复查修复后的端到端行为，追踪 UI、状态持有者、HTTP 和文件发布之间的边界。
- **技能与工具**：使用 `codebase-memory` 技能、图谱检索、Git 差异、源码及测试阅读、Gradle 检查。
- **素材来源**：当前提交源码、现有测试与项目验收记录；未开展外网调研。
- **决策**：优先修复错误对象删除、槽位恢复、未知排布写入和照片误去重，再处理历史记录、续期与分页体验。
- **未做事项与文件清单**：未安装 JDK、未构建 APK、未操作真实账号或相机；未新增或修改文件。上述并发与故障场景仍需补回归测试。