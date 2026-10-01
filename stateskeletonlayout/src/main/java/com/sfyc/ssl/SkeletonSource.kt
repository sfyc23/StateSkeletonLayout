package com.sfyc.ssl

/**
 * 骨架形状来源。
 *
 * - [CONTENT]：遍历业务内容布局中的可见叶子 View，按其位置生成骨架矩形。
 *   要求内容在进入 Loading 时已经完成测量布局且具备有效尺寸。
 * - [TEMPLATE]：使用调用方提供的独立 XML 模板描述骨架形状，适合内容尚未具备
 *   尺寸、列表首屏占位等场景。模板只描述形状，不承载真实业务数据。
 */
enum class SkeletonSource {
    CONTENT,
    TEMPLATE,
}
