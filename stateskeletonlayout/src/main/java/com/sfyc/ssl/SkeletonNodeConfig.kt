package com.sfyc.ssl

/** 单个 ID 对应的取形规则；排除容器时同时排除其后代。圆角单位为 px。 */
data class SkeletonNodeConfig(
    val excluded: Boolean = false,
    val shape: SkeletonShape = SkeletonShape.ROUNDED_RECTANGLE,
    val cornerRadius: Float? = null,
) {
    init {
        require(cornerRadius == null || (cornerRadius.isFinite() && cornerRadius >= 0f)) {
            "SkeletonNodeConfig.cornerRadius 必须是有限的非负数"
        }
    }
}

enum class SkeletonShape { RECTANGLE, ROUNDED_RECTANGLE, CIRCLE }
