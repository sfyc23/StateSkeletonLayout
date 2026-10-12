package com.sfyc.ssl.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout

/**
 * 顶层容器之间的状态切换动画控制器。
 *
 * # 为什么提取
 *
 * 这块逻辑原先散在主控件里（`crossfade` / `cancelTransitions` /
 * `normalizeToCurrentVisual` / `finishOutgoing` / `cleanupContainers` /
 * `finalVisibilityOf` 共约 190 行），是库内最难的一段：
 *
 * - 取消动画时要"从当前视觉值继续"，不能闪回旧状态；
 * - `cancel()` 会**同步**触发 `AnimatorListener` 回调，因此遍历动画列表前
 *   必须先快照，否则 `ConcurrentModificationException`；
 * - 同一具体 View 的无动画重呈现（SavedState 恢复 CONTENT）不是切换，
 *   若走 `outgoing === incoming` 分支会把当前画面永久冻结；
 * - 骨架遮罩的可见性归渲染器管，动画只能管透明度。
 *
 * 这些约束都有测试覆盖，但混在 1600 行的主控件里很难单独审查。
 * 提取后本类只依赖两个容器、一个遮罩层与三个回调，可以用 Robolectric
 * 直接测取消语义，不需要构造完整的 StateSkeletonLayout。
 *
 * # 职责边界
 *
 * 本类**不**决定何时切换（那是 [StateCoordinator] 的职责），也不决定
 * 切到哪个 View（主控件把 outgoing / incoming 算好传进来）。
 * 它只负责：把两个 View 的透明度与可见性按约定过渡到目标态。
 *
 * @param contentHost 业务内容容器。
 * @param stateHost 自定义状态页容器。
 * @param skeletonOverlay 骨架遮罩层。
 * @param durationProvider 当前过渡时长（主控件可变配置，延迟读取）。
 * @param effectProvider 当前过渡效果（主控件可变配置，延迟读取）。
 * @param onOutgoingFinished 旧顶层离开后的收尾：骨架渲染器 hide、
 *   自定义加载渲染器 hide 等。主控件提供，因为那些渲染器不属于本类。
 */
