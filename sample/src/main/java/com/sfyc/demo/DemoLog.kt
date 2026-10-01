package com.sfyc.demo

import android.widget.TextView

/**
 * 示例页面的行日志 helper：追加带相对时间的日志行，最多保留 50 行。
 *
 * 仅用于演示状态流转，不属于库能力。
 */
class DemoLog(private val view: TextView) {

    private val startMillis = android.os.SystemClock.uptimeMillis()
    private val lines = ArrayDeque<String>()

    fun add(message: String) {
        val elapsed = android.os.SystemClock.uptimeMillis() - startMillis
        lines.addLast("[+%4dms] %s".format(elapsed, message))
        while (lines.size > 50) lines.removeFirst()
        view.text = lines.joinToString("\n")
    }

    fun clear() {
        lines.clear()
        view.text = ""
    }
}
