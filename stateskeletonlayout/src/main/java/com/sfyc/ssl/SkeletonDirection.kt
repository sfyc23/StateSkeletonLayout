package com.sfyc.ssl

/**
 * 流光（[SkeletonEffect.SHIMMER]）的扫描方向。
 *
 * 方向按当前 Layout Direction 解析，不硬编码左右：
 * RTL 布局下 [START_TO_END] 即从右向左扫描。
 *
 * - [START_TO_END]：从起始边扫向结束边（LTR 下为从左向右）。
 * - [END_TO_START]：从结束边扫向起始边。
 */
enum class SkeletonDirection {
    START_TO_END,
    END_TO_START,
}
