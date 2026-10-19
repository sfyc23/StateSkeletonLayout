# stateskeletonlayout

[English](README_EN.md) | **中文** | [修改日志](CHANGELOG.md)

页面状态（内容 / 加载中 / 空内容 / 错误）与骨架加载（纯色 / 流光 / 脉冲）二合一的
Android View 控件。页面只提交目标状态，不直接协调状态页可见性、骨架动画、
最短展示时间与动画生命周期。

- 库模块：`:stateskeletonlayout`（命名空间 `com.sfyc.ssl`）
- 示例应用：`:sample`（源码包 `com.sfyc.demo`，namespace / applicationId `com.sfyc.demo.ssl`，20 个演示页）
- UI：Android View System + XML + ViewBinding，不引入 Jetpack Compose
- 环境：Kotlin 2.2.10、AGP 8.13.2、Gradle 8.14、`compileSdk` / `targetSdk` 36、
  `minSdk` 26、JVM 17
- 库显式依赖：仅 AndroidX Annotation（无 Core、Lottie、SVGA、Material、Navigation、Lifecycle）；Kotlin 插件自动添加 Kotlin 标准库

已完成模块与包名迁移。
示例源码包与 namespace 不同，R / ViewBinding 使用 `com.sfyc.demo.ssl`；新示例按新 applicationId 独立安装。
原模块和旧包调用方需同步依赖、import 与 XML 标签（历史迁移记录见内部 docs，未随公开仓库发布）。

## 接入方式

### JitPack（0.0.3）

本工程通过 JitPack 发布库模块的 Release AAR、源码包和 API 文档包，示例应用不进入发布制品。
在宿主工程的 `settings.gradle.kts` 中加入仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.sfyc23")
            }
        }
    }
}
```

然后在应用模块的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.sfyc23:StateSkeletonLayout:0.0.3")
}
```

构建状态与日志见 [JitPack 项目页面](https://jitpack.io/#sfyc23/StateSkeletonLayout/0.0.3)。
首次请求该版本时 JitPack 会构建制品，只有构建成功后才能解析依赖。

从 `0.0.1` 升级时，需要重新编译应用及使用 `SslGlobalDefaults` / `StateLayoutDiagnostics` 的依赖库。
这两个公开数据类新增字段，旧 AAR 中对构造函数或 `copy` 的调用可能不再匹配；详见 [修改日志](CHANGELOG.md)。

### 本地模块

需要直接修改库源码时，也可以在宿主工程中以本地模块依赖接入：

```kotlin
dependencies {
    implementation(project(":stateskeletonlayout"))
}
```

## 快速开始

### 1. 最小 XML

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:id="@+id/state_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_emptyLayout="@layout/state_empty"
    app:ssl_errorLayout="@layout/state_error"
    app:ssl_errorRetryViewId="@id/retry_button"
    app:ssl_useSkeleton="true"
    app:ssl_skeletonSource="content"
    app:ssl_skeletonEffect="shimmer">

    <!-- 唯一业务内容子 View（恰好一个，多了少了都会在膨胀时抛异常） -->
    <include layout="@layout/content_profile" />

</com.sfyc.ssl.StateSkeletonLayout>
```

### 2. Fragment / ViewBinding 中切换状态

```kotlin
class ProfileFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val stateLayout = binding.stateLayout
        // 错误页重试：单向事件，控件不自动切状态、不发网络请求。
        stateLayout.setOnRetryClickListener {
            viewModel.onRetryClicked()
        }
        viewModel.uiState.observe(viewLifecycleOwner) { ui ->
            stateLayout.render(ui.toLayoutState())
        }
    }
}
```

### 3. ViewModel StateFlow 到四态的映射

```kotlin
// ViewModel 只决定“目标状态”，显示规则（延迟、最短展示、最新优先）由控件执行。
val uiState: StateFlow<ProfileUiState> = repository.profile
    .map { result ->
        when {
            result.isLoading -> ProfileUiState.Loading
            result.error != null -> ProfileUiState.Error(result.error.message)
            result.data == null -> ProfileUiState.Empty
            else -> ProfileUiState.Content(result.data)
        }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState.Loading)

// Fragment 侧：
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { binding.stateLayout.render(it.toLayoutState()) }
    }
}
```

### 4. 程序化创建（不用 XML）

```kotlin
val layout = StateSkeletonLayout(context)
layout.applyOptions(
    SslLayoutOptions(
        emptyLayoutResId = R.layout.state_empty,
        errorLayoutResId = R.layout.state_error,
        errorRetryViewId = R.id.retry_button,
        useSkeleton = true,
        skeletonConfig = SkeletonConfig(effect = SkeletonEffect.PULSE),
    )
)
layout.setContentView(contentView) // 只能调用一次
```

`applyOptions` 只是把既有 setter 按固定顺序调用一遍，字段为 `null` 时跳过、保留当前值。
既有 setter 全部保留可用，两种入口可以混用。全空 `SslLayoutOptions()` 是空操作
（可用 `isEmpty` 判断）。

`SslLayoutOptions.skeleton(emptyLayoutResId, errorLayoutResId)` 是"骨架 + 两份状态页"的常用组合便捷工厂。

也可以用逐个 setter 的写法：

```kotlin
layout.setContentView(contentView) // 只能调用一次
layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.state_empty)
layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.state_error)
```

程序化创建可使用与 XML 相同的重试入口，默认节流间隔仍为 500 ms：

```kotlin
layout.errorRetryViewId = R.id.retry_button
layout.retryClickThrottleMillis = 500L
layout.setOnRetryClickListener { viewModel.onRetryClicked() }
// 请求期间由业务明确控制；控件不推断网络状态，也不修改按钮 enabled。
layout.retryClicksEnabled = !uiState.isRetrying
```

程序化实例在首次挂载时应用初始态，可以先配置布局资源；挂载前的显式 `render` 优先于初始态。

### 5. 不配置状态页也能用（内置兜底布局）

`EMPTY` / `ERROR` 布局**不配置时不再抛异常**，而是回退到库内置的兜底页面：
居中一行文案，错误页额外带一个重试按钮。最小接入只需要业务内容本身：

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="true">

    <include layout="@layout/content_profile" />

</com.sfyc.ssl.StateSkeletonLayout>
```

内置错误页自带重试按钮，未显式设置 `ssl_errorRetryViewId` 时控件会自动接管它作为统一重试入口，
无需额外配置。内置布局的固定 ID 是公开的，可用来替换文案：

```kotlin
// 库内置布局的 ID（声明在库的 ids.xml 中，可直接引用）。
com.sfyc.ssl.R.id.ssl_builtin_state_root     // 根 LinearLayout
com.sfyc.ssl.R.id.ssl_builtin_state_message  // 文案 TextView
com.sfyc.ssl.R.id.ssl_builtin_state_retry    // 重试按钮（仅错误页）

layout.setStateDescription(StateLayoutState.ERROR, "网络不可用")
// 或直接改文案：拿到根节点后查找内部控件
layout.getStateView(StateLayoutState.ERROR)
    ?.findViewById<TextView>(com.sfyc.ssl.R.id.ssl_builtin_state_message)
    ?.text = "网络不可用，请检查连接"

// 接入早期就希望"缺配置立即暴露"的工程可以关掉兜底，恢复严格失败：
StateSkeletonLayoutDefaults.install(SslGlobalDefaults(useBuiltInStateLayouts = false))
```

