package com.sfyc.ssl.internal.skeleton

import android.graphics.RectF
import com.sfyc.ssl.SkeletonShape

/**
 * 骨架几何：遮罩层坐标系下的圆角矩形。
 *
 * 几何只在“布局/配置失效”时重新计算，动画帧只复用本列表，
 * 不在 `onDraw` 或动画回调中遍历 View 树。
 *
 * @property bounds 矩形区域（相对于 [SkeletonOverlayView] 左上角）。
 */
internal data class SkeletonGeometry(
    val bounds: RectF,
    val shape: SkeletonShape = SkeletonShape.ROUNDED_RECTANGLE,
    val cornerRadius: Float? = null,
    /** 裁剪前原始边界；bounds 保留可见区域，绘制圆形时仍使用原圆心。 */
    val originalBounds: RectF? = null,
)
