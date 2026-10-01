package com.sfyc.ssl

/**
 * 状态切换过渡效果，与骨架动画相互独立。
 *
 * - [NONE]：立即提交目标状态，无装饰动画。适用于系统动画关闭、
 *   自动化测试与低性能设备。
 * - [CROSSFADE]：旧状态淡出、新状态淡入；新请求到来时取消旧动画，
 *   从当前透明度继续切换，不会闪回旧状态。
 */
enum class StateTransitionEffect {
    NONE,
    CROSSFADE,
}
