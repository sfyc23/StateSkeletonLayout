package com.sfyc.ssl.internal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/** 只改变库拥有的容器，避免淡出期间覆盖业务节点的 enabled / clickable 等属性。 */
internal class InteractionHost(context: Context) : FrameLayout(context) {
    private var blocked = false
    private var previousAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var previousFocusability = FOCUS_BEFORE_DESCENDANTS
    private var previousEnabled = true

    fun blockInteraction(block: Boolean) {
        if (blocked == block) return
        blocked = block
        if (block) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            previousAccessibility = importantForAccessibility
            previousFocusability = descendantFocusability
            previousEnabled = isEnabled
            isEnabled = false
            clearFocus()
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            isEnabled = previousEnabled
            descendantFocusability = previousFocusability
            importantForAccessibility = previousAccessibility
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = blocked || super.dispatchTouchEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = blocked || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = blocked || super.dispatchGenericMotionEvent(event)
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = blocked || super.dispatchHoverEvent(event)
}
