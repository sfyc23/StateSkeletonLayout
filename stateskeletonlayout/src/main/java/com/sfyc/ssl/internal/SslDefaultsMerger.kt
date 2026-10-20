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
)

/**
 * 按优先级链合并最终配置的内部工具。
 *
 * 优先级链（低 → 高）：
 * ```
 * 库内置常量 < Theme Attribute < Global Config < XML 属性 < 运行时 setter
 * ```
 * 同一属性在 Theme 与 Global 同时设置时，Global 优先。
 *
 * 合并仅在控件构造时执行一次；运行时 setter 与 SavedState 恢复优先级更高，
 * 不经由本合并器。
 */
internal object SslDefaultsMerger {

    /** 分开读取 Theme 默认和页面显式配置，避免 hasValue 将 Theme 当成 XML。 */
    fun read(context: Context, attrs: AttributeSet?, defStyleAttr: Int): Pair<XmlSslAttrs, XmlSslAttrs> {
        val styleable = R.styleable.SslStateSkeletonLayout
        val defaults = context.obtainStyledAttributes(null, styleable, defStyleAttr, 0)
        val theme = try { parseXmlAttrs(context, defaults) } finally { defaults.recycle() }
        if (attrs == null) return XmlSslAttrs() to theme

        val explicit = mutableSetOf<Int>()
        for (i in 0 until attrs.attributeCount) {
            val index = styleable.indexOf(attrs.getAttributeNameResource(i))
            if (index >= 0) explicit += index
        }
        // style 本身也可使用 ?attr，先在真实 Theme 中定位实际样式资源。
        var pageStyle = attrs.styleAttribute
        if (pageStyle != 0 && context.resources.getResourceTypeName(pageStyle) == "attr") {
            val value = TypedValue()
            pageStyle = if (context.theme.resolveAttribute(pageStyle, value, false) &&
                value.type == TypedValue.TYPE_REFERENCE) value.data else 0
        }
        // 用独立 Theme 只判定 XML style 的成员；最终值由真实 Theme 解析 ?attr 引用。
        if (pageStyle != 0) {
            val isolated = context.resources.newTheme()
            val style = isolated.obtainStyledAttributes(pageStyle, styleable)
            try {
                for (i in styleable.indices) if (style.hasValue(i)) explicit += i
            } finally { style.recycle() }
        }
        val page = context.obtainStyledAttributes(attrs, styleable, 0, 0)
        return try { parseXmlAttrs(context, page, explicit) to theme } finally { page.recycle() }
    }

