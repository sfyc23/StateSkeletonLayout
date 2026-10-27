# 修改日志

记录库模块、示例应用和公开文档的变更。

## 未发布（配置优先级与互斥修复，基于 0.0.4）

本次为配置合并修复，未升级依赖、未新增公开配置字段、未发布版本。

### 行为变更

- 页面 XML 显式配置优先于全局默认，覆盖适用于互斥的供给方式：页面选条目模板（T2）后，主题整页模板（T1）不再参与互斥校验；合法跨层组合不再构造崩溃。
- 高层模板供给覆盖低层不兼容的 `CONTENT` 默认值并确定为 `TEMPLATE`；高层 `CONTENT` 屏蔽低层模板供给及其数量；同层 `CONTENT + T2` 仍报错，同层 `CONTENT + T1` 保持许可。
- 页面重新声明 item 布局但不声明数量时改用默认 `8`，不再继承旧布局数量；只改数量时修改继承到的 T2；孤立数量报错。
- 布局资源显式清空截断继承：XML 直接属性支持 `@null` / `@empty`，页面 Style / Theme Style 用 `@empty`；清空 T1 只屏蔽 T1，清空 item 布局同时解除低层数量，数量 `@empty` 重置为默认；EMPTY / ERROR 清空后仍按 `useBuiltInStateLayouts` 使用库内置兜底。
- XML 直接属性与页面有效 Style 分开比较；Style 父子视为同一层，跨字段切换须显式清空另一种供给。
- 内部声明来源与三态只在构造合并期间使用，不存入长期配置、不新增公开 API、不写入 SavedState（仍为 v4）。

### 测试与验证

- 新增 `SslTemplatePriorityTest`（P01–P19、P21–P27 合并单元测试与真实布局 inflate，含直接 / Style 分层、Theme 引用、`@empty` 清空，覆盖 API 26 与 34）。
- 新增 `src/debug` 夹具：`ssl_test_template_priority.xml`、`ssl_test_host_template_priority_p*.xml`（全局 T1 / T2、页面覆盖及清空声明）。
- 已执行：`:stateskeletonlayout:testDebugUnitTest :sample:testDebugUnitTest`、`assembleDebug`、`lintRelease` / `lintDebug` 均通过。
- 未执行：真机 sample 条目复用骨架与宿主 AnanGame 技能审核页设备验收；本文不记为运行链路已验证。

## 0.0.4（2026-10-20）

本版本新增 EMPTY / ERROR 多重试入口和条目拼装模板 T2，包含 `0.0.3` 之后的示例改进。
`libraryVersion` / 示例 `versionName` / Git 标签均为 `0.0.4`，示例 `versionCode` 为 `4`。

### 升级说明

- 本版本包含不兼容的 API 调整，不保留旧单 ID 入口或回调适配层。
  `errorRetryViewId` 改为 `setRetryViewIds(StateLayoutState.ERROR, ...)`；
  `SslLayoutOptions.errorRetryViewId` 改为 `retryConfig`；
  XML `ssl_errorRetryViewId` 改为引用 ID 数组的 `ssl_errorRetryViewIds`；
  `setOnRetryClickListener` 改为 `setOnRetryListener { layout, state, clickedView -> ... }`。
- `SslGlobalDefaults`、`SslLayoutOptions` 等公开类型的 JVM 签名发生变化，应用和依赖本库的其他库均需重新编译。
- SavedState 使用当前 v4 格式，只保证同版状态恢复，不读取历史格式。
- README 中英文接入说明、迁移说明与版本链接同步更新；示例目录共 23 页。

### 库模块 `:stateskeletonlayout`：多重试入口

#### 新增公开 API

- `StateRetryConfig(emptyViewIds, errorViewIds)`：不可变重试配置；`null` 不参与覆盖（继承／跳过），空列表显式关闭；构造复制、按首次出现顺序去重，属性返回不可修改快照；`0` / `View.NO_ID` 拒绝，单状态去重后上限 `10000`。
- `OnRetryListener.onRetry(layout, state, clickedView)`：统一语义回调；`fun interface`，一次接受的点击只调用一次；库不自动切换状态、不发请求。
- `StateSkeletonLayout.setRetryViewIds(state, vararg viewIds)` / `getRetryViewIds(state)`：按状态替换／读取完整列表；只接受 EMPTY / ERROR，无 ID 显式关闭；相同列表重复赋值为空操作；`getRetryViewIds` 允许在状态页创建回调中只读调用。
- `SslGlobalDefaults.retryConfig`：全局默认重试入口，按状态参与合并。
- `SslLayoutOptions.retryConfig`：实例批量重试配置，非 null 状态列表覆盖当前值，null 状态列表跳过；`isEmpty` 同步更新（对象为 null 或两列表都为 null 时空操作）。
- XML 属性 `ssl_emptyRetryViewIds` / `ssl_errorRetryViewIds`（`format="reference"`，指向 `<array>`，条目为 `@id/...`）：空数组显式关闭，未声明继承；混入字符串、数值、颜色、布局或空条目抛出来自属性名与下标的异常。

