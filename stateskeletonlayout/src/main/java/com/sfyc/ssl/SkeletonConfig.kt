package com.sfyc.ssl

import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.annotation.Px
import android.content.Context

/**
 * 骨架渲染的不可变配置快照。
 *
 * 运行时修改骨架表现时必须整体替换 [StateSkeletonLayout.skeletonConfig]，
 * 控件会统一校验并使遮罩失效。禁止逐个修改可变属性，避免连续触发重复重建。
 *
 * 与加载时序相关的时长（显示延迟、最短展示）不属于本配置，
 * 它们是状态协调器的参数，见 [StateSkeletonLayout]。
 *
 * @property source 骨架形状来源：内容取形或独立模板。
 * @property templateLayoutResId 独立模板布局资源，仅 [SkeletonSource.TEMPLATE] 时必填。
 * @property effect 骨架效果，三者互斥。
 * @property maskColor 骨架基础颜色。
 * @property highlightColor 流光高亮颜色（仅 [SkeletonEffect.SHIMMER] 使用）。
 * @property cornerRadius 全部骨架矩形的默认圆角，单位 px，不得小于 0。
 * @property animationDurationMillis 单轮骨架动画时长，单位毫秒，必须大于 0。
 * @property direction 流光扫描方向，按 Layout Direction 解析。
 * @property angleDegrees 流光角度，构造时传入任意整数，读取
 *   [normalizedAngleDegrees] 可获得归一化到 `[0, 359]` 的值。
 * @property pulseMinAlpha 脉冲最小透明度，`[0, 1]`，不得大于 [pulseMaxAlpha]。
 * @property pulseMaxAlpha 脉冲最大透明度，`[0, 1]`。
 */
data class SkeletonConfig(
    val source: SkeletonSource = SkeletonSource.CONTENT,
    // 注解作用于构造参数（param）：配置对象在构造时校验，绘制时读取的是已校验的属性值。
    @param:LayoutRes val templateLayoutResId: Int? = null,
    val effect: SkeletonEffect = SkeletonEffect.SHIMMER,
    @param:ColorInt val maskColor: Int = DEFAULT_MASK_COLOR,
    @param:ColorInt val highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR,
    @param:Px val cornerRadius: Float = DEFAULT_CORNER_RADIUS_PX,
    val animationDurationMillis: Long = DEFAULT_ANIMATION_DURATION_MILLIS,
    val direction: SkeletonDirection = SkeletonDirection.START_TO_END,
    val angleDegrees: Int = DEFAULT_ANGLE_DEGREES,
    val pulseMinAlpha: Float = DEFAULT_PULSE_MIN_ALPHA,
    val pulseMaxAlpha: Float = DEFAULT_PULSE_MAX_ALPHA,
    /** ALPHA_8 遮罩的最大像素数；超限时静态降级，默认不限制。 */
    val maximumMaskPixels: Long = Long.MAX_VALUE,
) {

    init {
        // XML 与运行时共用同一套校验：关键错误直接抛异常，不静默修正。
        require(animationDurationMillis > 0) {
            "SkeletonConfig.animationDurationMillis 必须大于 0，当前=$animationDurationMillis"
        }
        require(cornerRadius.isFinite() && cornerRadius >= 0) {
            "SkeletonConfig.cornerRadius 必须是有限的非负数，当前=$cornerRadius"
        }
        require(maximumMaskPixels > 0) { "maximumMaskPixels 必须大于 0" }
        require(pulseMinAlpha in 0f..1f) {
            "SkeletonConfig.pulseMinAlpha 必须处于 [0, 1]，当前=$pulseMinAlpha"
        }
        require(pulseMaxAlpha in 0f..1f) {
            "SkeletonConfig.pulseMaxAlpha 必须处于 [0, 1]，当前=$pulseMaxAlpha"
        }
        require(pulseMinAlpha <= pulseMaxAlpha) {
            "SkeletonConfig.pulseMinAlpha 不得大于 pulseMaxAlpha，" +
                "当前=($pulseMinAlpha, $pulseMaxAlpha)"
        }
        require(source != SkeletonSource.TEMPLATE || (templateLayoutResId != null && templateLayoutResId != 0)) {
            "SkeletonConfig.source 为 TEMPLATE 时必须提供有效的 templateLayoutResId"
        }
    }

    /**
     * 归一化到 `[0, 359]` 的流光角度。
     *
     * 构造参数允许任意整数（如 `-90`、`450`），绘制时一律使用本属性，
     * 避免调用方自行处理角度回绕。
     */
    val normalizedAngleDegrees: Int
        get() = ((angleDegrees % 360) + 360) % 360

    companion object {
        /** 从当前资源读取昼夜颜色和 8dp 圆角；直接构造仍使用原有 px 常量。 */
        @JvmStatic
        fun from(context: Context): SkeletonConfig = SkeletonConfig(
            maskColor = context.getColor(R.color.ssl_mask_default),
            highlightColor = context.getColor(R.color.ssl_highlight_default),
            cornerRadius = context.resources.getDimension(R.dimen.ssl_corner_radius_default),
        )
        /** 骨架基础色默认值（XML 未配置且代码未指定时使用）。 */
        const val DEFAULT_MASK_COLOR: Int = 0xFFE3E6EA.toInt()

        /** 流光高亮色默认值。 */
        const val DEFAULT_HIGHLIGHT_COLOR: Int = 0xFFFFFFFF.toInt()

        /**
         * 默认圆角（px）。
         *
         * 注意：XML 属性 `ssl_cornerRadius` 是 dimension，解析为 px 后再构造本对象；
         * 直接构造本对象时如需 dp 语义，请调用方自行换算。
         */
        const val DEFAULT_CORNER_RADIUS_PX: Float = 8f

        /** 默认单轮骨架动画时长（毫秒）。 */
        const val DEFAULT_ANIMATION_DURATION_MILLIS: Long = 1200L

        /** 默认流光角度。 */
        const val DEFAULT_ANGLE_DEGREES: Int = 0

        /** 默认脉冲最小透明度。 */
        const val DEFAULT_PULSE_MIN_ALPHA: Float = 0.35f

        /** 默认脉冲最大透明度。 */
        const val DEFAULT_PULSE_MAX_ALPHA: Float = 1f
    }
}
