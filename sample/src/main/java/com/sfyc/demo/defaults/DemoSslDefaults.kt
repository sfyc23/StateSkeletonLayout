package com.sfyc.demo.defaults

import android.content.Context
import android.view.LayoutInflater
import com.sfyc.ssl.SkeletonDirection
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.ssl.StateSkeletonLayoutDefaults
import com.sfyc.ssl.StateTransitionEffect
import com.sfyc.demo.ssl.R

/**
 * 全局默认示例的配置中枢：基准配置与各示例专用配置集中在此。
 *
 * - [canonical]：`DemoApplication.onCreate` 安装，示例页退出后恢复，隔离示例间串扰。
 * - [installCanonical]：恢复基准全局默认；示例隔离一律走本方法，不使用测试用 `reset()`。
 */
object DemoSslDefaults {

    /** 基准全局默认（部分覆盖形态，兼作应用级品牌默认）。 */
    val canonical: SslGlobalDefaults = SslGlobalDefaults(
        maskColor = 0xFFD7E3EC.toInt(),
        cornerRadius = 12f,
        animationDurationMillis = 900L,
    )

    /** 全字段覆盖：17 个纳入字段全部赋值，且与库内置默认明显不同。 */
    val fullOverride: SslGlobalDefaults = SslGlobalDefaults(
        useSkeleton = true,
        skeletonSource = SkeletonSource.CONTENT,
        initialState = StateLayoutState.CONTENT,
        effect = SkeletonEffect.SOLID,
        maskColor = 0xFFFFCDD2.toInt(),
        highlightColor = 0xFFFFF59D.toInt(),
        cornerRadius = 6f,
        animationDurationMillis = 700L,
        direction = SkeletonDirection.END_TO_START,
        angleDegrees = 45,
        pulseMinAlpha = 0.2f,
        pulseMaxAlpha = 0.8f,
        loadingShowDelayMillis = 50L,
        minimumLoadingDurationMillis = 250L,
        transitionEffect = StateTransitionEffect.NONE,
        transitionDurationMillis = 120L,
        announceStateChanges = false,
    )

    /** 部分覆盖：仅声明两个字段，其余回落下一层默认。 */
    val partialOverride: SslGlobalDefaults = SslGlobalDefaults(
        maskColor = 0xFF2E7D32.toInt(),
        effect = SkeletonEffect.PULSE,
    )

    /** 优先级探针：与 Theme 层、XML 层同名字段取值互不相同。 */
    val priorityProbe: SslGlobalDefaults = SslGlobalDefaults(
        effect = SkeletonEffect.PULSE,
        maskColor = 0xFF80CBC4.toInt(),
        cornerRadius = 6f,
        animationDurationMillis = 500L,
    )

    fun installCanonical() {
        StateSkeletonLayoutDefaults.install(canonical)
    }

    /** 配置摘要：列出非空字段，用于示例页顶部「本页安装的全局默认」。 */
    fun describe(defaults: SslGlobalDefaults): String {
        val fields = listOf(
            "useSkeleton" to defaults.useSkeleton?.toString(),
            "skeletonSource" to defaults.skeletonSource?.toString(),
            "initialState" to defaults.initialState?.toString(),
            "effect" to defaults.effect?.toString(),
            "maskColor" to defaults.maskColor?.toHexColor(),
            "highlightColor" to defaults.highlightColor?.toHexColor(),
            "cornerRadius" to defaults.cornerRadius?.let { "${it}px" },
            "animationDurationMillis" to defaults.animationDurationMillis?.toString(),
            "direction" to defaults.direction?.toString(),
            "angleDegrees" to defaults.angleDegrees?.toString(),
            "pulseMinAlpha" to defaults.pulseMinAlpha?.toString(),
            "pulseMaxAlpha" to defaults.pulseMaxAlpha?.toString(),
            "loadingShowDelayMillis" to defaults.loadingShowDelayMillis?.toString(),
            "minimumLoadingDurationMillis" to defaults.minimumLoadingDurationMillis?.toString(),
            "transitionEffect" to defaults.transitionEffect?.toString(),
            "transitionDurationMillis" to defaults.transitionDurationMillis?.toString(),
            "announceStateChanges" to defaults.announceStateChanges?.toString(),
        )
        val set = fields.filter { it.second != null }.joinToString("\n") { "  ${it.first} = ${it.second}" }
        return if (set.isEmpty()) "（全 null，不参与覆盖）" else set
    }