#### 行为变更（统一单入口，不保留旧适配层）

- 移除 `StateSkeletonLayout.errorRetryViewId`、`SslLayoutOptions.errorRetryViewId`、XML 属性 `ssl_errorRetryViewId`（含 `XmlSslAttrs.errorRetryViewId`）、`SavedState.retryViewId`、旧 `setOnRetryClickListener` 及历史 SavedState 读取分支；所有调用方统一迁移到新模型，不提供别名或单元素投影。
- 配置优先级按状态独立合并：`XML 直接属性 > XML 显式 style（含父 style） > Global > Theme 默认 style > 库默认`；整份列表替换，不做并集，高优先级空列表截断继承。库默认：EMPTY 为空；ERROR 在使用库内置错误布局时为 `[ssl_builtin_state_retry]`，宿主布局为空。
- 绑定、节流与释放由新增 `internal/StateRetryController` 唯一管理：多根监听记录、按状态更新、事件门禁、共享节流与释放；`retryClicksEnabled` / `retryClickThrottleMillis` 委托控制器读写；`StateViewRegistry` 只负责创建、缓存与释放。
- 点击门禁：非 null 回调、`retryClicksEnabled`、挂载可见、目标及父链可见可用、`renderedState == expected && requestedState == expected`、原始根仍为当前缓存、包装层仍为当前显示层、目标仍在原根下且属于有效记录、通过节流；同控件同状态共享窗口，EMPTY / ERROR 分别计时，不同实例互不阻塞；`0` 关闭时间节流但门禁仍有效；回调前记录已接受时间；相同配置重复应用、回调重设、候选失败不清窗口，明确改变该状态列表清零该状态窗口。
- `SavedState` 格式升至 v4，只处理当前格式：标记或版本不匹配明确拒绝；按请求状态与布局资源、Loading 时序过渡播报、骨架节点条目、状态描述、重试快照（EMPTY／ERROR 列表、节流、可用开关）分组读写；重试快照保存最终配置，显式关闭写入数量 0；读取限 `0..10000` 并校验去重；所有校验完成后才修改实例或释放缓存；Listener／View／节流时间不保存。
- `checkMainThread` 绑定回调白名单新增 `getRetryViewIds`（回调内可读、不可修改）。
- 修复恢复前已创建状态页时重试监听未更新的问题：同版恢复会为保留的 EMPTY / ERROR 缓存根重新绑定最终列表，显式关闭会解绑旧入口。

#### 测试

- 新增 `StateRetryConfigTest`（不可变性、去重、三态语义、无效值）。
- 新增 `StateRetryBindingTest`（多入口、缓存更新、候选失败、释放、门禁与节流）。
- `SslAttributePriorityTest` 新增重试优先级用例（Global／Theme／XML／style 同字段，显式空列表关闭，错误类型与空条目异常；竞争列表使用不同 ID）。
- `SslDefaultsMergerTest` 新增逐状态部分覆盖、显式空列表与 install 隔离。
- `StateSkeletonLayoutSavedStateTest` 按当前格式全字段往返、多 ID、显式关闭与无效格式拒绝。
- 新增恢复前已缓存状态页的回归测试，验证显式关闭解绑和多入口恢复后的实际点击派发。
- `StateSkeletonLayoutViewTest`、`StateSkeletonLayoutRefinementsTest`、`StateSkeletonLayoutOptimizationTest` 使用新回调与列表入口验证事件、禁用、时序、缓存与替换；删除仅为历史格式保留的测试。
- 新增 `stateskeletonlayout/src/debug` 夹具：`ssl_test_state_retry_multi.xml`、`ssl_test_host_retry_priority.xml`、`ssl_test_host_retry_style_only.xml`、`ssl_test_retry.xml`；`ssl_test_host_attrs.xml` 单入口改为单元素数组。

### 示例应用 `:sample`：多重试入口

