# 修改日志

记录库模块、示例应用和公开文档的变更。

## 0.0.3（2026-10-13）

- Gradle Wrapper 改用官方分发地址，修复 JitPack 构建从腾讯镜像下载 Gradle 时返回 HTTP 616 的问题。
  Gradle 仍为 8.14，SHA-256 校验值保持原值。
- 接入文档与发布版本同步到 `0.0.3`，示例应用 `versionCode` 升至 `3`。
- 发布流程增加 JitPack 制品与独立接入项目验证，通过后再创建 GitHub Release。
- 库功能、公开 API 与升级说明沿用下方 `0.0.2` 记录；JitPack 接入使用 `0.0.3`。

## 0.0.2（2026-10-13）

本版本包含第二轮库优化，以及下方 2026-10-12 记录的 SVGA 示例和系统栏适配。
优化方案与分析见内部 docs `库-优化方案.md`（不随源码发布）。
`libraryVersion` 为 `0.0.2`，示例应用 `versionName` 为 `0.0.2`、`versionCode` 为 `2`；Git 标签与 JitPack 版本均使用 `0.0.2`。

### 升级说明

- `SslGlobalDefaults` 和 `StateLayoutDiagnostics` 新增字段，改变了构造函数、`copy` 与 `copy$default` 的 JVM 签名。
  升级后需要重新编译应用及使用这些类型的依赖库；直接复用针对 `0.0.1` 编译的旧 AAR 可能触发 `NoSuchMethodError`。
- 未配置空态或错误页时默认显示内置布局；需要保持缺少配置时抛出异常的行为，可安装
  `SslGlobalDefaults(useBuiltInStateLayouts = false)`。

### 库模块 `:stateskeletonlayout`

#### 新增公开 API

- `SslLayoutOptions` + `StateSkeletonLayout.applyOptions()`：实例级批量配置，替代程序化创建时
  8–10 次逐项 setter 调用。`null` 字段跳过、保留当前值；`SslLayoutOptions.skeleton(...)` 为常用组合便捷工厂。
- `StateSkeletonLayout.DEFAULT_LOADING_SHOW_DELAY_MILLIS` / `DEFAULT_MINIMUM_LOADING_DURATION_MILLIS` /
  `DEFAULT_RETRY_CLICK_THROTTLE_MILLIS`：此前 `private` 或 `internal`，文档却在引用，现提升为公开默认值常量。
- `SslGlobalDefaults.useBuiltInStateLayouts`：控制未配置状态页时是否回退库内置布局，默认 `true`。
- `StateLayoutDiagnostics.inStateViewTransaction`：标识快照是否取自状态页创建/绑定的中间态。
- 库内置布局的固定 ID 公开在 `ids.xml`：`ssl_builtin_state_root` / `ssl_builtin_state_message` /
  `ssl_builtin_state_retry`。

#### 行为变更

- **`EMPTY` / `ERROR` 未配置布局时不再抛异常**，改为回退库内置兜底页面（居中文案；错误页带重试按钮）。
  内置错误页的按钮自动接管统一重试入口，宿主无需配置 `ssl_errorRetryViewId`。
  需要"缺配置立即暴露"的工程可通过 `SslGlobalDefaults(useBuiltInStateLayouts = false)` 恢复严格失败。
  内置布局只用 `android:*` 属性，**未引入任何新依赖**。
- **新增 `onConfigurationChanged` 响应**：资源派生的骨架颜色/圆角按新 Configuration 重新解析
  （仅在未被宿主显式覆盖时）；三个状态页缓存清空并立即重建当前呈现态。
- `clearStateViewCache` 增加守卫：拒绝清理正在参与过渡动画的状态页（此前只拦"当前顶层"，
  而淡出中的旧画面恰好满足该条件）。
- 模板骨架在"等待布局期间 detach"后重新 attach 时，`show(onShown)` 的回调不再被静默吞掉。
- 配置变更发生在加载显示延迟或最短展示等待期间时，重建当前画面并保留原截止时间，避免等待期空白。
- 动态替换错误页时解绑旧重试入口；候选布局创建失败时清理候选监听并保留原入口。
- 零尺寸重挂载时，待退出 Loading 不再启动过期的骨架准备，避免迟到回调遮住最新内容页。

#### 性能

- **骨架动画改为进程级共享时钟**：N 个骨架实例从"N 个并行无限 `ValueAnimator`"变为
  "1 个动画器、主线程每帧一次回调"。相位仍按各实例自己的 `animationDurationMillis` 换算；
  系统关闭动画时整个时钟不启动，行为与改造前一致。
