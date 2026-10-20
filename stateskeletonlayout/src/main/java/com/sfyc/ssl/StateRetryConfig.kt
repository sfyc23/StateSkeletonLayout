package com.sfyc.ssl

import android.view.View
import java.util.Collections

/**
 * 多重试入口的不可变配置：分别声明 EMPTY / ERROR 的重试 View ID 列表。
 *
 * - `null` 表示不参与覆盖（继承下一层默认值，批量应用时跳过该状态）。
 * - 空列表表示显式关闭该状态的重试入口。
 * - 非空列表替换该状态的完整入口，不与低优先级做并集。
 *
 * 构造时复制输入列表、按首次出现顺序去重；属性返回不可修改的快照，
 * Java 调用方也无法通过返回值修改内部配置。
 *
 * 规则：
 * - 不允许元素为 `0` 或 [View.NO_ID]；关闭请用空列表。
 * - 去重后单状态 ID 数量不得超过 [MAX_RETRY_IDS]（与 Parcel 读取上限一致）。
 * - 相同 ID 可同时出现在 EMPTY 和 ERROR，查找范围分别限定在各自状态根内。
 */
class StateRetryConfig(
    emptyViewIds: List<Int>?,
    errorViewIds: List<Int>?,
) {
    private val backingEmpty: List<Int>? = normalizeRetryIds(emptyViewIds, "emptyViewIds")?.toList()
    private val backingError: List<Int>? = normalizeRetryIds(errorViewIds, "errorViewIds")?.toList()

    val emptyViewIds: List<Int>?
        get() = backingEmpty?.let { Collections.unmodifiableList(it.toList()) }
    val errorViewIds: List<Int>?
        get() = backingError?.let { Collections.unmodifiableList(it.toList()) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StateRetryConfig) return false
        return emptyViewIds == other.emptyViewIds && errorViewIds == other.errorViewIds
    }

    override fun hashCode(): Int {
        var result = emptyViewIds?.hashCode() ?: 0
        result = 31 * result + (errorViewIds?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "StateRetryConfig(emptyViewIds=$emptyViewIds, errorViewIds=$errorViewIds)"

    companion object {
        /** 单状态去重后允许的最大 ID 数量，与 Parcel 读取上限一致。 */
        const val MAX_RETRY_IDS = 10_000
    }
}

/**
 * 重试 ID 列表的规范化：复制、按首次出现顺序去重、校验 ID 与数量。
 *
 * @param ids 输入列表，`null` 表示不参与覆盖，直接返回 `null`。
 * @param fieldName 字段名，用于异常信息定位。
 * @return 规范化后的不可变快照（调用方需再包一层不可修改视图），`null` 输入返回 `null`。
 * @throws IllegalArgumentException 元素为 `0` / [View.NO_ID] 或去重后数量超限时抛出。
 */
internal fun normalizeRetryIds(ids: List<Int>?, fieldName: String): List<Int>? {
    if (ids == null) return null
    val deduped = LinkedHashSet<Int>(ids.size.coerceAtLeast(1))
    for ((index, id) in ids.withIndex()) {
        require(id != 0 && id != View.NO_ID) {
            "$fieldName[$index] 非法：不允许为 0 或 View.NO_ID，关闭入口请使用空列表，当前=$id"
        }
        deduped.add(id)
    }
    require(deduped.size <= StateRetryConfig.MAX_RETRY_IDS) {
        "$fieldName 数量超限：去重后=${deduped.size}，上限=${StateRetryConfig.MAX_RETRY_IDS}"
    }
    return deduped.toList()
}
