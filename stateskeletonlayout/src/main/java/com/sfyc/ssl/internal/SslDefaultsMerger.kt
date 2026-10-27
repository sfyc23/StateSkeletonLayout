package com.sfyc.ssl.internal

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.util.TypedValue
import com.sfyc.ssl.R
import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonDirection
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonItemSpec
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateTransitionEffect
import com.sfyc.ssl.normalizeRetryIds

/**
 * 合并后的最终配置：用于构造 [com.sfyc.ssl.StateSkeletonLayout]。
 *
 * 校验由 [SkeletonConfig] 的 `init` 统一执行，本类不重复校验。
 */
internal data class MergedSslConfig(
    val initialState: StateLayoutState,
    val useSkeleton: Boolean,
    val skeletonConfig: SkeletonConfig,
    val skeletonItemLayoutResId: Int,
    val skeletonItemCount: Int?,
    val loadingShowDelayMillis: Long,
    val minimumLoadingDurationMillis: Long,
    val transitionEffect: StateTransitionEffect,
    val transitionDurationMillis: Long,
    val announceStateChanges: Boolean,
    /** 未配置 EMPTY / ERROR 布局时是否回退到库内置布局。 */
    val useBuiltInStateLayouts: Boolean,
    /**
     * 合并后的重试声明（未补库默认）：`null` 表示三层均未声明，继承时由调用方补库默认；
     * 空列表表示显式关闭，非空表示替换该状态的完整入口。
     */
    val emptyRetryViewIds: List<Int>?,
    val errorRetryViewIds: List<Int>?,
    /**
     * 颜色与圆角是否来自资源（而非宿主显式指定）。
     *
     * 为 true 表示这三个值没有经过 XML / Global / Theme 任一层显式覆盖，
     * 完全由 `ssl_mask_default` / `ssl_highlight_default` /
     * `ssl_corner_radius_default` 决定。昼夜切换时应当用新 Configuration
     * 重新解析，否则骨架会停留在旧主题的颜色上。
     */
    val colorsFromResources: Boolean,
    /**
     * 归一化后的状态布局资源（含清空屏障与内置兜底）。
     *
     * `0` 表示无有效布局（内置兜底关闭或显式清空后无兜底）。
     * 由合并器统一处理 XML 直接属性 / 页面 Style / Theme 的清空屏障，
     * 调用方不得再用无条件 `?:` 重新继承被屏蔽的低层资源。
     */
    val emptyLayoutResId: Int = 0,
    val errorLayoutResId: Int = 0,
    val customLoadingLayoutResId: Int = 0,
)

/**
 * 分层声明：XML 直接属性、页面显式 Style、Theme 默认三层分开保存。
 *
 * 不能在比较 T1 / T2 之前把直接属性与页面 Style 合并成无法区分来源的对象，
 * 否则跨层互斥覆盖会被误判为同层冲突。
 */
internal data class LayeredSslAttrs(
    val direct: XmlSslAttrs = XmlSslAttrs(),
    val pageStyle: XmlSslAttrs = XmlSslAttrs(),
    val theme: XmlSslAttrs = XmlSslAttrs(),
)

/**
 * 按优先级链合并最终配置的内部工具。
 *
 * 优先级链（低 → 高）：
 * ```
 * 库内置常量 < Theme 默认 Style / Theme 值 < Global Config < XML 显式 Style（含父 Style） < XML 直接属性 < 运行时 setter
 * ```
 * 同一属性在 Theme 与 Global 同时设置时，Global 优先。
 * 页面 `style="?attr/..."` 仍属于页面显式 Style，不能降为 Theme 默认。
 *
 * 合并仅在控件构造时执行一次；运行时 setter 与 SavedState 恢复优先级更高，
 * 不经由本合并器。
 */
internal object SslDefaultsMerger {

    // 层级常量：数值越小优先级越高，用于跨层比较与错误信息。
    private const val LAYER_DIRECT = 0
    private const val LAYER_PAGE_STYLE = 1
    private const val LAYER_GLOBAL = 2
    private const val LAYER_THEME = 3
    private const val LAYER_LIBRARY = 4

    private fun layerName(layer: Int): String = when (layer) {
        LAYER_DIRECT -> "XML 直接属性"
        LAYER_PAGE_STYLE -> "XML 显式 Style"
        LAYER_GLOBAL -> "Global"
        LAYER_THEME -> "Theme 默认"
        else -> "库默认"
    }

    /** 分开读取 Theme 默认和页面显式配置，避免 hasValue 将 Theme 当成 XML。 */
    fun read(context: Context, attrs: AttributeSet?, defStyleAttr: Int): Pair<XmlSslAttrs, XmlSslAttrs> {
        val layered = readLayered(context, attrs, defStyleAttr)
        // 兼容旧调用：把直接属性与页面 Style 合并为一份页面声明。
        // 新构造路径请使用 readLayered + mergeLayered，避免互斥属性丢失来源。
        val page = combinePageForLegacy(layered.direct, layered.pageStyle)
        return page to layered.theme
    }

    /**
     * 分层读取：XML 直接属性、页面显式 Style（含父 Style）、Theme 默认分开保存。
     *
     * - 直接属性的 `@null` / `@empty` 均为显式清空（依据 AttributeSet 确实存在该属性判定）。
     * - 页面 Style / Theme Style 统一用 `@empty` 表达清空（`hasValueOrEmpty && !hasValue`）；
     *   Style 中的 `@null` 不承诺截断继承语义，按未声明处理。
     * - 资源值仍经真实 Theme 解析 `?attr/...`，独立 Theme 仅用于判定 Style 成员。
     */
    fun readLayered(context: Context, attrs: AttributeSet?, defStyleAttr: Int): LayeredSslAttrs {
        val styleable = R.styleable.SslStateSkeletonLayout
        val defaults = context.obtainStyledAttributes(null, styleable, defStyleAttr, 0)
        val theme = try {
            parseThemeLayer(context, defaults)
        } finally {
            defaults.recycle()
        }
        if (attrs == null) return LayeredSslAttrs(XmlSslAttrs(), XmlSslAttrs(), theme)

        val directIndices = mutableSetOf<Int>()
        for (i in 0 until attrs.attributeCount) {
            val index = styleable.indexOf(attrs.getAttributeNameResource(i))
            if (index >= 0) directIndices += index
        }
        // style 本身也可使用 ?attr，先在真实 Theme 中定位实际样式资源。
        var pageStyle = attrs.styleAttribute
        if (pageStyle != 0) {
            try {
                if (context.resources.getResourceTypeName(pageStyle) == "attr") {
                    val value = TypedValue()
                    pageStyle = if (context.theme.resolveAttribute(pageStyle, value, false) &&
                        value.type == TypedValue.TYPE_REFERENCE
                    ) {
                        value.data
                    } else {
                        0
                    }
                }
            } catch (_: Exception) {
                pageStyle = 0
            }
        }
        var pageStyleAttrs = XmlSslAttrs()
        if (pageStyle != 0) {
            var isolated: TypedArray? = null
            var realStyle: TypedArray? = null
            try {
                val isolatedTheme = context.resources.newTheme()
                isolated = isolatedTheme.obtainStyledAttributes(pageStyle, styleable)
                val memberEmpty = mutableSetOf<Int>()
                for (i in styleable.indices) {
                    try {
                        if (isolated.hasValueOrEmpty(i)) memberEmpty += i
                    } catch (_: Exception) {
                        if (isolated.hasValue(i)) memberEmpty += i
                    }
                }
                try {
                    realStyle = context.theme.obtainStyledAttributes(pageStyle, styleable)
                } catch (_: Exception) {
                    realStyle = null
                }
                if (realStyle != null) {
                    pageStyleAttrs = parseStyleLayer(context, realStyle, memberEmpty)
                } else {
                    // 拿不到真实 Style 值时退化为仅成员标记：无法解析资源，按未声明处理。
                    pageStyleAttrs = XmlSslAttrs()
                }
            } catch (_: Exception) {
                pageStyleAttrs = XmlSslAttrs()
            } finally {
                try { isolated?.recycle() } catch (_: Exception) { }
                try { realStyle?.recycle() } catch (_: Exception) { }
            }
        }
        val page = context.obtainStyledAttributes(attrs, styleable, 0, 0)
        return try {
            val direct = parseDirectLayer(context, page, directIndices)
            LayeredSslAttrs(direct, pageStyleAttrs, theme)
        } finally {
            page.recycle()
        }
    }