internal class TransitionController(
    private val contentHost: View,
    private val stateHost: FrameLayout,
    private val skeletonOverlay: View,
    private val durationProvider: () -> Long,
    private val effectProvider: () -> com.sfyc.ssl.StateTransitionEffect,
    private val onOutgoingFinished: (outgoing: View, endVisibility: Int) -> Unit,
) {

    /**
     * 当前实际呈现的顶层 View（内容容器、具体状态页或骨架层）。
     *
     * 用于切换动画的进出判断，也是 `finalVisibilityOf` 与 `cleanupContainers`
     * 的判定依据。主控件在提交新画面时必须更新它。
     */
    var currentTop: View? = null

    private val runningTransitions = mutableListOf<ViewPropertyAnimator>()

    /**
     * 正在参与淡入 / 淡出的顶层 View。
     *
     * `runningTransitions` 只持有 `ViewPropertyAnimator`，而它不暴露目标 View，
     * 因此无法反查"哪个 View 还在动画中"。清理状态页缓存时需要这个信息。
     */
    private val transitioningViews = mutableSetOf<View>()

    /** 指定 View 是否正在参与过渡；状态页缓存清理用它拒绝清理动画中的 View。 */
    fun isTransitioning(view: View): Boolean = view in transitioningViews

    /** 是否还有未结束的过渡动画。 */
    val hasRunningTransition: Boolean
        get() = runningTransitions.isNotEmpty()

    /**
     * 执行一次切换。
     *
     * @param outgoing 正在离开的顶层；null 表示首次呈现（没有旧画面）。
     * @param incoming 即将进入的顶层；null 表示只做收尾。
     * @param overlayFade incoming 是骨架遮罩时是否走"透明度由动画管、
     *   可见性由渲染器管"的特殊路径。
     */
    fun crossfade(
        outgoing: View?,
        incoming: View?,
        useAnimate: Boolean,
        overlayFade: Boolean = false,
    ) {
        val endVisibility = finalVisibilityOf(outgoing, incoming)
        val duration = durationProvider()
        restoreInteraction(incoming)
        // 同一具体 View 的无动画重呈现（例如 SavedState 恢复 CONTENT）不是切换；
        // 若先冻结再走 outgoing === incoming 分支，会让当前画面永久不可交互。
        if (outgoing !== incoming) {
            suspendInteraction(outgoing)
        }
        if (!useAnimate || outgoing == null || outgoing === incoming) {
            outgoing?.let {
                if (it !== incoming) {
                    finishOutgoing(it, endVisibility, incoming)
                }
            }
            incoming?.let {
                it.alpha = 1f
                if (it === skeletonOverlay && overlayFade) {
                    // 骨架遮罩由渲染器控制可见性（可能延迟到布局完成后），
                    // 这里只保证透明度起点正确，不强行改可见性。
                } else if (it.visibility != View.VISIBLE) {
                    it.visibility = View.VISIBLE
                }
            }
            cleanupContainers(incoming)
            return
        }
        // 旧容器淡出后彻底关闭交互与无障碍（GONE / INVISIBLE）。
        outgoing.animate().cancel()
        val fadeOut = outgoing.animate()
        runningTransitions.add(fadeOut)
        transitioningViews.add(outgoing)
        fadeOut.alpha(0f).setDuration(duration)
        fadeOut.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                runningTransitions.remove(fadeOut)
                transitioningViews.remove(outgoing)
                finishOutgoing(outgoing, endVisibility, incoming)
                outgoing.alpha = 1f
            }

            override fun onAnimationCancel(animation: Animator) {
                runningTransitions.remove(fadeOut)
                transitioningViews.remove(outgoing)
                outgoing.alpha = 1f
            }
        })
        fadeOut.start()
        // 新容器从透明淡入；骨架层若尚未布局完成则跳过淡入，由渲染器直接呈现。
        incoming?.let {
            if (it === skeletonOverlay && it.visibility != View.VISIBLE) {
                return@let
            }
            it.animate().cancel()
            it.alpha = 0f
            if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            val fadeIn = it.animate()
            runningTransitions.add(fadeIn)
            transitioningViews.add(it)
            fadeIn.alpha(1f).setDuration(duration)
            fadeIn.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runningTransitions.remove(fadeIn)
                    transitioningViews.remove(it)
                    it.alpha = 1f
                    cleanupContainers(incoming)
                }

                override fun onAnimationCancel(animation: Animator) {
                    runningTransitions.remove(fadeIn)
                    transitioningViews.remove(it)
                    it.alpha = 1f
                }
            })
            fadeIn.start()
        }
    }

    /**
     * 取消全部过渡动画并从当前视觉值收敛。
     *
     * 新状态到来时调用：取消而不是回滚，让画面从"当前这一帧"继续，
     * 不闪回旧状态。
     */
    fun cancelAll() {
        // cancel() 会同步触发 AnimatorListener 的 cancel 回调，
        // 回调内会从 runningTransitions 移除自身，因此必须先快照再清空，
        // 不能在遍历中删除（否则抛 ConcurrentModificationException）。
        val pending = runningTransitions.toList()
        runningTransitions.clear()
        transitioningViews.clear()
        for (animator in pending) {
            animator.cancel()
        }
        // 取消可能残留的 View 级动画（AnimatorListener 的 cancel 回调会复位透明度）。
        contentHost.animate().cancel()
        stateHost.animate().cancel()
        skeletonOverlay.animate().cancel()
        normalizeToCurrentVisual()
    }

    /** 判断是否应走动画路径：效果为 CROSSFADE、时长大于 0 且系统动画未关闭。 */
    fun shouldAnimate(animate: Boolean): Boolean =
        animate &&
            effectProvider() == com.sfyc.ssl.StateTransitionEffect.CROSSFADE &&
            durationProvider() > 0 &&
            android.animation.ValueAnimator.areAnimatorsEnabled()

    // 离开某顶层后的最终可见性：内容回到 INVISIBLE 参与布局，其余彻底 GONE。
    private fun finalVisibilityOf(outgoing: View?, incoming: View?): Int =
        if (outgoing === contentHost && incoming !== contentHost) View.INVISIBLE else View.GONE

    // StateHost 内保留目标 View 与正在淡出的旧 View；其余状态立即隐藏。
    fun showStateViewForTransition(view: View, outgoing: View?) {
        if (view.parent == null) {
            stateHost.addView(view)
        }
        for (i in 0 until stateHost.childCount) {
            val child = stateHost.getChildAt(i)
            child.visibility = if (child === view || child === outgoing) View.VISIBLE else View.GONE
        }
        view.bringToFront()
        view.visibility = View.VISIBLE
    }

    private fun finishOutgoing(outgoing: View, endVisibility: Int, incoming: View?) {
        // 骨架与自定义加载的收尾由主控件处理（那些渲染器不属于本类）；
        // 其余顶层直接落到最终可见性。
        onOutgoingFinished(outgoing, endVisibility)
        cleanupContainers(incoming)
    }

    private fun cleanupContainers(incoming: View?) {
        val incomingIsStateView = incoming?.parent === stateHost
        if (!incomingIsStateView) {
            val hasVisibleState = (0 until stateHost.childCount)
                .any { stateHost.getChildAt(it).visibility == View.VISIBLE }
            if (!hasVisibleState) {
                stateHost.visibility = View.GONE
                stateHost.alpha = 1f
            }
        }
        if (incoming !== contentHost && contentHost.visibility == View.VISIBLE && currentTop !== contentHost) {
            // 非动画路径或被取消动画的兜底；动画中的 Content 由 finishOutgoing 处理。
            if (runningTransitions.isEmpty()) contentHost.visibility = View.INVISIBLE
        }
    }

    private fun normalizeToCurrentVisual() {
        val current = currentTop
        restoreInteraction(current)
        contentHost.visibility = if (current === contentHost) View.VISIBLE else View.INVISIBLE
        for (index in 0 until stateHost.childCount) {
            stateHost.getChildAt(index).visibility =
                if (stateHost.getChildAt(index) === current) View.VISIBLE else View.GONE
        }
        stateHost.visibility = if (current?.parent === stateHost) View.VISIBLE else View.GONE
    }

    private fun suspendInteraction(root: View?) {
        (root as? InteractionHost)?.blockInteraction(true)
    }

    private fun restoreInteraction(root: View?) {
        (root as? InteractionHost)?.blockInteraction(false)
    }
}