内置文案资源为 `ssl_builtin_empty_message` / `ssl_builtin_error_message` / `ssl_builtin_retry`
（中英各一份），宿主可用同名资源覆盖。内置布局只使用 `android:*` 属性，**不引入 Material / AppCompat 依赖**。

### 6. 在 Jetpack Compose 中使用

库本身**不引入 Compose 依赖**，也不提供 Compose 工件；Compose 页面用 `AndroidView` 包装即可：

```kotlin
@Composable
fun ProfileScreen(state: StateLayoutState, content: @Composable () -> Unit) {
    val holder = remember { mutableStateOf<StateSkeletonLayout?>(null) }
    AndroidView(
        factory = { context ->
            StateSkeletonLayout(context).apply {
                addView(ComposeView(context).apply { /* 或用 XML 布局 */ })
                holder.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(state) { holder.value?.render(state) }
}
```

注意两点：`StateSkeletonLayout` 要求恰好一个业务内容子 View；用 `ComposeView` 作为该子 View 时，
骨架的"内容取形"只能取到 `ComposeView` 这一层，**要骨架就用模板取形**（`ssl_skeletonSource="template"`）。

## 全局设计默认属性

一次安装，全部新实例自动继承；XML 属性与运行时 setter 可逐项覆盖。
适合统一品牌视觉（骨架色、圆角、效果、最短展示时长等）。

### 安装（Application.onCreate）

```kotlin
StateSkeletonLayoutDefaults.install(
    SslGlobalDefaults(
        useSkeleton = true,
        skeletonSource = SkeletonSource.CONTENT,
        effect = SkeletonEffect.PULSE,
        maskColor = getColor(R.color.brand_skeleton),
        minimumLoadingDurationMillis = 500L,
    )
)
```

- **部分覆盖**：`SslGlobalDefaults` 字段为 `null` 时不参与覆盖，保留下一层默认值。
- **install 整体替换**：重复调用以最后一次为准，不做字段级合并。
- **生效时机**：仅在控件构造时合并一次；已创建实例不受后续 `install` 影响。
- **不覆盖布局资源类属性**（`emptyLayout` / `errorLayout` / `loadingLayout` /
  `skeletonTemplate` / `errorRetryViewId`），这些属性页面各异，仍由 XML / 运行时单独配置。

### 可选：Theme Attribute 辅助层

```xml
<style name="AppTheme" parent="...">
    <item name="ssl_stateSkeletonLayoutStyle">@style/AppSslDefaults</item>
</style>

<style name="AppSslDefaults" parent="ssl_StateSkeletonLayout">
    <item name="ssl_maskColor">@color/brand_skeleton</item>
    <item name="ssl_skeletonEffect">pulse</item>
</style>
```

### 优先级链（低 → 高）

```
库内置资源 / 常量 < Theme 默认 < Global Config < XML 显式 style < XML 直接属性 < 运行时 setter
```

同一属性在 Theme 与 Global 同时设置时，**Global 优先**。显式 style 的父样式也属于页面层；其中的 `?attr` 引用仍按真实 Theme 解析。

### 测试隔离

单元测试用例间调用 `StateSkeletonLayoutDefaults.reset()` 避免串扰。

## 骨架模式

### 内容取形 vs 模板取形

| | 内容取形（`content`） | 模板取形（`template`） |
| --- | --- | --- |
| 形状来源 | 遍历业务内容中的可见叶子 View | 独立 XML 模板 |
| 适用条件 | 进入 Loading 时内容已完成布局、有有效尺寸 | 内容无尺寸、列表首屏占位 |
| 失败行为 | 无有效矩形时降级为整块静态遮罩（Debug 打警告） | 入口无效模板快速失败；已验证模板变窄后暂时空几何则静态降级 |
| 声明 | `ssl_skeletonSource="content"`（默认） | `ssl_skeletonSource="template"` + `ssl_skeletonTemplate="@layout/…"` |

模板只描述形状，不承载真实文字与业务数据：

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="true"
    app:ssl_skeletonSource="template"
    app:ssl_skeletonTemplate="@layout/skeleton_profile_template">
    <include layout="@layout/content_profile" />
</com.sfyc.ssl.StateSkeletonLayout>
```

### 条目拼装模板（T2）：复用成功态 item 布局

`template` 来源有两种供给方式，互斥二选一：

| | T1 整张模板 | T2 条目拼装 |
| --- | --- | --- |
| 声明 | `ssl_skeletonTemplate="@layout/…"` | `ssl_skeletonItemLayout="@layout/item_…"` + `ssl_skeletonItemCount="8"` |
| 适用 | 形状与成功布局差异大，需手工编排 | 成功态 item 布局本身就是想要的形状，直接复用 |

T2 把成功布局逐条 inflate 后纵向拼装为离屏模板，再走同一套几何采集与遮罩绘制；
业务 Adapter 不被替换、不被读取。XML 只支持单类型（多类型用代码声明）：

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="true"
    app:ssl_skeletonSource="template"
    app:ssl_skeletonItemLayout="@layout/item_profile_row"
    app:ssl_skeletonItemCount="8">

    <androidx.recyclerview.widget.RecyclerView
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</com.sfyc.ssl.StateSkeletonLayout>
```

成功布局里多为 `wrap_content` 的空文本 `TextView`，inflate 出来高度为 0，
必须用占位绑定填充占位文本（只决定几何高度，不进入无障碍树）：

```kotlin
stateLayout.setSkeletonItemTemplate(R.layout.item_profile_row, 8) { itemView, _ ->
    itemView.findViewById<TextView>(R.id.demo_row_title)?.text = "占位标题"
    itemView.findViewById<TextView>(R.id.demo_row_subtitle)?.text = "占位副标题"
}
// 多类型：按首屏自上而下的顺序声明
stateLayout.skeletonItems = listOf(
    SkeletonItemSpec(R.layout.item_profile_row, 3) { v, _ -> /* 填充占位文本 */ },
    SkeletonItemSpec(R.layout.view_text_image, 2),
)
stateLayout.clearSkeletonItems()  // 清除条目拼装声明
```

规则：T1 与 T2 互斥（并存抛异常，错误信息含属性名）；`skeletonItems` 仅
`source=TEMPLATE` 下有效；binder 只做同步占位填充，不可序列化——进程重建后置空
（Debug 日志提示），宿主需在重建后重新设置；Grid 与横向行首版仍用 T1 整张模板。

### 三种效果（互斥）

- `solid`：静态纯色，无动画器。
- `shimmer`（默认）：流光扫过，方向遵循 RTL（`start_to_end` 在 RTL 下即从右向左）。
- `pulse`：整体透明度在 `[pulseMinAlpha, pulseMaxAlpha]` 内余弦呼吸。

