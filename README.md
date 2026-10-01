# stateskeletonlayout

页面状态（内容 / 加载中 / 空内容 / 错误）与骨架加载（纯色 / 流光 / 脉冲）二合一的
Android View 控件。页面只提交目标状态，不直接协调状态页可见性、骨架动画、
最短展示时间与动画生命周期。

- 库模块：`:stateskeletonlayout`（命名空间 `com.sfyc.ssl`）
- 示例应用：`:sample`（源码包 `com.sfyc.demo`，namespace / applicationId `com.sfyc.demo.ssl`，15 个演示页）
- UI：Android View System + XML + ViewBinding，不引入 Jetpack Compose
- 环境：Kotlin 2.2.10、AGP 8.13.2、Gradle 8.14、`compileSdk` / `targetSdk` 36、
  `minSdk` 26、JVM 17
- 库依赖：仅 AndroidX Annotation（无 Core、Lottie、Material、Navigation、Lifecycle）

已完成模块与包名迁移。
示例源码包与 namespace 不同，R / ViewBinding 使用 `com.sfyc.demo.ssl`；新示例按新 applicationId 独立安装。
原模块和旧包调用方需同步依赖、import 与 XML 标签（历史迁移记录见内部 docs，未随公开仓库发布）。

## 接入方式

本工程暂不发布 Maven 制品。宿主工程以本地模块依赖接入：

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
| `ssl_emptyLayout` | reference | 无 | 请求 EMPTY 缺配置时抛异常（含属性名） |
| `ssl_errorLayout` | reference | 无 | 请求 ERROR 缺配置时抛异常 |
| `ssl_loadingLayout` | reference | 无 | `useSkeleton=false`（默认）缺配置时打警告并回退骨架 |
| `ssl_useSkeleton` | boolean | `false` | `true`=骨架、`false`=自定义布局 |
| `ssl_skeletonSource` | `content`、`template` | `content` | — |
| `ssl_skeletonTemplate` | reference | 无 | `template` 缺模板/模板无形状时抛异常 |
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

val requestedState: StateLayoutState      // 最近请求（只读）
val renderedState: StateLayoutState       // 已呈现（只读）
var useSkeleton: Boolean                // true=骨架、false=自定义，默认false
var skeletonConfig: SkeletonConfig        // 整体替换
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
fun clearStateViewCache(state)             // 只能释放非当前态
fun setSkeletonNodeConfig(viewId, config)  // null 移除规则
fun setStateDescription(state, description)
fun diagnostics(): StateLayoutDiagnostics
```

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
- `maximumMaskPixels` 可设置像素预算，默认不限制。超限或分配失败静态降级；`diagnostics()` 按需查询实际分配字节、重建尝试次数、降级原因与动画状态，不自动记录或上传。

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
   静态行覆盖首屏即可。
5. **如何完整播放一轮 Lottie / Shimmer？**
   把 `ssl_minLoadingDuration` 配成与动画单轮时长一致（Lottie 在可见性回调中启停，
   库不依赖 Animator 帧回调做状态决策）。
6. **如何关闭切换动画？**
   `ssl_transitionEffect="none"`（或运行时 `transitionEffect = NONE`）；
   系统动画关闭时自动按 NONE 处理。
7. **如何运行时更换状态布局？**
   `setStateViewLayout(EMPTY, R.layout.…)`；旧 View 被移除，下次呈现重新 inflate；
   正在呈现该状态时会立即无动画重建。
8. **为什么请求未配置状态会抛异常？**
   有意为之：快速失败好过无反馈空白页；异常信息包含缺失的属性名
   （如 `ssl_emptyLayout`），按提示补配置即可。
9. **模块迁移后 Android Studio 提示找不到旧模块实体？**
   `Can't find module entity for StateSkeletonLayout.StateSkeletonLayout` 表示 IDE 导入工程结构时仍使用旧模块标识。
   重新打开本工程，执行 `File > Sync Project with Gradle Files`，同步完成后选择 `sample` 运行配置再构建。
   命令行构建通过不能代替 IDE 同步结果；如仍出现请 clean 后重建。