    /**
     * 合并 XML 属性与全局默认，产出最终配置。
     *
     * 兼容入口：`xmlAttrs` 视为已合并的页面声明（直接属性 + 显式 Style），
     * 按直接属性层参与骨架归一化；需要区分直接与 Style 的同层冲突请使用
     * [mergeLayered]。
     */
    fun merge(
        context: Context,
        xmlAttrs: XmlSslAttrs,
        global: SslGlobalDefaults,
        themeAttrs: XmlSslAttrs = XmlSslAttrs(),
    ): MergedSslConfig {
        return mergeLayered(context, xmlAttrs, XmlSslAttrs(), global, themeAttrs)
    }

    /**
     * 分层合并：XML 直接属性 > XML 显式 Style > Global > Theme 默认 > 库默认。
     *
     * 骨架来源（source）、模板供给（T1 整页模板 / T2 条目拼装）、item 数量一起归一化；
     * 颜色、效果、时长等字段继续独立继承。只检查最终有效组合，低层被高层明确替换的
     * 供给不再参与互斥检查、模板 inflate 或 binder 执行。
     */
    fun mergeLayered(
        context: Context,
        direct: XmlSslAttrs,
        pageStyle: XmlSslAttrs,
        global: SslGlobalDefaults,
        theme: XmlSslAttrs = XmlSslAttrs(),
    ): MergedSslConfig {
        // 三处显式来源都为空时，颜色 / 圆角完全由资源决定，昼夜切换需要重新解析。
        val maskFromResources = direct.maskColor == null &&
            pageStyle.maskColor == null && global.maskColor == null && theme.maskColor == null
        val highlightFromResources = direct.highlightColor == null &&
            pageStyle.highlightColor == null && global.highlightColor == null && theme.highlightColor == null
        val radiusFromResources = direct.cornerRadius == null &&
            pageStyle.cornerRadius == null && global.cornerRadius == null && theme.cornerRadius == null

        val useBuiltIn = global.useBuiltInStateLayouts
            ?: SslDefaultValues.USE_BUILT_IN_STATE_LAYOUTS

        // 状态布局：清空屏障截断同类继承；EMPTY / ERROR 清空后仍可按开关使用库内置兜底。
        val emptyRes = resolveLayoutWithBarrier(
            directRes = layoutDeclValue(direct.emptyLayoutResId, direct.emptyLayoutCleared),
            directCleared = direct.emptyLayoutCleared,
            styleRes = layoutDeclValue(pageStyle.emptyLayoutResId, pageStyle.emptyLayoutCleared),
            styleCleared = pageStyle.emptyLayoutCleared,
            themeRes = layoutDeclValue(theme.emptyLayoutResId, theme.emptyLayoutCleared),
            themeCleared = theme.emptyLayoutCleared,
            fallbackResId = if (useBuiltIn) R.layout.ssl_builtin_state_empty else 0,
        )
        val errorRes = resolveLayoutWithBarrier(
            directRes = layoutDeclValue(direct.errorLayoutResId, direct.errorLayoutCleared),
            directCleared = direct.errorLayoutCleared,
            styleRes = layoutDeclValue(pageStyle.errorLayoutResId, pageStyle.errorLayoutCleared),
            styleCleared = pageStyle.errorLayoutCleared,
            themeRes = layoutDeclValue(theme.errorLayoutResId, theme.errorLayoutCleared),
            themeCleared = theme.errorLayoutCleared,
            fallbackResId = if (useBuiltIn) R.layout.ssl_builtin_state_error else 0,
        )
        val loadingRes = resolveLayoutWithBarrier(
            directRes = layoutDeclValue(direct.customLoadingLayoutResId, direct.customLoadingLayoutCleared),
            directCleared = direct.customLoadingLayoutCleared,
            styleRes = layoutDeclValue(pageStyle.customLoadingLayoutResId, pageStyle.customLoadingLayoutCleared),
            styleCleared = pageStyle.customLoadingLayoutCleared,
            themeRes = layoutDeclValue(theme.customLoadingLayoutResId, theme.customLoadingLayoutCleared),
            themeCleared = theme.customLoadingLayoutCleared,
            fallbackResId = 0,
        )

        // 骨架来源 / 供给归一化（私有，含互斥与数量归属）。
        val skeleton = normalizeSkeletonSource(
            direct = direct,
            pageStyle = pageStyle,
            global = global,
            theme = theme,
        )

        val skeletonConfig = SkeletonConfig(
            source = skeleton.source,
            templateLayoutResId = skeleton.templateResId,
            effect = direct.effect ?: pageStyle.effect ?: global.effect ?: theme.effect ?: SslDefaultValues.EFFECT,
            maskColor = direct.maskColor ?: pageStyle.maskColor ?: global.maskColor ?: theme.maskColor
                ?: context.getColor(R.color.ssl_mask_default),
            highlightColor = direct.highlightColor ?: pageStyle.highlightColor
                ?: global.highlightColor ?: theme.highlightColor
                ?: context.getColor(R.color.ssl_highlight_default),
            cornerRadius = direct.cornerRadius ?: pageStyle.cornerRadius
                ?: global.cornerRadius ?: theme.cornerRadius
                ?: context.resources.getDimension(R.dimen.ssl_corner_radius_default),
            animationDurationMillis = direct.animationDurationMillis
                ?: pageStyle.animationDurationMillis
                ?: global.animationDurationMillis ?: theme.animationDurationMillis
                ?: SslDefaultValues.ANIMATION_DURATION_MILLIS,
            direction = direct.direction ?: pageStyle.direction ?: global.direction ?: theme.direction
                ?: SslDefaultValues.DIRECTION,
            angleDegrees = direct.angleDegrees ?: pageStyle.angleDegrees
                ?: global.angleDegrees ?: theme.angleDegrees
                ?: SslDefaultValues.ANGLE_DEGREES,
            pulseMinAlpha = direct.pulseMinAlpha ?: pageStyle.pulseMinAlpha
                ?: global.pulseMinAlpha ?: theme.pulseMinAlpha
                ?: SslDefaultValues.PULSE_MIN_ALPHA,
            pulseMaxAlpha = direct.pulseMaxAlpha ?: pageStyle.pulseMaxAlpha
                ?: global.pulseMaxAlpha ?: theme.pulseMaxAlpha
                ?: SslDefaultValues.PULSE_MAX_ALPHA,
        )
        // 重试入口逐状态合并：页面直接 > 页面 Style > Global > Theme；库默认由调用方在确定布局后补齐。
        // 高优先级空列表截断继承，不做并集。
        val emptyRetry = direct.emptyRetryViewIds
            ?: pageStyle.emptyRetryViewIds
            ?: global.retryConfig?.emptyViewIds
            ?: theme.emptyRetryViewIds
        val errorRetry = direct.errorRetryViewIds
            ?: pageStyle.errorRetryViewIds
            ?: global.retryConfig?.errorViewIds
            ?: theme.errorRetryViewIds
        return MergedSslConfig(
            initialState = direct.initialState ?: pageStyle.initialState
                ?: global.initialState ?: theme.initialState
                ?: SslDefaultValues.INITIAL_STATE,
            useSkeleton = direct.useSkeleton ?: pageStyle.useSkeleton
                ?: global.useSkeleton ?: theme.useSkeleton
                ?: SslDefaultValues.USE_SKELETON,
            skeletonConfig = skeletonConfig,
            skeletonItemLayoutResId = skeleton.itemLayoutResId,
            skeletonItemCount = skeleton.itemCount,
            loadingShowDelayMillis = direct.loadingShowDelayMillis
                ?: pageStyle.loadingShowDelayMillis
                ?: global.loadingShowDelayMillis ?: theme.loadingShowDelayMillis
                ?: SslDefaultValues.LOADING_SHOW_DELAY_MILLIS,
            minimumLoadingDurationMillis = direct.minimumLoadingDurationMillis
                ?: pageStyle.minimumLoadingDurationMillis
                ?: global.minimumLoadingDurationMillis ?: theme.minimumLoadingDurationMillis
                ?: SslDefaultValues.MINIMUM_LOADING_DURATION_MILLIS,
            transitionEffect = direct.transitionEffect ?: pageStyle.transitionEffect
                ?: global.transitionEffect ?: theme.transitionEffect
                ?: SslDefaultValues.TRANSITION_EFFECT,
            transitionDurationMillis = direct.transitionDurationMillis
                ?: pageStyle.transitionDurationMillis
                ?: global.transitionDurationMillis ?: theme.transitionDurationMillis
                ?: SslDefaultValues.TRANSITION_DURATION_MILLIS,
            announceStateChanges = direct.announceStateChanges
                ?: pageStyle.announceStateChanges
                ?: global.announceStateChanges ?: theme.announceStateChanges
                ?: SslDefaultValues.ANNOUNCE_STATE_CHANGES,
            useBuiltInStateLayouts = useBuiltIn,
            colorsFromResources = maskFromResources && highlightFromResources && radiusFromResources,
            emptyRetryViewIds = emptyRetry,
            errorRetryViewIds = errorRetry,
            emptyLayoutResId = emptyRes,
            errorLayoutResId = errorRes,
            customLoadingLayoutResId = loadingRes,
        )
    }

