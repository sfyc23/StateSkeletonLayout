package com.sfyc.ssl.internal.loading

import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonNodeConfig
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.internal.skeleton.MaskPreparationResult
import com.sfyc.ssl.internal.skeleton.SkeletonGeometry
import com.sfyc.ssl.internal.skeleton.SkeletonMaskBuilder
import com.sfyc.ssl.internal.skeleton.SkeletonOverlayView

/** 管理遮罩准备、失效合并和窗口生命周期；动画帧不采集 View 树。 */
internal class SkeletonLoadingRenderer(
    private val overlay: SkeletonOverlayView,
    private val contentHost: FrameLayout,
    private val configProvider: () -> SkeletonConfig,
    private val inflater: LayoutInflater,
    nodeConfigProvider: (Int) -> SkeletonNodeConfig? = { null },
) : LoadingRenderer {
    private val maskBuilder = SkeletonMaskBuilder(nodeConfigProvider)
    private val scratchGeometries = mutableListOf<SkeletonGeometry>()
    private val lastGeometries = mutableListOf<SkeletonGeometry>()
    private var templateHost: FrameLayout? = null
    private var templateResId: Int? = null
    private var templateConfiguration: Int = 0
    private data class ValidationKey(val resource: Int, val width: Int, val height: Int,
        val direction: Int, val configuration: Int, val rules: Int)
    private var validationKey: ValidationKey? = null
    private var rulesGeneration = 0
    private var observer: ViewTreeObserver? = null
    private var preDrawObserver: ViewTreeObserver? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var showing = false
    private var aggregatedVisible = true
    private var attached = false
    private var refreshPosted = false
    private var preparing = false
    private var pendingOnShown: (() -> Unit)? = null
    private val refreshTask = Runnable {
        refreshPosted = false
        if (showing && attached && aggregatedVisible) prepareAndStart()
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshContentGeometry() }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { refreshContentGeometry() }

    init {
        overlay.geometryProvider = { collectGeometries() }
        overlay.onMaskInvalidated = { scheduleRefresh() }
    }

    override fun show(onShown: () -> Unit) {
        showing = true
        pendingOnShown = onShown
        overlay.refreshConfig(configProvider())
        overlay.fallbackToFullBounds = configProvider().source != SkeletonSource.TEMPLATE
        overlay.visibility = View.INVISIBLE
        if ((attached && aggregatedVisible) || overlay.isInEditMode) prepareAndStart()
    }

    override fun hide() {
        showing = false
        pendingOnShown = null
        stopObserving()
        cancelRefresh()
        overlay.stopEffect()
        overlay.releaseMask()
        overlay.visibility = View.GONE
    }

    fun onConfigChanged() {
        overlay.refreshConfig(configProvider())
        overlay.fallbackToFullBounds = configProvider().source != SkeletonSource.TEMPLATE
        if (configProvider().source == SkeletonSource.TEMPLATE) unregisterContentListeners()
        if (showing && attached && aggregatedVisible) prepareAndStart()
    }

    /** 验证候选模板后才替换私有缓存；同尺寸、方向、资源配置及规则下只验证一次。 */
    fun preflight(config: SkeletonConfig = configProvider()) {
        if (config.source != SkeletonSource.TEMPLATE) return
        val resId = requireNotNull(config.templateLayoutResId)
        require(overlay.resources.getResourceTypeName(resId) == "layout") { "ssl_skeletonTemplate 必须指向布局资源" }
        val metrics = overlay.resources.displayMetrics
        val width = overlay.width.takeIf { it > 0 } ?: contentHost.width.takeIf { it > 0 } ?: metrics.widthPixels.coerceAtLeast(1)
        val height = overlay.height.takeIf { it > 0 } ?: contentHost.height.takeIf { it > 0 } ?: metrics.heightPixels.coerceAtLeast(1)
        val key = ValidationKey(resId, width, height, overlay.layoutDirection,
            overlay.resources.configuration.hashCode(), rulesGeneration)
        if (validationKey == key) return
        val candidate = if (templateResId == resId && templateConfiguration == key.configuration) templateHost else null
        val host = candidate ?: createTemplateHost(resId)
        measureTemplate(host, width, height)
        require(maskBuilder.collectFromTemplate(host, scratchGeometries)) {
            "骨架模板未产生有效形状：请检查 app:ssl_skeletonTemplate 指向的布局是否包含可见叶子 View"
        }
        templateHost = host
        templateResId = resId
        templateConfiguration = key.configuration
        validationKey = key
    }

    fun invalidateNodeRules(refresh: Boolean = true) {
        rulesGeneration++
        validationKey = null
        if (refresh) invalidate()
    }

    fun invalidate() {
        overlay.invalidateMask()
        if (showing && attached && aggregatedVisible) prepareAndStart()
    }

    override fun onAttachedToWindow() {
        attached = true
        if (showing && aggregatedVisible) prepareAndStart()
    }

    override fun onDetachedFromWindow() {
        attached = false
        pendingOnShown = null
        stopObserving()
        cancelRefresh()
        overlay.stopEffect()
        overlay.releaseMask()
    }

    override fun onAggregatedVisibilityChanged(isVisible: Boolean) {
        aggregatedVisible = isVisible
        if (!showing || !attached) return
        if (isVisible) {
            // 不可见期间允许布局更新，重见时比较一次几何，未变则直接复用 Bitmap。
            refreshContentGeometry()
            prepareAndStart()
        } else {
            stopObserving()
            cancelRefresh()
            overlay.stopEffect()
        }
    }

    private fun scheduleRefresh() {
        if (preparing || refreshPosted || !showing || !attached || !aggregatedVisible) return
        refreshPosted = true
        overlay.postOnAnimation(refreshTask)
    }

    private fun cancelRefresh() {
        overlay.removeCallbacks(refreshTask)
        refreshPosted = false
    }

    private fun prepareAndStart(geometries: List<SkeletonGeometry>? = null) {
        if (preparing) return
        preparing = true
        cancelRefresh()
        try {
            ensureOverlaySize()
            // 入口已验证的模板可能在 resize 后暂时全部落到裁剪区外，保持静态反馈，
            // 不把尺寸变化产生的空几何作为异步配置异常抛进主线程。
            val validatedTemplate = configProvider().source == SkeletonSource.TEMPLATE &&
                validationKey?.resource == configProvider().templateLayoutResId
            when (overlay.ensureMask(geometries, allowEmptyTemplateFallback = validatedTemplate)) {
                MaskPreparationResult.WaitingForLayout -> { waitForLayoutOnce(); return }
                MaskPreparationResult.EmptyTemplate -> error("骨架模板未产生有效形状：请检查 ssl_skeletonTemplate")
                else -> Unit
            }
            overlay.visibility = View.VISIBLE
            overlay.startEffect()
            registerContentListeners()
            pendingOnShown?.let { callback -> pendingOnShown = null; callback() }
        } finally {
            preparing = false
        }
    }

    private fun ensureOverlaySize() {
        if (overlay.width > 0 && overlay.height > 0) return
        // 内容 Host 的布局已经包含父容器 padding / margin；不得按整个窗口铺满。
        val width = contentHost.width
        val height = contentHost.height
        if (width <= 0 || height <= 0) return
        overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        overlay.layout(contentHost.left, contentHost.top, contentHost.left + width, contentHost.top + height)
    }

    private fun waitForLayoutOnce() {
        if (preDrawListener != null) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            unregisterPreDrawListener()
            if (showing && attached && aggregatedVisible) prepareAndStart()
            true
        }
        preDrawListener = listener
        preDrawObserver = overlay.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
    }

    private fun unregisterPreDrawListener() {
        preDrawListener?.let { listener ->
            preDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            overlay.viewTreeObserver.takeIf { it.isAlive && it !== preDrawObserver }?.removeOnPreDrawListener(listener)
        }
        preDrawListener = null
        preDrawObserver = null
    }

    private fun registerContentListeners() {
        if (observer != null || configProvider().source != SkeletonSource.CONTENT) return
        observer = contentHost.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnScrollChangedListener(scrollListener)
        }
    }

    private fun unregisterContentListeners() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(layoutListener)
            it.removeOnScrollChangedListener(scrollListener)
        }
        observer = null
    }

    private fun stopObserving() { unregisterContentListeners(); unregisterPreDrawListener() }

    private fun refreshContentGeometry() {
        if (!showing || !attached || !aggregatedVisible || configProvider().source != SkeletonSource.CONTENT) return
        maskBuilder.collectFromContent(contentHost, overlay, scratchGeometries)
        if (scratchGeometries == lastGeometries) return
        lastGeometries.clear()
        lastGeometries.addAll(scratchGeometries)
        overlay.invalidateMask()
        // 使用已采集几何重建，避免同一次布局回调遍历两遍树；空集合也会走静态降级。
        prepareAndStart(scratchGeometries)
    }

    private fun collectGeometries(): List<SkeletonGeometry> {
        if (configProvider().source == SkeletonSource.TEMPLATE) {
            val resId = requireNotNull(configProvider().templateLayoutResId)
            val configuration = overlay.resources.configuration.hashCode()
            val host = if (templateResId == resId && templateConfiguration == configuration) templateHost else null
            val target = host ?: createTemplateHost(resId).also {
                templateHost = it; templateResId = resId; templateConfiguration = configuration
            }
            measureTemplate(target, overlay.width, overlay.height)
            maskBuilder.collectFromTemplate(target, scratchGeometries)
        } else {
            maskBuilder.collectFromContent(contentHost, overlay, scratchGeometries)
        }
        lastGeometries.clear()
        lastGeometries.addAll(scratchGeometries)
        return scratchGeometries
    }

    private fun measureTemplate(host: FrameLayout, width: Int, height: Int) {
        host.layoutDirection = overlay.layoutDirection
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
    }

    private fun createTemplateHost(resId: Int): FrameLayout = FrameLayout(overlay.context).apply {
        visibility = View.GONE
        addView(inflater.inflate(resId, this, false))
    }
}
