package com.sfyc.ssl.internal

import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonDirection
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.ssl.StateTransitionEffect

/**
 * 库内置默认值的唯一真源。
 *
 * # 为什么需要这个对象
 *
 * 在此之前，同一批默认值分散在三处：
 * 1. `res/values/styles.xml` 的样式项（宿主可通过 Theme 覆写的公开契约）；
 * 2. [SslDefaultsMerger.merge] 中各字段的 `?:` 字面量；
 * 3. `SkeletonConfig` / `StateCoordinator` 数据类的构造默认值。
 *
 * 改一处漏一处不会编译失败、不会测试失败，只会让
 * 「宿主未在 AppTheme 声明 `ssl_stateSkeletonLayoutStyle`」与
 * 「宿主声明了该样式」两条构造路径拿到**不同**的默认值。
 * 当时两者数值恰好相同，属巧合而非约束。
 *
 * # 范围
 *
 * 本对象只收敛**非资源派生**的默认值。颜色与圆角由资源提供
 * （`ssl_mask_default` / `ssl_highlight_default` / `ssl_corner_radius_default`），
 * 它们在资源体系内本来就只定义一处，且需要昼夜限定符，不在此重复声明。
 *
 * 骨架视觉默认值直接引用 [SkeletonConfig] 的公开常量，不重新声明数值，
 * 避免出现第二个真源。[StateCoordinator] 与 [StateSkeletonLayout] 的公开常量同理。
 *
 * # 一致性保证
 *
 * `SslDefaultValuesTest` 逐字段断言 `res/values/styles.xml` 与本对象一致；
 * 任一侧改动而另一侧未同步时测试失败。
 */
internal object SslDefaultValues {

    /** XML 加载完成后的初始状态。 */
    val INITIAL_STATE: StateLayoutState = StateLayoutState.CONTENT

    /** 是否使用骨架遮罩。默认 false，即默认使用自定义加载布局。 */
    val USE_SKELETON: Boolean = false

    /** 骨架形状来源。 */
    val SOURCE: SkeletonSource = SkeletonSource.CONTENT

    /** 骨架效果。 */
    val EFFECT: SkeletonEffect = SkeletonEffect.SHIMMER

    /** 流光扫描方向。 */
    val DIRECTION: SkeletonDirection = SkeletonDirection.START_TO_END

    /** 请求 Loading 后的显示延迟，默认 0ms（首次加载立即反馈）。 */
    val LOADING_SHOW_DELAY_MILLIS: Long = StateCoordinator.DEFAULT_LOADING_SHOW_DELAY_MILLIS

    /** Loading 最短展示时间，默认 300ms（避免快速请求引起闪烁）。 */
    val MINIMUM_LOADING_DURATION_MILLIS: Long = StateCoordinator.DEFAULT_MINIMUM_LOADING_DURATION_MILLIS

    /** 状态切换过渡效果。 */
    val TRANSITION_EFFECT: StateTransitionEffect = StateTransitionEffect.CROSSFADE

    /** 状态切换时长，默认 180ms。 */
    val TRANSITION_DURATION_MILLIS: Long = StateSkeletonLayout.DEFAULT_TRANSITION_DURATION_MILLIS

    /** 进入非内容态时是否做一次性无障碍播报。 */
    val ANNOUNCE_STATE_CHANGES: Boolean = true

    /** 单轮骨架动画时长，默认 1200ms。 */
    const val ANIMATION_DURATION_MILLIS: Long = SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS

    /** 流光角度，默认 0。 */
    const val ANGLE_DEGREES: Int = SkeletonConfig.DEFAULT_ANGLE_DEGREES

    /** 脉冲最小透明度。 */
    const val PULSE_MIN_ALPHA: Float = SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA

    /** 脉冲最大透明度。 */
    const val PULSE_MAX_ALPHA: Float = SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA

    /** 重试统一防重复点击间隔，默认 500ms。真源在公开常量上。 */
    const val RETRY_CLICK_THROTTLE_MILLIS: Long =
        StateSkeletonLayout.DEFAULT_RETRY_CLICK_THROTTLE_MILLIS

    /** 是否默认使用库内置的空态 / 错误态布局。 */
    const val USE_BUILT_IN_STATE_LAYOUTS: Boolean = true
}