- 新增「多重试入口」演示页（`retry/MultiRetryDemoFragment` + `fragment_multi_retry.xml`、`demo_state_empty_retry.xml`、`demo_state_error_retry.xml`、`arrays_retry.xml`，`DemoCatalog` 入口 +1）：全局安装一次、页面注册一次回调后所有默认热区可用；两个局部实例隔离、禁用、替换与回调计数；离开恢复 canonical。
- 存量 8 处单 ID 声明统一替换为共享单元素数组引用；`fragment_builtin_state.xml` 清理已删除属性说明。
- `BasicFourStateFragment`、`BuiltInStateDemoFragment`、`SvgaLoadingFragment`、`FeedDemoFragment`、`WorkSectionStateView` 统一注册／释放 `OnRetryListener`。
- 中英文案新增 `demo_multi_*`；`demo_builtin_*` 同步新接口表述。
- 新增 `MultiRetryDemoFragmentTest`（示例回调、实例隔离与恢复）。

### 库模块 `:stateskeletonlayout`：条目拼装模板 T2

#### 新增公开 API

- `SkeletonItemSpec(layoutResId, count = 8, binder = null)`：单种条目的离屏拼装声明；
  `count` 必须大于 0，`binder` 为空表示该布局自身已有固定尺寸。
- `StateSkeletonLayout.skeletonItems` / `setSkeletonItemTemplate()` / `clearSkeletonItems()`：
  T2 条目拼装模板的运行时入口。`setSkeletonItemTemplate` 原子建立
  `TEMPLATE + 条目` 组合；`skeletonItems = null`（或空列表）表示未使用 T2。
- `SslLayoutOptions.skeletonItems`：程序化批量配置新增同名字段，`null` 跳过；
  与 `skeletonConfig` 同时出现时成对校验、成对提交。
- XML 属性 `ssl_skeletonItemLayout`（reference）/ `ssl_skeletonItemCount`（integer，默认 `8`）：
  单类型快捷声明，不参与 Global 覆盖，Theme 层可提供。

#### 行为变更

- T1（整张模板 `ssl_skeletonTemplate`）与 T2（条目拼装 `skeletonItems`）互斥：
  并存时抛 `IllegalArgumentException`（错误信息含双方属性名），失败方配置整体回滚、原值保留。
- `skeletonItems` 非空仅 `SkeletonSource.TEMPLATE` 下有效；`source=CONTENT` 配 items
  在 setter 即抛异常。`TEMPLATE` 暂无模板的中间态允许存在（程序化分步配置时），
  完整性在 XML 构造期严格校验、运行时渲染入口（`preflight`）兜底。
- `SkeletonConfig(source=TEMPLATE)` 允许暂不带 `templateLayoutResId`（此前构造即抛异常）；
  缺模板的失败改由控件统一校验在构造 / 渲染时抛出，错误信息不变。
- 当前 SavedState v4 格式包含条目布局与条数两份列表；binder 不可序列化，
  恢复后置空并打 Debug 日志（`StateSkeletonLayout` tag），宿主需在重建后重新设置占位绑定。
- 恢复与配置变更：条目拼装宿主按 `configuration.hashCode()` 失效重拼；
  昼夜切换后 Loading 不中断、不退化为空几何。

#### 测试

- 库单测新增 5 个测试类：`SkeletonItemSpecTest`（纯 JVM）、
  `SkeletonItemsValidationTest`（互斥/回滚/属性名）、`ItemAssembledTemplateTest`
  （8 行 24 几何、空文本失败指引、binder 逐条调用）、`ItemAssembledConfigChangeTest`
  （昼夜重建、SavedState 往返 + binder 置空日志）、`ItemAssembledScreenshotTest`
  （T1/T2 双 Roborazzi 基线 `skeleton_t1_item_rows.png` / `skeleton_t2_item_reuse.png`）。
- 既有 `SkeletonConfigTest` 同步放宽：TEMPLATE 缺模板构造不再抛异常（完整性改由控件校验）。

### 示例应用 `:sample`：条目模板与局部骨架

- 新增「条目复用骨架」演示页（`recycler/ItemReuseSkeletonFragment` + `fragment_item_reuse_skeleton.xml`，
  `DemoCatalog` 入口 +1）：同一 `RecyclerView` + 同一份 `item_profile_row`，
  一键切换 T2（复用 + 占位 binder）与 T1（手写模板）对比；业务 Adapter 全程不被替换。
- 新增 `ItemReuseSkeletonFragmentTest`（5 项）：XML 声明解析、T1/T2 切换、Adapter 不变。
- 中英文案各新增 `demo_item_reuse_*` 4 条；新增页仅引入与既有页同类的 ButtonStyle warning，
  无新增 Lint Error。
- 新增「简历局部骨架」演示页：上半介绍与下半工作经历独立控制加载，演示局部骨架、空态和错误态；详情页统一使用标题栏。
- 补齐简历示例缺少的 18 条英文资源，修复示例 Lint 的 `MissingTranslation` 错误。

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