    // region 声明三态与归一化

    private fun layoutDeclValue(resId: Int, cleared: Boolean): Int? {
        if (cleared) return null
        return resId.takeIf { it != 0 }
    }

    /**
     * 可清空布局的跨层解析：高优先级值胜出，显式清空截断同类继承。
     *
     * EMPTY / ERROR 清空后仍按 `fallbackResId` 使用库内置兜底（由调用方传入）；
     * 自定义 Loading 清空后回落 `0`。本函数不处理 T1 / T2 的互斥，只解决同字段继承。
     */
    private fun resolveLayoutWithBarrier(
        directRes: Int?,
        directCleared: Boolean,
        styleRes: Int?,
        styleCleared: Boolean,
        themeRes: Int?,
        themeCleared: Boolean,
        fallbackResId: Int,
    ): Int {
        if (directRes != null) return directRes
        if (directCleared) return fallbackResId
        if (styleRes != null) return styleRes
        if (styleCleared) return fallbackResId
        if (themeRes != null) return themeRes
        // Theme 显式清空同样截断（其下已无更低同类来源），仍可使用内置兜底。
        return fallbackResId
    }

    private sealed interface LayoutDecl {
        data object Undeclared : LayoutDecl
        data object Cleared : LayoutDecl
        data class Value(val resId: Int, val layer: Int) : LayoutDecl
    }

    private sealed interface CountDecl {
        data object Undeclared : CountDecl
        data class Cleared(val layer: Int) : CountDecl
        data class Value(val count: Int, val layer: Int) : CountDecl
    }

    private data class NormalizedSkeleton(
        val source: SkeletonSource,
        val templateResId: Int?,
        val itemLayoutResId: Int,
        val itemCount: Int?,
    )

    private fun t1DeclOf(attrs: XmlSslAttrs, layer: Int): LayoutDecl {
        if (attrs.templateCleared) return LayoutDecl.Cleared
        val v = attrs.templateLayoutResId
        if (v != null && v != 0) return LayoutDecl.Value(v, layer)
        return LayoutDecl.Undeclared
    }

    private fun t2DeclOf(attrs: XmlSslAttrs, layer: Int): LayoutDecl {
        if (attrs.skeletonItemLayoutCleared) return LayoutDecl.Cleared
        val v = attrs.skeletonItemLayoutResId
        if (v != 0) return LayoutDecl.Value(v, layer)
        return LayoutDecl.Undeclared
    }

    private fun countDeclOf(attrs: XmlSslAttrs, layer: Int): CountDecl {
        if (attrs.skeletonItemCountCleared) return CountDecl.Cleared(layer)
        val v = attrs.skeletonItemCount
        if (v != null) return CountDecl.Value(v, layer)
        return CountDecl.Undeclared
    }

