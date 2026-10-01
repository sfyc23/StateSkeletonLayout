package com.sfyc.ssl

import androidx.annotation.VisibleForTesting

/**
 * 进程级全局默认配置的安装入口。
 *
 * 在 `Application.onCreate()` 中一次性安装 [SslGlobalDefaults]，
 * 之后创建的每个 [StateSkeletonLayout] 实例自动继承其中的非 null 字段。
 *
 * **安装语义**：整体替换，不做字段级合并；重复调用以最后一次为准。
 *
 * **生效时机**：仅在控件构造时合并一次。已创建实例不受后续 [install] 影响。
 *
 * **线程**：建议在主线程调用 [install]（与 Application.onCreate 一致）。
 * 内部读取仅在控件构造路径发生，本对象不做额外线程安全包装。
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
 * 使用示例：
 * ```kotlin
 * // Application.onCreate()
 * StateSkeletonLayoutDefaults.install(
 *     SslGlobalDefaults(
 *         useSkeleton = true,
 *         skeletonSource = SkeletonSource.CONTENT,
 *         effect = SkeletonEffect.PULSE,
 *         maskColor = ContextCompat.getColor(this, R.color.brand_skeleton),
 *         minimumLoadingDurationMillis = 500L,
 *     )
 * )
 * ```
 */
object StateSkeletonLayoutDefaults {

    /**
     * 当前全局默认配置。未安装时为全 null 的 [SslGlobalDefaults]。
     *
     * 仅供 [StateSkeletonLayout] 构造路径读取，不作为公开查询 API。
     */
    internal val current: SslGlobalDefaults
        get() = _current

    @Volatile
    private var _current: SslGlobalDefaults = SslGlobalDefaults()

    /**
     * 安装全局默认配置。整体替换，不做字段级合并；重复调用以最后一次为准。
     *
     * 建议在 `Application.onCreate()` 中调用一次。
     * 已创建的 [StateSkeletonLayout] 实例不受影响。
     */
    fun install(defaults: SslGlobalDefaults) {
        _current = defaults
    }

    /**
     * 恢复到未安装状态。仅供单元测试隔离用例，生产代码不鼓励调用。
     */
    @VisibleForTesting
    fun reset() {
        _current = SslGlobalDefaults()
    }
}
