package com.sfyc.ssl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.MainThread
import com.sfyc.ssl.internal.SslDefaultsMerger
import com.sfyc.ssl.internal.StateCoordinator
import com.sfyc.ssl.internal.StateRetryController
import com.sfyc.ssl.internal.StateViewRegistry
import com.sfyc.ssl.internal.TransitionController
import com.sfyc.ssl.internal.InteractionHost
import com.sfyc.ssl.internal.loading.LayoutLoadingRenderer
import com.sfyc.ssl.internal.loading.LoadingRenderer
import com.sfyc.ssl.internal.loading.SkeletonLoadingRenderer
import com.sfyc.ssl.internal.skeleton.SkeletonOverlayView
import com.sfyc.ssl.internal.time.MainThreadScheduler
import com.sfyc.ssl.internal.time.UptimeMonotonicClock

/**
 * 页面状态与骨架加载的统一容器：四态切换 + 两种加载表现的唯一入口。
 *
 * # 单一状态源
 *
 * 调用方只提交目标状态，禁止直接修改内部内容页、状态页与骨架层的可见性：
 * ```
 * layout.render(StateLayoutState.LOADING)
 * layout.render(StateLayoutState.CONTENT)
 * ```
 * 内部区分 [requestedState]（最近请求）与 [renderedState]（已呈现画面），
 * 以支持加载显示延迟、最短展示时间与切换动画；请求成功不等于画面已切换。
 *
 * # 与业务分离
 *
 * 控件只负责显示状态，不发起网络请求、不判断空/错、不保存业务数据。
 * 错误页重试按钮只发出点击事件，Fragment / Activity 将其转为业务意图，
 * 新的 UI State 再驱动 [render]。
 *
 * # 结构
 *
 * 直接子 View 固定为三个内部容器（业务子 View 会被接管进 [ContentHost]）：
 * ```
 * StateSkeletonLayout
 * ├─ ContentHost      # 唯一业务内容；骨架期间保持布局、抑制绘制与交互
 * ├─ StateHost        # 自定义 Loading / Empty / Error（按需 inflate，复用）
 * └─ SkeletonOverlay  # 仅骨架加载时可见，拦截触摸、不进无障碍焦点
 * ```
 *
 * XML 中必须恰好包含一个业务内容子 View，否则 [onFinishInflate] 抛
 * [IllegalStateException]；程序化创建请使用 [setContentView]。
 */
class StateSkeletonLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.ssl_stateSkeletonLayoutStyle,
) : FrameLayout(context, attrs, defStyleAttr) {

    // region XML 初始配置（解析后不可变，只能整体替换运行时配置）

    private var emptyLayoutResId: Int = 0
    private var errorLayoutResId: Int = 0
    private var customLoadingLayoutResId: Int = 0
    private val initialState: StateLayoutState

    /**
     * 是否启用库内置的空态 / 错误态兜底布局（构造时从 Global 合并一次，随后不可变）。
     *
     * 为 true 且宿主未配置 `ssl_emptyLayout` / `ssl_errorLayout` 时，
     * 用 `ssl_builtin_state_empty` / `ssl_builtin_state_error` 兜底，
     * 避免每个宿主工程都必须自写两份状态页 XML 才能跑起来。
     * 为 false 时保持严格行为：未配置即抛异常，不显示任何兜底页面。
     */
    private val useBuiltInStateLayouts: Boolean

    /** 当前 EMPTY / ERROR 布局是否来自库内置资源（用于决定是否预设重试入口）。 */
    private val usingBuiltInErrorLayout: Boolean
        get() = errorLayoutResId == R.layout.ssl_builtin_state_error

    // endregion

    // region 公开运行时配置

    /**
     * 是否使用骨架遮罩。true=骨架，false=自定义布局，默认 false（即默认自定义布局）。
     * 切换时先停止旧渲染器再启动新渲染器，
     * 两种加载动画不会同时运行；正处 LOADING 时立即切换呈现。
     * 为 false 但未配置自定义布局时，渲染会自动回退到骨架并打警告。
     */
    @get:MainThread
    @set:MainThread
    var useSkeleton: Boolean = false
        set(value) {
            checkMainThread("useSkeleton")
            if (field == value) return
            // 膨胀完成前只记录，渲染器尚未创建，无需切换。
            if (!hostsInitialized) {
                field = value
                return
            }
            val oldRenderer = activeRenderer()
            if (renderedState == StateLayoutState.LOADING) {
                val nextSkeleton = value || !hasCustomLoadingLayout()
                if (nextSkeleton) {
                    if (detached) validateTemplateResource(skeletonConfig) else skeletonRenderer.preflight()
                } else {
                    if (detached) registry.validate(StateLayoutState.LOADING) else registry.require(StateLayoutState.LOADING)
                }
            }
            field = value
            syncLoadingVisibility(forceHidden = true)
            if (renderedState == StateLayoutState.LOADING) {
                oldRenderer.hide()
                if (detached) presentationInvalidated = true
                else presentState(StateLayoutState.LOADING, animate = false) { syncLoadingVisibility() }
            }
        }

    /**
     * 骨架配置快照。整体替换，控件统一校验并失效遮罩；
     * 动画位移/透明度变化不重建遮罩 Bitmap。
     */
    private var skeletonConfigField: SkeletonConfig = SkeletonConfig()
    private var skeletonItemsField: List<SkeletonItemSpec>? = null

    /**
     * 骨架配置快照。整体替换，控件统一校验并失效遮罩；
     * 动画位移/透明度变化不重建遮罩 Bitmap。
     */
    @get:MainThread
    @set:MainThread
    var skeletonConfig: SkeletonConfig
        get() = skeletonConfigField
        set(value) {
            checkMainThread("skeletonConfig")
            if (skeletonConfigField == value) return
            validateTemplateResource(value, skeletonItemsField)
            if (::skeletonRenderer.isInitialized) {
                if (detached) validateTemplateResource(value, skeletonItemsField)
                else if (isSkeletonPairComplete(value, skeletonItemsField)) {
                    skeletonRenderer.preflight(value, skeletonItemsField)
                }
            }
            val previous = skeletonConfigField
            skeletonConfigField = value
            if (::skeletonRenderer.isInitialized) {
                try {
                    skeletonRenderer.onConfigChanged()
                } catch (failure: Throwable) {
                    skeletonConfigField = previous
                    skeletonRenderer.onConfigChanged()
                    throw failure
                }
            }
        }

    /**
     * 条目拼装模板声明（T2）。`null` 表示未使用 T2 方式。
     *
     * 仅 `SkeletonSource.TEMPLATE` 下有效，与
     * `SkeletonConfig.templateLayoutResId`（T1 整张模板）互斥。
     * 整体替换，控件统一校验并失效遮罩；`binder` 不可序列化，
     * 进程重建与配置恢复后置空，需宿主重新设置。
     */
    @get:MainThread
    @set:MainThread
    var skeletonItems: List<SkeletonItemSpec>?
        get() = skeletonItemsField
        set(value) {
            checkMainThread("skeletonItems")
            val normalized = value?.takeIf { it.isNotEmpty() }
            if (skeletonItemsField == normalized) return
            validateTemplateResource(skeletonConfigField, normalized)
            if (::skeletonRenderer.isInitialized) {
                if (detached) validateTemplateResource(skeletonConfigField, normalized)
                else if (isSkeletonPairComplete(skeletonConfigField, normalized)) {
                    skeletonRenderer.preflight(skeletonConfigField, normalized)
                }
            }
            val previous = skeletonItemsField
            skeletonItemsField = normalized
            if (::skeletonRenderer.isInitialized) {
                try {
                    skeletonRenderer.onConfigChanged()
                } catch (failure: Throwable) {
                    skeletonItemsField = previous
                    skeletonRenderer.onConfigChanged()
                    throw failure
                }
            }
        }

    /**
     * 请求 Loading 后延迟多久才真正显示（毫秒）。
     * 延迟窗口内收到非 Loading 状态会直接跳过 Loading，避免闪烁。
     */
    @get:MainThread
    @set:MainThread
    var loadingShowDelayMillis: Long
        get() = coordinator.loadingShowDelayMillis
        set(value) {
            checkMainThread("loadingShowDelayMillis")
            coordinator.loadingShowDelayMillis = value
        }

    /**
     * Loading 一旦显示至少保留多久（毫秒）。
     * 窗口内收到的目标会被暂存，到期只提交最新者。
     */
    @get:MainThread
    @set:MainThread
    var minimumLoadingDurationMillis: Long
        get() = coordinator.minimumLoadingDurationMillis
        set(value) {
            checkMainThread("minimumLoadingDurationMillis")
            coordinator.minimumLoadingDurationMillis = value
        }

    /** 状态切换过渡效果，系统动画关闭时自动按 NONE 处理。 */
    @get:MainThread
    @set:MainThread
    var transitionEffect: StateTransitionEffect = StateTransitionEffect.CROSSFADE
        set(value) {
            checkMainThread("transitionEffect")
            if (field == value) return
            field = value
        }

    /** 状态切换时长（毫秒），不得小于 0。 */
    @get:MainThread
    @set:MainThread
    var transitionDurationMillis: Long = DEFAULT_TRANSITION_DURATION_MILLIS
        set(value) {
            checkMainThread("transitionDurationMillis")
            require(value >= 0) { "transitionDurationMillis 不得小于 0，当前=$value" }
            if (field == value) return
            field = value
        }

    /** 进入 Loading/Empty/Error 时是否做一次性无障碍播报（重复提交不重复播报）。 */
    @get:MainThread
    @set:MainThread
    var announceStateChanges: Boolean = true
        set(value) {
            checkMainThread("announceStateChanges")
            if (field == value) return
            field = value
        }

    @get:MainThread
    @set:MainThread
    var retryClickThrottleMillis: Long
        get() {
            checkMainThread("retryClickThrottleMillis")
            return retryController.retryClickThrottleMillis
        }
        set(value) {
            checkMainThread("retryClickThrottleMillis")
            retryController.retryClickThrottleMillis = value
        }

    /** 是否接受重试事件；请求进行中可暂时关闭，控件不修改业务按钮的 enabled。 */
    @get:MainThread
    @set:MainThread
    var retryClicksEnabled: Boolean
        get() {
            checkMainThread("retryClicksEnabled")
            return retryController.retryClicksEnabled
        }
        set(value) {
            checkMainThread("retryClicksEnabled")
            retryController.retryClicksEnabled = value
        }

    // endregion

    // region 状态只读视图（委托状态协调器）

    /** 最近一次请求的目标状态（调用方意图）。 */
    @get:MainThread
    val requestedState: StateLayoutState
        get() = coordinator.requestedState

    /** 当前已经呈现给用户的状态（画面事实）。 */
    @get:MainThread
    val renderedState: StateLayoutState
        get() = coordinator.renderedState

    // endregion

    // region 内部构件

    private val coordinator: StateCoordinator
    private lateinit var registry: StateViewRegistry
    private lateinit var contentHost: InteractionHost
    private lateinit var stateHost: FrameLayout
    private lateinit var skeletonOverlay: SkeletonOverlayView
    private lateinit var skeletonRenderer: SkeletonLoadingRenderer
    private lateinit var layoutRenderer: LayoutLoadingRenderer

    /** 业务内容 View（唯一）。 */
    private var contentView: View? = null

    /** 内部容器是否已建成；建成前 addView 走 XML 膨胀路径，建成后走业务内容路由。 */
    private var hostsInitialized: Boolean = false

    /** 内部添加 Host 时的放行标志，防止被业务子 View 校验拦截。 */
    private var internalAdding: Boolean = false

    /**
     * 顶层容器之间的切换动画控制器（P-11 提取）。
     *
     * 原先 `crossfade` / `cancelTransitions` / `normalizeToCurrentVisual` /
     * `finishOutgoing` / `cleanupContainers` / `finalVisibilityOf` 与
     * `runningTransitions` / `transitioningViews` 都内联在本类里，约 190 行。
     * 提取为 [TransitionController] 后可单独测试取消语义。
     *
     * 构造在 [initRuntime] 中（依赖三个容器）。
     */
    private lateinit var transitions: TransitionController

    /** 当前呈现的顶层 View；委托给控制器，保持既有读取语义。 */
    private val currentTop: View? get() = if (::transitions.isInitialized) transitions.currentTop else null

    private var onRetryListener: OnRetryListener? = null
    private var onStateViewCreatedListener: OnStateViewCreatedListener? = null
    private var onRenderedStateChangedListener: OnRenderedStateChangedListener? = null

    /**
     * 多重试入口的唯一管理者：有效列表、绑定记录、开关与节流。
     * 公开属性与保存快照均委托它，避免两处各存一份状态。
     * 构造时尚未绑定状态根，初始化注册表后才做实际 View 绑定。
     */
    private val retryController: StateRetryController
    private var lastAnnouncedState: StateLayoutState? = null
    private var lastPresentedState: StateLayoutState = StateLayoutState.CONTENT
    private var savedContentA11yMode: Int = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var aggregatedVisible: Boolean = true
    private var detached = false

    /**
     * 上次按资源解析出的颜色 / 圆角三元组，用于判断当前配置是否仍"由资源派生"。
     *
     * 不能只用一个构造期布尔量：宿主可以在运行时把 `skeletonConfig` 整体替换成
     * 显式品牌色，此后配置就不再由资源派生了。判据必须是**当前值是否仍等于
     * 上次从资源解析出的值**，而不是"构造时是否没显式指定"。
     *
     * 为 null 表示宿主在构造时就显式指定过，从一开始就不该被资源刷新覆盖。
     */
    private var resourceDerivedColors: Triple<Int, Int, Float>? = null
    private var explicitRequest = false
    private var initialApplied = false
    private var restoringPresentation = false
    private var bindingCallback = false
    private var pendingSavedState: SavedState? = null
    private var presentationInvalidated = false
    private val nodeConfigs = mutableMapOf<Int, SkeletonNodeConfig>()
    private val stateDescriptions = mutableMapOf<StateLayoutState, CharSequence>()
    private var onLoadingVisibilityChangedListener: OnLoadingVisibilityChangedListener? = null
    private var onStateViewReleasedListener: OnStateViewReleasedListener? = null
    private var visibleLoadingView: View? = null

    // endregion

    init {
        val (xmlAttrs, themeAttrs) = SslDefaultsMerger.read(context, attrs, defStyleAttr)
        val merged = SslDefaultsMerger.merge(context, xmlAttrs, StateSkeletonLayoutDefaults.current, themeAttrs)
        useBuiltInStateLayouts = merged.useBuiltInStateLayouts

        // 布局资源类属性不参与 Global 覆盖（页面各异），逐项按 XML > Theme > 内置兜底解析。
        val xmlEmpty = xmlAttrs.emptyLayoutResId.takeIf { it != 0 }
        val xmlError = xmlAttrs.errorLayoutResId.takeIf { it != 0 }
        emptyLayoutResId = xmlEmpty ?: themeAttrs.emptyLayoutResId
            .takeIf { it != 0 }
            ?: builtInLayoutOrZero(R.layout.ssl_builtin_state_empty)
        errorLayoutResId = xmlError ?: themeAttrs.errorLayoutResId
            .takeIf { it != 0 }
            ?: builtInLayoutOrZero(R.layout.ssl_builtin_state_error)
        customLoadingLayoutResId = xmlAttrs.customLoadingLayoutResId.takeIf { it != 0 } ?: themeAttrs.customLoadingLayoutResId

        // 创建尚未绑定状态根的控制器 → 解析声明与最终布局来源 → 安装有效列表。
        // 库默认：EMPTY 为空；ERROR 在使用库内置错误布局时为内置按钮，否则为空。
        // 显式列表不自动追加内置按钮；显式空列表可关闭内置按钮。
        val declaredEmpty = merged.emptyRetryViewIds
        val declaredError = merged.errorRetryViewIds
        val libraryEmpty = emptyList<Int>()
        val libraryError = if (usingBuiltInErrorLayout) {
            listOf(R.id.ssl_builtin_state_retry)
        } else {
            emptyList()
        }
        retryController = StateRetryController(
            emptyViewIds = declaredEmpty ?: libraryEmpty,
            errorViewIds = declaredError ?: libraryError,
            gates = object : StateRetryController.Gates {
                override fun renderedState(): StateLayoutState = this@StateSkeletonLayout.renderedState
                override fun requestedState(): StateLayoutState = this@StateSkeletonLayout.requestedState
                override fun isAttached(): Boolean = this@StateSkeletonLayout.isAttachedToWindow
                override fun isAggregatedVisible(): Boolean = aggregatedVisible
                override fun isShown(): Boolean = this@StateSkeletonLayout.isShown
                override fun cachedRoot(state: StateLayoutState): View? =
                    if (::registry.isInitialized) registry.content(state) else null
                override fun wrapperFor(state: StateLayoutState): View? =
                    if (::registry.isInitialized) registry.peek(state) else null
                override fun currentTop(): View? = this@StateSkeletonLayout.currentTop
                override fun hostView(): View = this@StateSkeletonLayout
                override fun retryListener(): Any? = onRetryListener
                override fun dispatch(state: StateLayoutState, clickedView: View) {
                    onRetryListener?.onRetry(this@StateSkeletonLayout, state, clickedView)
                }
            },
        )

        initialState = merged.initialState
        useSkeleton = merged.useSkeleton
        // 骨架模板供给原子初始化：T2 的 config 单独看是“TEMPLATE 却无模板”，
        // 分步走 setter 必然经过非法中间态，故统一校验后直写 backing field。
        // 此时渲染器尚未创建，无需 preflight / onConfigChanged。
        val initialItems: List<SkeletonItemSpec>? = if (merged.skeletonItemLayoutResId != 0) {
            val itemCount = merged.skeletonItemCount ?: SkeletonItemSpec.DEFAULT_COUNT
            listOf(SkeletonItemSpec(merged.skeletonItemLayoutResId, itemCount))
        } else {
            if (merged.skeletonItemCount != null) {
                throw IllegalArgumentException(
                    "ssl_skeletonItemCount 需要配合 ssl_skeletonItemLayout 使用，" +
                        "请先设置条目布局",
                )
            }
            null
        }
        validateTemplateResource(merged.skeletonConfig, initialItems)
        // XML 构造期严格校验完整性：TEMPLATE 下 T1 xor T2 至少其一。
        // 运行时 setter 允许中间态（见 validateTemplateResource），此处不允许。
        if (merged.skeletonConfig.source == SkeletonSource.TEMPLATE &&
            merged.skeletonConfig.templateLayoutResId == null && initialItems == null
        ) {
            throw IllegalArgumentException(
                "ssl_skeletonSource=template 时必须提供 ssl_skeletonTemplate 或 " +
                    "ssl_skeletonItemLayout 其中一种模板供给方式",
            )
        }
        skeletonConfigField = merged.skeletonConfig
        skeletonItemsField = initialItems
        transitionEffect = merged.transitionEffect
        transitionDurationMillis = merged.transitionDurationMillis
        announceStateChanges = merged.announceStateChanges

        // 只有三项全部由资源派生时才记录基准值，供配置变更时判定"是否仍未被显式覆盖"。
        // 构造时就显式指定过任一项的实例，从头到尾都不参与资源刷新。
        resourceDerivedColors = if (merged.colorsFromResources) {
            Triple(
                merged.skeletonConfig.maskColor,
                merged.skeletonConfig.highlightColor,
                merged.skeletonConfig.cornerRadius,
            )
        } else {
            null
        }

        coordinator = StateCoordinator(
            clock = UptimeMonotonicClock(),
            scheduler = MainThreadScheduler(),
            initialState = StateLayoutState.CONTENT,
            loadingShowDelayMillis = merged.loadingShowDelayMillis,
            minimumLoadingDurationMillis = merged.minimumLoadingDurationMillis,
        )
        coordinator.onPresent = { state, commit ->
            presentState(state, animate = !restoringPresentation, onPresented = commit)
        }
        coordinator.onPresented = ::onPresentationCommitted

        isSaveEnabled = true
    }

    /** 内置兜底布局开关为 off 时返回 0，保持"未配置即抛异常"的严格行为。 */
    private fun builtInLayoutOrZero(@LayoutRes layoutResId: Int): Int =
        if (useBuiltInStateLayouts) layoutResId else 0

    // region 内容接管

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (hostsInitialized) return
        // 此时全部直接子 View 均为 XML 声明的业务内容（内部 Host 尚未创建）。
        val businessCount = childCount
        if (businessCount != 1) {
            throw IllegalStateException(
                "StateSkeletonLayout 要求 XML 中恰好包含 1 个业务内容子 View，" +
                    "当前数量=$businessCount，" +
                    "请把业务布局作为唯一直接子 View，状态页通过 ssl_* 属性配置",
            )
        }
        val content = getChildAt(0)
        // buildHosts 会把全部暂存子 View 搬入 ContentHost，此处只需记录引用。
        buildHosts()
        contentView = content
        // 首次无动画渲染：结构恢复优先，ViewModel 后续提交的新状态会覆盖。
        initialApplied = true
        presentRestored(initialState)
    }

    /**
     * 程序化设置业务内容（不对 XML 膨胀路径使用）。
     *
     * 程序化创建时先调用本方法建成内部容器，再设置内容：
     * 只能设置一次；重复设置请直接操作已设置 View 的子树。
     * 必须在主线程调用；若当前正在显示骨架，会自动失效遮罩以重取形状。
     */
    @MainThread
    fun setContentView(view: View, params: ViewGroup.LayoutParams? = null) {
        checkMainThread("setContentView")
        if (!hostsInitialized) {
            // 程序化路径：不允许先 addView 再 setContentView，保证单内容不变量。
            if (childCount > 0) {
                throw IllegalStateException(
                    "程序化创建时请直接调用 setContentView 设置业务内容，不要先 addView",
                )
            }
            buildHosts()
        }
        if (contentView != null) {
            throw IllegalStateException("业务内容已设置，不支持重复设置；请直接更新已有内容 View 的子树")
        }
        val hostParams = (params ?: view.layoutParams) as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        contentHost.addView(view, hostParams)
        contentView = view
        pendingSavedState?.let { saved -> pendingSavedState = null; applyRestoredState(saved) }
        if (isAttachedToWindow && !initialApplied && !explicitRequest) {
            initialApplied = true
            presentRestored(initialState)
        }
        if (renderedState == StateLayoutState.LOADING &&
            effectiveUseSkeleton()
        ) {
            skeletonRenderer.invalidate()
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (internalAdding || !hostsInitialized) {
            // XML 膨胀阶段：原样保留，数量校验延迟到 onFinishInflate。
            super.addView(child, index, params)
            return
        }
        // 初始化完成后：外部 addView 路由为业务内容设置，保证单内容不变量。
        setContentView(child, params)
    }

    // 建成三个内部容器并初始化运行时构件；XML 路径调用前需先移除业务子 View。
    private fun buildHosts() {
        internalAdding = true
        try {
            // XML 路径：业务子 View 暂存在根容器，建成 Host 后再搬入 ContentHost。
            val pendingBusiness = (0 until childCount).map { getChildAt(it) }
            val pendingParams = pendingBusiness.map { it.layoutParams }
            removeAllViews()
            contentHost = InteractionHost(context)
            stateHost = FrameLayout(context).apply { visibility = GONE }
            skeletonOverlay = SkeletonOverlayView(context).apply { visibility = GONE }
            super.addView(
                contentHost,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            super.addView(
                stateHost,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            super.addView(
                skeletonOverlay,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            // 程序化路径 pendingBusiness 为空，内容后续由 setContentView 加入。
            pendingBusiness.forEachIndexed { index, child ->
                val hostParams = (pendingParams[index] as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                contentHost.addView(child, hostParams)
            }
        } finally {
            internalAdding = false
        }
        initRuntime()
        hostsInitialized = true
    }

    private fun initRuntime() {
        registry = StateViewRegistry(context, stateHost) { state, view ->
            try {
                retryController.bind(state, view)
                withBindingCallback { onStateViewCreatedListener?.onStateViewCreated(this, state, view) }
            } catch (failure: Throwable) {
                // 未提交的候选不能残留监听与根节点引用，也不能影响原布局的绑定。
                retryController.unbindRoot(view)
                throw failure
            }
        }
        registry.setInitialLayout(StateLayoutState.EMPTY, emptyLayoutResId)
        registry.setInitialLayout(StateLayoutState.ERROR, errorLayoutResId)
        registry.setInitialLayout(StateLayoutState.LOADING, customLoadingLayoutResId)
        skeletonRenderer = SkeletonLoadingRenderer(
            overlay = skeletonOverlay,
            contentHost = contentHost,
            configProvider = { skeletonConfig },
            inflater = LayoutInflater.from(context),
            nodeConfigProvider = { nodeConfigs[it] },
            itemSpecsProvider = { skeletonItems },
        )
        layoutRenderer = LayoutLoadingRenderer(registry)
        transitions = TransitionController(
            contentHost = contentHost,
            stateHost = stateHost,
            skeletonOverlay = skeletonOverlay,
            durationProvider = { transitionDurationMillis },
            effectProvider = { transitionEffect },
            onOutgoingFinished = ::onTopLayerFinished,
        )
        transitions.currentTop = contentHost
        savedContentA11yMode = contentHost.importantForAccessibility
    }

    /**
     * 旧顶层离开后的收尾。
     *
     * 骨架遮罩与自定义加载页各有自己的渲染器负责真正的"停"；
     * 其余顶层（内容容器、普通状态页）只需落到最终可见性。
     */
    private fun onTopLayerFinished(outgoing: View, endVisibility: Int) {
        when {
            outgoing === skeletonOverlay -> skeletonRenderer.hide()
            outgoing === registry.peek(StateLayoutState.LOADING) -> layoutRenderer.hide()
            else -> outgoing.visibility = endVisibility
        }
    }

    // endregion

    // region 公开状态接口（唯一入口，全部委托 render）

    /**
     * 提交目标状态。必须在主线程调用；重复提交相同状态为幂等空操作。
     *
     * 加载显示延迟、最短展示、最新状态优先等规则由内部协调器执行，
     * 本方法返回时画面可能尚未切换（见 [renderedState]）。
     */
    @MainThread
    fun render(state: StateLayoutState) {
        checkMainThread("render")
        check(hostsInitialized && contentView != null) { "请先通过 XML 或 setContentView 设置业务内容，再调用 render" }
        if (state != requestedState || (!coordinator.hasPendingWork && state != renderedState)) {
            validateStateConfiguration(state)
        }
        explicitRequest = true
        coordinator.request(state)
    }

    private fun validateStateConfiguration(state: StateLayoutState) {
        check(hostsInitialized) { "请先通过 XML 或 setContentView 设置业务内容，再调用 render" }
        when (state) {
            StateLayoutState.CONTENT -> checkNotNull(contentView) {
                "CONTENT 状态要求先通过 XML 或 setContentView 设置业务内容"
            }
            StateLayoutState.LOADING -> if (effectiveUseSkeleton(logIfFallback = true)) {
                if (detached) validateTemplateResource(skeletonConfig) else skeletonRenderer.preflight()
            } else {
                if (detached) registry.validate(StateLayoutState.LOADING) else registry.require(StateLayoutState.LOADING)
            }
            StateLayoutState.EMPTY, StateLayoutState.ERROR -> {
                if (detached) registry.validate(state) else registry.require(state)
            }
        }
    }

    /** [render] 的便捷包装：显示加载。 */
    @MainThread
    fun showLoading() = render(StateLayoutState.LOADING)

    /** [render] 的便捷包装：显示业务内容。 */
    @MainThread
    fun showContent() = render(StateLayoutState.CONTENT)

    /** [render] 的便捷包装：显示空内容页。 */
    @MainThread
    fun showEmpty() = render(StateLayoutState.EMPTY)

    /** [render] 的便捷包装：显示错误页。 */
    @MainThread
    fun showError() = render(StateLayoutState.ERROR)

    /**
     * 主动刷新骨架遮罩（内容替换导致形状变化时调用）。
     * 必须在主线程调用；非骨架加载期间调用仅标记失效。
     */
    @MainThread
    fun invalidateSkeleton() {
        checkMainThread("invalidateSkeleton")
        if (::skeletonRenderer.isInitialized) {
            skeletonRenderer.invalidate()
        }
    }

    /**
     * 更换某状态对应的布局资源。CONTENT 不支持（请用 XML 子 View / [setContentView]）。
     *
     * 更换会移除旧缓存 View；若该状态正在呈现，立即无动画重建一次。
     */
    @MainThread
    fun setStateViewLayout(state: StateLayoutState, @LayoutRes layoutResId: Int) {
        checkMainThread("setStateViewLayout")
        require(layoutResId != 0) { "layoutResId 必须是有效布局资源，当前=$layoutResId" }
        require(resources.getResourceTypeName(layoutResId) == "layout") { "layoutResId 必须指向布局资源" }
        if (!hostsInitialized) {
            // 膨胀完成前调用：仅记录，后续初始化时生效。
            when (state) {
                StateLayoutState.EMPTY -> emptyLayoutResId = layoutResId
                StateLayoutState.ERROR -> errorLayoutResId = layoutResId
                StateLayoutState.LOADING -> customLoadingLayoutResId = layoutResId
                StateLayoutState.CONTENT -> throw IllegalArgumentException(
                    "CONTENT 状态的内容 View 请通过 XML 子 View 或 setContentView 设置",
                )
            }
            return
        }
        if (registry.layoutResource(state) == layoutResId) return
        val active = renderedState == state && !(state == StateLayoutState.LOADING && effectiveUseSkeleton())
        val loadingAffected = renderedState == StateLayoutState.LOADING && state == StateLayoutState.LOADING && !useSkeleton
        val affected = active || loadingAffected
        val released = registry.setLayout(state, layoutResId, prepare = affected && !detached)
        if (affected) syncLoadingVisibility(forceHidden = true)
        if (affected) {
            if (detached) presentationInvalidated = true
            else presentState(state, animate = false) { onPresentationCommitted(state) }
        }
        released?.let { releaseStateView(state, it) }
    }

    /** 查询已缓存的原始布局根节点，不创建状态页。 */
    @MainThread
    fun getStateView(state: StateLayoutState): View? {
        checkMainThread("getStateView")
        return stateViewFor(state)
    }

    /**
     * 一次性应用一组页级配置。
     *
     * # 为什么需要
     *
     * 逐个 setter 配置一个完整实例需要 8–10 次调用：
     * `setStateViewLayout` ×3、重试配置、`retryClickThrottleMillis`、
     * `useSkeleton`、`skeletonConfig`、`transitionEffect`……
     * 程序化创建路径（没有 XML 可写）尤其冗长。
     *
     * 本方法不引入新的状态，只是把既有 setter 按固定顺序调用一遍：
     * 每个字段为 `null` 时跳过该项，保留当前值。
     * 已有 setter 全部保留可用，两种入口可以混用。
     *
     * # 顺序
     *
     * 先布局资源、再重试入口、最后渲染配置。布局资源在前是因为
     * 重试入口的绑定会查找已缓存状态页——状态页尚未创建时
     * 仅记录 ID，创建时再绑定，因此顺序对结果无影响；但先声明布局
     * 能让同一批里的重试配置立即生效于已缓存的状态页。
     *
     * 重试配置自身一致提交：先完成两个列表的规范化与校验，再交给控制器一次提交，
     * 不通过按字段先后赋值制造部分生效状态。这里只保证重试配置自身的一致提交。
     *
     * # 示例
     *
     * ```kotlin
     * val layout = StateSkeletonLayout(context)
     * layout.applyOptions(
     *     SslLayoutOptions(
     *         emptyLayoutResId = R.layout.state_empty,
     *         errorLayoutResId = R.layout.state_error,
     *         retryConfig = StateRetryConfig(
     *             emptyViewIds = listOf(R.id.retry_empty),
     *             errorViewIds = listOf(R.id.retry_button),
     *         ),
     *         useSkeleton = true,
     *         skeletonConfig = SkeletonConfig(effect = SkeletonEffect.PULSE),
     *     )
     * )
     * layout.setContentView(contentView)
     * ```
     */
    @MainThread
    fun applyOptions(options: SslLayoutOptions) {
        checkMainThread("applyOptions")
        options.emptyLayoutResId?.let { setStateViewLayout(StateLayoutState.EMPTY, it) }
        options.errorLayoutResId?.let { setStateViewLayout(StateLayoutState.ERROR, it) }
        options.loadingLayoutResId?.let { setStateViewLayout(StateLayoutState.LOADING, it) }
        options.retryConfig?.let { config ->
            val emptyRoot = if (::registry.isInitialized) registry.content(StateLayoutState.EMPTY) else null
            val errorRoot = if (::registry.isInitialized) registry.content(StateLayoutState.ERROR) else null
            retryController.replaceBoth(config.emptyViewIds, config.errorViewIds, emptyRoot, errorRoot)
        }
        options.retryClickThrottleMillis?.let { retryClickThrottleMillis = it }
        options.retryClicksEnabled?.let { retryClicksEnabled = it }
        options.useSkeleton?.let { useSkeleton = it }
        // 骨架模板供给原子应用：config 与 items 成对校验、成对提交，
        // 避免“TEMPLATE 却无模板”的非法中间态。仅当其中之一出现时才走原子路径。
        if (options.skeletonConfig != null || options.skeletonItems != null) {
            val targetConfig = options.skeletonConfig ?: skeletonConfigField
            val targetItems = options.skeletonItems ?: skeletonItemsField
            validateTemplateResource(targetConfig, targetItems)
            if (::skeletonRenderer.isInitialized) {
                if (detached) validateTemplateResource(targetConfig, targetItems)
                else if (isSkeletonPairComplete(targetConfig, targetItems)) {
                    skeletonRenderer.preflight(targetConfig, targetItems)
                }
            }
            val previousConfig = skeletonConfigField
            val previousItems = skeletonItemsField
            skeletonConfigField = targetConfig
            skeletonItemsField = targetItems?.takeIf { it.isNotEmpty() }
            if (::skeletonRenderer.isInitialized) {
                try {
                    skeletonRenderer.onConfigChanged()
                } catch (failure: Throwable) {
                    skeletonConfigField = previousConfig
                    skeletonItemsField = previousItems
                    skeletonRenderer.onConfigChanged()
                    throw failure
                }
            }
        }
        options.transitionEffect?.let { transitionEffect = it }
        options.transitionDurationMillis?.let { transitionDurationMillis = it }
        options.loadingShowDelayMillis?.let { loadingShowDelayMillis = it }
        options.minimumLoadingDurationMillis?.let { minimumLoadingDurationMillis = it }
        options.announceStateChanges?.let { announceStateChanges = it }
    }

    /** 更新动态文案等内容，不切换状态、不重置 Loading 计时；需要时创建状态页。 */
    @MainThread
    fun updateStateView(state: StateLayoutState, update: (View) -> Unit) {
        checkMainThread("updateStateView")
        check(hostsInitialized) { "请先设置业务内容" }
        val view = if (state == StateLayoutState.CONTENT) checkNotNull(contentView) else {
            require(state != StateLayoutState.LOADING || !effectiveUseSkeleton()) { "骨架加载没有可绑定的业务布局" }
            registry.require(state)
            checkNotNull(registry.content(state))
        }
        withBindingCallback { update(view) }
    }

    /**
     * 释放非当前态缓存，保留资源配置；当前可见态不得清除。
     *
     * 正在参与过渡的 View 也不得清除：`currentTop` 在交叉淡入开始时就已指向新画面，
     * 正在淡出的旧画面满足 `!== currentTop`，但它的淡出动画与 `finishOutgoing`
     * 还持有引用。放行会让淡出结束后对已移除的 View 操作可见性。
     */
    @MainThread
    fun clearStateViewCache(state: StateLayoutState) {
        checkMainThread("clearStateViewCache")
        require(state != StateLayoutState.CONTENT) { "不能清除业务内容" }
        check(hostsInitialized) { "请先设置业务内容" }
        val cached = registry.peek(state)
            ?: return
        check(cached !== currentTop) { "当前呈现的状态页不能清除" }
        check(!transitions.isTransitioning(cached)) {
            "正在参与状态切换动画的状态页不能清除，请等动画结束后重试"
        }
        cached.animate().cancel()
        registry.clear(state)?.let { releaseStateView(state, it) }
    }

    /**
     * 单类型快捷声明：原子建立“TEMPLATE + 条目”组合。
     *
     * 本方法把 config（source 切 TEMPLATE、清空 T1 模板）与 items
     * 成对校验、成对提交。纯程序化从默认 CONTENT 起步时推荐用本方法或
     * [applyOptions]；分步写法仅支持“先 config 到 TEMPLATE、再补 items”
     * （中间态渲染时会按缺模板抛异常），不支持“先 items 后 config”。
     *
     * XML 只支持单类型时用 `ssl_skeletonItemLayout` / `ssl_skeletonItemCount` 声明形状，
     * 占位绑定仍走本方法补齐。多类型且 config 已为 TEMPLATE 时可直接设置 [skeletonItems]。
     */
    @MainThread
    fun setSkeletonItemTemplate(
        @LayoutRes layoutResId: Int,
        itemCount: Int = SkeletonItemSpec.DEFAULT_COUNT,
        binder: ((View, Int) -> Unit)? = null,
    ) {
        checkMainThread("setSkeletonItemTemplate")
        val newItems = listOf(SkeletonItemSpec(layoutResId, itemCount, binder))
        val currentConfig = skeletonConfigField
        val newConfig = if (currentConfig.source == SkeletonSource.TEMPLATE &&
            currentConfig.templateLayoutResId == null
        ) {
            currentConfig
        } else {
            currentConfig.copy(source = SkeletonSource.TEMPLATE, templateLayoutResId = null)
        }
        validateTemplateResource(newConfig, newItems)
        if (::skeletonRenderer.isInitialized) {
            if (detached) validateTemplateResource(newConfig, newItems)
            else skeletonRenderer.preflight(newConfig, newItems)
        }
        val previousConfig = skeletonConfigField
        val previousItems = skeletonItemsField
        skeletonConfigField = newConfig
        skeletonItemsField = newItems
        if (::skeletonRenderer.isInitialized) {
            try {
                skeletonRenderer.onConfigChanged()
            } catch (failure: Throwable) {
                skeletonConfigField = previousConfig
                skeletonItemsField = previousItems
                skeletonRenderer.onConfigChanged()
                throw failure
            }
        }
    }

    /** 清除条目拼装声明（T2），回到 T1 / 未配置状态。 */
    @MainThread
    fun clearSkeletonItems() {
        checkMainThread("clearSkeletonItems")
        skeletonItems = null
    }

    /** 设置 / 移除指定 ID 的节点规则，适用于内容和模板中的相同 ID。 */
    @MainThread
    fun setSkeletonNodeConfig(@IdRes viewId: Int, config: SkeletonNodeConfig?) {
        checkMainThread("setSkeletonNodeConfig")
        require(viewId != 0 && viewId != View.NO_ID) { "viewId 必须是有效 ID" }
        val old = nodeConfigs[viewId]
        if (old == config) return
        if (config == null) nodeConfigs.remove(viewId) else nodeConfigs[viewId] = config
        try {
            if (::skeletonRenderer.isInitialized) {
                skeletonRenderer.invalidateNodeRules(refresh = false)
                if (!detached && isSkeletonPairComplete(skeletonConfigField, skeletonItemsField)) {
                    skeletonRenderer.preflight()
                }
                skeletonRenderer.invalidate()
            }
        } catch (failure: Throwable) {
            if (old == null) nodeConfigs.remove(viewId) else nodeConfigs[viewId] = old
            skeletonRenderer.invalidateNodeRules()
            throw failure
        }
    }

    @MainThread
    fun setStateDescription(state: StateLayoutState, description: CharSequence?) {
        checkMainThread("setStateDescription")
        if (description == null) stateDescriptions.remove(state) else stateDescriptions[state] = description
        if (renderedState == state) updateStateSemantics(state)
    }

    @MainThread
    fun diagnostics(): StateLayoutDiagnostics {
        checkMainThread("diagnostics")
        return StateLayoutDiagnostics(requestedState, renderedState, effectiveUseSkeleton(),
            if (hostsInitialized) skeletonOverlay.maskWidth else 0,
            if (hostsInitialized) skeletonOverlay.maskHeight else 0,
            if (hostsInitialized) skeletonOverlay.maskBytes else 0,
            if (hostsInitialized) skeletonOverlay.maskBuildCount else 0,
            if (hostsInitialized) skeletonOverlay.fallbackReason else SkeletonFallbackReason.NONE,
            hostsInitialized && skeletonOverlay.isEffectRunning, coordinator.hasPendingWork,
            bindingCallback)
    }

    @MainThread
    fun setOnLoadingVisibilityChangedListener(listener: OnLoadingVisibilityChangedListener?) {
        checkMainThread("setOnLoadingVisibilityChangedListener")
        onLoadingVisibilityChangedListener = listener
        visibleLoadingView?.let { listener?.onLoadingVisibilityChanged(this, true, it) }
    }

    @MainThread
    fun setOnStateViewReleasedListener(listener: OnStateViewReleasedListener?) {
        checkMainThread("setOnStateViewReleasedListener")
        onStateViewReleasedListener = listener
    }

    /** 注册统一重试回调（单向事件；控件不自动切换状态、不发起请求）。 */
    @MainThread
    fun setOnRetryListener(listener: OnRetryListener?) {
        checkMainThread("setOnRetryListener")
        onRetryListener = listener
    }

    /**
     * 替换指定状态的完整重试入口列表；无 ID 表示显式关闭该状态。
     *
     * 只接受 EMPTY / ERROR；传入 CONTENT / LOADING 抛出包含状态名的异常。
     * 相同列表重复赋值为空操作，不重绑、不重置节流窗口。
     */
    @MainThread
    fun setRetryViewIds(state: StateLayoutState, vararg viewIds: Int) {
        checkMainThread("setRetryViewIds")
        require(state == StateLayoutState.EMPTY || state == StateLayoutState.ERROR) {
            "setRetryViewIds 仅支持 EMPTY / ERROR，当前=$state"
        }
        val normalized = normalizeRetryIds(
            viewIds.toList(),
            if (state == StateLayoutState.EMPTY) "emptyViewIds" else "errorViewIds",
        ) ?: emptyList()
        val cached = if (::registry.isInitialized) registry.content(state) else null
        retryController.replace(state, normalized, cached)
    }

    /**
     * 返回最终有效配置的不可修改快照，包括尚未找到 View 的已配置 ID。
     * 只读查询，不创建布局；允许在状态页创建回调中调用。
     */
    @MainThread
    fun getRetryViewIds(state: StateLayoutState): List<Int> {
        checkMainThread("getRetryViewIds")
        require(state == StateLayoutState.EMPTY || state == StateLayoutState.ERROR) {
            "getRetryViewIds 仅支持 EMPTY / ERROR，当前=$state"
        }
        return retryController.getIds(state).toList()
    }

    /** 注册状态 View 首次创建回调（绑定动态控件、查找 Lottie 等）。 */
    @MainThread
    fun setOnStateViewCreatedListener(listener: OnStateViewCreatedListener?) {
        checkMainThread("setOnStateViewCreatedListener")
        onStateViewCreatedListener = listener
    }

    /** 注册画面呈现完成回调（启停 Lottie、测试等待、一次性播报等）。 */
    @MainThread
    fun setOnRenderedStateChangedListener(listener: OnRenderedStateChangedListener?) {
        checkMainThread("setOnRenderedStateChangedListener")
        onRenderedStateChangedListener = listener
    }

    // endregion

    // region 呈现流程（协调器回调出口，View 层不做时序决策）

    /**
     * 是否有可用的自定义加载布局。初始化前看资源 ID，初始化后看注册表（支持 setStateViewLayout 动态更换）。
     */
    private fun hasCustomLoadingLayout(): Boolean =
        if (!hostsInitialized) {
            customLoadingLayoutResId != 0
        } else {
            registry.hasLayout(StateLayoutState.LOADING)
        }

    /**
     * 生效的骨架开关。请求自定义但缺布局时回退到骨架，避免无反馈空白页。
     * @param logIfFallback 缺布局回退时是否打警告，仅在 render 入口为 true，避免其他路径重复打日志。
     */
    private fun effectiveUseSkeleton(logIfFallback: Boolean = false): Boolean {
        if (useSkeleton) return true
        if (hasCustomLoadingLayout()) return false
        if (logIfFallback) {
            Log.w(
                TAG,
                "ssl_useSkeleton=false 但未配置 ssl_loadingLayout，已自动回退到骨架；" +
                    "请设置 app:ssl_loadingLayout 或调用 setStateViewLayout(LOADING, …)",
            )
        }
        return true
    }

    private fun activeRenderer(): LoadingRenderer =
        if (effectiveUseSkeleton()) {
            skeletonRenderer
        } else {
            layoutRenderer
        }

    // 协调器发出的呈现命令：提交视图层级与交互状态，然后通知监听器。
    // 监听器在“提交完成”时触发，不等待装饰性淡入动画结束。
    private fun presentState(state: StateLayoutState, animate: Boolean, onPresented: () -> Unit) {
        if (!hostsInitialized) {
            onPresented()
            return
        }
        transitions.cancelAll()
        val useAnimate = transitions.shouldAnimate(animate)
        val previousTop = transitions.currentTop
        when (state) {
            StateLayoutState.CONTENT -> {
                contentHost.visibility = VISIBLE
                transitions.currentTop = contentHost
                transitions.crossfade(previousTop, contentHost, useAnimate)
                onPresented()
            }
            StateLayoutState.LOADING -> {
                if (effectiveUseSkeleton()) {
                    skeletonRenderer.show {
                        transitions.currentTop = skeletonOverlay
                        transitions.crossfade(previousTop, skeletonOverlay, useAnimate, overlayFade = true)
                        onPresented()
                    }
                } else {
                    layoutRenderer.show {
                        val loadingView = checkNotNull(registry.peek(StateLayoutState.LOADING))
                        transitions.showStateViewForTransition(loadingView, previousTop)
                        stateHost.visibility = VISIBLE
                        transitions.currentTop = loadingView
                        transitions.crossfade(previousTop, loadingView, useAnimate)
                        onPresented()
                    }
                }
            }
            StateLayoutState.EMPTY -> {
                val emptyView = registry.require(StateLayoutState.EMPTY)
                transitions.showStateViewForTransition(emptyView, previousTop)
                stateHost.visibility = VISIBLE
                transitions.currentTop = emptyView
                transitions.crossfade(previousTop, emptyView, useAnimate)
                onPresented()
            }
            StateLayoutState.ERROR -> {
                val errorView = registry.require(StateLayoutState.ERROR)
                transitions.showStateViewForTransition(errorView, previousTop)
                stateHost.visibility = VISIBLE
                transitions.currentTop = errorView
                transitions.crossfade(previousTop, errorView, useAnimate)
                onPresented()
            }
        }
    }

    private fun onPresentationCommitted(state: StateLayoutState) {
        updateContentAccessibility(state)
        maybeAnnounce(state)
        try { notifyRendered(state) } finally { syncLoadingVisibility() }
    }

    // endregion

    // region 无障碍与播报

    // 非内容状态下暂时隐藏业务内容的可访问节点，返回内容时恢复。
    private fun updateContentAccessibility(state: StateLayoutState) {
        if (!::contentHost.isInitialized) return
        if (state == StateLayoutState.CONTENT) {
            contentHost.importantForAccessibility = savedContentA11yMode
        } else if (contentHost.importantForAccessibility !=
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ) {
            savedContentA11yMode = contentHost.importantForAccessibility
            contentHost.importantForAccessibility =
                IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun stateDescriptionFor(state: StateLayoutState): CharSequence? = stateDescriptions[state]
        ?: when (state) {
            StateLayoutState.LOADING -> context.getString(R.string.ssl_desc_loading)
            StateLayoutState.EMPTY -> context.getString(R.string.ssl_desc_empty)
            StateLayoutState.ERROR -> context.getString(R.string.ssl_desc_error)
            StateLayoutState.CONTENT -> null
        }

    private fun updateStateSemantics(state: StateLayoutState) {
        if (Build.VERSION.SDK_INT >= 30) stateDescription = stateDescriptionFor(state)
        accessibilityLiveRegion = if (announceStateChanges && state != StateLayoutState.CONTENT)
            ACCESSIBILITY_LIVE_REGION_POLITE else ACCESSIBILITY_LIVE_REGION_NONE
    }

    private fun maybeAnnounce(state: StateLayoutState) {
        updateStateSemantics(state)
        if (!announceStateChanges || !isAttachedToWindow || !aggregatedVisible || state == lastAnnouncedState) return
        lastAnnouncedState = state
        // API 30+ 使用持久状态语义和 live region；旧系统保留一次性播报兼容路径。
        if (Build.VERSION.SDK_INT < 30) stateDescriptionFor(state)?.let { announceNow(it) }
    }

    @Suppress("DEPRECATION")
    private fun announceNow(text: CharSequence) { announceForAccessibility(text) }

    // endregion

    // region 回调与重试（绑定、门禁与节流由 StateRetryController 统一管理）

    private fun notifyRendered(state: StateLayoutState) {
        val old = lastPresentedState
        lastPresentedState = state
        onRenderedStateChangedListener?.onRenderedStateChanged(
            this, old, state, stateViewFor(state),
        )
    }

    private fun stateViewFor(state: StateLayoutState): View? {
        if (!hostsInitialized) return null
        return when (state) {
            StateLayoutState.CONTENT -> contentView
            StateLayoutState.LOADING -> if (effectiveUseSkeleton()) {
                skeletonOverlay
            } else {
                registry.content(StateLayoutState.LOADING)
            }
            StateLayoutState.EMPTY -> registry.content(StateLayoutState.EMPTY)
            StateLayoutState.ERROR -> registry.content(StateLayoutState.ERROR)
        }
    }

    // endregion

    // region 生命周期与系统事件

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        if (!hostsInitialized) return
        // 两种渲染器始终收到同一窗口状态，运行时切换不需要猜测旧渲染器的状态。
        skeletonRenderer.onAggregatedVisibilityChanged(aggregatedVisible)
        layoutRenderer.onAggregatedVisibilityChanged(aggregatedVisible)
        skeletonRenderer.onAttachedToWindow()
        layoutRenderer.onAttachedToWindow()
        if (presentationInvalidated) {
            presentationInvalidated = false
            // detach 已清除加载计时，待退出的 LOADING 交由协调器直接切换，
            // 避免先启动一次即将过期的异步骨架准备。
            if (requestedState == renderedState || renderedState != StateLayoutState.LOADING) {
                validateStateConfiguration(renderedState)
                presentState(renderedState, animate = false) { syncLoadingVisibility() }
            }
        }
        if (!initialApplied && !explicitRequest && contentView != null) {
            initialApplied = true
            coordinator.onAttachedToWindow()
            presentRestored(initialState)
        } else {
            coordinator.onAttachedToWindow()
        }
        maybeAnnounce(renderedState)
        syncLoadingVisibility()
    }

    override fun onDetachedFromWindow() {
        detached = true
        if (hostsInitialized) {
            syncLoadingVisibility(forceHidden = true)
            transitions.cancelAll()
            coordinator.onDetachedFromWindow()
            skeletonRenderer.onDetachedFromWindow()
            layoutRenderer.onDetachedFromWindow()
        }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        if (!hostsInitialized) return
        skeletonRenderer.onAggregatedVisibilityChanged(isVisible)
        layoutRenderer.onAggregatedVisibilityChanged(isVisible)
        if (isVisible) maybeAnnounce(renderedState)
        syncLoadingVisibility()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hostsInitialized) return
        // 尺寸变化意味着旧遮罩几何全部过期。
        if (w != oldw || h != oldh) {
            skeletonOverlay.invalidateMask()
        }
    }

    /**
     * 配置变更（昼夜 / 字体缩放 / locale / 方向）。
     *
     * # 为什么需要显式处理
     *
     * 控件在此之前完全不响应配置变更：`SkeletonConfig` 是构造时求值的快照，
     * 而它的颜色与圆角来自 `ssl_mask_default` 等资源。宿主切到夜间模式后，
     * 骨架会继续用旧主题的颜色绘制，直到该实例被重建。
     *
     * # 处理范围
     *
     * - **骨架颜色 / 圆角**：仅当当前配置的三项仍等于上次从资源解析出的值（即未被宿主显式覆盖）
     *   才重新解析。宿主显式指定过任一项时保持不动，避免覆盖品牌色。
     * - **状态页缓存**：清空按资源声明的缓存 View，下次显示时用新 Configuration
     *   重新 inflate。布局资源 ID 本身保留，不改变宿主的声明。
     * - 骨架模板由 `SkeletonLoadingRenderer` 按 `configuration.hashCode()`
     *   自行判定是否需要重新 inflate，这里不重复处理。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!hostsInitialized) return

        val current = skeletonConfig
        if (resourceDerivedColors?.let { (mask, highlight, radius) ->
                current.maskColor == mask && current.highlightColor == highlight && current.cornerRadius == radius
            } == true
        ) {
            val refreshed = current.copy(
                maskColor = context.getColor(R.color.ssl_mask_default),
                highlightColor = context.getColor(R.color.ssl_highlight_default),
                cornerRadius = resources.getDimension(R.dimen.ssl_corner_radius_default),
            )
            if (refreshed != current) skeletonConfig = refreshed
            resourceDerivedColors = Triple(refreshed.maskColor, refreshed.highlightColor, refreshed.cornerRadius)
        }

        // 状态页可能含固定 dp 尺寸与静态文案，按新 Configuration 重新 inflate。
        // 三个状态页的缓存全部清空（含正在呈现的那个），再由下面的重新呈现用新配置重建；
        // 只清非活动状态会留下一个仍按旧配置 inflate 的画面。
        val activeState = renderedState
        transitions.cancelAll()
        if (activeState == StateLayoutState.LOADING && !effectiveUseSkeleton()) {
            syncLoadingVisibility(forceHidden = true)
        }
        for (state in listOf(StateLayoutState.EMPTY, StateLayoutState.ERROR, StateLayoutState.LOADING)) {
            if (state == StateLayoutState.LOADING && effectiveUseSkeleton()) continue
            registry.clear(state)?.let { releaseStateView(state, it) }
        }
        if (activeState != StateLayoutState.CONTENT) {
            // 刷新画面不提交新状态，协调器继续使用原目标与延迟截止时间。
            presentationInvalidated = detached
            if (!detached) presentState(activeState, animate = false) { syncLoadingVisibility() }
        }
        skeletonOverlay.invalidateMask()
    }

    // endregion

    // region 状态保存与恢复

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(superState).apply {
            // 1. 请求状态与状态布局资源。
            requestedOrdinal = requestedState.ordinal
            emptyResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.EMPTY) else emptyLayoutResId
            errorResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.ERROR) else errorLayoutResId
            loadingResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.LOADING) else customLoadingLayoutResId
            // 2. Loading 时序、过渡效果与状态播报策略。
            useSkeletonFlag = useSkeleton
            showDelay = loadingShowDelayMillis
            minDuration = minimumLoadingDurationMillis
            transitionOrdinal = transitionEffect.ordinal
            transitionDuration = transitionDurationMillis
            announce = announceStateChanges
            // 3. 骨架配置、节点规则及条目拼装声明。
            val config = skeletonConfig
            skeletonSourceOrdinal = config.source.ordinal
            skeletonTemplateResId = config.templateLayoutResId ?: 0
            skeletonEffectOrdinal = config.effect.ordinal
            maskColor = config.maskColor
            highlightColor = config.highlightColor
            cornerRadius = config.cornerRadius
            animationDuration = config.animationDurationMillis
            directionOrdinal = config.direction.ordinal
            angle = config.angleDegrees
            pulseMin = config.pulseMinAlpha
            pulseMax = config.pulseMaxAlpha
            maximumPixels = config.maximumMaskPixels
            nodeRules.putAll(nodeConfigs)
            // binder 不可序列化：只保存布局与条数，恢复后 binder 置空（见 applyRestoredState）。
            val items = skeletonItems?.takeIf { it.isNotEmpty() }
            skeletonItemLayouts = items?.map { it.layoutResId } ?: emptyList()
            skeletonItemCounts = items?.map { it.count } ?: emptyList()
            // 4. 状态描述。
            descriptions.putAll(stateDescriptions.mapValues { it.value.toString() })
            // 5. 重试配置快照：最终有效列表（不只保存找到的 View），空列表写入数量 0。
            emptyRetryViewIds = retryController.snapshotEmpty()
            errorRetryViewIds = retryController.snapshotError()
            retryThrottle = retryController.retryClickThrottleMillis
            retryEnabled = retryController.retryClicksEnabled
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        if (!hostsInitialized) { pendingSavedState = state; return }
        applyRestoredState(state)
    }

    private fun applyRestoredState(state: SavedState) {
        // 单一当前格式：标记或版本不匹配明确拒绝，不尝试单 ID 转列表；校验完成前不修改实例。
        require(state.formatVersion == SavedState.CURRENT_FORMAT_VERSION) {
            "SavedState 版本不匹配：期望=${SavedState.CURRENT_FORMAT_VERSION}，实际=${state.formatVersion}"
        }
        // 先得到完整配置快照并校验，再更新布局与控制器。
        val restoredEmpty = normalizeRetryIds(state.emptyRetryViewIds, "emptyViewIds") ?: emptyList()
        val restoredError = normalizeRetryIds(state.errorRetryViewIds, "errorViewIds") ?: emptyList()
        require(state.retryThrottle >= 0L) { "SavedState 重试节流非法：${state.retryThrottle}" }
        val requested = StateLayoutState.entries.getOrElse(state.requestedOrdinal) {
            StateLayoutState.CONTENT
        }
        val restoredEffect = StateTransitionEffect.entries.getOrElse(state.transitionOrdinal) {
            StateTransitionEffect.CROSSFADE
        }
        val restoredConfig = SkeletonConfig(
            source = SkeletonSource.entries.getOrElse(state.skeletonSourceOrdinal) {
                SkeletonSource.CONTENT
            },
            templateLayoutResId = state.skeletonTemplateResId.takeIf { it != 0 },
            effect = SkeletonEffect.entries.getOrElse(state.skeletonEffectOrdinal) {
                SkeletonEffect.SHIMMER
            },
            maskColor = state.maskColor,
            highlightColor = state.highlightColor,
            cornerRadius = state.cornerRadius,
            animationDurationMillis = state.animationDuration.coerceAtLeast(1L),
            direction = SkeletonDirection.entries.getOrElse(state.directionOrdinal) {
                SkeletonDirection.START_TO_END
            },
            angleDegrees = state.angle,
            pulseMinAlpha = state.pulseMin,
            pulseMaxAlpha = state.pulseMax,
            maximumMaskPixels = state.maximumPixels.takeIf { it > 0 } ?: Long.MAX_VALUE,
        )
        val layouts = state.skeletonItemLayouts
        val counts = state.skeletonItemCounts
        require(layouts.size == counts.size) { "SavedState 条目布局与条数不匹配" }
        val restoredItems: List<SkeletonItemSpec>? = if (layouts.isEmpty()) {
            null
        } else {
            layouts.mapIndexed { index, layoutResId ->
                SkeletonItemSpec(layoutResId, counts[index])
            }
        }
        if (restoredItems != null && BuildConfig.DEBUG) {
            android.util.Log.d(
                TAG,
                "条目拼装模板的 binder 不可序列化，已置空；宿主需在重建后重新设置占位绑定",
            )
        }
        validateTemplateResource(restoredConfig, restoredItems)
        // 预校验布局资源，避免部分生效。
        listOf(
            StateLayoutState.EMPTY to state.emptyResource,
            StateLayoutState.ERROR to state.errorResource,
            StateLayoutState.LOADING to state.loadingResource,
        ).forEach { (target, resource) ->
            if (resource != 0) registry.checkResource(target, resource)
        }

        initialApplied = true
        explicitRequest = true
        syncLoadingVisibility(forceHidden = true)
        transitions.cancelAll()
        skeletonRenderer.hide()
        layoutRenderer.hide()
        coordinator.restore(StateLayoutState.CONTENT)
        val released = mutableListOf<Pair<StateLayoutState, View>>()
        listOf(StateLayoutState.EMPTY to state.emptyResource,
            StateLayoutState.ERROR to state.errorResource,
            StateLayoutState.LOADING to state.loadingResource).forEach { (target, resource) ->
            val old = if (resource != 0) registry.setLayout(target, resource)
                else registry.clearLayout(target)
            old?.let { released += target to it }
        }
        // 保存的最终列表优先于重建实例的声明；恢复后运行时 setter 仍可覆盖。
        retryController.installInitial(restoredEmpty, restoredError)
        // 相同布局资源会保留缓存根；恢复后的列表也必须更新这些根的实际监听。
        listOf(StateLayoutState.EMPTY, StateLayoutState.ERROR).forEach { target ->
            registry.content(target)?.let { retryController.bind(target, it) }
        }
        retryController.retryClickThrottleMillis = state.retryThrottle.coerceAtLeast(0)
        retryController.retryClicksEnabled = state.retryEnabled
        nodeConfigs.clear()
        nodeConfigs.putAll(state.nodeRules)
        stateDescriptions.clear()
        stateDescriptions.putAll(state.descriptions)
        useSkeleton = state.useSkeletonFlag
        transitionEffect = restoredEffect
        transitionDurationMillis = state.transitionDuration.coerceAtLeast(0L)
        announceStateChanges = state.announce
        loadingShowDelayMillis = state.showDelay.coerceAtLeast(0L)
        minimumLoadingDurationMillis = state.minDuration.coerceAtLeast(0L)
        // 原子恢复两者：分步走 setter 必然经过“两者并存 / 两者皆空”的非法中间态，
        // 故先统一校验最终态，再直写 backing field，最后统一通知渲染器。
        skeletonConfigField = restoredConfig
        skeletonItemsField = restoredItems
        if (::skeletonRenderer.isInitialized) {
            skeletonRenderer.onConfigChanged()
        }
        // 画面事实仍由真实提交推进；恢复 Loading 也重新建立最短展示计时。
        presentRestored(requested)
        released.forEach { (target, view) -> releaseStateView(target, view) }
    }

    /**
     * 自定义保存状态：只保存请求状态与运行时配置标识，
     * 不保存 Listener / Bitmap / Animator / 业务数据与绝对时间。
     *
     * 字段按以下分组保存，读写顺序保持一致：
     * 1. 请求状态与状态布局资源；2. Loading 时序、过渡效果与状态播报策略；
     * 3. 骨架配置、节点规则及条目拼装声明；4. 状态描述；
     * 5. 重试配置快照（EMPTY 列表、ERROR 列表、节流间隔与可用开关）。
     */
    class SavedState : BaseSavedState {
        internal var formatVersion: Int = CURRENT_FORMAT_VERSION

        // 1. 请求状态与状态布局资源。
        var requestedOrdinal: Int = StateLayoutState.CONTENT.ordinal
        var emptyResource: Int = 0
        var errorResource: Int = 0
        var loadingResource: Int = 0
        // 2. Loading 时序、过渡效果与状态播报策略。
        var useSkeletonFlag: Boolean = false
        var showDelay: Long = 0L
        var minDuration: Long = StateCoordinator.DEFAULT_MINIMUM_LOADING_DURATION_MILLIS
        var transitionOrdinal: Int = StateTransitionEffect.CROSSFADE.ordinal
        var transitionDuration: Long = DEFAULT_TRANSITION_DURATION_MILLIS
        var announce: Boolean = true
        // 3. 骨架配置、节点规则及条目拼装声明。
        var skeletonSourceOrdinal: Int = SkeletonSource.CONTENT.ordinal
        var skeletonTemplateResId: Int = 0
        var skeletonEffectOrdinal: Int = SkeletonEffect.SHIMMER.ordinal
        var maskColor: Int = SkeletonConfig.DEFAULT_MASK_COLOR
        var highlightColor: Int = SkeletonConfig.DEFAULT_HIGHLIGHT_COLOR
        var cornerRadius: Float = SkeletonConfig.DEFAULT_CORNER_RADIUS_PX
        var animationDuration: Long = SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS
        var directionOrdinal: Int = SkeletonDirection.START_TO_END.ordinal
        var angle: Int = SkeletonConfig.DEFAULT_ANGLE_DEGREES
        var pulseMin: Float = SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA
        var pulseMax: Float = SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA
        var maximumPixels: Long = Long.MAX_VALUE
        val nodeRules = mutableMapOf<Int, SkeletonNodeConfig>()
        // T2 条目拼装声明：只保存布局与条数，binder 不可序列化，恢复后置空。
        var skeletonItemLayouts: List<Int> = emptyList()
        var skeletonItemCounts: List<Int> = emptyList()
        // 4. 状态描述。
        val descriptions = mutableMapOf<StateLayoutState, String>()
        // 5. 重试配置快照。
        var emptyRetryViewIds: List<Int> = emptyList()
        var errorRetryViewIds: List<Int> = emptyList()
        var retryThrottle: Long = DEFAULT_RETRY_CLICK_THROTTLE_MILLIS
        var retryEnabled: Boolean = true

        constructor(superState: Parcelable?) : super(superState)

        private constructor(parcel: Parcel) : super(parcel) {
            val marker = parcel.readInt()
            require(marker == FORMAT_MARKER) { "SavedState 格式标记不匹配" }
            val version = parcel.readInt()
            require(version == CURRENT_FORMAT_VERSION) {
                "SavedState 版本不匹配：期望=$CURRENT_FORMAT_VERSION，实际=$version"
            }
            formatVersion = version
            // 1. 请求状态与状态布局资源。
            requestedOrdinal = parcel.readInt()
            emptyResource = parcel.readInt()
            errorResource = parcel.readInt()
            loadingResource = parcel.readInt()
            // 2. Loading 时序、过渡效果与状态播报策略。
            useSkeletonFlag = parcel.readInt() == 1
            showDelay = parcel.readLong()
            minDuration = parcel.readLong()
            transitionOrdinal = parcel.readInt()
            transitionDuration = parcel.readLong()
            announce = parcel.readInt() == 1
            // 3. 骨架配置、节点规则及条目拼装声明。
            skeletonSourceOrdinal = parcel.readInt()
            skeletonTemplateResId = parcel.readInt()
            skeletonEffectOrdinal = parcel.readInt()
            maskColor = parcel.readInt()
            highlightColor = parcel.readInt()
            cornerRadius = parcel.readFloat()
            animationDuration = parcel.readLong()
            directionOrdinal = parcel.readInt()
            angle = parcel.readInt()
            pulseMin = parcel.readFloat()
            pulseMax = parcel.readFloat()
            maximumPixels = parcel.readLong()
            val ruleCount = parcel.readInt()
            require(ruleCount in 0..10000) { "SavedState 节点数量非法" }
            repeat(ruleCount) {
                val id = parcel.readInt()
                val excluded = parcel.readInt() == 1
                val shape = SkeletonShape.entries.getOrElse(parcel.readInt()) { SkeletonShape.ROUNDED_RECTANGLE }
                val radius = if (parcel.readInt() == 1) parcel.readFloat() else null
                nodeRules[id] = SkeletonNodeConfig(excluded, shape, radius)
            }
            val layoutCount = parcel.readInt()
            require(layoutCount in 0..10000) { "SavedState 条目数量非法" }
            val layouts = ArrayList<Int>(layoutCount)
            repeat(layoutCount) { layouts += parcel.readInt() }
            skeletonItemLayouts = layouts
            val countsSize = parcel.readInt()
            require(countsSize == layoutCount) { "SavedState 条目布局与条数不匹配" }
            val counts = ArrayList<Int>(countsSize)
            repeat(countsSize) { counts += parcel.readInt() }
            skeletonItemCounts = counts
            // 4. 状态描述。
            val descriptionCount = parcel.readInt()
            require(descriptionCount in 0..StateLayoutState.entries.size) { "SavedState 描述数量非法" }
            repeat(descriptionCount) {
                val target = StateLayoutState.entries.getOrElse(parcel.readInt()) { StateLayoutState.CONTENT }
                parcel.readString()?.let { descriptions[target] = it }
            }
            // 5. 重试配置快照。
            val emptyCount = parcel.readInt()
            require(emptyCount in 0..StateRetryConfig.MAX_RETRY_IDS) { "SavedState EMPTY 重试数量非法" }
            val emptyIds = ArrayList<Int>(emptyCount)
            repeat(emptyCount) { emptyIds += parcel.readInt() }
            require(emptyIds.all { it != 0 && it != View.NO_ID }) { "SavedState EMPTY 重试 ID 非法" }
            emptyRetryViewIds = emptyIds.distinct()
            val errorCount = parcel.readInt()
            require(errorCount in 0..StateRetryConfig.MAX_RETRY_IDS) { "SavedState ERROR 重试数量非法" }
            val errorIds = ArrayList<Int>(errorCount)
            repeat(errorCount) { errorIds += parcel.readInt() }
            require(errorIds.all { it != 0 && it != View.NO_ID }) { "SavedState ERROR 重试 ID 非法" }
            errorRetryViewIds = errorIds.distinct()
            retryThrottle = parcel.readLong()
            retryEnabled = parcel.readInt() == 1
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            // 版本号读字段而不是写字面量：读侧按它决定解析哪些字段，
            // 若这里与 formatVersion 脱节，恢复会按错误布局解析同一段字节。
            val version = formatVersion
            require(version == CURRENT_FORMAT_VERSION) {
                "SavedState.formatVersion=$version 与写入布局不匹配，当前写入布局对应 $CURRENT_FORMAT_VERSION"
            }
            out.writeInt(FORMAT_MARKER)
            out.writeInt(version)
            // 1. 请求状态与状态布局资源。
            out.writeInt(requestedOrdinal)
            out.writeInt(emptyResource)
            out.writeInt(errorResource)
            out.writeInt(loadingResource)
            // 2. Loading 时序、过渡效果与状态播报策略。
            out.writeInt(if (useSkeletonFlag) 1 else 0)
            out.writeLong(showDelay)
            out.writeLong(minDuration)
            out.writeInt(transitionOrdinal)
            out.writeLong(transitionDuration)
            out.writeInt(if (announce) 1 else 0)
            // 3. 骨架配置、节点规则及条目拼装声明。
            out.writeInt(skeletonSourceOrdinal)
            out.writeInt(skeletonTemplateResId)
            out.writeInt(skeletonEffectOrdinal)
            out.writeInt(maskColor)
            out.writeInt(highlightColor)
            out.writeFloat(cornerRadius)
            out.writeLong(animationDuration)
            out.writeInt(directionOrdinal)
            out.writeInt(angle)
            out.writeFloat(pulseMin)
            out.writeFloat(pulseMax)
            out.writeLong(maximumPixels)
            out.writeInt(nodeRules.size)
            nodeRules.forEach { (id, rule) ->
                out.writeInt(id)
                out.writeInt(if (rule.excluded) 1 else 0)
                out.writeInt(rule.shape.ordinal)
                out.writeInt(if (rule.cornerRadius != null) 1 else 0)
                rule.cornerRadius?.let { out.writeFloat(it) }
            }
            out.writeInt(skeletonItemLayouts.size)
            skeletonItemLayouts.forEach { out.writeInt(it) }
            out.writeInt(skeletonItemCounts.size)
            skeletonItemCounts.forEach { out.writeInt(it) }
            // 4. 状态描述。
            out.writeInt(descriptions.size)
            descriptions.forEach { (target, text) -> out.writeInt(target.ordinal); out.writeString(text) }
            // 5. 重试配置快照。
            out.writeInt(emptyRetryViewIds.size)
            emptyRetryViewIds.forEach { out.writeInt(it) }
            out.writeInt(errorRetryViewIds.size)
            errorRetryViewIds.forEach { out.writeInt(it) }
            out.writeLong(retryThrottle)
            out.writeInt(if (retryEnabled) 1 else 0)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            private const val FORMAT_MARKER = -0x53534C

            /**
             * 当前 Parcel 写入布局对应的版本号。
             *
             * `formatVersion` 字段与 [writeToParcel] 的写入布局必须同步演进：
             * 本方案只处理当前格式，不保留历史读取分支；标记或版本不匹配明确拒绝。
             */
            internal const val CURRENT_FORMAT_VERSION = 4
            override fun createFromParcel(parcel: Parcel): SavedState = SavedState(parcel)

            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }

    // endregion

    /**
     * 运行时即时校验：只拒绝明确冲突的组合，允许“TEMPLATE 暂无模板”的中间态。
     *
     * 程序化从默认 CONTENT 起步时，允许先把 `skeletonConfig` 切到
     * `TEMPLATE`（暂无模板）再补 `skeletonItems`，或先清 items 再换 config；
     * 完整性（TEMPLATE 下 T1 xor T2 至少其一）在 XML 构造时严格校验，
     * 在渲染时由 `preflight` / `validateStateConfiguration` 兜底。
     */
    private fun validateTemplateResource(
        config: SkeletonConfig,
        items: List<SkeletonItemSpec>? = skeletonItems,
    ) {
        val activeItems = items?.takeIf { it.isNotEmpty() }
        if (!activeItems.isNullOrEmpty()) {
            require(config.source == SkeletonSource.TEMPLATE) {
                "skeletonItems 需要配合 SkeletonSource.TEMPLATE 使用，" +
                    "当前 source=${config.source}，请设置 skeletonConfig.source=TEMPLATE"
            }
            require(config.templateLayoutResId == null) {
                "skeletonItems 与 ssl_skeletonTemplate 互斥，" +
                    "请只保留其中一种模板供给方式"
            }
            activeItems.forEachIndexed { index, spec ->
                require(resources.getResourceTypeName(spec.layoutResId) == "layout") {
                    "skeletonItems[$index] 必须指向布局资源"
                }
            }
            return
        }
        // 无条目时只校验形式（给了值必须指向布局），不校验完整性；
        // TEMPLATE 暂无模板的中间态允许存在，渲染时 preflight 会按缺模板抛异常。
        config.templateLayoutResId?.let {
            require(resources.getResourceTypeName(it) == "layout") {
                "ssl_skeletonTemplate 必须指向布局资源"
            }
        }
    }

    /**
     * 模板供给是否完整（值得预检）：CONTENT 无需模板；
     * TEMPLATE 下 T1 与 T2 恰居其一。中间态（两者皆无）跳过预检，
     * 留待渲染时按缺模板抛异常。
     */
    private fun isSkeletonPairComplete(
        config: SkeletonConfig,
        items: List<SkeletonItemSpec>?,
    ): Boolean {
        if (config.source != SkeletonSource.TEMPLATE) return true
        val hasItems = !items.isNullOrEmpty()
        val hasTemplate = config.templateLayoutResId != null
        return hasItems xor hasTemplate
    }

    private fun presentRestored(state: StateLayoutState) {
        validateStateConfiguration(state)
        restoringPresentation = true
        try { coordinator.restoreRequested(state) } finally { restoringPresentation = false }
    }

    private inline fun withBindingCallback(action: () -> Unit) {
        check(!bindingCallback) { "状态页绑定事务不能重入" }
        bindingCallback = true
        try { action() } finally { bindingCallback = false }
    }

    private fun releaseStateView(state: StateLayoutState, view: View) {
        // EMPTY / ERROR 均转交控制器，按实际根与实际目标解绑；不读当前 ID 列表。
        // 释放的可能是旧列表时代创建的缓存，读当前值会查错按钮甚至查不到。
        if (state == StateLayoutState.EMPTY || state == StateLayoutState.ERROR) {
            retryController.unbindRoot(view)
        }
        withBindingCallback { onStateViewReleasedListener?.onStateViewReleased(this, state, view) }
    }

    private fun syncLoadingVisibility(forceHidden: Boolean = false) {
        val next = if (!forceHidden && hostsInitialized && !detached && isAttachedToWindow && aggregatedVisible &&
            renderedState == StateLayoutState.LOADING && currentTop?.visibility == VISIBLE)
            stateViewFor(StateLayoutState.LOADING) else null
        if (visibleLoadingView === next) return
        val previous = visibleLoadingView
        visibleLoadingView = next
        if (previous != null) onLoadingVisibilityChangedListener?.onLoadingVisibilityChanged(this, false, previous)
        if (next != null && visibleLoadingView === next)
            onLoadingVisibilityChangedListener?.onLoadingVisibilityChanged(this, true, next)
    }

    private fun checkMainThread(method: String) {
        check(Looper.getMainLooper().isCurrentThread) { "StateSkeletonLayout.$method 必须在主线程调用" }
        check(!bindingCallback || method.startsWith("setOn") || method == "getStateView" ||
            method == "diagnostics" || method == "getRetryViewIds") {
            "状态页创建 / 释放回调中不得调用 $method，避免事务重入"
        }
    }

    companion object {
        private const val TAG = "StateSkeletonLayout"

        /**
         * 默认状态切换时长（毫秒）。
         *
         * 这些常量是公开默认值的唯一真源：`res/values/styles.xml`、
         * [com.sfyc.ssl.internal.SslDefaultValues] 与状态协调器都引用本处，
         * 不再各自声明字面量（历史上三处各写一遍，改一处漏两处不会报错）。
         * `SslDefaultValuesTest` 逐字段断言 styles.xml 与本处一致。
         */
        const val DEFAULT_TRANSITION_DURATION_MILLIS: Long = 180L

        /** 默认错误重试统一防重复点击间隔（毫秒）。 */
        const val DEFAULT_RETRY_CLICK_THROTTLE_MILLIS: Long = 500L

        /** 默认加载显示延迟（毫秒）：0 表示首次加载立即反馈。 */
        const val DEFAULT_LOADING_SHOW_DELAY_MILLIS: Long = 0L

        /** 默认 Loading 最短展示时间（毫秒）：避免快速请求引起闪烁。 */
        const val DEFAULT_MINIMUM_LOADING_DURATION_MILLIS: Long = 300L
    }
}
