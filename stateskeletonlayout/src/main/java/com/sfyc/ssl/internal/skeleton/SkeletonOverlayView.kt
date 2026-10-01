package com.sfyc.ssl.internal.skeleton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.annotation.ColorInt
import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonFallbackReason
import kotlin.math.cos
import kotlin.math.abs

/**
 * 骨架遮罩层：绘制单张 ALPHA_8 遮罩 + 流光/脉冲动效。
 *
 * # 绘制原理
 *
 * 遮罩 Bitmap 只保存形状（Alpha 通道），动画帧只更新以下两者之一并重绘，
 * 绝不在动画帧中创建 Bitmap / Path / Matrix / Paint / 集合：
 * - SHIMMER：复用成员 [Matrix] 平移 [LinearGradient]，把高亮带扫过遮罩。
 * - PULSE：用余弦函数把动画进度映射为画笔透明度。
 * - SOLID：静态绘制基础色，无动画器。
 *
 * # 装饰语义
 *
 * 本层拦截触摸（消费掉，不穿透到被遮挡的内容），但自身不进入无障碍焦点，
 * 不携带 contentDescription。
 *
 * # 遮罩生命周期
 *
 * - [invalidateMask] 标记失效；下次 [ensureMask] 时重建并释放旧 Bitmap。
 * - 尺寸变化（`onSizeChanged`）自动标记失效。
 * - detach 时停止动画并释放 Bitmap（重建留待下次显示，避免后台持有大内存）。
 */
internal sealed interface MaskPreparationResult {
    data class Ready(val fallbackUsed: Boolean) : MaskPreparationResult
    data object WaitingForLayout : MaskPreparationResult
    data object EmptyTemplate : MaskPreparationResult
    data class AllocationFailed(val retainedPreviousMask: Boolean) : MaskPreparationResult
    data class PixelBudgetExceeded(val retainedPreviousMask: Boolean) : MaskPreparationResult
}

