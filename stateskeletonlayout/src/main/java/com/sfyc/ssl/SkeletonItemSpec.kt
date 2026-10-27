package com.sfyc.ssl

import android.view.View
import androidx.annotation.LayoutRes

/**
 * 单种条目在离屏拼装模板中的声明（T2 条目拼装模板）。
 *
 * 用于 `SkeletonSource.TEMPLATE` 下复用成功态 item 布局：
 * 库按 [layoutResId] 逐条 inflate [count] 次，可选经 [binder] 填充占位文本 /
 * 固定尺寸，再纵向拼装为离屏模板宿主，走同一套几何采集与遮罩绘制。
 * 业务 `RecyclerView.Adapter` 不被替换、不被读取。
 *
 * 与整张模板布局（`SkeletonConfig.templateLayoutResId`）互斥，
 * 最终有效组合中两者同时存在时抛异常；低层被高层明确替换的供给不参与检查。
 * 数量跟随选中的 item 布局：重新声明布局但不声明数量时用默认 [DEFAULT_COUNT]，
 * 不继承旧布局数量；`@empty` 重置数量继承时同样恢复默认。
 *
 * @property layoutResId 成功态 item 布局资源（常常与业务 Adapter 的 item 布局为同一份）。
 * @property count 该布局重复拼装的条数，必须大于 0。
 * @property binder 占位绑定回调，可空。`position` 为该 spec 内的序号（`0..count-1`）。
 *   用于给 `wrap_content` 的 `TextView` 填充占位文本、给图片占位设置固定尺寸等。
 *   只操作传入 View 的子树，不得调用 `render` / 修改控件配置。
 *   不可序列化：进程重建与配置恢复后置空，需宿主重新设置。
 */
data class SkeletonItemSpec(
    @param:LayoutRes val layoutResId: Int,
    val count: Int = DEFAULT_COUNT,
    val binder: ((view: View, position: Int) -> Unit)? = null,
) {
    init {
        require(layoutResId != 0) { "SkeletonItemSpec.layoutResId 必须是有效布局资源" }
        require(count > 0) { "SkeletonItemSpec.count 必须大于 0，当前=$count" }
    }

    companion object {
        /**
         * XML 未显式设置 `ssl_skeletonItemCount` 时的默认条数。
         *
         * 与既有整屏模板（8 行）及示例 `tools:itemCount="8"` 对齐，覆盖首屏。
         */
        const val DEFAULT_COUNT: Int = 8
    }
}
