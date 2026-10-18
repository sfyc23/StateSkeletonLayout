package com.sfyc.demo

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewDemoTitleBarBinding

/** 目录打开详情时写入。没有这颗参数就不包标题栏，直接 commit 的单测仍看到原来的内容根。 */
const val ARG_PAGE_TITLE = "demo_page_title"

private const val LIGHT_TITLE_COLOR = 0xFFE8EDF2.toInt()

/**
 * 标题栏放进 Fragment 根 View，预测性返回才会带着标题一起滑动。
 * 系统栏 padding 仍由各页对 `onViewCreated` 的 `view` 施加，因此落在这层外壳上。
 */
fun Fragment.wrapDemoPage(inflater: LayoutInflater, content: View): View {
    val title = arguments?.getString(ARG_PAGE_TITLE)?.takeIf { it.isNotEmpty() } ?: return content
    val root = LinearLayout(content.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = content.layoutParams
            ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
    }
    content.background?.constantState?.newDrawable()?.mutate()?.let { root.background = it }
    val bar = ViewDemoTitleBarBinding.inflate(inflater, root, false)
    val color = titleColor(root)
    bar.demoPageTitle.text = title
    bar.demoPageTitle.setTextColor(color)
    bar.demoPageBack.imageTintList = ColorStateList.valueOf(color)
    bar.demoPageBack.setOnClickListener {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }
    root.addView(bar.root)
    root.addView(
        content,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ),
    )
    return root
}

private fun titleColor(root: View): Int {
    val background = root.background
    if (background is ColorDrawable && Color.luminance(background.color) < 0.5f) {
        return LIGHT_TITLE_COLOR
    }
    return root.context.getColor(R.color.demo_text_primary)
}
