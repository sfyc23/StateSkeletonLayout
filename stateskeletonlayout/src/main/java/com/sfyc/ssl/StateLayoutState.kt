package com.sfyc.ssl

/**
 * 页面四态：控件同一时间只会呈现其中一种状态。
 *
 * - [CONTENT]：展示业务内容。
 * - [LOADING]：展示加载表现（骨架或自定义加载布局）。
 * - [EMPTY]：展示空内容页。
 * - [ERROR]：展示错误页（含重试入口）。
 */
enum class StateLayoutState {
    CONTENT,
    LOADING,
    EMPTY,
    ERROR,
}