    /**
     * 合并 XML 属性与全局默认，产出最终配置。
     *
     * @param context 用于读取资源默认值（颜色 / 圆角）。
     * @param xmlAttrs XML 属性解析结果；程序化创建路径传入未设置的字段即可。
     * @param global 全局默认配置，null 字段不参与覆盖。
     */
    fun merge(
        context: Context,
        xmlAttrs: XmlSslAttrs,
        global: SslGlobalDefaults,
        themeAttrs: XmlSslAttrs = XmlSslAttrs(),
    ): MergedSslConfig {
        // 三处显式来源都为空时，颜色 / 圆角完全由资源决定，昼夜切换需要重新解析。
        val maskFromResources = xmlAttrs.maskColor == null &&
            global.maskColor == null && themeAttrs.maskColor == null
        val highlightFromResources = xmlAttrs.highlightColor == null &&
            global.highlightColor == null && themeAttrs.highlightColor == null
        val radiusFromResources = xmlAttrs.cornerRadius == null &&
            global.cornerRadius == null && themeAttrs.cornerRadius == null

        val skeletonConfig = SkeletonConfig(
            source = xmlAttrs.source ?: global.skeletonSource ?: themeAttrs.source ?: SslDefaultValues.SOURCE,
            templateLayoutResId = xmlAttrs.templateLayoutResId ?: themeAttrs.templateLayoutResId,
            effect = xmlAttrs.effect ?: global.effect ?: themeAttrs.effect ?: SslDefaultValues.EFFECT,
            maskColor = xmlAttrs.maskColor ?: global.maskColor ?: themeAttrs.maskColor
                ?: context.getColor(R.color.ssl_mask_default),
            highlightColor = xmlAttrs.highlightColor ?: global.highlightColor ?: themeAttrs.highlightColor
                ?: context.getColor(R.color.ssl_highlight_default),
            cornerRadius = xmlAttrs.cornerRadius ?: global.cornerRadius ?: themeAttrs.cornerRadius
                ?: context.resources.getDimension(R.dimen.ssl_corner_radius_default),
            animationDurationMillis = xmlAttrs.animationDurationMillis
                ?: global.animationDurationMillis ?: themeAttrs.animationDurationMillis
                ?: SslDefaultValues.ANIMATION_DURATION_MILLIS,
            direction = xmlAttrs.direction ?: global.direction ?: themeAttrs.direction
                ?: SslDefaultValues.DIRECTION,
            angleDegrees = xmlAttrs.angleDegrees ?: global.angleDegrees ?: themeAttrs.angleDegrees
                ?: SslDefaultValues.ANGLE_DEGREES,
            pulseMinAlpha = xmlAttrs.pulseMinAlpha ?: global.pulseMinAlpha ?: themeAttrs.pulseMinAlpha
                ?: SslDefaultValues.PULSE_MIN_ALPHA,
            pulseMaxAlpha = xmlAttrs.pulseMaxAlpha ?: global.pulseMaxAlpha ?: themeAttrs.pulseMaxAlpha
                ?: SslDefaultValues.PULSE_MAX_ALPHA,
        )
        // 条目拼装模板是布局资源类属性，不参与 Global 覆盖（页面各异）。
        val itemLayoutResId = xmlAttrs.skeletonItemLayoutResId.takeIf { it != 0 }
            ?: themeAttrs.skeletonItemLayoutResId.takeIf { it != 0 } ?: 0
        val itemCount = xmlAttrs.skeletonItemCount ?: themeAttrs.skeletonItemCount
        // 重试入口逐状态合并：页面显式（含 style）> Global > Theme；库默认由调用方在确定布局后补齐。
        // 高优先级空列表截断继承，不做并集。
        val emptyRetry = xmlAttrs.emptyRetryViewIds
            ?: global.retryConfig?.emptyViewIds
            ?: themeAttrs.emptyRetryViewIds
        val errorRetry = xmlAttrs.errorRetryViewIds
            ?: global.retryConfig?.errorViewIds
            ?: themeAttrs.errorRetryViewIds
        return MergedSslConfig(
            initialState = xmlAttrs.initialState ?: global.initialState ?: themeAttrs.initialState
                ?: SslDefaultValues.INITIAL_STATE,
            useSkeleton = xmlAttrs.useSkeleton ?: global.useSkeleton ?: themeAttrs.useSkeleton
                ?: SslDefaultValues.USE_SKELETON,
            skeletonConfig = skeletonConfig,
            skeletonItemLayoutResId = itemLayoutResId,
            skeletonItemCount = itemCount,
            loadingShowDelayMillis = xmlAttrs.loadingShowDelayMillis
                ?: global.loadingShowDelayMillis ?: themeAttrs.loadingShowDelayMillis
                ?: SslDefaultValues.LOADING_SHOW_DELAY_MILLIS,
            minimumLoadingDurationMillis = xmlAttrs.minimumLoadingDurationMillis
                ?: global.minimumLoadingDurationMillis ?: themeAttrs.minimumLoadingDurationMillis
                ?: SslDefaultValues.MINIMUM_LOADING_DURATION_MILLIS,
            transitionEffect = xmlAttrs.transitionEffect ?: global.transitionEffect ?: themeAttrs.transitionEffect
                ?: SslDefaultValues.TRANSITION_EFFECT,
            transitionDurationMillis = xmlAttrs.transitionDurationMillis
                ?: global.transitionDurationMillis ?: themeAttrs.transitionDurationMillis
                ?: SslDefaultValues.TRANSITION_DURATION_MILLIS,
            announceStateChanges = xmlAttrs.announceStateChanges
                ?: global.announceStateChanges ?: themeAttrs.announceStateChanges
                ?: SslDefaultValues.ANNOUNCE_STATE_CHANGES,
            useBuiltInStateLayouts = global.useBuiltInStateLayouts
                ?: SslDefaultValues.USE_BUILT_IN_STATE_LAYOUTS,
            colorsFromResources = maskFromResources && highlightFromResources && radiusFromResources,
            emptyRetryViewIds = emptyRetry,
            errorRetryViewIds = errorRetry,
        )
    }

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
        val arrayResId = a.getResourceId(index, 0)
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
 * XML 属性解析结果。字段为 null 表示 XML 未显式设置。
 *
 * 布局资源类属性（emptyLayout / errorLayout / loadingLayout / skeletonTemplate）
 * 不参与全局覆盖，始终保留 Int 值（0 表示未配置）。
 * 重试 ID 列表按状态参与 Global / Theme 合并：null 表示未声明（继承），
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
)