    /**
     * 骨架来源与模板供给的归一化：先解决来源，再执行互斥校验。
     *
     * - T1 / T2 分别处理清空屏障，找到各自最高有效声明；屏障以下同类资源不参与选择。
     * - 更高层 CONTENT 可屏蔽低层模板供给及其数量；模板供给高于来源时同时确定 `TEMPLATE`。
     * - 需要模板时 T1 / T2 来源更高者胜出；最高有效同层同时提供两种方式时报错。
     * - 同层 CONTENT + T2 报错，同层 CONTENT + T1 保持许可（CONTENT 生效）。
     * - 数量跟随选中的 item 布局；孤立数量报错，不静默忽略。
     */
    private fun normalizeSkeletonSource(
        direct: XmlSslAttrs,
        pageStyle: XmlSslAttrs,
        global: SslGlobalDefaults,
        theme: XmlSslAttrs,
    ): NormalizedSkeleton {
        // 各自最高有效声明（含屏障）。
        val t1Effective = firstEffectiveLayout(
            listOf(
                t1DeclOf(direct, LAYER_DIRECT),
                t1DeclOf(pageStyle, LAYER_PAGE_STYLE),
                t1DeclOf(theme, LAYER_THEME),
            ),
        )
        val t2Effective = firstEffectiveLayout(
            listOf(
                t2DeclOf(direct, LAYER_DIRECT),
                t2DeclOf(pageStyle, LAYER_PAGE_STYLE),
                t2DeclOf(theme, LAYER_THEME),
            ),
        )
        // 最高有效来源声明。
        val sourceCandidates = listOfNotNull(
            direct.source?.let { it to LAYER_DIRECT },
            pageStyle.source?.let { it to LAYER_PAGE_STYLE },
            global.skeletonSource?.let { it to LAYER_GLOBAL },
            theme.source?.let { it to LAYER_THEME },
        )
        val effectiveSource = sourceCandidates.minByOrNull { it.second }
        val sourceValue = effectiveSource?.first ?: SkeletonSource.CONTENT
        val sourceLayer = effectiveSource?.second ?: LAYER_LIBRARY

        // 同层 T1 + T2 的检查延后到屏蔽判定之后：被高层 CONTENT 明确屏蔽的低层供给
        // 不再参与互斥检查（只检查最终有效组合）。
        val t1Value = t1Effective as? LayoutDecl.Value
        val t2Value = t2Effective as? LayoutDecl.Value
        // 来源与供给的跨层解决。
        if (sourceValue == SkeletonSource.CONTENT) {
            val t2Layer = t2Value?.layer
            val t1Layer = t1Value?.layer
            // 更高层 CONTENT 屏蔽所有低层供给：直接返回 CONTENT，不检查被屏蔽层的内部互斥。
            val minSupplyLayer = listOfNotNull(t1Layer, t2Layer).minOrNull()
            if (minSupplyLayer != null && sourceLayer < minSupplyLayer) {
                checkOrphanCountForNoT2(
                    decisionLayer = sourceLayer,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                return NormalizedSkeleton(
                    source = SkeletonSource.CONTENT,
                    templateResId = null,
                    itemLayoutResId = 0,
                    itemCount = null,
                )
            }
            // 未被屏蔽时再检查最高有效同层 T1 + T2。
            if (t1Value != null && t2Value != null && t1Value.layer == t2Value.layer) {
                throw IllegalArgumentException(
                    "ssl_skeletonTemplate 与 ssl_skeletonItemLayout / skeletonItems 互斥，" +
                        "请只保留其中一种模板供给方式（同层 ${layerName(t1Value.layer)} 同时声明了两者）",
                )
            }
            // 按 T1 / T2 胜出者与来源比较（两者并存时层级更高者胜出）。
            val winnerIsT2 = when {
                t1Value == null && t2Value == null -> null
                t1Value == null -> true
                t2Value == null -> false
                else -> t2Value.layer < t1Value.layer
            }
            if (winnerIsT2 == true) {
                if (sourceLayer == t2Layer) {
                    // 同层显式 CONTENT + T2：硬冲突。
                    throw IllegalArgumentException(
                        "ssl_skeletonSource=content 与 ssl_skeletonItemLayout / skeletonItems 冲突，" +
                            "请将来源改为 template 或移除条目模板（同层 ${layerName(sourceLayer)}）",
                    )
                }
                // 剩余情况必为 T2 高于 CONTENT（屏蔽已在上文返回）：供给胜出，同时确定 TEMPLATE。
                val count = resolveItemCountForSelected(
                    selectedLayer = t2Layer!!,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                return NormalizedSkeleton(
                    source = SkeletonSource.TEMPLATE,
                    templateResId = null,
                    itemLayoutResId = t2Value!!.resId,
                    itemCount = count,
                )
            }
            // 无 T2 或 T1 胜出：检查 T1 与 CONTENT 的关系。
            if (t1Layer != null && winnerIsT2 != true) {
                if (sourceLayer == t1Layer) {
                    // 同层 CONTENT + T1：保持许可，CONTENT 生效，T1 作为未启用声明保留。
                    // 为避免被误用为模板，归一化结果不携带 T1。
                    return NormalizedSkeleton(
                        source = SkeletonSource.CONTENT,
                        templateResId = null,
                        itemLayoutResId = 0,
                        itemCount = null,
                    )
                }
                // 剩余情况必为 T1 高于 CONTENT：覆盖为 TEMPLATE + T1。
                checkOrphanCountForNoT2AllowsT1(
                    t1Layer = t1Layer,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                return NormalizedSkeleton(
                    source = SkeletonSource.TEMPLATE,
                    templateResId = t1Value!!.resId,
                    itemLayoutResId = 0,
                    itemCount = null,
                )
            }
            // CONTENT 且无任何供给：合法的内容取形，检查孤立数量。
            checkOrphanCountForNoT2(
                decisionLayer = sourceLayer,
                direct = direct,
                pageStyle = pageStyle,
                theme = theme,
            )
            return NormalizedSkeleton(
                source = SkeletonSource.CONTENT,
                templateResId = null,
                itemLayoutResId = 0,
                itemCount = null,
            )
        } else {
            // 来源 TEMPLATE（含库默认被供给覆盖的情况同样走此分支；库默认 CONTENT 无供给时已在上分支返回）。
            // 若来源为显式 TEMPLATE，允许继承低层有效 T1 / T2。
            // TEMPLATE 不屏蔽供给，此处检查同层双供给。
            if (t1Value != null && t2Value != null && t1Value.layer == t2Value.layer) {
                throw IllegalArgumentException(
                    "ssl_skeletonTemplate 与 ssl_skeletonItemLayout / skeletonItems 互斥，" +
                        "请只保留其中一种模板供给方式（同层 ${layerName(t1Value.layer)} 同时声明了两者）",
                )
            }
            if (t1Value == null && t2Value == null) {
                // 没有任何有效供给：报告缺配（清空后亦不再继承）。
                checkOrphanCountForNoT2(
                    decisionLayer = sourceLayer,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                throw IllegalArgumentException(
                    "ssl_skeletonSource=template 时必须提供 ssl_skeletonTemplate 或 " +
                        "ssl_skeletonItemLayout 其中一种模板供给方式",
                )
            }
            // T1 / T2 中来源更高者胜出（同层双供给已在上文报错）。
            val winnerIsT2 = when {
                t1Value == null -> true
                t2Value == null -> false
                else -> t2Value.layer < t1Value.layer
            }
            if (winnerIsT2) {
                val count = resolveItemCountForSelected(
                    selectedLayer = t2Value!!.layer,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                return NormalizedSkeleton(
                    source = SkeletonSource.TEMPLATE,
                    templateResId = null,
                    itemLayoutResId = t2Value.resId,
                    itemCount = count,
                )
            } else {
                // 选中 T1：丢弃被覆盖的低层 item 及数量；若仍有高优先级孤立数量则报错。
                checkOrphanCountForNoT2AllowsT1(
                    t1Layer = t1Value!!.layer,
                    direct = direct,
                    pageStyle = pageStyle,
                    theme = theme,
                )
                return NormalizedSkeleton(
                    source = SkeletonSource.TEMPLATE,
                    templateResId = t1Value.resId,
                    itemLayoutResId = 0,
                    itemCount = null,
                )
            }
        }
    }

    private fun firstEffectiveLayout(decls: List<LayoutDecl>): LayoutDecl {
        for (d in decls) {
            when (d) {
                is LayoutDecl.Value -> return d
                is LayoutDecl.Cleared -> return d
                is LayoutDecl.Undeclared -> continue
            }
        }
        return LayoutDecl.Undeclared
    }

    /**
     * 数量跟随选中的 item 布局：只看选中层同层或更高层的数量声明。
     *
     * - 没有数量时使用默认 8，不继承更低层旧布局的数量。
     * - 更高层显式 `@empty` 重置时恢复默认 8，低层数量不能重新出现。
     * - 有效数量必须大于 0。
     */
    private fun resolveItemCountForSelected(
        selectedLayer: Int,
        direct: XmlSslAttrs,
        pageStyle: XmlSslAttrs,
        theme: XmlSslAttrs,
    ): Int {
        val candidates = mutableListOf<CountDecl>()
        if (LAYER_DIRECT <= selectedLayer) candidates += countDeclOf(direct, LAYER_DIRECT)
        if (LAYER_PAGE_STYLE <= selectedLayer) candidates += countDeclOf(pageStyle, LAYER_PAGE_STYLE)
        if (LAYER_THEME <= selectedLayer) candidates += countDeclOf(theme, LAYER_THEME)
        // 高优先级在前：DIRECT > PAGE_STYLE > THEME。
        val ordered = candidates.sortedBy {
            when (it) {
                is CountDecl.Value -> it.layer
                is CountDecl.Cleared -> it.layer
                is CountDecl.Undeclared -> Int.MAX_VALUE
            }
        }
        for (d in ordered) {
            when (d) {
                is CountDecl.Value -> {
                    require(d.count > 0) {
                        "ssl_skeletonItemCount 必须大于 0，当前=${d.count}（来源 ${layerName(d.layer)}）"
                    }
                    return d.count
                }
                is CountDecl.Cleared -> return SkeletonItemSpec.DEFAULT_COUNT
                is CountDecl.Undeclared -> continue
            }
        }
        return SkeletonItemSpec.DEFAULT_COUNT
    }

    /**
     * 最终无 T2 时的孤立数量检查：决定层及其以上仍有有效数量声明则报错。
     *
     * 被高层覆盖的低层数量不进入最终配置，也不触发本错误。
     */
    private fun checkOrphanCountForNoT2(
        decisionLayer: Int,
        direct: XmlSslAttrs,
        pageStyle: XmlSslAttrs,
        theme: XmlSslAttrs,
    ) {
        val candidates = mutableListOf<CountDecl>()
        if (LAYER_DIRECT <= decisionLayer) candidates += countDeclOf(direct, LAYER_DIRECT)
        if (LAYER_PAGE_STYLE <= decisionLayer) candidates += countDeclOf(pageStyle, LAYER_PAGE_STYLE)
        if (LAYER_THEME <= decisionLayer) candidates += countDeclOf(theme, LAYER_THEME)
        val ordered = candidates.sortedBy {
            when (it) {
                is CountDecl.Value -> it.layer
                is CountDecl.Cleared -> it.layer
                is CountDecl.Undeclared -> Int.MAX_VALUE
            }
        }
        for (d in ordered) {
            when (d) {
                is CountDecl.Value -> throw IllegalArgumentException(
                    "ssl_skeletonItemCount 需要配合 ssl_skeletonItemLayout 使用，" +
                        "请先设置条目布局（孤立数量来源 ${layerName(d.layer)}）",
                )
                is CountDecl.Cleared -> return
                is CountDecl.Undeclared -> continue
            }
        }
    }

    private fun checkOrphanCountForNoT2AllowsT1(
        t1Layer: Int,
        direct: XmlSslAttrs,
        pageStyle: XmlSslAttrs,
        theme: XmlSslAttrs,
    ) = checkOrphanCountForNoT2(decisionLayer = t1Layer, direct = direct, pageStyle = pageStyle, theme = theme)

    // endregion

    private fun combinePageForLegacy(direct: XmlSslAttrs, pageStyle: XmlSslAttrs): XmlSslAttrs {
        // 旧合并入口的页面层：直接属性优先，Style 填补未声明字段；清空标记同样高优先。
        return XmlSslAttrs(
            emptyLayoutResId = if (direct.emptyLayoutResId != 0 || direct.emptyLayoutCleared) {
                direct.emptyLayoutResId
            } else {
                pageStyle.emptyLayoutResId
            },
            errorLayoutResId = if (direct.errorLayoutResId != 0 || direct.errorLayoutCleared) {
                direct.errorLayoutResId
            } else {
                pageStyle.errorLayoutResId
            },
            customLoadingLayoutResId = if (direct.customLoadingLayoutResId != 0 || direct.customLoadingLayoutCleared) {
                direct.customLoadingLayoutResId
            } else {
                pageStyle.customLoadingLayoutResId
            },
            skeletonItemLayoutResId = if (direct.skeletonItemLayoutResId != 0 || direct.skeletonItemLayoutCleared) {
                direct.skeletonItemLayoutResId
            } else {
                pageStyle.skeletonItemLayoutResId
            },
            skeletonItemCount = if (direct.skeletonItemCount != null || direct.skeletonItemCountCleared) {
                direct.skeletonItemCount
            } else {
                pageStyle.skeletonItemCount
            },
            emptyRetryViewIds = direct.emptyRetryViewIds ?: pageStyle.emptyRetryViewIds,
            errorRetryViewIds = direct.errorRetryViewIds ?: pageStyle.errorRetryViewIds,
            initialState = direct.initialState ?: pageStyle.initialState,
            useSkeleton = direct.useSkeleton ?: pageStyle.useSkeleton,
            source = direct.source ?: pageStyle.source,
            templateLayoutResId = if (direct.templateLayoutResId != null || direct.templateCleared) {
                direct.templateLayoutResId
            } else {
                pageStyle.templateLayoutResId
            },
            effect = direct.effect ?: pageStyle.effect,
            maskColor = direct.maskColor ?: pageStyle.maskColor,
            highlightColor = direct.highlightColor ?: pageStyle.highlightColor,
            cornerRadius = direct.cornerRadius ?: pageStyle.cornerRadius,
            animationDurationMillis = direct.animationDurationMillis ?: pageStyle.animationDurationMillis,
            direction = direct.direction ?: pageStyle.direction,
            angleDegrees = direct.angleDegrees ?: pageStyle.angleDegrees,
            pulseMinAlpha = direct.pulseMinAlpha ?: pageStyle.pulseMinAlpha,
            pulseMaxAlpha = direct.pulseMaxAlpha ?: pageStyle.pulseMaxAlpha,
            loadingShowDelayMillis = direct.loadingShowDelayMillis ?: pageStyle.loadingShowDelayMillis,
            minimumLoadingDurationMillis = direct.minimumLoadingDurationMillis
                ?: pageStyle.minimumLoadingDurationMillis,
            transitionEffect = direct.transitionEffect ?: pageStyle.transitionEffect,
            transitionDurationMillis = direct.transitionDurationMillis ?: pageStyle.transitionDurationMillis,
            announceStateChanges = direct.announceStateChanges ?: pageStyle.announceStateChanges,
            templateCleared = direct.templateCleared || (isUndeclaredDirect(direct) && pageStyle.templateCleared),
            skeletonItemLayoutCleared = direct.skeletonItemLayoutCleared ||
                (direct.skeletonItemLayoutResId == 0 && pageStyle.skeletonItemLayoutCleared),
            skeletonItemCountCleared = direct.skeletonItemCountCleared ||
                (direct.skeletonItemCount == null && pageStyle.skeletonItemCountCleared),
            emptyLayoutCleared = direct.emptyLayoutCleared ||
                (direct.emptyLayoutResId == 0 && pageStyle.emptyLayoutCleared),
            errorLayoutCleared = direct.errorLayoutCleared ||
                (direct.errorLayoutResId == 0 && pageStyle.errorLayoutCleared),
            customLoadingLayoutCleared = direct.customLoadingLayoutCleared ||
                (direct.customLoadingLayoutResId == 0 && pageStyle.customLoadingLayoutCleared),
        )
    }

    private fun isUndeclaredDirect(direct: XmlSslAttrs): Boolean =
        direct.templateLayoutResId == null && !direct.templateCleared

    /**
     * 从 TypedArray 解析 XML 属性。字段为 null 表示 XML 未显式设置，
     * 由合并链上层（Global / Theme / 库内置）填补。
     *
     * 属性未显式设置时 [TypedArray] 返回默认值，此处通过
     * [TypedArray.hasValue] 区分「未设置」与「设为默认值」。
     */
    fun parseXmlAttrs(
        context: Context,
        a: TypedArray,
        explicitIndices: Set<Int>? = null,
    ): XmlSslAttrs {
        fun has(index: Int): Boolean = a.hasValue(index) &&
            (explicitIndices == null || index in explicitIndices)
        fun resource(index: Int): Int = if (has(index)) a.getResourceId(index, 0) else 0

        val templateResId = resource(R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate).takeIf { it != 0 }
        val itemLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemLayout)

        val source = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonSource)) {
            when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonSource, 0)) {
                1 -> SkeletonSource.TEMPLATE
                else -> SkeletonSource.CONTENT
            }
        } else {
            null
        }
        val effect = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonEffect)) {
            when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonEffect, 1)) {
                0 -> SkeletonEffect.SOLID
                2 -> SkeletonEffect.PULSE
                else -> SkeletonEffect.SHIMMER
            }
        } else {
            null
        }
        val direction = if (has(R.styleable.SslStateSkeletonLayout_ssl_shimmerDirection)) {
            when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_shimmerDirection, 0)) {
                1 -> SkeletonDirection.END_TO_START
                else -> SkeletonDirection.START_TO_END
            }
        } else {
            null
        }
        val initialState = if (has(R.styleable.SslStateSkeletonLayout_ssl_initialState)) {
            when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_initialState, 0)) {
                1 -> StateLayoutState.LOADING
                2 -> StateLayoutState.EMPTY
                3 -> StateLayoutState.ERROR
                else -> StateLayoutState.CONTENT
            }
        } else {
            null
        }
        val transition = if (has(R.styleable.SslStateSkeletonLayout_ssl_transitionEffect)) {
            when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_transitionEffect, 1)) {
                0 -> StateTransitionEffect.NONE
                else -> StateTransitionEffect.CROSSFADE
            }
        } else {
            null
        }

        return XmlSslAttrs(
            emptyLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_emptyLayout),
            errorLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_errorLayout),
            customLoadingLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_loadingLayout),
            skeletonItemLayoutResId = itemLayoutResId,
            skeletonItemCount = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount)) {
                a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount, SkeletonItemSpec.DEFAULT_COUNT)
            } else {
                null
            },
            emptyRetryViewIds = parseRetryIdsArray(
                context,
                a,
                R.styleable.SslStateSkeletonLayout_ssl_emptyRetryViewIds,
                "ssl_emptyRetryViewIds",
                ::has,
            ),
            errorRetryViewIds = parseRetryIdsArray(
                context,
                a,
                R.styleable.SslStateSkeletonLayout_ssl_errorRetryViewIds,
                "ssl_errorRetryViewIds",
                ::has,
            ),
            initialState = initialState,
            useSkeleton = if (has(R.styleable.SslStateSkeletonLayout_ssl_useSkeleton)) {
                a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_useSkeleton, false)
            } else {
                null
            },
            source = source,
            templateLayoutResId = templateResId,
            effect = effect,
            maskColor = if (has(R.styleable.SslStateSkeletonLayout_ssl_maskColor)) {
                a.getColor(R.styleable.SslStateSkeletonLayout_ssl_maskColor, 0)
            } else {
                null
            },
            highlightColor = if (has(R.styleable.SslStateSkeletonLayout_ssl_highlightColor)) {
                a.getColor(R.styleable.SslStateSkeletonLayout_ssl_highlightColor, 0)
            } else {
                null
            },
            cornerRadius = if (has(R.styleable.SslStateSkeletonLayout_ssl_cornerRadius)) {
                a.getDimension(R.styleable.SslStateSkeletonLayout_ssl_cornerRadius, 0f)
            } else {
                null
            },
            animationDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_animationDuration)) {
                a.getInt(
                    R.styleable.SslStateSkeletonLayout_ssl_animationDuration,
                    SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS.toInt(),
                ).toLong()
            } else {
                null
            },
            direction = direction,
            angleDegrees = if (has(R.styleable.SslStateSkeletonLayout_ssl_shimmerAngle)) {
                a.getInt(
                    R.styleable.SslStateSkeletonLayout_ssl_shimmerAngle,
                    SkeletonConfig.DEFAULT_ANGLE_DEGREES,
                )
            } else {
                null
            },
            pulseMinAlpha = if (has(R.styleable.SslStateSkeletonLayout_ssl_pulseMinAlpha)) {
                a.getFloat(
                    R.styleable.SslStateSkeletonLayout_ssl_pulseMinAlpha,
                    SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA,
                )
            } else {
                null
            },
            pulseMaxAlpha = if (has(R.styleable.SslStateSkeletonLayout_ssl_pulseMaxAlpha)) {
                a.getFloat(
                    R.styleable.SslStateSkeletonLayout_ssl_pulseMaxAlpha,
                    SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA,
                )
            } else {
                null
            },
            loadingShowDelayMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_loadingShowDelay)) {
                a.getInt(R.styleable.SslStateSkeletonLayout_ssl_loadingShowDelay, 0).toLong()
            } else {
                null
            },
            minimumLoadingDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_minLoadingDuration)) {
                a.getInt(
                    R.styleable.SslStateSkeletonLayout_ssl_minLoadingDuration,
                    com.sfyc.ssl.internal.StateCoordinator
                        .DEFAULT_MINIMUM_LOADING_DURATION_MILLIS.toInt(),
                ).toLong()
            } else {
                null
            },
            transitionEffect = transition,
            transitionDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_transitionDuration)) {
                a.getInt(
                    R.styleable.SslStateSkeletonLayout_ssl_transitionDuration,
                    com.sfyc.ssl.StateSkeletonLayout
                        .DEFAULT_TRANSITION_DURATION_MILLIS.toInt(),
                ).toLong()
            } else {
                null
            },
            announceStateChanges = if (has(R.styleable.SslStateSkeletonLayout_ssl_announceStateChanges)) {
                a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_announceStateChanges, true)
            } else {
                null
            },
        )
    }

    private fun parseThemeLayer(context: Context, a: TypedArray): XmlSslAttrs {
        fun hasValue(i: Int): Boolean = try { a.hasValue(i) } catch (_: Exception) { false }
        fun hasEmpty(i: Int): Boolean = try { a.hasValueOrEmpty(i) } catch (_: Exception) { hasValue(i) }
        fun isCleared(i: Int): Boolean = hasEmpty(i) && !hasValue(i)
        fun resOrZero(i: Int, clearable: Boolean): Int {
            if (!hasEmpty(i)) return 0
            if (!hasValue(i)) return 0
            return try { a.getResourceId(i, 0) } catch (_: Exception) { 0 }
        }

        val templateIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate
        val itemLayoutIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemLayout
        val itemCountIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount
        val emptyIdx = R.styleable.SslStateSkeletonLayout_ssl_emptyLayout
        val errorIdx = R.styleable.SslStateSkeletonLayout_ssl_errorLayout
        val loadingIdx = R.styleable.SslStateSkeletonLayout_ssl_loadingLayout

        val base = parseXmlAttrsWithHas(context, a, ::hasValue)
        return base.copy(
            templateLayoutResId = resOrZero(templateIdx, true).takeIf { it != 0 },
            templateCleared = isCleared(templateIdx),
            skeletonItemLayoutResId = resOrZero(itemLayoutIdx, true),
            skeletonItemLayoutCleared = isCleared(itemLayoutIdx),
            skeletonItemCount = if (hasValue(itemCountIdx)) {
                try {
                    a.getInt(itemCountIdx, SkeletonItemSpec.DEFAULT_COUNT)
                } catch (_: Exception) { SkeletonItemSpec.DEFAULT_COUNT }
            } else {
                null
            },
            skeletonItemCountCleared = isCleared(itemCountIdx),
            emptyLayoutResId = resOrZero(emptyIdx, true),
            emptyLayoutCleared = isCleared(emptyIdx),
            errorLayoutResId = resOrZero(errorIdx, true),
            errorLayoutCleared = isCleared(errorIdx),
            customLoadingLayoutResId = resOrZero(loadingIdx, true),
            customLoadingLayoutCleared = isCleared(loadingIdx),
        )
    }

    private fun parseDirectLayer(context: Context, merged: TypedArray, directIndices: Set<Int>): XmlSslAttrs {
        fun hasDirect(i: Int): Boolean = i in directIndices && try { merged.hasValue(i) } catch (_: Exception) { false }
        // 直接属性存在但无值（@null / @empty）即显式清空。
        fun isDirectCleared(i: Int): Boolean {
            if (i !in directIndices) return false
            return try { !merged.hasValue(i) } catch (_: Exception) { true }
        }
        fun resDirect(i: Int): Int {
            if (!hasDirect(i)) return 0
            return try { merged.getResourceId(i, 0) } catch (_: Exception) { 0 }
        }
        val templateIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate
        val itemLayoutIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemLayout
        val itemCountIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount
        val emptyIdx = R.styleable.SslStateSkeletonLayout_ssl_emptyLayout
        val errorIdx = R.styleable.SslStateSkeletonLayout_ssl_errorLayout
        val loadingIdx = R.styleable.SslStateSkeletonLayout_ssl_loadingLayout

        val base = parseXmlAttrsWithHas(context, merged, ::hasDirect)
        // 直接数量：存在即读取，@empty/@null 走清空分支（hasDirect 为 false 但在集合中）。
        val countValue = if (hasDirect(itemCountIdx)) {
            try { merged.getInt(itemCountIdx, SkeletonItemSpec.DEFAULT_COUNT) } catch (_: Exception) {
                SkeletonItemSpec.DEFAULT_COUNT
            }
        } else {
            null
        }
        return base.copy(
            emptyLayoutResId = resDirect(emptyIdx),
            emptyLayoutCleared = isDirectCleared(emptyIdx),
            errorLayoutResId = resDirect(errorIdx),
            errorLayoutCleared = isDirectCleared(errorIdx),
            customLoadingLayoutResId = resDirect(loadingIdx),
            customLoadingLayoutCleared = isDirectCleared(loadingIdx),
            skeletonItemLayoutResId = resDirect(itemLayoutIdx),
            skeletonItemLayoutCleared = isDirectCleared(itemLayoutIdx),
            skeletonItemCount = countValue,
            skeletonItemCountCleared = isDirectCleared(itemCountIdx),
            templateLayoutResId = resDirect(templateIdx).takeIf { it != 0 },
            templateCleared = isDirectCleared(templateIdx),
        )
    }

    private fun parseStyleLayer(context: Context, real: TypedArray, memberEmpty: Set<Int>): XmlSslAttrs {
        fun hasReal(i: Int): Boolean = try { real.hasValue(i) } catch (_: Exception) { false }
        fun hasRealEmpty(i: Int): Boolean = try { real.hasValueOrEmpty(i) } catch (_: Exception) { hasReal(i) }
        fun inStyle(i: Int): Boolean = i in memberEmpty
        fun hasStyleValue(i: Int): Boolean = inStyle(i) && hasReal(i)
        fun isStyleCleared(i: Int): Boolean {
            if (!inStyle(i)) return false
            // Style 统一用 @empty 表达清空；@null 不承诺截断语义，按未声明处理。
            // 因此仅当真实值侧同样呈现“有空无值”时才视为清空。
            return !hasReal(i) && hasRealEmpty(i)
        }
        fun resStyle(i: Int): Int {
            if (!hasStyleValue(i)) return 0
            return try { real.getResourceId(i, 0) } catch (_: Exception) { 0 }
        }
        fun hasForBase(i: Int): Boolean = hasStyleValue(i)

        val templateIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate
        val itemLayoutIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemLayout
        val itemCountIdx = R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount
        val emptyIdx = R.styleable.SslStateSkeletonLayout_ssl_emptyLayout
        val errorIdx = R.styleable.SslStateSkeletonLayout_ssl_errorLayout
        val loadingIdx = R.styleable.SslStateSkeletonLayout_ssl_loadingLayout

        val base = parseXmlAttrsWithHas(context, real, ::hasForBase)
        val countValue = if (hasStyleValue(itemCountIdx)) {
            try { real.getInt(itemCountIdx, SkeletonItemSpec.DEFAULT_COUNT) } catch (_: Exception) {
                SkeletonItemSpec.DEFAULT_COUNT
            }
        } else {
            null
        }
        return base.copy(
            emptyLayoutResId = resStyle(emptyIdx),
            emptyLayoutCleared = isStyleCleared(emptyIdx),
            errorLayoutResId = resStyle(errorIdx),
            errorLayoutCleared = isStyleCleared(errorIdx),
            customLoadingLayoutResId = resStyle(loadingIdx),
            customLoadingLayoutCleared = isStyleCleared(loadingIdx),
            skeletonItemLayoutResId = resStyle(itemLayoutIdx),
            skeletonItemLayoutCleared = isStyleCleared(itemLayoutIdx),
            skeletonItemCount = countValue,
            skeletonItemCountCleared = isStyleCleared(itemCountIdx),
            templateLayoutResId = resStyle(templateIdx).takeIf { it != 0 },
            templateCleared = isStyleCleared(templateIdx),
        )
    }

    private fun parseXmlAttrsWithHas(
        context: Context,
        a: TypedArray,
        has: (Int) -> Boolean,
    ): XmlSslAttrs {
        fun resource(index: Int): Int = if (has(index)) {
            try { a.getResourceId(index, 0) } catch (_: Exception) { 0 }
        } else {
            0
        }
        val templateResId = resource(R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate).takeIf { it != 0 }
        val itemLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemLayout)
        val source = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonSource)) {
            try {
                when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonSource, 0)) {
                    1 -> SkeletonSource.TEMPLATE
                    else -> SkeletonSource.CONTENT
                }
            } catch (_: Exception) { SkeletonSource.CONTENT }
        } else {
            null
        }
        val effect = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonEffect)) {
            try {
                when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonEffect, 1)) {
                    0 -> SkeletonEffect.SOLID
                    2 -> SkeletonEffect.PULSE
                    else -> SkeletonEffect.SHIMMER
                }
            } catch (_: Exception) { SkeletonEffect.SHIMMER }
        } else {
            null
        }
        val direction = if (has(R.styleable.SslStateSkeletonLayout_ssl_shimmerDirection)) {
            try {
                when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_shimmerDirection, 0)) {
                    1 -> SkeletonDirection.END_TO_START
                    else -> SkeletonDirection.START_TO_END
                }
            } catch (_: Exception) { SkeletonDirection.START_TO_END }
        } else {
            null
        }
        val initialState = if (has(R.styleable.SslStateSkeletonLayout_ssl_initialState)) {
            try {
                when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_initialState, 0)) {
                    1 -> StateLayoutState.LOADING
                    2 -> StateLayoutState.EMPTY
                    3 -> StateLayoutState.ERROR
                    else -> StateLayoutState.CONTENT
                }
            } catch (_: Exception) { StateLayoutState.CONTENT }
        } else {
            null
        }
        val transition = if (has(R.styleable.SslStateSkeletonLayout_ssl_transitionEffect)) {
            try {
                when (a.getInt(R.styleable.SslStateSkeletonLayout_ssl_transitionEffect, 1)) {
                    0 -> StateTransitionEffect.NONE
                    else -> StateTransitionEffect.CROSSFADE
                }
            } catch (_: Exception) { StateTransitionEffect.CROSSFADE }
        } else {
            null
        }
        return XmlSslAttrs(
            emptyLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_emptyLayout),
            errorLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_errorLayout),
            customLoadingLayoutResId = resource(R.styleable.SslStateSkeletonLayout_ssl_loadingLayout),
            skeletonItemLayoutResId = itemLayoutResId,
            skeletonItemCount = if (has(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount)) {
                try {
                    a.getInt(R.styleable.SslStateSkeletonLayout_ssl_skeletonItemCount, SkeletonItemSpec.DEFAULT_COUNT)
                } catch (_: Exception) { SkeletonItemSpec.DEFAULT_COUNT }
            } else {
                null
            },
            emptyRetryViewIds = try {
                parseRetryIdsArray(context, a, R.styleable.SslStateSkeletonLayout_ssl_emptyRetryViewIds, "ssl_emptyRetryViewIds", has)
            } catch (e: IllegalArgumentException) { throw e } catch (_: Exception) { null },
            errorRetryViewIds = try {
                parseRetryIdsArray(context, a, R.styleable.SslStateSkeletonLayout_ssl_errorRetryViewIds, "ssl_errorRetryViewIds", has)
            } catch (e: IllegalArgumentException) { throw e } catch (_: Exception) { null },
            initialState = initialState,
            useSkeleton = if (has(R.styleable.SslStateSkeletonLayout_ssl_useSkeleton)) {
                try { a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_useSkeleton, false) } catch (_: Exception) { false }
            } else {
                null
            },
            source = source,
            templateLayoutResId = templateResId,
            effect = effect,
            maskColor = if (has(R.styleable.SslStateSkeletonLayout_ssl_maskColor)) {
                try { a.getColor(R.styleable.SslStateSkeletonLayout_ssl_maskColor, 0) } catch (_: Exception) { null }
            } else {
                null
            },
            highlightColor = if (has(R.styleable.SslStateSkeletonLayout_ssl_highlightColor)) {
                try { a.getColor(R.styleable.SslStateSkeletonLayout_ssl_highlightColor, 0) } catch (_: Exception) { null }
            } else {
                null
            },
            cornerRadius = if (has(R.styleable.SslStateSkeletonLayout_ssl_cornerRadius)) {
                try { a.getDimension(R.styleable.SslStateSkeletonLayout_ssl_cornerRadius, 0f) } catch (_: Exception) { null }
            } else {
                null
            },
            animationDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_animationDuration)) {
                try {
                    a.getInt(
                        R.styleable.SslStateSkeletonLayout_ssl_animationDuration,
                        SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS.toInt(),
                    ).toLong()
                } catch (_: Exception) { null }
            } else {
                null
            },
            direction = direction,
            angleDegrees = if (has(R.styleable.SslStateSkeletonLayout_ssl_shimmerAngle)) {
                try {
                    a.getInt(
                        R.styleable.SslStateSkeletonLayout_ssl_shimmerAngle,
                        SkeletonConfig.DEFAULT_ANGLE_DEGREES,
                    )
                } catch (_: Exception) { null }
            } else {
                null
            },
            pulseMinAlpha = if (has(R.styleable.SslStateSkeletonLayout_ssl_pulseMinAlpha)) {
                try {
                    a.getFloat(
                        R.styleable.SslStateSkeletonLayout_ssl_pulseMinAlpha,
                        SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA,
                    )
                } catch (_: Exception) { null }
            } else {
                null
            },
            pulseMaxAlpha = if (has(R.styleable.SslStateSkeletonLayout_ssl_pulseMaxAlpha)) {
                try {
                    a.getFloat(
                        R.styleable.SslStateSkeletonLayout_ssl_pulseMaxAlpha,
                        SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA,
                    )
                } catch (_: Exception) { null }
            } else {
                null
            },
            loadingShowDelayMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_loadingShowDelay)) {
                try { a.getInt(R.styleable.SslStateSkeletonLayout_ssl_loadingShowDelay, 0).toLong() } catch (_: Exception) { null }
            } else {
                null
            },
            minimumLoadingDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_minLoadingDuration)) {
                try {
                    a.getInt(
                        R.styleable.SslStateSkeletonLayout_ssl_minLoadingDuration,
                        com.sfyc.ssl.internal.StateCoordinator
                            .DEFAULT_MINIMUM_LOADING_DURATION_MILLIS.toInt(),
                    ).toLong()
                } catch (_: Exception) { null }
            } else {
                null
            },
            transitionEffect = transition,
            transitionDurationMillis = if (has(R.styleable.SslStateSkeletonLayout_ssl_transitionDuration)) {
                try {
                    a.getInt(
                        R.styleable.SslStateSkeletonLayout_ssl_transitionDuration,
                        com.sfyc.ssl.StateSkeletonLayout
                            .DEFAULT_TRANSITION_DURATION_MILLIS.toInt(),
                    ).toLong()
                } catch (_: Exception) { null }
            } else {
                null
            },
            announceStateChanges = if (has(R.styleable.SslStateSkeletonLayout_ssl_announceStateChanges)) {
                try { a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_announceStateChanges, true) } catch (_: Exception) { null }
            } else {
                null
            },
        )
    }

    /**
     * 解析重试 ID 数组属性：保留 null / 空列表 / 非空三种状态。
     *
     * - 未声明返回 `null`（继承）。
     * - 空数组返回空列表（显式关闭）。
     * - 条目必须为 ID 资源，否则抛出来自属性名与下标的异常。
     */
    private fun parseRetryIdsArray(
        context: Context,
        a: TypedArray,
        index: Int,
        attrName: String,
        has: (Int) -> Boolean,
    ): List<Int>? {
        if (!has(index)) return null
        val arrayResId = try { a.getResourceId(index, 0) } catch (_: Exception) { 0 }
        require(arrayResId != 0) { "$attrName 必须指向 <array> 资源" }
        val outerType = try {
            context.resources.getResourceTypeName(arrayResId)
        } catch (e: Exception) {
            throw IllegalArgumentException("$attrName 指向的资源无效：$arrayResId", e)
        }
        require(outerType == "array") { "$attrName 必须指向 <array> 资源，当前为 $outerType" }
        val typed = context.resources.obtainTypedArray(arrayResId)
        try {
            if (typed.length() == 0) return emptyList()
            val raw = ArrayList<Int>(typed.length())
            for (i in 0 until typed.length()) {
                val id = typed.getResourceId(i, 0)
                if (id == 0) {
                    throw IllegalArgumentException("$attrName[$i] 必须为 @id/... 资源，混入了字符串、数值、颜色、布局或空条目")
                }
                val entryType = try {
                    context.resources.getResourceTypeName(id)
                } catch (e: Exception) {
                    throw IllegalArgumentException("$attrName[$i] 指向的资源无效", e)
                }
                require(entryType == "id") { "$attrName[$i] 必须为 ID 资源，当前为 $entryType" }
                raw += id
            }
            // 复制、去重与数量／ID 校验集中在配置对象，共用同一规则。
            return normalizeRetryIds(raw, attrName) ?: emptyList()
        } finally {
            typed.recycle()
        }
    }
}