系统动画被关闭时自动降级为静态显示，不留空白。

### RecyclerView：只用模板，不替换 Adapter

首版不支持替换 Adapter 的列表骨架（会破坏滚动位置、状态恢复与业务 Adapter 生命周期）。
推荐做法：为列表写独立模板，静态放置足够覆盖首屏的重复行；Grid 按目标列数设计：

```xml
app:ssl_skeletonSource="template"
app:ssl_skeletonTemplate="@layout/skeleton_list_template"
```

如果成功态 item 布局本身就是想要的形状，可改用条目拼装模板（T2）直接复用，
免去手写 `skeleton_*` 镜像（见上节"条目拼装模板"与示例"条目复用骨架"）；
Grid 首版仍用 T1 整张模板。

**列表骨架推荐"一个实例覆盖整屏"，而不是每个 item 一个 `StateSkeletonLayout`。**

原因有两层，改造后仍然成立：

- **遮罩内存**：每个实例稳定持有一张与自身等大的 `ALPHA_8` 遮罩 Bitmap。
  20 个 item 级实例 = 20 张遮罩同时驻留。
- **动画开销**：库已把骨架动画改为**进程级共享时钟**（N 个实例只驱动一个
  `ValueAnimator`，主线程每帧一次回调），这项开销不再是 N 倍——但遮罩仍是每个实例一份。

因此"整屏模板"是当前唯一能同时保住内存与帧率的做法：模板里静态放 8 行左右的占位，
滚动时不会续接（这属于"无限列表骨架"的范畴，尚不支持）。

如果确实需要 item 级骨架，建议先用 `diagnostics()` 的 `maskBytes` / `maskBuildCount`
测量真实占用，再决定规模上限。

### 配置变更（昼夜 / 字体缩放 / locale / RTL）

控件已覆写 `onConfigurationChanged`，会自动处理：

- **骨架颜色与圆角**：仅当它们**仍等于上次从资源解析出的值**（即宿主从未显式覆盖过）时，
  用新 Configuration 重新解析 `ssl_mask_default` / `ssl_highlight_default` /
  `ssl_corner_radius_default`。宿主通过 XML / Global / setter 指定过任一项时保持不动，
  品牌色不会被资源默认值顶掉。
- **状态页缓存**：三个状态的缓存全部清空，当前呈现的状态页按新配置立即重建
  （状态页可能含固定 dp 尺寸与静态文案）。布局资源声明保留。
- **骨架模板**：渲染器按 `configuration.hashCode()` 自行判定是否需要重新 inflate。
- **RTL 流光**：`SkeletonOverlayView.onRtlPropertiesChanged` 重建着色器并失效遮罩。

如果你的 Activity 声明了 `android:configChanges` 而不重建 View，上述逻辑生效；
不声明时 View 会被重建，配置自然由新实例读入。两种情况都不需要业务额外处理。

切换列数时同步更换模板并刷新：

```kotlin
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    templateLayoutResId = R.layout.skeleton_grid_template,
)
stateLayout.invalidateSkeleton()
```

### 运行时更新配置

配置是不可变快照，整体替换（逐个修改可变属性会被统一校验打回）：

```kotlin
// 切换效果：遮罩不重建，动画即时切换。
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    effect = SkeletonEffect.PULSE,
)
// 内容替换导致形状变化时主动刷新。
stateLayout.invalidateSkeleton()
```

### 节点规则与资源默认

```kotlin
stateLayout.setSkeletonNodeConfig(R.id.avatar, SkeletonNodeConfig(shape = SkeletonShape.CIRCLE))
stateLayout.setSkeletonNodeConfig(R.id.divider, SkeletonNodeConfig(excluded = true))
stateLayout.setSkeletonNodeConfig(R.id.title, SkeletonNodeConfig(cornerRadius = 4f)) // px
stateLayout.setSkeletonNodeConfig(R.id.title, null) // 恢复全局圆角
// 创建与当前资源一致的配置；复制时保留模板来源等页面参数。
val defaults = SkeletonConfig.from(context)
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    maskColor = defaults.maskColor,
    highlightColor = defaults.highlightColor,
    maximumMaskPixels = 2_000_000L,
)
```

规则按 ID 应用于内容和模板；同一 ID 的多个节点共享规则，找不到 ID 时无操作。排除容器会排除整棵子树；如果最终内容几何全部为空，仍执行整块静态降级。
布局编辑器中保持静态反馈，`isInEditMode` 下不运行无限骨架动画；预览不替代设备验收。

## 自定义加载布局与 Lottie

库不依赖 Lottie。创建回调负责绑定，加载可见性回调负责启停，释放回调负责清理：

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="false"
    app:ssl_loadingLayout="@layout/state_loading_lottie">
    <include layout="@layout/content_profile" />
</com.sfyc.ssl.StateSkeletonLayout>
```

```kotlin
var lottieView: LottieAnimationView? = null

stateLayout.setOnStateViewCreatedListener { _, created, createdView ->
    // 状态布局首次 inflate 时回调一次，适合查找 Lottie 并持有引用。
    if (created == StateLayoutState.LOADING) {
        lottieView = createdView.findViewById(R.id.lottie_view)
    }
}
stateLayout.setOnLoadingVisibilityChangedListener { _, visible, loadingView ->
    // 同为 LOADING 的布局替换、聚合隐藏、detach / attach 也会通知。
    if (visible) {
        lottieView = loadingView?.findViewById(R.id.lottie_view)
        lottieView?.playAnimation()
    } else lottieView?.pauseAnimation()
}
stateLayout.setOnStateViewReleasedListener { _, state, releasedView ->
    if (state == StateLayoutState.LOADING) {
        releasedView.findViewById<LottieAnimationView>(R.id.lottie_view)?.cancelAnimation()
    }
}
```

Fragment 还应在 `onStop` 暂停、`onStart` 根据实际可见性恢复，在 `onDestroyView` 取消动画并解除回调；完整接入见 Lottie 示例。

创建、动态绑定和释放回调只操作传入 View，不得重入 `render` 或修改控件配置。查询与公开回调返回原始布局根节点；内部包装容器由库负责交互隔离。呈现回调继续表示状态提交，不等淡入完成，也不代表窗口一直可见。

## SVGA 加载示例

示例首页的「SVGA 加载」演示本地动画作为自定义 Loading 页面。进入页面自动模拟 7 秒加载；
「Loading」按钮持续加载，Content / Empty / Error 按钮取消模拟任务并切换状态，错误页重试重新执行模拟加载。
Lottie 和其他示例继续保留，SVGA 依赖只进入 `sample`。

固定版本为 `com.github.yyued:SVGAPlayer-Android:2.6.1`。应用接入时，需要在
`settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中配置 JitPack，内容过滤包含 `com.github.yyued`，
再添加依赖。本项目已有 Parcelize 运行库，SVGA 旧扩展运行库会引入重复类，应用按以下配置排除：

```kotlin
implementation("com.github.yyued:SVGAPlayer-Android:2.6.1") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}
```

