package com.sfyc.ssl.internal.skeleton

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Space
import com.sfyc.ssl.BuildConfig
import com.sfyc.ssl.SkeletonNodeConfig
import com.sfyc.ssl.SkeletonShape

internal sealed interface MaskBuildResult {
    data class Success(
        val bitmap: Bitmap,
        val fallbackUsed: Boolean,
    ) : MaskBuildResult

    data object InvalidSize : MaskBuildResult

    data object EmptyGeometry : MaskBuildResult

    data object AllocationFailed : MaskBuildResult

    data object PixelBudgetExceeded : MaskBuildResult
}

/**
 * 骨架遮罩构建器：几何采集 + 单张 ALPHA_8 遮罩 Bitmap 生成。
 *
 * # 缓存策略（与实现方案第 14.2 节一致）
 *
 * 遮罩只在以下情况失效重建：
 * - 控件尺寸变化（`onSizeChanged`）。
 * - 内容/模板重新布局且几何发生变化（加载期间的全局布局监听）。
 * - 骨架来源、模板、圆角变化或调用方主动 `invalidateSkeleton()`。
 *
 * 以下变化不重建遮罩：流光位移、脉冲透明度、已展示时长。
 *
 * # 资源纪律
 *
 * - 稳定持有一张 ALPHA_8 Bitmap；成功替换期间新旧两张可能短暂并存。
 * - 替换遮罩时释放旧 Bitmap；复用成员级 Paint/Rect，避免逐帧分配。
 * - 内容取形找不到有效矩形时降级为整块静态遮罩（Debug 记录警告，
 *   不抛异常）；模板配置无效则快速失败。
 */
internal class SkeletonMaskBuilder(
    private val nodeConfigProvider: (Int) -> SkeletonNodeConfig? = { null },
    private val bitmapAllocator: (width: Int, height: Int) -> Bitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    },
) {

    private var allocationFailureLogged = false
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    fun collectFromContent(host: ViewGroup, overlay: View, out: MutableList<SkeletonGeometry>): Boolean {
        out.clear()
        if (host.width <= 0 || host.height <= 0 || overlay.width <= 0 || overlay.height <= 0) return false
        collectLeaves(host, host.x - overlay.x, host.y - overlay.y,
            RectF(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat()), out, true)
        return out.isNotEmpty()
    }

    fun collectFromTemplate(host: ViewGroup, out: MutableList<SkeletonGeometry>): Boolean {
        out.clear()
        if (host.width <= 0 || host.height <= 0) return false
        collectLeaves(host, 0f, 0f, RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()), out, true)
        return out.isNotEmpty()
    }

    // 两种来源使用相同坐标链：x/y 含 translation，减去每层滚动并叠加祖先矩形裁剪。
    // 根 Host 隐藏后仍参与取形；业务子树的 visibility 则正常过滤。
    private fun collectLeaves(
        node: View, x: Float, y: Float, inheritedClip: RectF,
        out: MutableList<SkeletonGeometry>, isRoot: Boolean,
    ) {
        if ((!isRoot && node.visibility != View.VISIBLE) || node is Space) return
        val rule = if (node.id != View.NO_ID) nodeConfigProvider(node.id) else null
        if (rule?.excluded == true || node.width <= 0 || node.height <= 0) return
        val clip = RectF(inheritedClip)
        node.clipBounds?.let {
            if (!clip.intersect(x + it.left, y + it.top, x + it.right, y + it.bottom)) return
        }
        if (node is ViewGroup && node.childCount > 0) {
            if (node.clipChildren && !clip.intersect(x, y, x + node.width, y + node.height)) return
            if (node.clipToPadding && (node.paddingLeft != 0 || node.paddingTop != 0 ||
                    node.paddingRight != 0 || node.paddingBottom != 0)) {
                if (!clip.intersect(x + node.paddingLeft, y + node.paddingTop,
                        x + node.width - node.paddingRight, y + node.height - node.paddingBottom)) return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i)
                collectLeaves(child, x + child.x - node.scrollX, y + child.y - node.scrollY, clip, out, false)
            }
            // 容器的后代被隐藏或排除时保持空几何，不把整个父容器补成一块骨架。
            return
        }
        if (isRoot) return
        val original = RectF(x, y, x + node.width, y + node.height)
        val visible = RectF(original)
        if (!visible.intersect(clip)) return
        out += SkeletonGeometry(visible, rule?.shape ?: SkeletonShape.ROUNDED_RECTANGLE,
            rule?.cornerRadius, if (visible != original) original else null)
    }

    /**
     * 按几何生成单张 ALPHA_8 遮罩。
     *
     * @param fallbackToFullBounds 几何为空时是否降级为整块遮罩
     *  （内容取形传 true；模板取形传 false，由上层快速失败）。
     * @return 明确区分成功、尺寸未就绪、空模板与内存分配失败。
     */
    fun buildMask(
        geometries: List<SkeletonGeometry>,
        width: Int,
        height: Int,
        cornerRadius: Float,
        fallbackToFullBounds: Boolean,
        maximumPixels: Long = Long.MAX_VALUE,
    ): MaskBuildResult {
        if (width <= 0 || height <= 0) return MaskBuildResult.InvalidSize
        if (geometries.isEmpty() && !fallbackToFullBounds) return MaskBuildResult.EmptyGeometry
        if (width.toLong() * height > maximumPixels) return MaskBuildResult.PixelBudgetExceeded
        var mask: Bitmap? = null
        try {
            mask = bitmapAllocator(width, height)
            val canvas = Canvas(mask)
            val radius = cornerRadius.coerceIn(0f, (minOf(width, height) / 2f))
            if (geometries.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.w(
                        TAG,
                        "取形未找到有效矩形，已降级为整块静态遮罩；" +
                            "内容尚未布局时建议改用独立模板（ssl_skeletonSource=template）",
                    )
                }
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, maskPaint)
            } else {
                for (geometry in geometries) {
                    val bounds = geometry.originalBounds ?: geometry.bounds
                    val saved = canvas.save()
                    canvas.clipRect(geometry.bounds)
                    when (geometry.shape) {
                        SkeletonShape.RECTANGLE -> canvas.drawRect(bounds, maskPaint)
                        SkeletonShape.ROUNDED_RECTANGLE -> {
                            val nodeRadius = (geometry.cornerRadius ?: radius).coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f)
                            canvas.drawRoundRect(bounds, nodeRadius, nodeRadius, maskPaint)
                        }
                        SkeletonShape.CIRCLE -> canvas.drawCircle(bounds.centerX(), bounds.centerY(),
                            minOf(bounds.width(), bounds.height()) / 2f, maskPaint)
                    }
                    canvas.restoreToCount(saved)
                }
            }
            return MaskBuildResult.Success(mask, fallbackUsed = geometries.isEmpty())
        } catch (_: OutOfMemoryError) {
            // 大尺寸页面 + 并行骨架属于已知内存风险：放弃本次遮罩，不让 Loading 崩溃。
            // 内存紧张时不打印堆栈，并按 builder 实例只记录一次，避免日志路径继续分配。
            if (!allocationFailureLogged) {
                allocationFailureLogged = true
                Log.e(TAG, "骨架遮罩 Bitmap 分配失败，已跳过本次重建")
            }
            mask?.recycle()
            return MaskBuildResult.AllocationFailed
        }
    }

    companion object {
        private const val TAG = "SkeletonMaskBuilder"
    }
}