    /** 对照表：字段 / 期望来源 / 期望值 / 实际值。期望值为空表示回落库内置默认。 */
    fun compareTable(expected: SslGlobalDefaults, actual: StateSkeletonLayout): String {
        fun row(name: String, exp: String?, act: String): String {
            val source = if (exp == null) "库内置" else "Global"
            val expText = exp ?: "（库内置默认）"
            return "$name | $source | $expText | $act"
        }
        val c = actual.skeletonConfig
        return listOf(
            "字段 | 期望来源 | 期望值 | 实际值",
            row("useSkeleton", expected.useSkeleton?.toString(), actual.useSkeleton.toString()),
            row("skeletonSource", expected.skeletonSource?.toString(), c.source.toString()),
            row("initialState", expected.initialState?.toString(), "renderedState=${actual.renderedState}"),
            row("effect", expected.effect?.toString(), c.effect.toString()),
            row("maskColor", expected.maskColor?.toHexColor(), c.maskColor.toHexColor()),
            row("highlightColor", expected.highlightColor?.toHexColor(), c.highlightColor.toHexColor()),
            row("cornerRadius", expected.cornerRadius?.let { "${it}px" }, "${c.cornerRadius}px"),
            row("animationDurationMillis", expected.animationDurationMillis?.toString(), c.animationDurationMillis.toString()),
            row("direction", expected.direction?.toString(), c.direction.toString()),
            row("angleDegrees", expected.angleDegrees?.toString(), c.angleDegrees.toString()),
            row("pulseMinAlpha", expected.pulseMinAlpha?.toString(), c.pulseMinAlpha.toString()),
            row("pulseMaxAlpha", expected.pulseMaxAlpha?.toString(), c.pulseMaxAlpha.toString()),
            row("loadingShowDelayMillis", expected.loadingShowDelayMillis?.toString(), actual.loadingShowDelayMillis.toString()),
            row("minimumLoadingDurationMillis", expected.minimumLoadingDurationMillis?.toString(), actual.minimumLoadingDurationMillis.toString()),
            row("transitionEffect", expected.transitionEffect?.toString(), actual.transitionEffect.toString()),
            row("transitionDurationMillis", expected.transitionDurationMillis?.toString(), actual.transitionDurationMillis.toString()),
            row("announceStateChanges", expected.announceStateChanges?.toString(), actual.announceStateChanges.toString()),
        ).joinToString("\n")
    }

    /** 程序化创建示例实例：内容区复用资料卡片，空态与错误态显式注入（布局资源不纳入全局默认）。 */
    fun createStateLayout(context: Context): StateSkeletonLayout {
        val state = StateSkeletonLayout(context)
        val content = LayoutInflater.from(context).inflate(R.layout.content_profile, state, false)
        state.setContentView(content)
        state.setStateViewLayout(StateLayoutState.EMPTY, R.layout.state_empty)
        state.setStateViewLayout(StateLayoutState.ERROR, R.layout.state_error)
        return state
    }
}

/** 有效值摘要：读取实例公开属性，用于对照表「实际值」列。 */
fun StateSkeletonLayout.describeEffective(): String {
    val c = skeletonConfig
    return listOf(
        "renderedState = $renderedState",
        "useSkeleton = $useSkeleton",
        "source = ${c.source}",
        "effect = ${c.effect}",
        "maskColor = ${c.maskColor.toHexColor()}",
        "highlightColor = ${c.highlightColor.toHexColor()}",
        "cornerRadius = ${c.cornerRadius}px",
        "animationDurationMillis = ${c.animationDurationMillis}",
        "direction = ${c.direction}",
        "angleDegrees = ${c.angleDegrees}",
        "pulseMinAlpha = ${c.pulseMinAlpha}",
        "pulseMaxAlpha = ${c.pulseMaxAlpha}",
        "loadingShowDelayMillis = $loadingShowDelayMillis",
        "minimumLoadingDurationMillis = $minimumLoadingDurationMillis",
        "transitionEffect = $transitionEffect",
        "transitionDurationMillis = $transitionDurationMillis",
        "announceStateChanges = $announceStateChanges",
    ).joinToString("\n")
}

/** 优先级探针字段摘要（4 个高辨识度字段）。 */
fun StateSkeletonLayout.describeProbes(): String {
    val c = skeletonConfig
    return "effect=${c.effect}  maskColor=${c.maskColor.toHexColor()}  " +
        "cornerRadius=${c.cornerRadius}px  animationDurationMillis=${c.animationDurationMillis}"
}

internal fun Int.toHexColor(): String = "#%08X".format(this)