- 遮罩几何采集的裁剪矩形改为按深度复用，消除每个被访问节点在每次布局/滚动回调中的 `RectF` 分配。

#### 内部重构

- 提取 `internal/TransitionController.kt`（256 行）：交叉淡入淡出的执行与取消语义从主控件移出，
  可单独测试。`StateSkeletonLayout.kt` 1655 → 1498 行。
- 提取 `internal/SslDefaultValues.kt`：默认值唯一真源，替换合并器里 13 处字面量。
- `SavedState.writeToParcel` 改为读 `formatVersion` 字段并自检与写入布局一致（此前硬编码版本号）。
- 五处 setter 补相同值短路，与其余 setter 语义统一。
- 重试入口的绑定/解绑改为按"实际绑定的 ID + 根节点"配对操作，不再依赖两处逻辑碰巧互补。
- `ShimmerMotion.frame` 标注 `@VisibleForTesting`：它是每帧两条三角函数且会分配的死代码路径，
  生产绘制走的是无分配的 `distance` + 缓存扫描轴。

#### 测试

- 库单测从 127 项增至 **170 项**，新增 6 个测试类：
  - `SslDefaultValuesTest`：逐字段断言 `styles.xml` 与内置常量一致，并断言两条构造路径相等。
  - `StateSkeletonLayoutSavedStateTest`：**反射遍历** `SavedState` 全部实例字段验证 Parcel 往返，
    字段数异常时失败；覆盖版本号自检。
  - `StateSkeletonLayoutRefinementsTest`：批量配置、setter 短路、重试绑定配对、配置变更三类场景。
  - `SkeletonTickerTest`：共享时钟的订阅/退订配对、系统动画关闭、相位换算。
  - `SkeletonInstanceFootprintTest`：计量遮罩字节数与重建次数，固化 4 个实测数字。
  - `SkeletonScreenshotTest`：Roborazzi 截图回归，6 张基线。
- 发布复查补充 6 条回归，覆盖错误页替换与创建失败、配置变更期间的延迟与最短展示计时，以及重挂载后的画面恢复。

### 示例应用 `:sample`

- 新增 4 个演示页，入口由 16 增至 **20**：
  - **内置状态页**：不配置 `ssl_emptyLayout` / `ssl_errorLayout` 也能直接用，重试入口由库自动接管。
  - **整屏列表模板**：一个骨架实例覆盖整屏多行，对照"每 item 一份遮罩内存"的成本，呼应 README 的推荐做法。
  - **配置变更**：昼夜 / 字体缩放 / RTL / locale 变化后的骨架颜色与状态页重建。
  - **骨架诊断**：`diagnostics()` 快照、`maximumMaskPixels` 像素预算降级与 `clearStateViewCache()` 缓存清理，
    填补 FAQ 推荐排障手段的示例空白。
- 新增 `sample/src/main/res/values-en/strings.xml`：**132 条**，与 `values/strings.xml` 键名一一对应、
  格式符完全一致（此前示例只有中文资源，README 的"中英文资源"承诺在示例中不成立）。
- 新增布局 6 个与 1 个圆形头像 drawable（整屏模板按 8 行静态占位，行内节点全部固定尺寸）。
- 新增 4 个测试类共 **26 项**：配置变更 9、内置状态页 6、骨架诊断 6、整屏列表模板 5。
  示例单测由 60 项增至 **86 项**，全部通过。
- 修掉两个示例自身缺陷：重建后恢复态恰为 `LOADING` 时摘要区空白（`showLoading()` 幂等、回调不触发，
  改为 `onViewCreated` 末尾无条件刷新，并补 `addOnConfigurationChangedListener` 覆盖"声明 configChanges 不重建"路径）；
  诊断页"遮罩归零"文案与实际行为不符（预算超限时**保留**已有遮罩用于静态绘制，仅此前无遮罩时才为空），已按实改写中英文文案。

### 构建与依赖

- 新增 Dokka 2.1.0 + `dokkaJavadocJar`：发布制品此前缺 API 文档包。
  **`0.0.1` 已发布且无法追溯补发，从 `0.0.2` 起制品包含 `-javadoc.jar`。**
- 新增 `binary-compatibility-validator` 0.18.2，导出 `stateskeletonlayout/api/stateskeletonlayout.api` 并接入 CI。
  首次 dump 为 362 行；本轮新增的公开 API 全部落地后为 **408 行**，`apiCheck` 通过。