internal class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    private val maskBuilder: SkeletonMaskBuilder = SkeletonMaskBuilder(),
) : View(context) {

    /** 当前渲染配置（整体替换，见 [refreshConfig]）。 */
    var config: SkeletonConfig = SkeletonConfig()
        private set

    /**
     * 遮罩几何来源：调用方（渲染器）在 [ensureMask] 前设置。
     * 内容取形与模板取形二选一，由渲染器按配置决定。
     */
    var geometryProvider: (() -> List<SkeletonGeometry>?)? = null
    /** 尺寸等失效通知交给渲染器合并成一次帧回调。 */
    var onMaskInvalidated: (() -> Unit)? = null

    /** 几何为空时是否降级为整块静态遮罩（内容取形 true，模板取形 false）。 */
    var fallbackToFullBounds: Boolean = true

    private val scratchGeometries = mutableListOf<SkeletonGeometry>()

    private var mask: Bitmap? = null
    private var maskInvalid: Boolean = true
    private var maskFallbackUsed: Boolean = false
    private var drawAllocationFallback: Boolean = false
    internal var fallbackReason = SkeletonFallbackReason.NONE
        private set
    internal val maskBytes: Int get() = mask?.takeUnless { it.isRecycled }?.allocationByteCount ?: 0
    internal val maskWidth: Int get() = mask?.takeUnless { it.isRecycled }?.width ?: 0
    internal val maskHeight: Int get() = mask?.takeUnless { it.isRecycled }?.height ?: 0

    internal var maskBuildCount: Int = 0
        private set

    internal val isEffectRunning: Boolean
        get() = animator.isRunning

    // 成员级绘制对象：动画帧只修改它们的状态，不分配新对象。
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerShader: LinearGradient? = null
    private val shaderMatrix = Matrix()
    private var shaderSpan: Float = 0f
    private var bandHalfWidth: Float = 0f
    @ColorInt private var transparentHighlight: Int = Color.TRANSPARENT
    private var scanAxisX: Float = 1f
    private var scanAxisY: Float = 0f
    private var gradientRotationDegrees: Float = 0f

    private val animator = SkeletonAnimator { invalidate() }

    init {
        // 装饰层：不聚焦、不播报、不携带描述。
        isFocusable = false
        isClickable = true
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 整体替换配置：颜色/角度/方向/时长变化只重建着色器，
     * 来源/模板/圆角变化才标记遮罩失效（几何不变时不重建 Bitmap）。
     */
    fun refreshConfig(newConfig: SkeletonConfig) {
        val old = config
        config = newConfig
        animator.durationMillis = newConfig.animationDurationMillis
        transparentHighlight = newConfig.highlightColor and 0x00FFFFFF
        if (old.effect != newConfig.effect ||
            old.direction != newConfig.direction ||
            old.normalizedAngleDegrees != newConfig.normalizedAngleDegrees ||
            old.highlightColor != newConfig.highlightColor
        ) {
            rebuildShader()
        }
        if (old.source != newConfig.source ||
            old.templateLayoutResId != newConfig.templateLayoutResId ||
            old.cornerRadius != newConfig.cornerRadius ||
            old.maximumMaskPixels != newConfig.maximumMaskPixels
        ) {
            invalidateMask()
        }
        // 同一效果持续显示时（如运行时切换颜色），若动画在跑则保持，
        // 否则由渲染器决定是否启动。
        if (animator.isRunning && newConfig.effect == SkeletonEffect.SOLID) {
            animator.stop()
        }
        invalidate()
    }

    /** 标记遮罩失效（尺寸/布局/配置变化与主动刷新入口）。 */
    fun invalidateMask() {
        maskInvalid = true
        invalidate()
        onMaskInvalidated?.invoke()
    }

    /**
     * 确保遮罩已构建。尺寸为 0 时返回 false（调用方等待布局完成再试）。
     *
     * @return 遮罩是否可用（模板几何为空时返回 false，由渲染器快速失败）。
     */
    fun ensureMask(
        preparedGeometries: List<SkeletonGeometry>? = null,
        allowEmptyTemplateFallback: Boolean = false,
    ): MaskPreparationResult {
        if (!maskInvalid && (mask != null || drawAllocationFallback)) {
            return MaskPreparationResult.Ready(maskFallbackUsed || drawAllocationFallback)
        }
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return MaskPreparationResult.WaitingForLayout
        scratchGeometries.clear()
        val geometries = preparedGeometries ?: geometryProvider?.invoke()
            ?: scratchGeometries
        if (geometries.isEmpty() && !fallbackToFullBounds && !allowEmptyTemplateFallback) {
            return MaskPreparationResult.EmptyTemplate
        }
        maskBuildCount++
        return when (val result = maskBuilder.buildMask(
            geometries, width, height,
            config.cornerRadius, fallbackToFullBounds || allowEmptyTemplateFallback, config.maximumMaskPixels,
        )) {
            is MaskBuildResult.Success -> {
                mask?.recycle()
                mask = result.bitmap
                maskFallbackUsed = result.fallbackUsed
                drawAllocationFallback = false
                fallbackReason = when {
                    !result.fallbackUsed -> SkeletonFallbackReason.NONE
                    !fallbackToFullBounds -> SkeletonFallbackReason.EMPTY_TEMPLATE_AT_CURRENT_SIZE
                    else -> SkeletonFallbackReason.EMPTY_CONTENT
                }
                maskInvalid = false
                rebuildShader()
                MaskPreparationResult.Ready(result.fallbackUsed)
            }
            MaskBuildResult.InvalidSize -> MaskPreparationResult.WaitingForLayout
            MaskBuildResult.EmptyGeometry -> MaskPreparationResult.EmptyTemplate
            MaskBuildResult.AllocationFailed -> {
                val retained = mask?.isRecycled == false
                drawAllocationFallback = !retained
                fallbackReason = SkeletonFallbackReason.ALLOCATION_FAILED
                maskInvalid = false
                MaskPreparationResult.AllocationFailed(retained)
            }
            MaskBuildResult.PixelBudgetExceeded -> {
                val retained = mask?.isRecycled == false
                drawAllocationFallback = !retained
                fallbackReason = SkeletonFallbackReason.PIXEL_BUDGET_EXCEEDED
                maskInvalid = false
                MaskPreparationResult.PixelBudgetExceeded(retained)
            }
        }
    }

    /** 启动动效（SOLID / 系统动画关闭时自动保持静态）。 */
    fun startEffect() {
        animator.durationMillis = config.animationDurationMillis
        if (isInEditMode) animator.stop() else animator.start(effectiveEffect())
        invalidate()
    }

    /** 停止动效。 */
    fun stopEffect() {
        animator.stop()
    }

    /** 释放遮罩 Bitmap（detach / 隐藏时调用，避免后台持有大内存）。 */
    fun releaseMask() {
        mask?.recycle()
        mask = null
        maskInvalid = true
        maskFallbackUsed = false
        drawAllocationFallback = false
        fallbackReason = SkeletonFallbackReason.NONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化意味着旧遮罩几何全部过期。
        if (w != oldw || h != oldh) {
            invalidateMask()
        }
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        // Start/End 流光方向依赖布局方向，RTL 切换后重建着色器。
        rebuildShader()
        invalidate()
        if (width > 0 && height > 0) invalidateMask()
    }

    override fun onDetachedFromWindow() {        // 注册与注销成对：分离时停动画、放 Bitmap，重建留待下次显示。
        animator.stop()
        releaseMask()
        super.onDetachedFromWindow()
    }

    // 消费触摸：骨架覆盖期间触摸不得穿透到被遮挡的业务内容。
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    // 覆盖 performClick 以满足无障碍点击语义；遮罩层本身不触发业务动作。
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val currentMask = mask
        if ((currentMask == null || currentMask.isRecycled) && drawAllocationFallback) {
            basePaint.color = config.maskColor
            basePaint.shader = null
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
            return
        }
        if (currentMask == null || currentMask.isRecycled) {
            // 遮罩尚未就绪时不绘制半成品；渲染器负责在布局完成后重建。
            return
        }
        when (effectiveEffect()) {
            SkeletonEffect.SOLID -> {
                basePaint.color = config.maskColor
                basePaint.shader = null
                canvas.drawBitmap(currentMask, 0f, 0f, basePaint)
            }
            SkeletonEffect.PULSE -> {
                // 余弦呼吸：progress 0→1 对应一次完整明暗周期，首尾相接无跳变。
                val alpha = if (animator.isRunning) {
                    val min = config.pulseMinAlpha
                    val max = config.pulseMaxAlpha
                    val cosine = 0.5f - 0.5f * cos(animator.progress * TAU)
                    min + (max - min) * cosine
                } else {
                    // 动画被系统关闭或尚未启动时显示静态满透明度，不留空白。
                    1f
                }
                basePaint.color = config.maskColor
                basePaint.alpha = (alpha.coerceIn(0f, 1f) * Color.alpha(config.maskColor)).toInt()
                basePaint.shader = null
                canvas.drawBitmap(currentMask, 0f, 0f, basePaint)
            }
            SkeletonEffect.SHIMMER -> {
                basePaint.color = config.maskColor
                basePaint.shader = null
                canvas.drawBitmap(currentMask, 0f, 0f, basePaint)
                if (animator.isRunning) {
                    // 高亮带中心沿扫描轴移动；首尾各留半个带宽，保证进出平滑。
                    // 着色器矩阵复用成员对象，动画帧不分配新对象。
                    val distance = ShimmerMotion.distance(animator.progress, shaderSpan, bandHalfWidth)
                    val centerX = width / 2f + scanAxisX * distance
                    val centerY = height / 2f + scanAxisY * distance
                    shaderMatrix.reset()
                    // LinearGradient 沿局部 X 轴变化，因此它的旋转角必须与位移轴一致；
                    // 额外旋转 90° 会让平移发生在等色线上，产生“动画在跑但画面不动”。
                    shaderMatrix.postRotate(gradientRotationDegrees)
                    shaderMatrix.postTranslate(centerX, centerY)
                    shimmerShader?.setLocalMatrix(shaderMatrix)
                    canvas.drawBitmap(currentMask, 0f, 0f, shimmerPaint)
                }
            }
        }
    }

    private fun effectiveEffect(): SkeletonEffect =
        if (fallbackReason != SkeletonFallbackReason.NONE) SkeletonEffect.SOLID else config.effect

    // 重建流光着色器：只在尺寸/配置变化时执行，不在动画帧分配。
    private fun rebuildShader() {
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return
        val axis = ShimmerMotion.resolveAxis(
            direction = config.direction,
            angleDegrees = config.normalizedAngleDegrees,
            isRtl = layoutDirection == LAYOUT_DIRECTION_RTL,
        )
        scanAxisX = axis.x
        scanAxisY = axis.y
        gradientRotationDegrees = axis.gradientRotationDegrees
        bandHalfWidth = (maxOf(width, height) * BAND_RATIO).coerceAtLeast(1f)
        // 矩形在扫描轴上的投影长度；与 ShimmerMotion.frame 的中心对称轨迹配合，
        // 保证动画首尾高亮带都完整位于边界外。
        shaderSpan = abs(scanAxisX) * width + abs(scanAxisY) * height
        // 着色器局部坐标：以原点为中心的横向色带，绘制时经矩阵旋转平移到扫描位置。
        val gradient = LinearGradient(
            -bandHalfWidth, 0f, bandHalfWidth, 0f,
            intArrayOf(transparentHighlight, config.highlightColor, transparentHighlight),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        shimmerShader = gradient
        shimmerPaint.shader = gradient
    }

    companion object {
        private const val TAU = (Math.PI * 2).toFloat()

        /** 高亮带半宽占最大边的比例。 */
        private const val BAND_RATIO = 0.3f
    }
}
