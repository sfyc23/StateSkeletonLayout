package com.sfyc.demo

import androidx.fragment.app.Fragment
import com.sfyc.demo.accessibility.AccessibilityDemoFragment
import com.sfyc.demo.basic.BasicFourStateFragment
import com.sfyc.demo.content.ContentShapeFragment
import com.sfyc.demo.defaults.GlobalDefaultsFragment
import com.sfyc.demo.defaults.InvalidValueFragment
import com.sfyc.demo.defaults.PartialOverrideFragment
import com.sfyc.demo.defaults.PriorityChainFragment
import com.sfyc.demo.effects.EffectsFragment
import com.sfyc.demo.lifecycle.LifecycleDemoFragment
import com.sfyc.demo.lottie.LottieLoadingFragment
import com.sfyc.demo.network.FeedDemoFragment
import com.sfyc.demo.rapid.RapidStateFragment
import com.sfyc.demo.recycler.RecyclerSkeletonFragment
import com.sfyc.demo.template.TemplateFragment
import com.sfyc.demo.timing.TimingFragment

/**
 * 示例目录：首页列表的数据源，每个条目说明正在演示的控件能力。
 */
object DemoCatalog {

    data class Entry(
        val title: String,
        val description: String,
        val factory: () -> Fragment,
    )

    val entries: List<Entry> = listOf(
        Entry("基础四态", "手动切换 Content / Loading / Empty / Error，验证重试事件", ::BasicFourStateFragment),
        Entry("内容取形", "根据真实资料卡片生成 Shimmer 骨架", ::ContentShapeFragment),
        Entry("模板取形", "内容无尺寸时使用独立模板骨架", ::TemplateFragment),
        Entry("骨架效果", "Solid / Shimmer / Pulse 及运行时配置切换", ::EffectsFragment),
        Entry("列表骨架", "Linear 与 Grid 列表使用模板骨架，不替换 Adapter", ::RecyclerSkeletonFragment),
        Entry("Lottie 加载", "自定义加载布局 + 状态回调启停 Lottie", ::LottieLoadingFragment),
        Entry("快速请求", "100ms / 500ms / 1500ms 模拟结果，展示延迟与最短展示规则", ::TimingFragment),
        Entry("连续状态", "Loading 后快速提交多个结果，验证最新状态优先", ::RapidStateFragment),
        Entry("生命周期", "切后台、切页面、旋转设备后的动画停止与恢复", ::LifecycleDemoFragment),
        Entry("无障碍", "TalkBack 文案、焦点隔离、动画关闭后的静态效果", ::AccessibilityDemoFragment),
        Entry("全局默认与程序化创建", "Application 安装后代码创建的控件自动继承，含 install 整体替换与实例隔离", ::GlobalDefaultsFragment),
        Entry("部分覆盖", "全局默认仅声明部分字段，未声明字段回落库内置默认", ::PartialOverrideFragment),
        Entry("优先级链", "库内置 < Theme < Global < XML < 运行时 setter 逐层叠加对比", ::PriorityChainFragment),
        Entry("非法值校验", "安装非法全局默认后创建实例，观察含属性名的 IllegalArgumentException", ::InvalidValueFragment),
        Entry("网络多类型", "刷新、分页与多类型列表的骨架 / 空 / 错误示例", ::FeedDemoFragment),
    )
}