- 新增 Roborazzi 1.76.0（仅测试依赖，跑在 Robolectric 上，不需要设备）。
- 发布坐标单点化：`libraryGroup` / `libraryArtifactId` / `sampleVersionCode` 移入 `gradle.properties`。
- 库 POM 补 `developers` 与 `scm`（此前缺失）。
- `verification-metadata.xml` 重新生成并覆盖三个新插件；仍全部使用 SHA-256，未启用 GPG 签名校验。

### CI

- 加 Gradle Wrapper 校验、并发控制（同分支 push+PR 互相取消）、最小权限、45 分钟超时、
  失败时也上传测试与 Lint 报告。
- 补 `:stateskeletonlayout:lintDebug`，使两模块 lint 覆盖对称。
- 加 `:stateskeletonlayout:apiCheck`。
- 加 `:stateskeletonlayout:verifyRoborazziDebug`，使 CI 对 6 张截图基线执行实际比对。
- 加"依赖校验元数据在本次改动中发生变化"的 PR 提示（该文件是放行白名单，变更需人工 review）。

### 文档

- README / README_EN 接入与发布说明同步到 `0.0.2`，StateLayout 鸣谢修正为 `liangjingkanji/StateLayout`。
- README / README_EN 修正：示例页数量口径（15→16，并标注当前 20）、
  "已签名的 validation APK"是仓库外手工产物的说明、CI 环境说明（Windows-only、单 JDK、无矩阵、
  设备测试从不执行）。
- README / README_EN 同步新增：快速开始第 5 节（内置状态页用法与固定 ID）、第 6 节（Compose 用 `AndroidView` 包装）、
  骨架模式的"列表骨架推荐整屏模板"（含量化依据）与"配置变更"小节、
  `consumer-rules.pro` 为空的设计决策与反射场景示例规则、接口速查中的 `applyOptions` / `SslLayoutOptions`
  与公开默认值常量。
- 方案文档中两项建议经实施核实后**作废并就地标注**：
  - `P-18`（`AccessibilityNodeInfo.setLoading`）：`javap` 核对 android-36 全部 248 个成员，
    不存在该 API，只存在于 AndroidX `AccessibilityNodeInfoCompat`，而库零依赖 AndroidX Core。
  - `P-11` 的 `SavedState` 独立文件化：该类已在公开 API 基线中，移动文件会改变二进制名，属破坏性变更。

### 仓库卫生

- `.gitignore` 补 `*.stackdump` 与 `*.dmp`。
- 删除根目录重复的 `loading.svga`（与 `sample/src/main/assets/loading.svga` SHA-256 相同）。

### 验证与边界

- 库 170 项 JVM / Robolectric 单测通过；`apiCheck`、Debug / Release Lint、Release AAR、本地 Maven 发布均通过。
- 库 Release Lint 为 **0 Error / 17 Warning**，17 条全部是 `GradleDependency` / `NewerVersionAvailable` /
  `AndroidGradlePluginVersion` / `OldTargetApi` 这类“有新版本可用”提示，无代码缺陷类告警，未整体屏蔽。
- Roborazzi 截图校验通过（6 张基线）。
- 库制品经核对包含 `.aar` / `-sources.jar` / `-javadoc.jar`（325 条目，含 217 个 HTML 页面）与补全的 POM。
- 示例 86 项 JVM / Robolectric 单测通过；`lintDebug` / `lintRelease` 均为 **0 Error / 86 Warning**（新增 4 页带来的 `ButtonStyle` / `Overdraw` / `SetTextI18n` 提示，未整体屏蔽）；`assembleRelease`（含 R8 压缩）与 `assembleDebugAndroidTest` 通过。
- 设备验证尝试在 Android 12 / MIUI 上执行：首条模板尺寸重建用例完成，随后测试桩 Activity 的后台启动被系统拒绝。
  日志包含 `MIUILOG- Permission Denied Activity`，调试线程栈停在 `Instrumentation.startActivitySync`；
  已停止该轮执行，不计为设备测试整体通过。完整设备用例、真机 TalkBack 播报次数、帧率与内存峰值、
  100 次循环泄漏验证仍需在允许测试 Activity 启动的设备或模拟器上验收。
- 本轮只固化了与设备无关的标量（遮罩字节数、重建次数、时钟订阅者数），
  **未测量帧率、GPU 耗时或构建耗时**，因此不提供性能提升百分比。

## 2026-10-12 开发记录（随 0.0.2 发布）

本次记录基于上次推送的 `main` 提交 `219e9af`（`build: preserve JitPack source artifact downloads`）。

### 库模块 `:stateskeletonlayout`