/**
 * XML 属性解析结果。字段为 null 表示该层未显式设置。
 *
 * 可清空的布局资源（整页模板、条目布局、空态、错误态、自定义加载）与条目数量保留三种声明状态：
 * - 未声明：字段为 `0` / `null` 且对应 `*Cleared` 为 false，继续查找低优先级来源；
 * - 有值：字段为明确资源，截断同字段继承；
 * - 显式清空：对应 `*Cleared` 为 true，无资源且截断同字段继承。
 *   XML 直接属性支持 `@null` / `@empty`；页面 Style / Theme Style 统一用 `@empty`。
 *   `ssl_skeletonItemCount="@empty"` 表示重置数量继承：最终采用 T2 时使用默认 8。
 *
 * 布局资源类属性（emptyLayout / errorLayout / loadingLayout / skeletonTemplate）
 * 不参与全局覆盖。重试 ID 列表按状态参与 Global / Theme 合并：null 表示未声明（继承），
 * 空列表表示显式关闭，非空表示替换该状态的完整入口。
 */
internal data class XmlSslAttrs(
    val emptyLayoutResId: Int = 0,
    val errorLayoutResId: Int = 0,
    val customLoadingLayoutResId: Int = 0,
    val skeletonItemLayoutResId: Int = 0,
    val skeletonItemCount: Int? = null,
    val emptyRetryViewIds: List<Int>? = null,
    val errorRetryViewIds: List<Int>? = null,
    val initialState: StateLayoutState? = null,
    val useSkeleton: Boolean? = null,
    val source: SkeletonSource? = null,
    val templateLayoutResId: Int? = null,
    val effect: SkeletonEffect? = null,
    val maskColor: Int? = null,
    val highlightColor: Int? = null,
    val cornerRadius: Float? = null,
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
    /** 整页模板显式清空（`@null` / `@empty` 或 Style `@empty`），截断低层同类继承。 */
    val templateCleared: Boolean = false,
    /** 条目布局显式清空，同时解除该 item 的低层数量。 */
    val skeletonItemLayoutCleared: Boolean = false,
    /** 条目数量显式重置（`@empty`），低层数量不能重新出现。 */
    val skeletonItemCountCleared: Boolean = false,
    /** 空态布局显式清空，不再继承 Theme 宿主布局（仍可使用库内置兜底）。 */
    val emptyLayoutCleared: Boolean = false,
    /** 错误态布局显式清空，不再继承 Theme 宿主布局（仍可使用库内置兜底）。 */
    val errorLayoutCleared: Boolean = false,
    /** 自定义加载布局显式清空，不再继承 Theme 布局。 */
    val customLoadingLayoutCleared: Boolean = false,
)
