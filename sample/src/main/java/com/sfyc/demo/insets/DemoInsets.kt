package com.sfyc.demo.insets

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 示例沉浸式落点：把 systemBars + displayCutout 以 padding 形式叠加到调用方初始 padding 上。
 *
 * - 只在各页根 View 调用一次（如 Fragment 的 `view`、首页内容区），内部子 View 不再重复调用，
 *   避免双重叠加；监听器返回原始 insets（不消费），不阻断后代接收。
 * - 原有 XML padding（如 16dp）会被保留，只做加法，不覆盖。
 * - 不处理 IME：示例页无输入框，键盘弹起时不整体上移。
 * - 库模块（StateSkeletonLayout）几何跟随内容 padding，本文件只进 `:sample`。
 */
fun View.applyDemoSystemBarPadding(
    applyTop: Boolean = true,
    applyBottom: Boolean = true,
    applySides: Boolean = true,
) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(
            initialLeft + if (applySides) bars.left else 0,
            initialTop + if (applyTop) bars.top else 0,
            initialRight + if (applySides) bars.right else 0,
            initialBottom + if (applyBottom) bars.bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
