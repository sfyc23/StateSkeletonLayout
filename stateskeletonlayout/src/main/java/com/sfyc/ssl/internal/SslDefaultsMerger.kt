package com.sfyc.ssl.internal

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.util.TypedValue
import com.sfyc.ssl.R
import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonDirection
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateTransitionEffect

/**
 * 合并后的最终配置：用于构造 [com.sfyc.ssl.StateSkeletonLayout]。
 *
 * 校验由 [SkeletonConfig] 的 `init` 统一执行，本类不重复校验。
 */
internal data class MergedSslConfig(
    val initialState: StateLayoutState,
    val useSkeleton: Boolean,
    val skeletonConfig: SkeletonConfig,
    val loadingShowDelayMillis: Long,
    val minimumLoadingDurationMillis: Long,
    val transitionEffect: StateTransitionEffect,
    val transitionDurationMillis: Long,
    val announceStateChanges: Boolean,
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
        val theme = try { parseXmlAttrs(defaults) } finally { defaults.recycle() }
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
        return try { parseXmlAttrs(page, explicit) to theme } finally { page.recycle() }
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
        val skeletonConfig = SkeletonConfig(
            source = xmlAttrs.source ?: global.skeletonSource ?: themeAttrs.source ?: SkeletonSource.CONTENT,
            templateLayoutResId = xmlAttrs.templateLayoutResId ?: themeAttrs.templateLayoutResId,
            effect = xmlAttrs.effect ?: global.effect ?: themeAttrs.effect ?: SkeletonEffect.SHIMMER,
            maskColor = xmlAttrs.maskColor ?: global.maskColor ?: themeAttrs.maskColor
                ?: context.getColor(R.color.ssl_mask_default),
            highlightColor = xmlAttrs.highlightColor ?: global.highlightColor ?: themeAttrs.highlightColor
                ?: context.getColor(R.color.ssl_highlight_default),
            cornerRadius = xmlAttrs.cornerRadius ?: global.cornerRadius ?: themeAttrs.cornerRadius
                ?: context.resources.getDimension(R.dimen.ssl_corner_radius_default),
            animationDurationMillis = xmlAttrs.animationDurationMillis
                ?: global.animationDurationMillis ?: themeAttrs.animationDurationMillis
                ?: SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS,
            direction = xmlAttrs.direction ?: global.direction ?: themeAttrs.direction
                ?: SkeletonDirection.START_TO_END,
            angleDegrees = xmlAttrs.angleDegrees ?: global.angleDegrees ?: themeAttrs.angleDegrees
                ?: SkeletonConfig.DEFAULT_ANGLE_DEGREES,
            pulseMinAlpha = xmlAttrs.pulseMinAlpha ?: global.pulseMinAlpha ?: themeAttrs.pulseMinAlpha
                ?: SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA,
            pulseMaxAlpha = xmlAttrs.pulseMaxAlpha ?: global.pulseMaxAlpha ?: themeAttrs.pulseMaxAlpha
                ?: SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA,
        )
        return MergedSslConfig(
            initialState = xmlAttrs.initialState ?: global.initialState ?: themeAttrs.initialState
                ?: StateLayoutState.CONTENT,
            useSkeleton = xmlAttrs.useSkeleton ?: global.useSkeleton ?: themeAttrs.useSkeleton ?: false,
            skeletonConfig = skeletonConfig,
            loadingShowDelayMillis = xmlAttrs.loadingShowDelayMillis
                ?: global.loadingShowDelayMillis ?: themeAttrs.loadingShowDelayMillis ?: 0L,
            minimumLoadingDurationMillis = xmlAttrs.minimumLoadingDurationMillis
                ?: global.minimumLoadingDurationMillis ?: themeAttrs.minimumLoadingDurationMillis ?: 300L,
            transitionEffect = xmlAttrs.transitionEffect ?: global.transitionEffect ?: themeAttrs.transitionEffect
                ?: StateTransitionEffect.CROSSFADE,
            transitionDurationMillis = xmlAttrs.transitionDurationMillis
                ?: global.transitionDurationMillis ?: themeAttrs.transitionDurationMillis ?: 180L,
            announceStateChanges = xmlAttrs.announceStateChanges
                ?: global.announceStateChanges ?: themeAttrs.announceStateChanges ?: true,
        )
    }

    /**
     * 从 TypedArray 解析 XML 属性。字段为 null 表示 XML 未显式设置，
     * 由合并链上层（Global / Theme / 库内置）填补。
     *
     * 属性未显式设置时 [TypedArray] 返回默认值，此处通过
     * [TypedArray.hasValue] 区分「未设置」与「设为默认值」。
     */
    fun parseXmlAttrs(a: TypedArray, explicitIndices: Set<Int>? = null): XmlSslAttrs {
        fun has(index: Int): Boolean = a.hasValue(index) &&
            (explicitIndices == null || index in explicitIndices)
        fun resource(index: Int): Int = if (has(index)) a.getResourceId(index, 0) else 0

        val templateResId = resource(R.styleable.SslStateSkeletonLayout_ssl_skeletonTemplate).takeIf { it != 0 }

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
            errorRetryViewId = resource(R.styleable.SslStateSkeletonLayout_ssl_errorRetryViewId),
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
}

/**
 * XML 属性解析结果。字段为 null 表示 XML 未显式设置。
 *
 * 布局资源类属性（emptyLayout / errorLayout / loadingLayout / skeletonTemplate /
 * errorRetryViewId）不参与全局覆盖，始终保留 Int 值（0 表示未配置）。
 */
internal data class XmlSslAttrs(
    val emptyLayoutResId: Int = 0,
    val errorLayoutResId: Int = 0,
    val customLoadingLayoutResId: Int = 0,
    val errorRetryViewId: Int = 0,
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