- 本次未修改库源码、公共 API、资源或库发布配置，`libraryVersion` 保持 `0.0.1`。
- SVGA 播放器及 Wire 等依赖仅用于示例应用。库仍仅显式依赖 AndroidX Annotation，Kotlin 标准库由插件自动添加。
- SVGA 示例复用现有自定义 Loading 布局和状态 View 创建、加载可见性、释放回调，展示第三方动画的接入方式。

### 示例应用 `:sample`

#### 添加 SVGA 动画示例

- 添加「SVGA 加载」入口、`SvgaLoadingFragment` 和配套加载布局，示例页总数由 15 个增加到 16 个，保留原有 Lottie 示例。
- 保留根目录 `loading.svga` 素材，并将同一素材打包到 `sample/src/main/assets/loading.svga`；运行时从本地解析，无需下载动画。
- 进入页面自动模拟 7 秒加载；支持持续 Loading、手动切换 Content / Empty / Error 和错误页重试，手动操作取消旧模拟任务。
- 使用加载可见性和 View 生命周期控制播放：后台或隐藏时停止，重新可见时从首帧播放；隐藏时复用解析结果，释放布局或销毁 View 时清理资源。
- 使用弱引用、解析代次和目标 View 身份检查处理迟到回调；校验播放参数，解析失败保留加载反馈，系统动画关闭时显示静态文案。

#### 添加沉浸式系统栏适配

- `MainActivity` 启用 `enableEdgeToEdge()`，系统栏图标随日夜主题调整，并允许横屏绘制进入刘海区。
- 添加 `applyDemoSystemBarPadding()`，在首页内容区与各 Fragment 根 View 上应用系统栏和刘海安全区域。
- 保留 XML 原始 padding，以初始值叠加 Insets，避免重复分发时累加；首页与详情分别处理，内部子 View 不重复添加。
- 返回原始 Insets 供后代继续接收；示例无输入框，本次未处理 IME Insets。

#### 修复返回目录后的遮挡

- 预测性返回提交或取消后，在下一轮主线程同步详情容器可见性；回退栈为空时设为 `GONE`，避免空详情背景遮挡目录。
- 补充普通返回、预测性返回、取消返回、前后台切换和重建后再次进入的回归覆盖。

### 依赖与构建

- 为示例固定添加 `com.github.yyued:SVGAPlayer-Android:2.6.1`，JitPack 仓库仅允许 `com.github.yyued` 依赖组。
- 排除 SVGA 的 `kotlin-android-extensions-runtime`，避免与现有 Parcelize 运行库产生重复类。
- 添加 Manifest 合并规则，保留示例应用现有的备份配置和名称；添加 Wire / SVGA Proto 的 R8 保留规则。
- 补充 SVGA、Wire 和相关 Kotlin 依赖附件的 SHA-256 校验记录。

### 文档

- 添加 `README_EN.md`，提供库接入、状态与骨架配置、自定义动画、测试和发布流程的英文说明。
- 在中英文 README 中添加语言切换、修改日志入口和沉浸式系统栏说明，补充 SVGA 接入与首页导航回归说明。
- 添加本文件，分别记录库模块、示例应用、构建与文档的修改范围。

### 验证与边界

- 添加 `SvgaLoadingFragmentTest`，覆盖模拟任务、解析失败、无效播放参数、迟到回调、生命周期、系统动画关闭及真实素材的 Native Canvas 帧像素检查。
- 添加 `DemoInsetsTest` 和 `MainActivityNavigationTest`，覆盖 padding 与方向开关、普通返回和预测性返回。
- 添加 `SvgaLoadingSmokeTest` 并扩展 `DemoSmokeTest`，供连接设备后检查入口、播放、返回、取消返回和重建。
- 本次提交验证（2026-10-12）：在隔离工作树中关闭构建缓存并重跑测试，库 127 项、示例 60 项，共 187 项通过，零失败、错误或跳过。
- 两模块 Debug / Release、AndroidTest APK 组装通过，示例 Release 完成 R8 压缩；库本地 Maven 发布的 AAR、源码包和 POM 均已生成。
- 库 Release Lint 为 0 Error / 16 Warning；示例 Debug / Release Lint 均为 0 Error / 58 Warning，未整体屏蔽警告。
- Debug / Release APK 内的 `assets/loading.svga` 与源码素材 SHA-256 一致；库 Release AAR 不包含示例动画或 Debug 测试资源，发布 POM 不包含 SVGA / Wire 依赖。
- 本次设备清单为空；设备冒烟、系统栏在横竖屏及不同导航模式下的表现、TalkBack 和 R8 Release 实际运行仍需设备验收。