`sample` Manifest 的 `tools:replace="android:allowBackup,android:label"` 保留应用现有的备份设置和名称，
解决 SVGA AAR 自带 `<application>` 属性导致的合并冲突。Release 使用官方建议的 Wire / SVGA Proto 保留规则，见 `sample/proguard-rules.pro`。

素材打包在 `sample/src/main/assets/loading.svga`，来自
[SVGAPlayer-Web-Lite 的 loading.svga](https://github.com/svga/SVGAPlayer-Web-Lite/blob/master/__test__/svga/loading.svga)。
运行时不下载动画、不需要网络权限。资源为 SVGA 2.0 数据，画布 `375 × 667`，90 帧、15 FPS，标称单轮约 6 秒；
示例采用 `144dp × 256dp` 的 `fitCenter` View，完整显示画布。

接入由 `sample/src/main/java/com/sfyc/demo/svga/SvgaLoadingFragment.kt` 和两份 XML 展示：

- `fragment_svga.xml`：配置 `ssl_useSkeleton="false"` 和 `ssl_loadingLayout="@layout/state_loading_svga"`。
- `state_loading_svga.xml`：`SVGAImageView` 禁止自动播放、不设置 `source`，解析失败时保留进度指示和加载文案。
- Fragment 使用 `SVGAParser(applicationContext).decodeFromAssets` 异步解析，通过弱引用、解析代次和目标 View 身份丢弃过期结果。
- Loading 在窗口可见、View 生命周期达到 STARTED、资源就绪且系统动画启用时播放；隐藏或后台停止，恢复时从首帧重播。
- 普通隐藏保留实体；布局释放或 `onDestroyView` 时停止并 `clear()`，迟到的未绑定实体直接清理。
- 系统动画关闭时仅保留静态加载文案。模拟请求与动画解析、单轮播放完成事件相互独立。

同一页面重复加载复用已解析实体；解析失败后，每次明确重试最多发起一次解析。
配置变化会重新绑定页面并重新执行 7 秒模拟，不保存动画帧位置或剩余请求时间。

新增的 `SvgaLoadingFragmentTest` 覆盖 7 秒计时、取消任务、解析失败、无效播放参数、迟到回调、后台恢复、系统动画关闭，
并用真实资源与 Native Canvas 检查不同帧的像素变化；`SvgaLoadingSmokeTest` 用于连接设备后的入口、播放和生命周期冒烟。
JVM Native Canvas 绘制检查不替代真机、模拟器或 R8 Release 的实际运行验收。

SVGA 接入验证记录（2026-10-11；本次提交验证见[修改日志](CHANGELOG.md)）：示例 54 项、库 127 项 JVM / Robolectric 测试共 181 项通过，无失败、错误或跳过；
新增 SVGA 用例在 API 26 / 34 共执行 18 项，包含真实资源解析和 Native Canvas 帧像素检查。
Debug、R8 Release、AndroidTest APK 均组装通过；Debug / Release Lint 均为 0 Error / 58 Warning，
其中本页新增 1 项 Overdraw、4 项 ButtonStyle，沿用现有示例背景与按钮样式，没有整体屏蔽警告。
新增 7 个依赖校验附件已与 JitPack / Maven Central 文件核对，库运行依赖图没有 SVGA 或 Wire。
已生成调试密钥签名的 `build/svga-implementation/sample-release-validation.apk`，v2 / v3 验签通过，仅供本地验证。
注意：该 APK 是**仓库外手工签名**的临时产物，仓库里没有任何 `signingConfig` 或 keystore 配置。
按本文件「版本发布」一节的命令构建出的标准 Release APK 一定是**未签名**的，两者不要混淆。
设备任务已尝试，但因 `No connected devices!` 未执行；设备冒烟、R8 Release 实际运行、TalkBack 和内存场景仍需补验。

[官方 SVGA Android 仓库](https://github.com/svga/SVGAPlayer-Android)已归档并停止维护。本示例固定使用 2.6.1，
新项目接入仍需核对实际依赖解析和 Release 运行表现。

## 状态时序

### requestedState vs renderedState

- `requestedState`：最近一次请求的目标（调用方意图）。
- `renderedState`：已呈现给用户的状态（画面事实）。

请求成功不等于画面已切换：`render(LOADING)` 可能因显示延迟尚未呈现，
也可能因请求太快被直接跳过。请按 `renderedState`（或呈现回调）做断言与埋点。
内部采用事务式提交：目标 View 真正可见后才推进 `renderedState`；同步配置或
View 创建失败时保留旧画面与旧状态，修正后可重新提交同一目标。

### 加载显示延迟（`ssl_loadingShowDelay`，默认 0ms）

请求 Loading 后延迟一段时间再真正显示。延迟窗口内收到 Content / Empty / Error，
Loading 被直接跳过——100ms 级快速请求不会闪现骨架。

### 最短展示时间（`ssl_minLoadingDuration`，默认 300ms）

Loading 一旦真正上屏至少保留该时长（等待首次布局的时间不计入）。窗口内收到的目标被暂存，到期只提交最新者；
等待期间重新请求 Loading 则取消退出、继续当前 Loading（开始时间与动画不重置）。

### 最新状态优先与幂等

- 等待退出期间连续提交 Content → Empty → Error，最终只呈现 Error，不闪回中间态。
- 重复提交相同状态是空操作：不重建 View、不重启动画、不重置计时。
- 新请求到来时取消旧切换动画并归一化到当前提交画面，最终只保留最新状态。
- `CROSSFADE` 以库拥有的包装容器为动画对象；动画开始即拦截旧画面的触摸、焦点与无障碍访问，保留业务后代属性。
- 所有延迟任务携带 generation token，已取消的旧任务即使被异常投递也无法覆盖新状态。

### detach / attach

- detach：停止全部动画、注销布局 / 滚动监听、释放遮罩并取消延迟任务；之后的新请求只校验资源声明并记录最终意图，不创建状态 View 或 Handler 任务。
- 聚合不可见：停止动画与几何监听，保留遮罩；协调器继续现有计时，重新可见时核对几何后恢复。
- attach：已呈现落后于最新请求时，按最新请求重新调度（计时重新开始；
  进程重建后不继续旧的最短展示倒计时）。
- 旋转设备：`SavedState` 恢复请求状态、状态页资源、骨架配置、节点规则、重试参数和实例状态描述；不保存监听器或业务数据。恢复无切换动画，Loading 从真实提交重新计时。
- `ViewModel` 随后提交的新状态优先级更高。

## XML 属性参考

与 `stateskeletonlayout/src/main/res/values/attrs.xml` 保持一致。
所有属性使用 `ssl_` 前缀。挂载状态下在入口预检配置；detach 后仅校验资源类型，创建 / 绑定错误在重新挂载时暴露。
优先级：库内置 < Theme 默认 < Global Config < XML 显式 style < XML 直接属性 < 运行时 setter。

| 属性 | 格式 / 枚举 | 默认值 | 前置条件 / 错误行为 |
| --- | --- | --- | --- |
| `ssl_initialState` | `content`、`loading`、`empty`、`error` | `content` | 初始请求进入真实呈现流程；骨架就绪后才推进 rendered |
| `ssl_emptyLayout` | reference | 无（回退库内置空态页） | 未配置时用内置兜底页，不抛异常；`useBuiltInStateLayouts=false` 时缺配置抛异常（含属性名） |
| `ssl_errorLayout` | reference | 无（回退库内置错误页） | 未配置时用内置兜底页，自带重试按钮并被自动接管为统一重试入口 |
| `ssl_loadingLayout` | reference | 无 | `useSkeleton=false`（默认）缺配置时打警告并回退骨架 |
| `ssl_useSkeleton` | boolean | `false` | `true`=骨架、`false`=自定义布局 |
| `ssl_skeletonSource` | `content`、`template` | `content` | — |
| `ssl_skeletonTemplate` | reference | 无 | `template` 缺模板/模板无形状时抛异常；与 `ssl_skeletonItemLayout` 互斥 |
| `ssl_skeletonItemLayout` | reference | 无 | 仅 `template` 下有效；常指向成功后 RecyclerView 的 item 布局 |
| `ssl_skeletonItemCount` | integer | `8` | 必须大于 0；XML 单类型快捷声明，多类型用代码 `skeletonItems` |
| `ssl_skeletonEffect` | `solid`、`shimmer`、`pulse` | `shimmer` | 三者互斥 |
| `ssl_maskColor` | color | `@color/ssl_mask_default` | 骨架基础色 |
| `ssl_highlightColor` | color | `@color/ssl_highlight_default` | 仅流光使用 |
| `ssl_cornerRadius` | dimension | `8dp` | 不得小于 0 |
| `ssl_animationDuration` | integer（毫秒） | `1200` | 必须大于 0；与最短展示是两个独立概念 |
| `ssl_shimmerDirection` | `start_to_end`、`end_to_start` | `start_to_end` | 遵循 RTL，不硬编码左右 |
| `ssl_shimmerAngle` | integer（度） | `0` | 归一化到 `[0, 359]` |
| `ssl_pulseMinAlpha` | float | `0.35` | `[0, 1]` 且不得大于最大值 |
| `ssl_pulseMaxAlpha` | float | `1.0` | `[0, 1]` |
| `ssl_loadingShowDelay` | integer（毫秒） | `0` | 不得小于 0 |
| `ssl_minLoadingDuration` | integer（毫秒） | `300` | 不得小于 0 |
| `ssl_transitionEffect` | `none`、`crossfade` | `crossfade` | 系统动画关闭时按 `none` 处理 |
| `ssl_transitionDuration` | integer（毫秒） | `180` | 不得小于 0 |
| `ssl_errorRetryViewId` | reference | 无 | 指向错误布局内部 View；找不到只打警告 |
| `ssl_announceStateChanges` | boolean | `true` | 进入 Loading/Empty/Error 时播报一次 |

另提供默认样式 `@style/ssl_StateSkeletonLayout`（与上表默认值一致），
可直接引用以减少重复声明。注意：`declare-styleable` 名为
`SslStateSkeletonLayout`（`resourcePrefix` 要求库资源带 `ssl_` 前缀），
XML 使用侧的属性名不受影响。

Theme Attribute `ssl_stateSkeletonLayoutStyle` 指向一个 Style 作为辅助层，
控件构造器 `defStyleAttr` 默认值即该属性；不配置时该层跳过。

## Kotlin 接口速查

```kotlin
// 全局默认（进程级，构造时合并一次）
StateSkeletonLayoutDefaults.install(SslGlobalDefaults(...))
StateSkeletonLayoutDefaults.reset()       // 测试隔离用

// 实例级批量配置（程序化创建路径，与逐个 setter 等价；null 字段跳过）
layout.applyOptions(SslLayoutOptions(emptyLayoutResId = ..., errorLayoutResId = ...))
SslLayoutOptions.skeleton(emptyLayoutResId, errorLayoutResId)  // 常用组合便捷工厂

val requestedState: StateLayoutState      // 最近请求（只读）
val renderedState: StateLayoutState       // 已呈现（只读）
var useSkeleton: Boolean                // true=骨架、false=自定义，默认false
var skeletonConfig: SkeletonConfig        // 整体替换
var skeletonItems: List<SkeletonItemSpec>?  // T2 条目拼装声明；null=未使用
fun setSkeletonItemTemplate(layoutResId, itemCount, binder)  // 单类型快捷声明（原子建立 TEMPLATE+条目）
fun clearSkeletonItems()                  // 清除条目拼装声明
var loadingShowDelayMillis: Long
var minimumLoadingDurationMillis: Long
var transitionEffect: StateTransitionEffect
var transitionDurationMillis: Long
var announceStateChanges: Boolean

fun render(state: StateLayoutState)       // 唯一状态入口（主线程）
fun showLoading() / showContent() / showEmpty() / showError()  // 均委托 render
fun invalidateSkeleton()                  // 主动刷新遮罩（主线程）
fun setStateViewLayout(state, layoutResId) // 更换状态布局（CONTENT 不支持）
var errorRetryViewId: Int
var retryClickThrottleMillis: Long       // 默认 500ms，可设 0
var retryClicksEnabled: Boolean
fun setOnRetryClickListener(listener)     // 重试单向事件
fun setOnStateViewCreatedListener(listener)
fun setOnRenderedStateChangedListener(listener)
fun setOnLoadingVisibilityChangedListener(listener)
fun setOnStateViewReleasedListener(listener)
fun getStateView(state): View?            // 只查询缓存；骨架加载返回装饰层
fun updateStateView(state, update)         // 显式创建 / 绑定，不切状态
fun clearStateViewCache(state)             // 只能释放非当前态，且不得是过渡中的 View
fun setSkeletonNodeConfig(viewId, config)  // null 移除规则
fun setStateDescription(state, description)
fun diagnostics(): StateLayoutDiagnostics  // 含 inStateViewTransaction 标记
```

公开默认值常量（唯一真源，XML 属性表与它们保持一致）：
`DEFAULT_TRANSITION_DURATION_MILLIS`、`DEFAULT_RETRY_CLICK_THROTTLE_MILLIS`、
`DEFAULT_LOADING_SHOW_DELAY_MILLIS`、`DEFAULT_MINIMUM_LOADING_DURATION_MILLIS`。

上述运行时配置 setter、状态布局与监听器注册均属于 UI API，只允许在主线程调用；
后台线程调用会得到一致的 `IllegalStateException`。

## 无障碍和性能

### 无障碍

- 骨架遮罩是装饰层：无描述、不进焦点顺序，但拦截触摸。
- Loading 显示时业务内容暂时对读屏隐藏，返回内容态时恢复。
- Empty / Error / Content 切换时只保留当前状态可访问。
- API 30+ 用 `stateDescription` 和 polite live region 表达状态；API 26–29 保留一次性播报。实例可覆盖状态描述，重复提交不重复通知，隐藏时不播报。
- 默认文案提供中文和英文资源；默认骨架颜色提供 `values-night`。昼夜变化后重建实例，或用 `SkeletonConfig.from(context)` 读取新资源并整体替换配置；显式 Global / XML / setter 颜色保持其覆盖语义。
- 错误页重试控件有效触控区域不小于 `48dp × 48dp`（示例布局已满足）。
- 状态不只靠颜色区分；系统动画关闭时显示静态骨架/状态页，不留空白。

### 性能

- 每个骨架控件稳定持有一张 `ALPHA_8` 遮罩；安全替换时旧、新两张短暂并存，之后释放旧遮罩。动画帧只更新矩阵 / 透明度并重绘。
- 不在 `onDraw` 与动画回调中遍历 View 树、不分配对象；几何只在布局/配置失效时重算。
- effect、颜色、方向、角度或时长变化只更新 Paint/Shader，不重建遮罩 Bitmap。
- Bitmap 分配失败时优先保留旧遮罩；没有旧遮罩则显示静态纯色，不把内存不足误报为模板错误。
- 状态页按需创建；不可见时停止动画，detach 才取消协调器延迟任务。非当前态缓存可显式释放。
- 多个控件实例的状态、计时、Bitmap、监听器完全独立；全局默认配置只读共享、
  构造时合并一次，不构成可变全局状态。
- `maximumMaskPixels` 可设置像素预算，默认不限制。**预算是单张遮罩的限值**：遮罩替换瞬间旧、新两张并存，因此峰值约为该值对应内存的 2 倍。超限或分配失败静态降级；`diagnostics()` 按需查询实际分配字节、重建尝试次数、降级原因与动画状态，不自动记录或上传。
- 遮罩字节数已实测确认：`ALPHA_8` 为 1 字节/像素，`maskWidth × maskHeight` 即字节数（400×160 → 64000）。遮罩**不跨实例共享**，N 个骨架实例就是 N 张——列表场景请参考上文"列表骨架推荐整屏模板"。

## 常见问题

1. **请求 Loading 后没看到骨架？**
   先看是否配置了显示延迟（延迟窗口内完成会跳过）；再看内容是否有有效尺寸
   （内容取形要求内容已布局）；`useSkeleton=false` 缺少自定义 Loading 时会打警告并回退骨架。可用 `diagnostics()` 检查当前降级原因和遮罩尺寸。
2. **内容取形没生成预期形状？**
   只采集业务子树中的可见叶子 View：GONE / INVISIBLE 业务节点、`Space`、零宽高 View 会被跳过；
   Loading 期间内部 `ContentHost` 虽为 INVISIBLE，仍通过局部坐标持续采集后代布局变化；
   可按 ID 排除节点、设置圆形或独立圆角；复杂列表可使用独立模板。支持平移、滚动、祖先矩形裁剪，不处理任意旋转、缩放、clipPath 或文本逐行取形。
   单独修改 `translationX/Y` 或 `clipBounds` 而没有触发布局 / 滚动事件时，调用 `invalidateSkeleton()` 更新取形；库不会逐动画帧扫描 View 树。
3. **初始内容高度为 0 时怎么办？**
   用模板模式（`ssl_skeletonSource="template"`）；内容取形此时会降级为整块
   静态遮罩并在 Debug 打警告。
4. **RecyclerView 为什么不替换 Adapter？**
   替换会破坏滚动位置、状态恢复与业务 Adapter 生命周期；首版只支持模板骨架，
   静态行覆盖首屏即可。成功态 item 布局本身就是想要的形状时，可用条目拼装模板（T2）
   直接复用同一份布局，仍不触碰 Adapter（见"条目拼装模板"与示例"条目复用骨架"）。
5. **如何完整播放一轮 Lottie / Shimmer？**
   把 `ssl_minLoadingDuration` 配成与动画单轮时长一致（Lottie 在可见性回调中启停，
   库不依赖 Animator 帧回调做状态决策）。
6. **如何关闭切换动画？**
   `ssl_transitionEffect="none"`（或运行时 `transitionEffect = NONE`）；
   系统动画关闭时自动按 NONE 处理。
7. **如何运行时更换状态布局？**
   `setStateViewLayout(EMPTY, R.layout.…)`；旧 View 被移除，下次呈现重新 inflate；
   正在呈现该状态时会立即无动画重建。
8. **请求未配置的状态页会怎样？**
   默认**回退库内置兜底页**（居中文案，错误页自带重试按钮），不再抛异常。
   内置页视觉刻意极简，有自己的设计稿时应通过 `ssl_emptyLayout` / `ssl_errorLayout` 覆盖。
   若希望"缺配置立即暴露"，安装 `SslGlobalDefaults(useBuiltInStateLayouts = false)`
   恢复严格失败，此时异常信息会包含缺失的属性名（如 `ssl_emptyLayout`）。
9. **模块迁移后 Android Studio 提示找不到旧模块实体？**
   `Can't find module entity for StateSkeletonLayout.StateSkeletonLayout` 表示 IDE 导入工程结构时仍使用旧模块标识。
   重新打开本工程，执行 `File > Sync Project with Gradle Files`，同步完成后选择 `sample` 运行配置再构建。
   命令行构建通过不能代替 IDE 同步结果；如仍出现请 clean 后重建。

## 示例沉浸式系统栏

示例应用在 `MainActivity` 中调用 `enableEdgeToEdge()`，系统栏图标随日夜主题调整；
横屏允许绘制进入刘海区，内容安全区域由 `sample/src/main/java/com/sfyc/demo/insets/DemoInsets.kt` 处理。

- 首页仅对 `demoHomeContent` 添加 padding；详情由各 Fragment 根 View 单独处理，避免两层 Insets 叠加。
- `applyDemoSystemBarPadding()` 保留 XML 原始 padding，叠加 `systemBars` 与 `displayCutout`；重复分发 Insets 时始终以初始 padding 计算。
- 顶部、底部和两侧可分别关闭；监听器返回原始 Insets，不消费后代 Insets。
- 网络示例只在外层根 View 处理，SmartRefreshLayout 和 StateSkeletonLayout 内部不再重复添加。
- 本次适配仅在 `sample` 中实现；示例没有输入框，未处理 IME Insets。

`DemoInsetsTest` 覆盖原始 padding 的保留和方向开关。设备验收应检查横竖屏、刘海、手势导航、
三键导航和日夜模式下，标题、状态按钮及列表内容是否被系统栏遮挡。

## 工程结构与测试

```text
StateSkeletonLayout/                    # 工作区目录保留；Gradle 根工程名为 stateskeletonlayout
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradle/libs.versions.toml            # 版本固定，无动态版本
├─ gradle/verification-metadata.xml     # 全部依赖的 SHA-256 校验
├─ stateskeletonlayout/                 # :stateskeletonlayout（库）
│  ├─ api/stateskeletonlayout.api       # 公开 API 基线（apiCheck 校验）
│  ├─ consumer-rules.pro                # 有意为空，见下
│  ├─ src/main/java/com/sfyc/ssl/
│  │  ├─ StateSkeletonLayout.kt         # 唯一公开容器
│  │  ├─ SslLayoutOptions.kt            # 实例级批量配置快照
│  │  ├─ StateLayoutState / SkeletonSource /
│  │  │   SkeletonEffect / SkeletonDirection / SkeletonConfig /
│  │  │   StateTransitionEffect / SkeletonNodeConfig / 四个监听器 / 诊断快照
│  │  └─ internal/                      # 协调器/注册表/渲染器/遮罩/时钟（不公开）
│  │     ├─ TransitionController.kt     # 状态切换动画（含取消语义）
│  │     ├─ SslDefaultValues.kt         # 默认值唯一真源
│  │     └─ skeleton/SkeletonTicker.kt  # 进程级共享动画时钟
│  ├─ src/main/res/values/             # attrs/colors/dimens/strings/styles/ids
│  ├─ src/main/res/layout/             # 内置空态 / 错误态兜底布局
│  ├─ src/debug/res/layout/            # 测试夹具（不进 Release AAR）
│  ├─ src/test/                        # JVM：时序 + 配置 + 默认值一致性 + SavedState 往返 + 截图回归
│  ├─ src/test/screenshots/            # Roborazzi 截图基线（需入库）
│  └─ src/androidTest/                 # 真机：切换/触摸/无障碍/detach 恢复
└─ sample/                              # :sample（20 个示例页，源码包 com.sfyc.demo）
```

```powershell
# JVM 单元测试（含 Robolectric View / 绘制与优化回归测试）
.\gradlew.bat :stateskeletonlayout:testDebugUnitTest
# Lint Error 强制失败 / 组装 / Release / R8 消费端
.\gradlew.bat :stateskeletonlayout:lintRelease
.\gradlew.bat :stateskeletonlayout:assembleDebug
.\gradlew.bat :stateskeletonlayout:assembleRelease
.\gradlew.bat :stateskeletonlayout:assembleDebugAndroidTest
.\gradlew.bat :sample:testDebugUnitTest
.\gradlew.bat :sample:lintDebug
.\gradlew.bat :sample:lintRelease
.\gradlew.bat :sample:assembleDebug
.\gradlew.bat :sample:assembleRelease
.\gradlew.bat :sample:assembleDebugAndroidTest
# 有设备时：
.\gradlew.bat :stateskeletonlayout:connectedDebugAndroidTest
.\gradlew.bat :sample:connectedDebugAndroidTest
```

示例目录通过 Fragment 回退栈关闭详情。预测性返回提交或取消后，`MainActivity` 在下一轮主线程同步详情容器可见性；
回退栈为空时，详情容器必须为 `GONE`，避免空容器的全屏背景遮挡目录。
`MainActivityNavigationTest` 覆盖普通返回、预测性返回、取消返回和前后台切换；
`DemoSmokeTest` 在设备上覆盖全部示例返回、取消返回和重建后再次进入。

```powershell
# 首页导航回归
.\gradlew.bat :sample:testDebugUnitTest --tests com.sfyc.demo.MainActivityNavigationTest
.\gradlew.bat :sample:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sfyc.demo.DemoSmokeTest
```

本轮模块与包名迁移验证（2026-10-01）：

- 库 127 项、示例 36 项 JVM / Robolectric 测试，共 163 项通过，无失败、错误或跳过。
- 库 Release Lint：0 Error / 16 Warning；示例 Debug / Release Lint：均为 0 Error / 53 Warning。
  库 Release 和示例 Debug 的 Warning 数量及问题类型分布与迁移前一致。
- 两模块 Debug / Release、两模块 AndroidTest APK 均组装通过；示例 Release 完成 R8 压缩，标准 Release APK 仍未签名。
- AAR 公开类、ViewBinding、六类 Epoxy 生成模型及示例 / 测试 Manifest 的新包名已核对。
- 设备测试已尝试，因真机 ADB 未授权、模拟器不可用而报告 `No online devices found`，未实际执行。
  当时为 15 个示例页（后增至 16 个，当前为 20 个）；旋转恢复、R8 Release 启动等仍需设备补验。

以下历史记录属于迁移前状态，旧任务名与旧产物路径按原样保留，不作为本轮验证证据。

此前优化验证记录（2026-10-01，新增网络示例前、模块迁移前）：

- 库 JVM / Robolectric：127 项通过，0 失败、0 错误、0 跳过；包含 API 26 / 34 的关键回归。
- 库 Release Lint：0 Error / 13 Warning；demo Debug Lint：0 Error / 45 Warning。保留版本更新、示例样式和文案提示，没有随功能修改升级依赖或整体屏蔽警告。
- 库 Release AAR、demo Debug / Release、两模块 AndroidTest APK 均组装通过；demo Release 的 R8 处理通过。Release APK 未签名。
- 连接的 M2004J7AC（Android 12 / API 31）拒绝安装：`INSTALL_FAILED_USER_RESTRICTED`。两模块设备测试均未实际执行，不能据 APK 组装结果认定通过。
- TalkBack、不同屏宽 / 字体、布局编辑器与长期内存场景仍需人工验收。

历史验证记录（2026-09-22，模块迁移前，真机 PDVM00，Android 11；不是本轮通过证明）：

- JVM 单元测试 72/72 通过（含协调器事务、流光方向、遮罩/OOM、动态几何、状态恢复和过渡取消用例）。
- 真机 `:StateSkeletonLayout` 5/5 通过（含 Shimmer 相邻帧像素变化），`:demo` 冒烟 1/1 通过。
- 双模块 Lint 0 Error；Release AAR 与 R8 压缩 demo Release 组装通过。
- 库依赖树经核查仅含 AndroidX Annotation；Gradle Wrapper 与依赖均启用 SHA-256 校验，
  CI 会执行单测、Lint、Release 与两模块 AndroidTest APK 组装。
  CI 环境为 `windows-latest` + 单一 JDK 17，无多 JDK / 多 API 矩阵（与开发机保持一致）；
  AndroidTest 只**组装 APK**，从不执行设备测试，设备用例始终需要人工连接设备运行。

未在本次自动化覆盖、需手工验收的项目：

- 360dp / 375dp / 393dp / 411dp 四档宽度、横竖屏、超大字体下的示例页布局。
- TalkBack 全流程（播报、焦点隔离、重试可达）。
- 开发者选项动画比例 0x / 0.5x / 1x 下的骨架与切换表现。
- 连续快速点击状态按钮与重试按钮的手感。
- Linear / Grid 列表在真机上的模板对齐与滚动。

## 网络多类型示例

首页进入「网络多类型」。刷新区域按 SmartRefreshLayout → StateSkeletonLayout →
EpoxyRecyclerView 组合：首屏使用模板骨架，下拉刷新与上拉分页保留已有内容。
网络使用本地 `delay` 模拟，不需要服务端、联网权限或图片加载库。

- 六种模型：Banner、Section、Profile、Article、Tile、横向推荐行；默认 10 / 10 / 7
  分页，共 27 条业务记录、30 个展示模型。
- 九种确定性场景：正常三页、首屏空、首屏失败一次、首屏持续失败、刷新空、刷新失败、
  第二页失败一次、首屏无更多、空末页。首屏失败一次可通过错误页重试恢复；重新播放会重置故障。
- 控制区可选择 100 / 500 / 1500 ms 延迟、切换 Linear / 双列 Grid、重新播放或加载下一页。
  改延迟影响下次请求，切布局不发请求；Grid 中只有 Tile 占半行。
- 旋转保留 ViewModel 请求与数据，模型提交后恢复外层及横向位置；进程重建只恢复小参数并重新请求首屏。
  刷新或分页失败保留数据和游标，刷新成功为空则进入 Empty。
- 仅 `sample` 接入 SmartRefreshLayout 3.0.0-alpha、Epoxy 5.2.1、KSP 2.2.10-2.0.2 和
  Coroutines 1.10.2。库模块 API、源文件和依赖未改动。

Windows 跨盘构建已验证：当前缓存位于 F 盘、项目位于 G 盘，KSP2 的增量依赖路径记录会失败，
因此 `gradle.properties` 设置 `ksp.incremental=false`。Kotlin 插件仍为 2.2.10；sample 运行标准库
实际解析为 2.2.21，处理器配置的标准库为 2.3.0-Beta1，后者不进入 APK。

网络示例迁移前验证记录（2026-10-01）：

- Demo JVM / Robolectric 36 项、库回归 127 项，共 163 项通过，0 失败 / 错误 / 跳过。
- Demo Debug Lint：0 Error / 53 Warning；库 Release Lint：0 Error / 16 Warning。
  新页面的 8 项 Overdraw 提示对应嵌套卡片、横向子项和日夜页面背景，未整体屏蔽警告。
- Debug、R8 Release、两模块 AndroidTest APK 和库 Release AAR 均通过正常依赖校验构建；
  新增 112 个 SHA-256 校验附件与官方仓库字节一致，已有校验条目与信任范围保留。
- 已生成本地调试密钥签名的 `build/network-implementation/demo-release-validation.apk`，
  v2 / v3 签名验证通过，仅用于本地 R8 补验，不修改项目 Release 签名配置。
- M2004J7AC（Android 12 / API 31）拒绝安装 `INSTALL_FAILED_USER_RESTRICTED`；
  两模块设备测试均未执行，Release 运行、实际手势、TalkBack 与屏宽矩阵仍需补验。

## 版本发布

### 关于 `consumer-rules.pro`（R8 / 混淆）

`stateskeletonlayout/consumer-rules.pro` 会随 Release AAR 透传给宿主。它**只有注释、没有任何 keep 规则**，
这是有意的：

- XML 里使用的自定义 View（`<com.sfyc.ssl.StateSkeletonLayout>`）由 Android Gradle Plugin
  自动生成精确的 keep 规则，不需要手工声明；
- 通过 Kotlin / Java 直接调用的公开 API 由 R8 可达性分析保留；
- 写 `public *` 一类通配规则会把大量实际用不到的代码一起留下，掩盖真实的压缩问题。
  `:sample` 的 `minifyEnabled = true` Release 构建作为消费端回归。

**例外**：如果你的工程只通过**反射或动态类名**使用本控件（例如配置驱动的页面框架），
AGP 无法推断引用关系，需要自行加规则：

```proguard
-keep class com.sfyc.ssl.StateSkeletonLayout { public *; }
-keep class com.sfyc.ssl.SkeletonConfig { public *; }
-keep class com.sfyc.ssl.SslLayoutOptions { public *; }
```

### 发布流程

版本号在根目录 `gradle.properties` 的 `libraryVersion` 中统一维护，库 POM 与示例应用读取同一值。
当前版本为 `0.0.3`，Git 标签也使用 `0.0.3`（不加 `v` 前缀）。

发布坐标同样集中在 `gradle.properties`：`libraryGroup`（`com.github.sfyc23`）、
`libraryArtifactId`（`StateSkeletonLayout`）。示例的 `sampleVersionCode` 与 `libraryVersion`
分开维护——前者只能单调递增的整数，后者是语义化版本字符串，两者无法互相推导，发布时一起改。

> 制品说明：从 `0.0.2` 起发布制品包含 AAR、`-sources.jar` 与 `-javadoc.jar`
> （由 Dokka 生成）。`0.0.1` 已发布且无法追溯补发，缺少 doc 制品。
> 本工程走 JitPack 托管，不带 GPG 签名；也不发布到 Maven Central。

1. 修改版本后，运行库和示例测试、Lint、Release 构建，并验证本地 Maven 发布：

   ```powershell
   .\gradlew.bat :stateskeletonlayout:testDebugUnitTest :stateskeletonlayout:verifyRoborazziDebug :stateskeletonlayout:apiCheck :stateskeletonlayout:lintRelease :stateskeletonlayout:publishReleasePublicationToMavenLocal :sample:testDebugUnitTest :sample:lintRelease :sample:assembleRelease
   ```

2. 提交并推送源码，然后创建并推送与 `libraryVersion` 相同的版本标签：

   ```powershell
   git push origin main
   git tag -a 0.0.3 -m "Release 0.0.3"
   git push origin 0.0.3
   ```

3. 在 JitPack 项目页面请求构建，检查状态和日志；成功后核对 POM、AAR、源码包与 API 文档包，并验证接入项目的依赖解析与构建，再创建对应标签的 GitHub Release。

JitPack 使用 Java 17，通过根目录 `jitpack.yml` 调用库模块的 Maven 发布任务。
发布采用 Maven POM 与标准 `-sources.jar`；为兼容 JitPack 的源码文件名处理，不发布 Gradle `.module` 元数据。
插件与依赖均优先从 Google、Maven Central 和 Gradle Plugin Portal 解析，阿里云镜像作为备用；依赖版本与 SHA-256 校验保持固定。
GitHub 仓库包含库、示例、测试、Gradle Wrapper 与 CI；本机 SDK 配置、构建产物、缓存、签名密钥和内部 `docs/` 不入库。
发布后的标签应保持不变，后续修改使用新的版本号。

## 限制（首版不包含）

- Jetpack Compose 接口。
- 自动发起网络请求 / 自动绑定 ViewModel。
- 任意业务状态数量与动态状态注册。
- 替换 Adapter 的列表骨架、ViewPager2 骨架。
- 节点级 XML 属性、任意 Path、逐行文本取形、旋转与缩放几何。
- 公共自定义 LoadingRenderer 插件接口。
- Maven Central 发布流水线、图片加载、服务端错误模型。

## 参考与许可

- 感谢 [liangjingkanji/StateLayout](https://github.com/liangjingkanji/StateLayout) 和 [Faltenreich/SkeletonLayout](https://github.com/Faltenreich/SkeletonLayout) 提供的设计参考。
- 库模块显式依赖 AndroidX Annotation（Apache-2.0），另有 Kotlin 标准库（Apache-2.0）；示例模块另用 AppCompat / Material / RecyclerView / Lifecycle / Lottie / SVGAPlayer-Android / SmartRefreshLayout / Epoxy / Coroutines 等，均为 Apache-2.0（Checker Qual 为 MIT），仅供示例演示。
- 本工程采用 Apache License 2.0，见根目录 `LICENSE`。
