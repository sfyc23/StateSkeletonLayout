package com.sfyc.ssl

/**
 * 骨架动效类型，三者互斥，同一时间只生效一种。
 *
 * 使用枚举而非两个布尔开关，是为了从类型层面杜绝
 * “同时开启流光与脉冲”这类无效组合。
 *
 * - [SOLID]：静态纯色遮罩，不创建动画器。系统动画被关闭时，
 *   其它两种效果会自动降级为该表现。
 * - [SHIMMER]：流光扫过，高亮带按 [SkeletonDirection] 与角度移动。
 * - [PULSE]：整体透明度在区间内平滑呼吸。
 */
enum class SkeletonEffect {
    SOLID,
    SHIMMER,
    PULSE,
}
