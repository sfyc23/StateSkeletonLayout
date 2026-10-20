package com.sfyc.ssl

import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.annotation.Px

/**
 * 页级配置的批量快照，配合 [StateSkeletonLayout.applyOptions] 一次性应用。
 *
 * # 与 [SslGlobalDefaults] 的分工
 *
 * - [SslGlobalDefaults]：**进程级**，`Application.onCreate` 安装一次，所有新实例继承，
 *   且明确**不覆盖**布局资源类属性（`emptyLayout` / `errorLayout` / `loadingLayout` /
 *   `skeletonTemplate`）——这些属性页面各异。
 * - [SslLayoutOptions]：**实例级**，恰好补上被排除的那部分，用于程序化创建时
 *   一次性配置一个实例；重试入口通过 [retryConfig] 按状态批量配置。
 *
 * 二者不重叠：全局默认管"品牌视觉"，本对象管"页面结构"。
 *
 * # 语义
 *
 * 每个字段为 `null` 时跳过该项，保留当前值——与 [SslGlobalDefaults] 相同的部分覆盖语义。
 * 重试入口通过 [retryConfig] 按状态表达：指定状态列表为 `null` 时跳过该状态，
 * 空列表显式关闭该状态，非空列表替换该状态的完整入口。
 *
 * 本类是纯数据快照，不做校验；校验由对应的 setter / 控制器执行，非法值会抛出与直接调用 setter 相同的异常。
 */
data class SslLayoutOptions(
    /** 空态布局资源；不设置则保留当前声明（可能是 XML、Theme 或库内置兜底）。 */
    @param:LayoutRes val emptyLayoutResId: Int? = null,
    /** 错误态布局资源。 */
    @param:LayoutRes val errorLayoutResId: Int? = null,
    /** 自定义加载布局资源；`useSkeleton = true` 时用不到。 */
    @param:LayoutRes val loadingLayoutResId: Int? = null,
    /** 按状态批量配置重试入口；`null` 跳过，内层 `null` 跳过该状态，空列表关闭该状态。 */
    val retryConfig: StateRetryConfig? = null,
    /** 重试防重复点击间隔，单位毫秒，不得小于 0。 */
    val retryClickThrottleMillis: Long? = null,
    /** 是否接受重试事件。 */
    val retryClicksEnabled: Boolean? = null,
    /** 是否使用骨架遮罩；false 时为自定义加载布局。 */
    val useSkeleton: Boolean? = null,
    /** 骨架配置快照。 */
    val skeletonConfig: SkeletonConfig? = null,
    /**
     * 条目拼装模板声明（T2）。非空时要求 `skeletonConfig.source == TEMPLATE`
     * 且 `skeletonConfig.templateLayoutResId == null`。
     */
    val skeletonItems: List<SkeletonItemSpec>? = null,
    /** 状态切换过渡效果。 */
    val transitionEffect: StateTransitionEffect? = null,
    /** 状态切换时长，单位毫秒，不得小于 0。 */
    val transitionDurationMillis: Long? = null,
    /** 加载显示延迟，单位毫秒，不得小于 0。 */
    val loadingShowDelayMillis: Long? = null,
    /** Loading 最短展示时间，单位毫秒，不得小于 0。 */
    val minimumLoadingDurationMillis: Long? = null,
    /** 进入非内容态时是否做一次性无障碍播报。 */
    val announceStateChanges: Boolean? = null,
) {
    /** 是否没有任何字段被设置（应用本对象是空操作）。 */
    val isEmpty: Boolean
        get() {
            val retryEmpty = retryConfig == null ||
                (retryConfig.emptyViewIds == null && retryConfig.errorViewIds == null)
            return emptyLayoutResId == null && errorLayoutResId == null && loadingLayoutResId == null &&
                retryEmpty && retryClickThrottleMillis == null && retryClicksEnabled == null &&
                useSkeleton == null && skeletonConfig == null && skeletonItems == null &&
                transitionEffect == null && transitionDurationMillis == null &&
                loadingShowDelayMillis == null && minimumLoadingDurationMillis == null &&
                announceStateChanges == null
        }

    companion object {
        /**
         * 骨架加载的常用组合：开启骨架、内容取形、指定兜底状态页。
         *
         * 只是把几个高频字段拼好的便捷工厂，不引入新语义。
         */
        @JvmStatic
        fun skeleton(
            emptyLayoutResId: Int,
            errorLayoutResId: Int,
            skeletonConfig: SkeletonConfig = SkeletonConfig(),
        ): SslLayoutOptions = SslLayoutOptions(
            emptyLayoutResId = emptyLayoutResId,
            errorLayoutResId = errorLayoutResId,
            useSkeleton = true,
            skeletonConfig = skeletonConfig,
        )
    }
}
