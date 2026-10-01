package com.sfyc.ssl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
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
import com.sfyc.ssl.internal.StateViewRegistry
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
    @get:MainThread
    @set:MainThread
    var skeletonConfig: SkeletonConfig = SkeletonConfig()
        set(value) {
            checkMainThread("skeletonConfig")
            if (field == value) return
            validateTemplateResource(value)
            if (::skeletonRenderer.isInitialized) {
                if (detached) validateTemplateResource(value) else skeletonRenderer.preflight(value)
            }
            val previous = field
            field = value
            if (::skeletonRenderer.isInitialized) {
                try {
                    skeletonRenderer.onConfigChanged()
                } catch (failure: Throwable) {
                    field = previous
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
            field = value
        }

    /** 状态切换时长（毫秒），不得小于 0。 */
    @get:MainThread
    @set:MainThread
    var transitionDurationMillis: Long = DEFAULT_TRANSITION_DURATION_MILLIS
        set(value) {
            checkMainThread("transitionDurationMillis")
            require(value >= 0) { "transitionDurationMillis 不得小于 0，当前=$value" }
            field = value
        }

    /** 进入 Loading/Empty/Error 时是否做一次性无障碍播报（重复提交不重复播报）。 */
    @get:MainThread
    @set:MainThread
    var announceStateChanges: Boolean = true
        set(value) {
            checkMainThread("announceStateChanges")
            field = value
        }

    /** 重试按钮 ID；更换时解除旧入口并绑定已缓存的错误页。 */
    @get:MainThread
    @set:MainThread
    var errorRetryViewId: Int = 0
        set(value) {
            checkMainThread("errorRetryViewId")
            require(value != View.NO_ID) { "errorRetryViewId 不能为 View.NO_ID；0 表示关闭统一重试" }
            if (field == value) return
            if (::registry.isInitialized) registry.content(StateLayoutState.ERROR)
                ?.findViewById<View>(field)?.setOnClickListener(null)
            field = value
            lastRetryClickMillis = null
            if (::registry.isInitialized) registry.content(StateLayoutState.ERROR)
                ?.let { bindRetryIfNeeded(StateLayoutState.ERROR, it) }
        }

    @get:MainThread
    @set:MainThread
    var retryClickThrottleMillis: Long = RETRY_CLICK_THROTTLE_MILLIS
        set(value) {
            checkMainThread("retryClickThrottleMillis")
            require(value >= 0L) { "retryClickThrottleMillis 不得小于 0" }
            field = value
        }

    /** 是否接受重试事件；请求进行中可暂时关闭，控件不修改业务按钮的 enabled。 */
    @get:MainThread
    @set:MainThread
    var retryClicksEnabled: Boolean = true
        set(value) { checkMainThread("retryClicksEnabled"); field = value }

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

    /** 当前实际呈现的内容容器、具体状态 View 或骨架 View，用于切换动画的进出判断。 */
    private var currentTop: View? = null

    private val runningTransitions = mutableListOf<ViewPropertyAnimator>()

    private var onRetryClickListener: OnClickListener? = null
    private var onStateViewCreatedListener: OnStateViewCreatedListener? = null
    private var onRenderedStateChangedListener: OnRenderedStateChangedListener? = null

    private var lastRetryClickMillis: Long? = null
    private var lastAnnouncedState: StateLayoutState? = null
    private var lastPresentedState: StateLayoutState = StateLayoutState.CONTENT
    private var savedContentA11yMode: Int = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var aggregatedVisible: Boolean = true
    private var detached = false
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
        emptyLayoutResId = xmlAttrs.emptyLayoutResId.takeIf { it != 0 } ?: themeAttrs.emptyLayoutResId
        errorLayoutResId = xmlAttrs.errorLayoutResId.takeIf { it != 0 } ?: themeAttrs.errorLayoutResId
        customLoadingLayoutResId = xmlAttrs.customLoadingLayoutResId.takeIf { it != 0 } ?: themeAttrs.customLoadingLayoutResId
        errorRetryViewId = xmlAttrs.errorRetryViewId.takeIf { it != 0 } ?: themeAttrs.errorRetryViewId
        val merged = SslDefaultsMerger.merge(context, xmlAttrs, StateSkeletonLayoutDefaults.current, themeAttrs)

        initialState = merged.initialState
        useSkeleton = merged.useSkeleton
        skeletonConfig = merged.skeletonConfig
        transitionEffect = merged.transitionEffect
        transitionDurationMillis = merged.transitionDurationMillis
        announceStateChanges = merged.announceStateChanges

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
            bindRetryIfNeeded(state, view)
            withBindingCallback { onStateViewCreatedListener?.onStateViewCreated(this, state, view) }
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
        )
        layoutRenderer = LayoutLoadingRenderer(registry)
        currentTop = contentHost
        savedContentA11yMode = contentHost.importantForAccessibility
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

    /** 释放非当前态缓存，保留资源配置；当前可见态不得清除。 */
    @MainThread
    fun clearStateViewCache(state: StateLayoutState) {
        checkMainThread("clearStateViewCache")
        require(state != StateLayoutState.CONTENT) { "不能清除业务内容" }
        check(hostsInitialized) { "请先设置业务内容" }
        check(registry.peek(state) !== currentTop) { "当前呈现的状态页不能清除" }
        registry.peek(state)?.animate()?.cancel()
        registry.clear(state)?.let { releaseStateView(state, it) }
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
                if (!detached) skeletonRenderer.preflight()
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
            hostsInitialized && skeletonOverlay.isEffectRunning, coordinator.hasPendingWork)
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

    /** 注册错误页重试点击监听（单向事件；控件不自动切换状态、不发起请求）。 */
    @MainThread
    fun setOnRetryClickListener(listener: OnClickListener?) {
        checkMainThread("setOnRetryClickListener")
        onRetryClickListener = listener
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
        cancelTransitions()
        val useAnimate = animate &&
            transitionEffect == StateTransitionEffect.CROSSFADE &&
            transitionDurationMillis > 0 &&
            android.animation.ValueAnimator.areAnimatorsEnabled()
        val previousTop = currentTop
        when (state) {
            StateLayoutState.CONTENT -> {
                restoreContent()
                currentTop = contentHost
                crossfade(previousTop, contentHost, finalVisibilityOf(previousTop, contentHost), useAnimate)
                onPresented()
            }
            StateLayoutState.LOADING -> {
                if (effectiveUseSkeleton()) {
                    skeletonRenderer.show {
                        currentTop = skeletonOverlay
                        crossfade(
                            previousTop,
                            skeletonOverlay,
                            finalVisibilityOf(previousTop, skeletonOverlay),
                            useAnimate,
                            overlayFade = true,
                        )
                        onPresented()
                    }
                } else {
                    layoutRenderer.show {
                        val loadingView = checkNotNull(registry.peek(StateLayoutState.LOADING))
                        showStateViewForTransition(loadingView, previousTop)
                        stateHost.visibility = VISIBLE
                        currentTop = loadingView
                        crossfade(
                            previousTop,
                            loadingView,
                            finalVisibilityOf(previousTop, loadingView),
                            useAnimate,
                        )
                        onPresented()
                    }
                }
            }
            StateLayoutState.EMPTY -> {
                val emptyView = registry.require(StateLayoutState.EMPTY)
                showStateViewForTransition(emptyView, previousTop)
                stateHost.visibility = VISIBLE
                currentTop = emptyView
                crossfade(
                    previousTop,
                    emptyView,
                    finalVisibilityOf(previousTop, emptyView),
                    useAnimate,
                )
                onPresented()
            }
            StateLayoutState.ERROR -> {
                val errorView = registry.require(StateLayoutState.ERROR)
                showStateViewForTransition(errorView, previousTop)
                stateHost.visibility = VISIBLE
                currentTop = errorView
                crossfade(
                    previousTop,
                    errorView,
                    finalVisibilityOf(previousTop, errorView),
                    useAnimate,
                )
                onPresented()
            }
        }
    }

    private fun onPresentationCommitted(state: StateLayoutState) {
        updateContentAccessibility(state)
        maybeAnnounce(state)
        try { notifyRendered(state) } finally { syncLoadingVisibility() }
    }

    // 恢复内容的绘制、交互与无障碍属性（骨架/状态页离开时调用）。
    private fun restoreContent() {
        contentHost.visibility = VISIBLE
    }

    // 抑制内容的绘制与交互：INVISIBLE 仍参与测量布局，不修改后代 visibility。
    private fun suppressContent() {
        contentHost.visibility = INVISIBLE
    }

    // StateHost 内保留目标 View 与正在淡出的旧 View；其余状态立即隐藏。
    private fun showStateViewForTransition(view: View, outgoing: View?) {
        if (view.parent == null) {
            stateHost.addView(view)
        }
        for (i in 0 until stateHost.childCount) {
            val child = stateHost.getChildAt(i)
            child.visibility = if (child === view || child === outgoing) VISIBLE else GONE
        }
        view.bringToFront()
        view.visibility = VISIBLE
    }

    // 新旧顶层容器之间的交叉淡入淡出；NONE 模式直接提交最终可见性。
    private fun crossfade(
        outgoing: View?,
        incoming: View?,
        outgoingEndVisibility: Int,
        useAnimate: Boolean,
        overlayFade: Boolean = false,
    ) {
        val duration = transitionDurationMillis
        restoreInteraction(incoming)
        // 同一具体 View 的无动画重呈现（例如 SavedState 恢复 CONTENT）不是切换；
        // 若先冻结再走 outgoing === incoming 分支，会让当前画面永久不可交互。
        if (outgoing !== incoming) {
            suspendInteraction(outgoing)
        }
        if (!useAnimate || outgoing == null || outgoing === incoming) {
            outgoing?.let {
                if (it !== incoming) {
                    finishOutgoing(it, outgoingEndVisibility, incoming)
                }
            }
            incoming?.let {
                it.alpha = 1f
                if (it === skeletonOverlay && overlayFade) {
                    // 骨架遮罩由渲染器控制可见性（可能延迟到布局完成后），
                    // 这里只保证透明度起点正确，不强行改可见性。
                } else if (it.visibility != VISIBLE) {
                    it.visibility = VISIBLE
                }
            }
            cleanupContainers(incoming)
            return
        }
        // 旧容器淡出后彻底关闭交互与无障碍（GONE / INVISIBLE）。
        outgoing.animate().cancel()
        val fadeOut = outgoing.animate()
        runningTransitions.add(fadeOut)
        fadeOut.alpha(0f).setDuration(duration)
        fadeOut.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                runningTransitions.remove(fadeOut)
                finishOutgoing(outgoing, outgoingEndVisibility, incoming)
                outgoing.alpha = 1f
            }

            override fun onAnimationCancel(animation: Animator) {
                runningTransitions.remove(fadeOut)
                outgoing.alpha = 1f
            }
        })
        fadeOut.start()
        // 新容器从透明淡入；骨架层若尚未布局完成则跳过淡入，由渲染器直接呈现。
        incoming?.let {
            if (it === skeletonOverlay && it.visibility != VISIBLE) {
                return@let
            }
            it.animate().cancel()
            it.alpha = 0f
            if (it.visibility != VISIBLE) it.visibility = VISIBLE
            val fadeIn = it.animate()
            runningTransitions.add(fadeIn)
            fadeIn.alpha(1f).setDuration(duration)
            fadeIn.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runningTransitions.remove(fadeIn)
                    it.alpha = 1f
                    cleanupContainers(incoming)
                }

                override fun onAnimationCancel(animation: Animator) {
                    runningTransitions.remove(fadeIn)
                    it.alpha = 1f
                }
            })
            fadeIn.start()
        }
    }

    // 离开某顶层后的最终可见性：内容回到 VISIBLE 参与布局，其余彻底 GONE。
    private fun finalVisibilityOf(outgoing: View?, incoming: View): Int {
        return if (outgoing === contentHost && incoming !== contentHost) {
            INVISIBLE
        } else {
            GONE
        }
    }

    private fun finishOutgoing(outgoing: View, endVisibility: Int, incoming: View?) {
        when {
            outgoing === skeletonOverlay -> skeletonRenderer.hide()
            outgoing === registry.peek(StateLayoutState.LOADING) -> layoutRenderer.hide()
            else -> outgoing.visibility = endVisibility
        }
        cleanupContainers(incoming)
    }

    private fun cleanupContainers(incoming: View?) {
        val incomingIsStateView = incoming?.parent === stateHost
        if (!incomingIsStateView) {
            val hasVisibleState = (0 until stateHost.childCount)
                .any { stateHost.getChildAt(it).visibility == VISIBLE }
            if (!hasVisibleState) {
                stateHost.visibility = GONE
                stateHost.alpha = 1f
            }
        }
        if (incoming !== contentHost && contentHost.visibility == VISIBLE && currentTop !== contentHost) {
            // 非动画路径或被取消动画的兜底；动画中的 Content 由 finishOutgoing 处理。
            if (runningTransitions.isEmpty()) contentHost.visibility = INVISIBLE
        }
    }

    private fun suspendInteraction(root: View?) { (root as? InteractionHost)?.blockInteraction(true) }

    private fun restoreInteraction(root: View?) { (root as? InteractionHost)?.blockInteraction(false) }

    private fun cancelTransitions() {
        // 新状态到来时取消旧切换动画，从当前视觉值继续，不闪回旧状态。
        // 注意：cancel() 会同步触发 AnimatorListener 的 cancel 回调，
        // 回调内会从 runningTransitions 移除自身，因此必须先快照再清空，
        // 不能在遍历中删除（否则抛 ConcurrentModificationException）。
        val pending = runningTransitions.toList()
        runningTransitions.clear()
        for (animator in pending) {
            animator.cancel()
        }
        // 取消可能残留的 View 级动画（AnimatorListener 的 cancel 回调会复位透明度）。
        contentHost.animate().cancel()
        stateHost.animate().cancel()
        skeletonOverlay.animate().cancel()
        normalizeToCurrentVisual()
    }

    private fun normalizeToCurrentVisual() {
        if (!hostsInitialized) return
        val current = currentTop
        restoreInteraction(current)
        contentHost.visibility = if (current === contentHost) VISIBLE else INVISIBLE
        for (index in 0 until stateHost.childCount) {
            stateHost.getChildAt(index).visibility =
                if (stateHost.getChildAt(index) === current) VISIBLE else GONE
        }
        stateHost.visibility = if (current?.parent === stateHost) VISIBLE else GONE
        if (current !== skeletonOverlay) {
            skeletonRenderer.hide()
        }
        val customLoading = registry.peek(StateLayoutState.LOADING)
        if (current !== customLoading) {
            layoutRenderer.hide()
        }
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

    // region 回调与重试

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

    // 错误页首次创建时绑定统一重试入口：只发事件，不自动切换状态。
    private fun bindRetryIfNeeded(state: StateLayoutState, view: View) {
        if (state != StateLayoutState.ERROR || errorRetryViewId == 0) return
        val retryView = view.findViewById<View>(errorRetryViewId)
        if (retryView == null) {
            Log.w(
                TAG,
                "ssl_errorRetryViewId 指向的 View 在错误布局中不存在，" +
                    "请检查 ID 是否写在 @layout 指向的错误布局内部",
            )
            return
        }
        retryView.setOnClickListener {
            // 统一防重复点击，避免快速连点产生重复业务意图。
            if (!retryClicksEnabled || renderedState != StateLayoutState.ERROR ||
                currentTop !== registry.peek(StateLayoutState.ERROR)) return@setOnClickListener
            val now = SystemClock.uptimeMillis()
            val lastClick = lastRetryClickMillis
            if (lastClick != null && now - lastClick < retryClickThrottleMillis) {
                return@setOnClickListener
            }
            lastRetryClickMillis = now
            onRetryClickListener?.onClick(it)
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
        if (presentationInvalidated && requestedState == renderedState) {
            presentationInvalidated = false
            validateStateConfiguration(requestedState)
            presentState(requestedState, animate = false) { syncLoadingVisibility() }
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
            cancelTransitions()
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

    // endregion

    // region 状态保存与恢复

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(superState).apply {
            requestedOrdinal = requestedState.ordinal
            useSkeletonFlag = useSkeleton
            transitionOrdinal = transitionEffect.ordinal
            transitionDuration = transitionDurationMillis
            announce = announceStateChanges
            showDelay = loadingShowDelayMillis
            minDuration = minimumLoadingDurationMillis
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
            emptyResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.EMPTY) else emptyLayoutResId
            errorResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.ERROR) else errorLayoutResId
            loadingResource = if (hostsInitialized) registry.layoutResource(StateLayoutState.LOADING) else customLoadingLayoutResId
            retryViewId = errorRetryViewId
            retryThrottle = retryClickThrottleMillis
            retryEnabled = retryClicksEnabled
            nodeRules.putAll(nodeConfigs)
            descriptions.putAll(stateDescriptions.mapValues { it.value.toString() })
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
        initialApplied = true
        explicitRequest = true
        syncLoadingVisibility(forceHidden = true)
        cancelTransitions()
        skeletonRenderer.hide()
        layoutRenderer.hide()
        coordinator.restore(StateLayoutState.CONTENT)
        val released = mutableListOf<Pair<StateLayoutState, View>>()
        listOf(StateLayoutState.EMPTY to state.emptyResource,
            StateLayoutState.ERROR to state.errorResource,
            StateLayoutState.LOADING to state.loadingResource).forEach { (target, resource) ->
            val old = if (resource != 0) registry.setLayout(target, resource)
                else if (state.formatVersion >= 2) registry.clearLayout(target) else null
            old?.let { released += target to it }
        }
        if (state.formatVersion >= 2) errorRetryViewId = state.retryViewId
        retryClickThrottleMillis = state.retryThrottle.coerceAtLeast(0)
        retryClicksEnabled = state.retryEnabled
        nodeConfigs.clear()
        nodeConfigs.putAll(state.nodeRules)
        stateDescriptions.clear()
        stateDescriptions.putAll(state.descriptions)
        val requested = StateLayoutState.entries.getOrElse(state.requestedOrdinal) {
            StateLayoutState.CONTENT
        }
        useSkeleton = state.useSkeletonFlag
        transitionEffect = StateTransitionEffect.entries.getOrElse(state.transitionOrdinal) {
            StateTransitionEffect.CROSSFADE
        }
        transitionDurationMillis = state.transitionDuration.coerceAtLeast(0L)
        announceStateChanges = state.announce
        loadingShowDelayMillis = state.showDelay.coerceAtLeast(0L)
        minimumLoadingDurationMillis = state.minDuration.coerceAtLeast(0L)
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
        skeletonConfig = restoredConfig
        // 画面事实仍由真实提交推进；恢复 Loading 也重新建立最短展示计时。
        presentRestored(requested)
        released.forEach { (target, view) -> releaseStateView(target, view) }
    }

    /**
     * 自定义保存状态：只保存请求状态与运行时配置标识，
     * 不保存 Listener / Bitmap / Animator / 业务数据与绝对时间。
     */
    class SavedState : BaseSavedState {
        internal var formatVersion: Int = 2

        var requestedOrdinal: Int = StateLayoutState.CONTENT.ordinal
        var useSkeletonFlag: Boolean = false
        var transitionOrdinal: Int = StateTransitionEffect.CROSSFADE.ordinal
        var transitionDuration: Long = DEFAULT_TRANSITION_DURATION_MILLIS
        var announce: Boolean = true
        var showDelay: Long = 0L
        var minDuration: Long = StateCoordinator.DEFAULT_MINIMUM_LOADING_DURATION_MILLIS
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
        var emptyResource: Int = 0
        var errorResource: Int = 0
        var loadingResource: Int = 0
        var retryViewId: Int = 0
        var retryThrottle: Long = RETRY_CLICK_THROTTLE_MILLIS
        var retryEnabled: Boolean = true
        val nodeRules = mutableMapOf<Int, SkeletonNodeConfig>()
        val descriptions = mutableMapOf<StateLayoutState, String>()

        constructor(superState: Parcelable?) : super(superState)

        private constructor(parcel: Parcel) : super(parcel) {
            val marker = parcel.readInt()
            val version = if (marker == FORMAT_MARKER) parcel.readInt() else 1
            formatVersion = version
            requestedOrdinal = if (marker == FORMAT_MARKER) parcel.readInt() else marker
            useSkeletonFlag = parcel.readInt() == 1
            transitionOrdinal = parcel.readInt()
            transitionDuration = parcel.readLong()
            announce = parcel.readInt() == 1
            showDelay = parcel.readLong()
            minDuration = parcel.readLong()
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
            if (version >= 2) {
                maximumPixels = parcel.readLong()
                emptyResource = parcel.readInt()
                errorResource = parcel.readInt()
                loadingResource = parcel.readInt()
                retryViewId = parcel.readInt()
                retryThrottle = parcel.readLong()
                retryEnabled = parcel.readInt() == 1
                val ruleCount = parcel.readInt()
                require(ruleCount in 0..10000) { "SavedState 节点数量非法" }
                repeat(ruleCount) {
                    val id = parcel.readInt()
                    val excluded = parcel.readInt() == 1
                    val shape = SkeletonShape.entries.getOrElse(parcel.readInt()) { SkeletonShape.ROUNDED_RECTANGLE }
                    val radius = if (parcel.readInt() == 1) parcel.readFloat() else null
                    nodeRules[id] = SkeletonNodeConfig(excluded, shape, radius)
                }
                val descriptionCount = parcel.readInt()
                require(descriptionCount in 0..StateLayoutState.entries.size) { "SavedState 描述数量非法" }
                repeat(descriptionCount) {
                    val target = StateLayoutState.entries.getOrElse(parcel.readInt()) { StateLayoutState.CONTENT }
                    parcel.readString()?.let { descriptions[target] = it }
                }
            }
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(FORMAT_MARKER)
            out.writeInt(2)
            out.writeInt(requestedOrdinal)
            out.writeInt(if (useSkeletonFlag) 1 else 0)
            out.writeInt(transitionOrdinal)
            out.writeLong(transitionDuration)
            out.writeInt(if (announce) 1 else 0)
            out.writeLong(showDelay)
            out.writeLong(minDuration)
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
            out.writeInt(emptyResource)
            out.writeInt(errorResource)
            out.writeInt(loadingResource)
            out.writeInt(retryViewId)
            out.writeLong(retryThrottle)
            out.writeInt(if (retryEnabled) 1 else 0)
            out.writeInt(nodeRules.size)
            nodeRules.forEach { (id, rule) ->
                out.writeInt(id)
                out.writeInt(if (rule.excluded) 1 else 0)
                out.writeInt(rule.shape.ordinal)
                out.writeInt(if (rule.cornerRadius != null) 1 else 0)
                rule.cornerRadius?.let { out.writeFloat(it) }
            }
            out.writeInt(descriptions.size)
            descriptions.forEach { (target, text) -> out.writeInt(target.ordinal); out.writeString(text) }
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            private const val FORMAT_MARKER = -0x53534C
            override fun createFromParcel(parcel: Parcel): SavedState = SavedState(parcel)

            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }

    // endregion

    private fun validateTemplateResource(config: SkeletonConfig) {
        if (config.source == SkeletonSource.TEMPLATE) {
            require(resources.getResourceTypeName(requireNotNull(config.templateLayoutResId)) == "layout") {
                "ssl_skeletonTemplate 必须指向布局资源"
            }
        }
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
        if (state == StateLayoutState.ERROR) view.findViewById<View>(errorRetryViewId)?.setOnClickListener(null)
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
        check(!bindingCallback || method.startsWith("setOn") || method == "getStateView" || method == "diagnostics") {
            "状态页创建 / 释放回调中不得调用 $method，避免事务重入"
        }
    }

    companion object {
        private const val TAG = "StateSkeletonLayout"

        /** 默认状态切换时长（毫秒）。 */
        const val DEFAULT_TRANSITION_DURATION_MILLIS: Long = 180L

        /**
         * 错误重试统一防重复点击间隔（毫秒）。
         *
         * 运行时可通过 retryClickThrottleMillis 调整；此值保留为默认间隔。
         */
        private const val RETRY_CLICK_THROTTLE_MILLIS: Long = 500L
    }
}