## 工程结构与测试

```text
StateSkeletonLayout/                    # 工作区目录保留；Gradle 根工程名为 stateskeletonlayout
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradle/libs.versions.toml            # 版本固定，无动态版本
├─ stateskeletonlayout/                 # :stateskeletonlayout（库）
│  ├─ src/main/java/com/sfyc/ssl/
│  │  ├─ StateSkeletonLayout.kt         # 唯一公开容器
│  │  ├─ StateLayoutState / SkeletonSource /
│  │  │   SkeletonEffect / SkeletonDirection / SkeletonConfig /
│  │  │   StateTransitionEffect / SkeletonNodeConfig / 四个监听器 / 诊断快照
│  │  └─ internal/                      # 协调器/注册表/渲染器/遮罩/时钟（不公开）
│  ├─ src/main/res/values/             # attrs/colors/dimens/strings/styles
│  ├─ src/debug/res/layout/            # 测试夹具（不进 Release AAR）
│  ├─ src/test/                        # JVM：协调器时序 + 配置校验 + Robolectric View 测试
│  └─ src/androidTest/                 # 真机：切换/触摸/无障碍/detach 恢复
└─ sample/                              # :sample（15 个示例页，源码包 com.sfyc.demo）
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

本轮模块与包名迁移验证（2026-10-01）：

- 库 127 项、示例 36 项 JVM / Robolectric 测试，共 163 项通过，无失败、错误或跳过。
- 库 Release Lint：0 Error / 16 Warning；示例 Debug / Release Lint：均为 0 Error / 53 Warning。
  库 Release 和示例 Debug 的 Warning 数量及问题类型分布与迁移前一致。
- 两模块 Debug / Release、两模块 AndroidTest APK 均组装通过；示例 Release 完成 R8 压缩，标准 Release APK 仍未签名。
- AAR 公开类、ViewBinding、六类 Epoxy 生成模型及示例 / 测试 Manifest 的新包名已核对。
- 设备测试已尝试，因真机 ADB 未授权、模拟器不可用而报告 `No online devices found`，未实际执行。
  15 个示例页、旋转恢复、R8 Release 启动等仍需设备补验。

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

未在本次自动化覆盖、需手工验收的项目：

- 360dp / 375dp / 393dp / 411dp 四档宽度、横竖屏、超大字体下的示例页布局。
- TalkBack 全流程（播报、焦点隔离、重试可达）。
- 开发者选项动画比例 0x / 0.5x / 1x 下的骨架与切换表现。
- 连续快速点击状态按钮与重试按钮的手感。
- Linear / Grid 列表在真机上的模板对齐与滚动。

## 网络多类型示例

首页进入第 15 项「网络多类型」。刷新区域按 SmartRefreshLayout → StateSkeletonLayout →
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

## 限制（首版不包含）

- Jetpack Compose 接口。
- 自动发起网络请求 / 自动绑定 ViewModel。
- 任意业务状态数量与动态状态注册。
- 替换 Adapter 的列表骨架、ViewPager2 骨架。
- 节点级 XML 属性、任意 Path、逐行文本取形、旋转与缩放几何。
- 公共自定义 LoadingRenderer 插件接口。
- Maven 发布流水线、图片加载、服务端错误模型。

## 参考与许可

- 设计思路参考 Drakeet StateLayout（https://github.com/Drakeet/StateLayout）与 Faltenreich SkeletonLayout（https://github.com/Faltenreich/SkeletonLayout），未复制其源代码，两者均为 Apache-2.0。
- 库模块仅依赖 AndroidX Annotation（Apache-2.0）；示例模块另用 AppCompat / Material / RecyclerView / Lifecycle / Lottie / SmartRefreshLayout / Epoxy / Coroutines 等，均为 Apache-2.0（Checker Qual 为 MIT），仅供示例演示。
- 本工程采用 Apache License 2.0，见根目录 `LICENSE`。
