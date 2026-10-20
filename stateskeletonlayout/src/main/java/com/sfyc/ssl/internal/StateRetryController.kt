package com.sfyc.ssl.internal

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.normalizeRetryIds

/**
 * 多重试入口的唯一管理者：有效 ID 列表、实际 View 绑定记录、可用开关与节流。
 *
 * - 持有唯一的最终有效列表与交互参数；公开方法与保存快照均委托本控制器。
 * - ID 仅在绑定时从该状态原始根查找；不从整个控件查找，不遍历 CONTENT。
 * - 只持有当前实例的缓存根与尚在创建事务中的候选根，根释放后删除记录。
 * - 解绑时清除监听并恢复因设置监听而改变的 `isClickable`，不改业务 `enabled` 等。
 */
internal class StateRetryController(
    emptyViewIds: List<Int> = emptyList(),
    errorViewIds: List<Int> = emptyList(),
    retryClicksEnabled: Boolean = true,
    retryClickThrottleMillis: Long = 500L,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val gates: Gates,
) {
    /** 宿主提供的状态事实与派发出口，避免控制器直接依赖 Fragment / 业务对象。 */
    interface Gates {
        fun renderedState(): StateLayoutState
        fun requestedState(): StateLayoutState
        fun isAttached(): Boolean
        fun isAggregatedVisible(): Boolean
        fun isShown(): Boolean
        fun cachedRoot(state: StateLayoutState): View?
        fun wrapperFor(state: StateLayoutState): View?
        fun currentTop(): View?
        fun hostView(): View
        fun retryListener(): Any?
        fun dispatch(state: StateLayoutState, clickedView: View)
    }

    private data class Binding(
        val state: StateLayoutState,
        val root: View,
        val target: View,
        @androidx.annotation.IdRes val id: Int,
        val clickableBefore: Boolean,
        var valid: Boolean = true,
    )

    private var emptyIds: List<Int> = normalizeRetryIds(emptyViewIds, "emptyViewIds") ?: emptyList()
    private var errorIds: List<Int> = normalizeRetryIds(errorViewIds, "errorViewIds") ?: emptyList()

    var retryClicksEnabled: Boolean = retryClicksEnabled
        set(value) {
            field = value
        }

    var retryClickThrottleMillis: Long = retryClickThrottleMillis
        set(value) {
            require(value >= 0L) { "retryClickThrottleMillis 不得小于 0" }
            field = value
        }

    private val bindings = mutableListOf<Binding>()
    private val lastAccepted = mutableMapOf<StateLayoutState, Long?>(
        StateLayoutState.EMPTY to null,
        StateLayoutState.ERROR to null,
    )
    private val missingWarned = mutableSetOf<Pair<Int, Int>>()

    fun getIds(state: StateLayoutState): List<Int> {
        requireRetryState(state)
        return when (state) {
            StateLayoutState.EMPTY -> emptyIds.toList()
            StateLayoutState.ERROR -> errorIds.toList()
            else -> error("unreachable")
        }
    }

    /** 替换单个状态的完整列表；相同列表重复赋值为空操作，不重绑、不重置节流窗口。 */
    fun replace(state: StateLayoutState, ids: List<Int>, cachedRoot: View?): Boolean {
        requireRetryState(state)
        val normalized = normalizeRetryIds(ids, state.fieldName()) ?: emptyList()
        val current = if (state == StateLayoutState.EMPTY) emptyIds else errorIds
        if (normalized == current) return false
        // 先准备新目标集合，再统一提交：校验失败不解绑旧目标。
        val prepared = prepareTargets(state, cachedRoot, normalized)
        commitSingle(state, normalized, cachedRoot, prepared)
        return true
    }

    /**
     * 批量提交两个状态：先完成两个列表的校验与目标准备，再统一提交。
     * `null` 表示跳过该状态（继承当前值），非 null（含空列表）替换该状态。
     */
    fun replaceBoth(
        empty: List<Int>?,
        error: List<Int>?,
        emptyRoot: View?,
        errorRoot: View?,
    ): Boolean {
        val normEmpty = empty?.let { normalizeRetryIds(it, "emptyViewIds") ?: emptyList() }
        val normError = error?.let { normalizeRetryIds(it, "errorViewIds") ?: emptyList() }
        if (normEmpty == null && normError == null) return false
        // 全部校验通过后再准备目标，避免部分生效。
        val preparedEmpty = normEmpty?.let { prepareTargets(StateLayoutState.EMPTY, emptyRoot, it) }
        val preparedError = normError?.let { prepareTargets(StateLayoutState.ERROR, errorRoot, it) }
        var changed = false
        if (normEmpty != null) {
            if (normEmpty != emptyIds) {
                commitSingle(StateLayoutState.EMPTY, normEmpty, emptyRoot, preparedEmpty!!)
                changed = true
            }
        }
        if (normError != null) {
            if (normError != errorIds) {
                commitSingle(StateLayoutState.ERROR, normError, errorRoot, preparedError!!)
                changed = true
            }
        }
        return changed
    }

    /** 构造 / 恢复时直接安装最终列表；恢复方需重新绑定仍保留的缓存根。 */
    fun installInitial(empty: List<Int>, error: List<Int>) {
        emptyIds = normalizeRetryIds(empty, "emptyViewIds") ?: emptyList()
        errorIds = normalizeRetryIds(error, "errorViewIds") ?: emptyList()
        lastAccepted[StateLayoutState.EMPTY] = null
        lastAccepted[StateLayoutState.ERROR] = null
    }

    /** 状态页首次创建时为该根绑定当前有效列表，再执行既有创建回调。 */
    fun bind(state: StateLayoutState, root: View) {
        if (state != StateLayoutState.EMPTY && state != StateLayoutState.ERROR) return
        // 同一根重复绑定先清理，避免重复监听。
        unbindRoot(root)
        val ids = if (state == StateLayoutState.EMPTY) emptyIds else errorIds
        for (id in ids) {
            val target = root.findViewById<View>(id)
            if (target == null) {
                warnMissingOnce(root, id, state)
                continue
            }
            attach(state, root, target, id)
        }
    }

    /** 解除指定根的全部目标（释放、候选失败回滚、配置重建共用）。 */
    fun unbindRoot(root: View) {
        val iterator = bindings.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            if (b.root === root) {
                detach(b)
                iterator.remove()
            }
        }
        // 清理该根的缺失警告，下次重建可重新提示。
        val key = System.identityHashCode(root)
        missingWarned.removeAll { it.first == key }
    }

    /** 解除某状态的全部记录（保留列表与节流窗口，仅清理 View 绑定）。 */
    fun unbindState(state: StateLayoutState) {
        val iterator = bindings.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            if (b.state == state) {
                detach(b)
                iterator.remove()
            }
        }
    }

    fun snapshotEmpty(): List<Int> = emptyIds.toList()
    fun snapshotError(): List<Int> = errorIds.toList()

    // region 内部

    private fun prepareTargets(
        state: StateLayoutState,
        root: View?,
        ids: List<Int>,
    ): List<Pair<Int, View?>> {
        if (root == null) return ids.map { it to null }
        return ids.map { id -> id to root.findViewById<View>(id) }
    }

    private fun commitSingle(
        state: StateLayoutState,
        normalized: List<Int>,
        cachedRoot: View?,
        prepared: List<Pair<Int, View?>>,
    ) {
        // 解绑旧目标。
        val iterator = bindings.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            if (b.state == state) {
                detach(b)
                iterator.remove()
            }
        }
        if (state == StateLayoutState.EMPTY) emptyIds = normalized else errorIds = normalized
        // 明确改变该状态有效列表时清零该状态窗口。
        lastAccepted[state] = null
        if (cachedRoot != null) {
            for ((id, target) in prepared) {
                if (target == null) {
                    warnMissingOnce(cachedRoot, id, state)
                    continue
                }
                // 目标可能已不在原根下（布局被业务移动）：跳过，避免绑错。
                if (!isDescendantOf(target, cachedRoot)) {
                    warnMissingOnce(cachedRoot, id, state)
                    continue
                }
                attach(state, cachedRoot, target, id)
            }
        }
    }

    private fun attach(state: StateLayoutState, root: View, target: View, id: Int) {
        val before = target.isClickable
        target.setOnClickListener { clicked ->
            handleClick(state, root, clicked)
        }
        // setOnClickListener 已将 clickable 置 true；记录原始值以便恢复。
        bindings += Binding(state, root, target, id, before, valid = true)
    }

    private fun detach(b: Binding) {
        b.valid = false
        // 只清理仍指向本记录监听的目标，避免误清宿主后续设置的监听。
        // 由于宿主不得在库管理热区上再设监听，这里按“仍是库监听”直接清理。
        try {
            b.target.setOnClickListener(null)
        } catch (_: Throwable) {
            // 解绑不得抛异常影响释放流程。
        }
        try {
            b.target.isClickable = b.clickableBefore
        } catch (_: Throwable) {
        }
    }

    private fun handleClick(expected: StateLayoutState, root: View, clicked: View) {
        // 门禁 1：回调与可用开关。
        val listenerPresent = gates.retryListener() != null
        if (!listenerPresent || !retryClicksEnabled) return
        // 门禁 2：挂载与可见；隐藏或禁用目标的 performClick 也不得绕过。
        if (!gates.isAttached() || !gates.isAggregatedVisible() || !gates.isShown()) return
        if (!isInteractivelyVisible(clicked, root)) return
        // 门禁 3：请求与呈现一致，且旧页面已退场。
        if (gates.renderedState() != expected || gates.requestedState() != expected) return
        // 门禁 4：原始根仍为当前缓存根，包装层仍为当前显示层，目标仍位于原根下。
        val currentRoot = gates.cachedRoot(expected)
        if (currentRoot == null || currentRoot !== root) return
        val wrapper = gates.wrapperFor(expected)
        if (wrapper == null || gates.currentTop() !== wrapper) return
        if (!isDescendantOf(clicked, root)) return
        // 门禁 5：目标仍属于有效绑定记录。
        val record = bindings.firstOrNull { it.valid && it.state == expected && it.root === root && it.target === clicked }
            ?: return
        // 节流：同控件同状态共享窗口；回调前记录已接受时间。
        val now = clock()
        val window = retryClickThrottleMillis
        if (window > 0) {
            val last = lastAccepted[expected]
            if (last != null && now - last < window) return
        }
        // 记录窗口后再派发，避免业务回调内同步触发第二次点击。
        lastAccepted[expected] = now
        // 派发前二次确认记录仍有效（派发本身不改变绑定）。
        if (!record.valid) return
        gates.dispatch(expected, clicked)
    }

    private fun isDescendantOf(target: View, root: View): Boolean {
        if (target === root) return true
        var p = target.parent
        while (p != null) {
            if (p === root) return true
            // 到达宿主仍未经过根，说明目标已移出原根。
            if (p === gates.hostView()) return false
            p = p.parent
        }
        return false
    }

    private fun isInteractivelyVisible(target: View, root: View): Boolean {
        if (target.visibility != View.VISIBLE || !target.isEnabled) return false
        var current: android.view.ViewParent? = target.parent
        val host = gates.hostView()
        while (current != null && current is View) {
            if (current.visibility != View.VISIBLE || !current.isEnabled) return false
            if (current === root) break
            if (current === host) break
            current = current.parent
        }
        // host 自身也需可见可用。
        if (host.visibility != View.VISIBLE || !host.isEnabled) return false
        return true
    }

    private fun warnMissingOnce(root: View, id: Int, state: StateLayoutState) {
        val key = System.identityHashCode(root) to id
        if (!missingWarned.add(key)) return
        val name = try {
            root.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            id.toString()
        }
        Log.w(
            TAG,
            "ssl_${state.name.lowercase()}RetryViewIds 中的 @$name 在${state.name}布局中不存在，已跳过；" +
                "请检查 ID 是否写在对应状态布局内部",
        )
    }

    private fun requireRetryState(state: StateLayoutState) {
        require(state == StateLayoutState.EMPTY || state == StateLayoutState.ERROR) {
            "重试入口仅支持 EMPTY / ERROR，当前=$state"
        }
    }

    private fun StateLayoutState.fieldName(): String =
        if (this == StateLayoutState.EMPTY) "emptyViewIds" else "errorViewIds"

    companion object {
        private const val TAG = "StateSkeletonLayout"
        const val DEFAULT_THROTTLE_MILLIS = 500L

        internal fun invalidStateMessage(state: StateLayoutState): String =
            "重试入口仅支持 EMPTY / ERROR，当前=$state"
    }
}
