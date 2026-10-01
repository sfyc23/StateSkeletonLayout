package com.sfyc.ssl

import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * 进程级全局默认配置快照。
 *
 * 通过 [StateSkeletonLayoutDefaults.install] 在应用启动时一次性安装，
 * 之后创建的每个 [StateSkeletonLayout] 实例自动继承本配置中的非 null 字段。
 *
 * **部分覆盖语义**：字段为 `null` 时不参与覆盖，保留下一层默认值
 * （库内置常量或 Theme Attribute）；字段非 `null` 时强制覆盖。
 *
 * **不覆盖布局资源类属性**（`emptyLayout` / `errorLayout` / `loadingLayout` /
 * `skeletonTemplate` / `errorRetryViewId`），这些属性页面各异，
 * 仍由 XML 或运行时单独配置。
 *
 * **优先级链**（低 → 高）：
 * ```
 * 库内置常量 < Theme Attribute < Global Config < XML 属性 < 运行时 setter
 * ```
 * 同一属性在 Theme 与 Global 同时设置时，Global 优先。
 *
 * 配置是不可变快照；[StateSkeletonLayoutDefaults.install] 采用整体替换语义，
 * 不做字段级合并。已创建实例不受后续 install 影响。
 *
 * @property useSkeleton 是否使用骨架遮罩，true=骨架、false=自定义布局。
 * @property skeletonSource 骨架形状来源：内容取形或独立模板。
 * @property initialState XML 加载完成后的初始状态。
 * @property effect 骨架效果，三者互斥。
 * @property maskColor 骨架基础颜色。
 * @property highlightColor 流光高亮颜色（仅 [SkeletonEffect.SHIMMER] 使用）。
 * @property cornerRadius 全部骨架矩形的默认圆角，单位 px，不得小于 0。
 * @property animationDurationMillis 单轮骨架动画时长，单位毫秒，必须大于 0。
 * @property direction 流光扫描方向，按 Layout Direction 解析。
 * @property angleDegrees 流光角度，任意整数，读取时归一化到 `[0, 359]`。
 * @property pulseMinAlpha 脉冲最小透明度，`[0, 1]`，不得大于 [pulseMaxAlpha]。
 * @property pulseMaxAlpha 脉冲最大透明度，`[0, 1]`。
 * @property loadingShowDelayMillis 请求 Loading 后延迟多久才真正显示，单位毫秒。
 * @property minimumLoadingDurationMillis Loading 一旦显示至少保留多久，单位毫秒。
 * @property transitionEffect 状态切换过渡效果。
 * @property transitionDurationMillis 状态切换时长，单位毫秒，不得小于 0。
 * @property announceStateChanges 进入 Loading/Empty/Error 时是否做一次性无障碍播报。
 */
data class SslGlobalDefaults(
    val useSkeleton: Boolean? = null,
    val skeletonSource: SkeletonSource? = null,
    val initialState: StateLayoutState? = null,
    val effect: SkeletonEffect? = null,
    @param:ColorInt val maskColor: Int? = null,
    @param:ColorInt val highlightColor: Int? = null,
    @param:Px val cornerRadius: Float? = null,
    val animationDurationMillis: Long? = null,
    val direction: SkeletonDirection? = null,
    val angleDegrees: Int? = null,
    val pulseMinAlpha: Float? = null,
    val pulseMaxAlpha: Float? = null,
    val loadingShowDelayMillis: Long? = null,
    val minimumLoadingDurationMillis: Long? = null,
    val transitionEffect: StateTransitionEffect? = null,
    val transitionDurationMillis: Long? = null,
    val announceStateChanges: Boolean? = null,
)
